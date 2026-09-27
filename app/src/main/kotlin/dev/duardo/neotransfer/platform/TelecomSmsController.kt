package dev.duardo.neotransfer.platform

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

data class TelecomSmsDraft(
    val id: String,
    val actionId: String,
    val destination: String,
    val body: String,
    val subscriptionId: Int,
    val callbackToken: String,
    val createdAtMillis: Long,
)

enum class TelecomSmsStatus { DRAFT, UNKNOWN, SENT, FAILED }
data class TelecomSmsRecord(val draft: TelecomSmsDraft, val status: TelecomSmsStatus, val resultCode: Int? = null)

interface TelecomSmsStore {
    fun get(id: String): TelecomSmsRecord?
    /** Must be durable before returning true. */
    fun put(record: TelecomSmsRecord): Boolean
    fun records(): List<TelecomSmsRecord>
}

interface TelecomSmsRuntime {
    fun isActiveSubscription(subscriptionId: Int): Boolean
    fun hasSendPermission(): Boolean
}

fun interface TelecomSmsTransport { fun send(draft: TelecomSmsDraft) }

sealed interface TelecomSmsPrepare {
    data class Ready(val draft: TelecomSmsDraft) : TelecomSmsPrepare
    data object InvalidAction : TelecomSmsPrepare
    data object InactiveSim : TelecomSmsPrepare
    data object StorageFailed : TelecomSmsPrepare
}

enum class TelecomSmsSubmit { UNKNOWN, PERMISSION_REQUIRED, INACTIVE_SIM, STALE_REVIEW, STORAGE_FAILED }

/** A submission remains UNKNOWN until the unique sent callback reports a result; never auto-retry it. */
class TelecomSmsController(
    private val store: TelecomSmsStore,
    private val runtime: TelecomSmsRuntime,
    private val transport: TelecomSmsTransport,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun prepare(actionId: String, subscriptionId: Int): TelecomSmsPrepare = synchronized(lock) {
        val action = TelecomActions.find(actionId)?.takeIf { it.kind == TelecomActionKind.SMS_INFO }
            ?: return@synchronized TelecomSmsPrepare.InvalidAction
        if (!runtime.isActiveSubscription(subscriptionId)) return@synchronized TelecomSmsPrepare.InactiveSim
        val id = newId()
        if (store.get(id) != null) return@synchronized TelecomSmsPrepare.StorageFailed
        val draft = TelecomSmsDraft(id, action.id, action.destination, requireNotNull(action.body),
            subscriptionId, newId(), nowMillis())
        if (!store.put(TelecomSmsRecord(draft, TelecomSmsStatus.DRAFT))) TelecomSmsPrepare.StorageFailed
        else TelecomSmsPrepare.Ready(draft)
    }

    /** Call only after displaying and confirming this exact draft with its SIM and destination. */
    fun sendAfterReview(draft: TelecomSmsDraft): TelecomSmsSubmit = synchronized(lock) {
        val saved = store.get(draft.id) ?: return@synchronized TelecomSmsSubmit.STALE_REVIEW
        val action = TelecomActions.find(draft.actionId)?.takeIf { it.kind == TelecomActionKind.SMS_INFO }
            ?: return@synchronized TelecomSmsSubmit.STALE_REVIEW
        if (saved.status != TelecomSmsStatus.DRAFT || saved.draft != draft ||
            action.destination != draft.destination || action.body != draft.body)
            return@synchronized TelecomSmsSubmit.STALE_REVIEW
        if (!runtime.hasSendPermission()) return@synchronized TelecomSmsSubmit.PERMISSION_REQUIRED
        if (!runtime.isActiveSubscription(draft.subscriptionId)) return@synchronized TelecomSmsSubmit.INACTIVE_SIM
        // Commit before invoking a side effect. A crash or transport exception leaves an uncertain request.
        if (!store.put(saved.copy(status = TelecomSmsStatus.UNKNOWN))) return@synchronized TelecomSmsSubmit.STORAGE_FAILED
        try { transport.send(draft) } catch (_: Exception) { /* Dispatch may already have happened. */ }
        TelecomSmsSubmit.UNKNOWN
    }

    /** A repeated or late callback can update only its own still-unknown request. */
    fun onSentCallback(id: String, callbackToken: String, success: Boolean, resultCode: Int): Boolean = synchronized(lock) {
        val saved = store.get(id) ?: return@synchronized false
        if (saved.status != TelecomSmsStatus.UNKNOWN || saved.draft.callbackToken != callbackToken) return@synchronized false
        store.put(saved.copy(status = if (success) TelecomSmsStatus.SENT else TelecomSmsStatus.FAILED,
            resultCode = resultCode))
    }

    fun record(id: String): TelecomSmsRecord? = store.get(id)
    fun records(): List<TelecomSmsRecord> = store.records()

    companion object {
        private val lock = Any()
        fun android(context: Context): TelecomSmsController {
            val app = context.applicationContext
            return TelecomSmsController(PreferencesTelecomSmsStore(app), AndroidTelecomSmsRuntime(app), AndroidTelecomSmsTransport(app))
        }
    }
}

