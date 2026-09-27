package dev.duardo.neotransfer.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Explicit, non-exported sent callback. The manifest must declare exported=false. */
class TelecomSmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(REQUEST_ID)?.takeIf(String::isNotBlank) ?: return
        val token = intent.getStringExtra(CALLBACK_TOKEN)?.takeIf(String::isNotBlank) ?: return
        if (intent.data != uriFor(id)) return
        TelecomSmsController.android(context).onSentCallback(id, token, resultCode == Activity.RESULT_OK, resultCode)
    }

    companion object {
        const val ACTION = "dev.duardo.neotransfer.TELECOM_INFO_SMS_SENT"
        const val REQUEST_ID = "request_id"
        const val CALLBACK_TOKEN = "callback_token"
        fun uriFor(id: String): Uri = Uri.parse("neotransfer-sms-sent://request/$id")
    }
}
