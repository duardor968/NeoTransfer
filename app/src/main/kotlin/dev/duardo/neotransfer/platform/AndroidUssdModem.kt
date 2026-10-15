package dev.duardo.neotransfer.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.protocol.UssdTransport
import dev.duardo.neotransfer.engine.UssdModem
import dev.duardo.neotransfer.engine.UssdReply
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The SIM of the default voice subscription. One request at a time is the controller's job; this
 * class only talks to the modem. A reply arriving after the caller stopped waiting is dropped.
 */
class AndroidUssdModem(context: Context) : UssdModem {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override suspend fun request(command: UssdCommand): UssdReply = suspendCancellableCoroutine { continuation ->
        require(command.transport == UssdTransport.REQUEST)
        fun answer(reply: UssdReply) { if (continuation.isActive) continuation.resume(reply) }
        try {
            telephony().sendUssdRequest(command.valueForTransport(), object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(manager: TelephonyManager, request: String, response: CharSequence) =
                    answer(UssdReply.Text(response.toString()))

                override fun onReceiveUssdResponseFailed(manager: TelephonyManager, request: String, failureCode: Int) =
                    answer(UssdReply.Failure(if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) "El servicio USSD no está disponible" else "La red no aceptó la solicitud"))
            }, handler)
        } catch (_: SecurityException) {
            answer(UssdReply.Failure("Falta el permiso de llamadas"))
        } catch (_: RuntimeException) {
            answer(UssdReply.Failure("El teléfono no pudo enviar la solicitud"))
        }
    }

    /** `ACTION_CALL` with `#` encoded; it opens the system's USSD dialog, so call it with the app in front. */
    override fun dial(command: UssdCommand) {
        require(command.transport == UssdTransport.DIAL)
        val uri = Uri.parse("tel:" + command.valueForTransport().replace("#", Uri.encode("#")))
        context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun telephony(): TelephonyManager {
        val manager = context.getSystemService(TelephonyManager::class.java)
        val subscription = SubscriptionManager.getDefaultVoiceSubscriptionId()
        return if (SubscriptionManager.isValidSubscriptionId(subscription)) manager.createForSubscriptionId(subscription) else manager
    }
}