private class PreferencesTelecomSmsStore(context: Context) : TelecomSmsStore {
    private val prefs = context.getSharedPreferences("telecom_info_sms", Context.MODE_PRIVATE)
    private fun key(id: String) = "request:$id"

    override fun get(id: String): TelecomSmsRecord? = runCatching {
        prefs.getString(key(id), null)?.let(::decode)
    }.getOrNull()

    override fun put(record: TelecomSmsRecord): Boolean = prefs.edit()
        .putString(key(record.draft.id), encode(record)).commit()

    override fun records(): List<TelecomSmsRecord> = prefs.all.values.mapNotNull { raw ->
        (raw as? String)?.let { runCatching { decode(it) }.getOrNull() }
    }.sortedByDescending { it.draft.createdAtMillis }

    private fun encode(record: TelecomSmsRecord): String = JSONObject().apply {
        put("id", record.draft.id)
        put("action", record.draft.actionId)
        put("destination", record.draft.destination)
        put("body", record.draft.body)
        put("subscription", record.draft.subscriptionId)
        put("token", record.draft.callbackToken)
        put("created", record.draft.createdAtMillis)
        put("status", record.status.name)
        record.resultCode?.let { put("result", it) }
    }.toString()

    private fun decode(raw: String): TelecomSmsRecord = JSONObject(raw).let { json ->
        TelecomSmsRecord(TelecomSmsDraft(json.getString("id"), json.getString("action"), json.getString("destination"),
            json.getString("body"), json.getInt("subscription"), json.getString("token"), json.getLong("created")),
            TelecomSmsStatus.valueOf(json.getString("status")), if (json.has("result")) json.getInt("result") else null)
    }
}

private class AndroidTelecomSmsRuntime(private val context: Context) : TelecomSmsRuntime {
    override fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    override fun isActiveSubscription(subscriptionId: Int): Boolean {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
                ?.any { it.subscriptionId == subscriptionId } == true
        } catch (_: SecurityException) { false }
    }
}

private class AndroidTelecomSmsTransport(private val context: Context) : TelecomSmsTransport {
    override fun send(draft: TelecomSmsDraft) {
        val intent = Intent(context, TelecomSmsSentReceiver::class.java).apply {
            action = TelecomSmsSentReceiver.ACTION
            data = TelecomSmsSentReceiver.uriFor(draft.id)
            setPackage(context.packageName)
            putExtra(TelecomSmsSentReceiver.REQUEST_ID, draft.id)
            putExtra(TelecomSmsSentReceiver.CALLBACK_TOKEN, draft.callbackToken)
        }
        val callback = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val manager = checkNotNull(context.getSystemService(SmsManager::class.java)) { "SMS no disponible" }
        manager.createForSubscriptionId(draft.subscriptionId)
            .sendTextMessage(draft.destination, null, draft.body, callback, null)
    }
}
