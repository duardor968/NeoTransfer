package dev.duardo.neotransfer.platform

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import java.time.Instant

class BankSmsRecord(
    val id: Long?,
    val body: String,
    val receivedAt: Instant,
    val subscriptionId: Int?,
    val deliveryId: String? = null,
) {
    override fun toString() = "BankSmsRecord(id=$id)"
}

class BankSmsInbox(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /** Run off the main thread. Permission errors are propagated to the permission flow. */
    fun read(limit: Int = Int.MAX_VALUE): List<BankSmsRecord> {
        require(limit > 0)
        val projection = arrayOf("_id", "body", "date", "sub_id")
        return buildList {
            checkNotNull(resolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, "address = ? COLLATE NOCASE", arrayOf("PAGOxMOVIL"), "date DESC, _id DESC")) {
                "No se pudo leer la bandeja de mensajes"
            }.use { cursor ->
                while (size < limit && cursor.moveToNext()) {
                    val subscription = if (cursor.isNull(3)) null else
                        cursor.getInt(3).takeIf(SubscriptionManager::isValidSubscriptionId)
                    add(BankSmsRecord(cursor.getLong(0), cursor.getString(1), Instant.ofEpochMilli(cursor.getLong(2)), subscription))
                }
            }
        }
    }

    companion object {
        fun fromBroadcast(intent: Intent): BankSmsRecord? {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return null
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return null
            if (parts.isEmpty() || parts.any { !it.originatingAddress.equals("PAGOxMOVIL", ignoreCase = true) }) return null
            val subscription = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID))
                .takeIf(SubscriptionManager::isValidSubscriptionId)
            return BankSmsRecord(null, parts.joinToString("") { it.messageBody }, Instant.now(), subscription,
                broadcastDeliveryId(subscription, parts.map { it.pdu }))
        }
    }
}

/** Stable delivery identity; a fresh SMS with the same text has a different PDU timestamp. */
internal fun broadcastDeliveryId(subscriptionId: Int?, pdus: List<ByteArray>): String {
    require(pdus.isNotEmpty() && pdus.all { it.isNotEmpty() })
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    hash.update("PAGOxMOVIL:${subscriptionId ?: "unknown"}:".toByteArray(Charsets.UTF_8))
    pdus.forEach { pdu ->
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(pdu.size).array())
        hash.update(pdu)
    }
    return "sms:" + hash.digest().joinToString("") { "%02x".format(it) }
}
