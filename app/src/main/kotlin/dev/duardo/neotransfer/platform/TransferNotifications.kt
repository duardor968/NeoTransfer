package dev.duardo.neotransfer.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.duardo.neotransfer.MainActivity
import dev.duardo.neotransfer.R
import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.core.Currency
import dev.duardo.neotransfer.core.Money
import dev.duardo.neotransfer.core.MovementKind
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.ReceiptRecord
import dev.duardo.neotransfer.data.WalletSnapshot
import java.text.NumberFormat
import java.util.Locale

/** No credentials or banking calls are needed to announce an already received transfer. */
class TransferNotifications(private val context: Context) {
    private val preferences = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    var enabled: Boolean
        get() = preferences.getBoolean("received", true)
        set(value) { preferences.edit().putBoolean("received", value).apply() }

    /**
     * Only receipts from WalletRepository.pendingTransferNotifications may be passed here.
     * True acknowledges posting or an explicit disabled/invalid delivery; false retains it for retry.
     */
    fun deliver(stored: ReceiptRecord, wallet: WalletSnapshot): Boolean {
        val receipt = queuedTransferMessage(stored) ?: return true
        return try {
            if (!enabled || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
                !manager.areNotificationsEnabled()) return true
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return true
            // A process can die after notify but before the database acknowledgement.
            if (manager.activeNotifications.any { it.tag == stored.id && it.id == 0 }) return true
            publish(stored.id, receipt, wallet)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun publish(receiptId: String, receipt: BankMessage.TransferReceived, wallet: WalletSnapshot) {
        val sender = incomingTransferSender(receipt, wallet.contacts)
        val amount = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CU")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }.format(receipt.amount.amount) + " " + receipt.amount.currency.name

        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferencias recibidas", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Avisos de transferencias recibidas en tu cartera"
        })
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_RECEIPT
            identifier = receiptId
            putExtra(EXTRA_RECEIPT, receiptId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neotransfer_monochrome)
            .setContentTitle("Transferencia recibida")
            .setContentText("Desbloquea el teléfono para ver los detalles")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neotransfer_monochrome)
            .setContentTitle("$sender te envió $amount")
            .setContentText("Transferencia recibida")
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(receiptId, 0, notification)
    }

    companion object {
        const val ACTION_RECEIPT = "dev.duardo.neotransfer.OPEN_RECEIPT"
        const val EXTRA_RECEIPT = "receipt_id"
        private const val CHANNEL = "incoming_transfers"
    }
}

internal fun queuedTransferMessage(receipt: ReceiptRecord): BankMessage.TransferReceived? {
    if (receipt.id.isBlank() || receipt.kind != MovementKind.RECEIVED.name || receipt.referenceConflict || receipt.amountIsNominal) return null
    val amount = receipt.amount.toBigDecimalOrNull() ?: return null
    val currency = Currency.entries.singleOrNull { it.name == receipt.currency } ?: return null
    if (amount.signum() < 0 || amount.scale() > 2) return null
    return BankMessage.TransferReceived(receipt.account.orEmpty(), receipt.party, Money(amount, currency), receipt.reference.orEmpty())
}

/** The received account belongs to the recipient, so it must never identify the sender. */
internal fun incomingTransferSender(receipt: BankMessage.TransferReceived, contacts: List<ContactRecord>): String {
    val phone = exactCubanPhone(receipt.senderPhone) ?: return receipt.senderPhone
    val contact = contacts.filter { person -> person.phones.any { exactCubanPhone(it.number) == phone } }.singleOrNull()
    return contact?.name?.takeIf(String::isNotBlank) ?: receipt.senderPhone
}

private fun exactCubanPhone(value: String): String? {
    val formatted = value.trim()
    if (!formatted.matches(Regex("\\+?[0-9()\\s-]+"))) return null
    val digits = formatted.filter { it in '0'..'9' }
    return when {
        digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
        digits.length == 8 && !formatted.startsWith('+') -> digits
        else -> null
    }
}
