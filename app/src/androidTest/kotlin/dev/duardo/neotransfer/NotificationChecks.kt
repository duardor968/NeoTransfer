package dev.duardo.neotransfer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.duardo.neotransfer.data.ContactPhone
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.RoomWalletRepository
import dev.duardo.neotransfer.data.SmsIngestor
import dev.duardo.neotransfer.platform.BankSmsRecord
import dev.duardo.neotransfer.platform.TransferNotifications
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Uses isolated Room and a synthetic received message; removes only a channel created by this check. */
object NotificationChecks {
    private const val CHANNEL = "incoming_transfers"

    fun run(context: Context, onPassed: (String) -> Unit = {}, onSkipped: (String) -> Unit = {}): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = TransferNotifications(context)
        val channel = manager.getNotificationChannel(CHANNEL)
        val canPublish = notifications.enabled &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE

        val name = "notification_test_${UUID.randomUUID()}.db"
        val repository = RoomWalletRepository.open(context, name)
        var ownReceiptId: String? = null
        var createdChannel = false
        try {
            if (canPublish && channel == null) {
                createdChannel = true
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Transferencias recibidas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avisos de transferencias recibidas en tu cartera"
                })
                check(manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_DEFAULT)
            }
            val at = Instant.now().minusSeconds(1)
            val body = "El titular del telefono 50000001 le ha realizado una transferencia a la cuenta " +
                "0000000000000002 de 25.00 CUP. Nro. Transaccion NOTIFY01"
            val result = SmsIngestor(repository).ingest(BankSmsRecord(null, body, at, 7, "synthetic-notification-pdu"))
            check(result.newFinancialMovement)
            val stored = repository.pendingTransferNotifications().single()
            check(stored.id == result.stored.receiptId)
            ownReceiptId = stored.id
            val wallet = repository.snapshot().copy(contacts = listOf(
                ContactRecord("synthetic-contact", "Contacto sintético", listOf(ContactPhone("+5350000001")))))

            check(notifications.deliver(stored, wallet))
            if (!canPublish) {
                check(manager.activeNotifications.none { it.tag == stored.id && it.id == 0 })
                onPassed("Notification delivery respects the current disabled permission or setting")
                onSkipped("NotificationManager content: notifications are disabled by current user or Android settings")
                return 1
            }

            val posted = awaitOwnNotification(manager, stored.id)
            check(posted != null) { "Synthetic transfer notification was not posted" }
            val privateNotice = posted.notification
            val publicNotice = checkNotNull(privateNotice.publicVersion)
            val amount = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CU")).apply {
                minimumFractionDigits = 2; maximumFractionDigits = 2
            }.format(25) + " CUP"
            check(privateNotice.visibility == Notification.VISIBILITY_PRIVATE)
            check(privateNotice.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Contacto sintético te envió $amount")
            check(privateNotice.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == "Transferencia recibida")
            check(publicNotice.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Transferencia recibida")
            check(publicNotice.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == "Desbloquea el teléfono para ver los detalles")
            check(publicNotice.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Contacto sintético").not())
            check(publicNotice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(amount).not())
            check(privateNotice.contentIntent != null)

            SystemClock.sleep(50)
            check(notifications.deliver(stored, wallet))
            val afterRetry = manager.activeNotifications.filter { it.tag == stored.id && it.id == 0 }
            check(afterRetry.size == 1 && afterRetry.single().postTime == posted.postTime)
            onPassed("NotificationManager keeps the private contact and amount, generic public version and one posting after retry")
            return 1
        } finally {
            try { ownReceiptId?.let { manager.cancel(it, 0) } }
            finally {
                try { if (createdChannel) manager.deleteNotificationChannel(CHANNEL) }
                finally { repository.close(); check(context.deleteDatabase(name)) }
            }
        }
    }

    private fun awaitOwnNotification(manager: NotificationManager, receiptId: String): android.service.notification.StatusBarNotification? {
        repeat(40) {
            manager.activeNotifications.singleOrNull { it.tag == receiptId && it.id == 0 }?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }
}
