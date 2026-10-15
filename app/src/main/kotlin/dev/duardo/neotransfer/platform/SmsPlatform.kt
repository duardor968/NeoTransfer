package dev.duardo.neotransfer.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import dev.duardo.neotransfer.core.sms.SmsRecord
import dev.duardo.neotransfer.engine.InboxSms
import dev.duardo.neotransfer.engine.SmsInbox
import dev.duardo.neotransfer.engine.controller

/** PAGOxMOVIL rows of `content://sms/inbox`; call off the main thread. */
class AndroidSmsInbox(context: Context) : SmsInbox {
    private val resolver = context.applicationContext.contentResolver

    override fun read(fromId: Long): List<InboxSms> = buildList {
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.BODY)
        val selection = "${Telephony.Sms.ADDRESS} = ? COLLATE NOCASE AND ${Telephony.Sms._ID} >= ?"
        resolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, arrayOf(SmsRecord.SENDER, fromId.toString()), "${Telephony.Sms._ID} ASC")
            ?.use { cursor ->
                while (cursor.moveToNext()) add(InboxSms(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3).orEmpty()))
            }
    }

    /** Calls [listener] on every change of the SMS store, for as long as the process lives. */
    fun observe(listener: () -> Unit) {
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = listener()
        })
    }
}

/**
 * Wakes the app (closed or not) when PAGOxMOVIL writes; the controller then reads the inbox from
 * its last `_id`. Other senders are ignored without starting any work.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.none { it?.originatingAddress.equals(SmsRecord.SENDER, ignoreCase = true) }) return
        val pending = goAsync()
        context.controller.onBankSms().invokeOnCompletion { pending.finish() }
    }
}
