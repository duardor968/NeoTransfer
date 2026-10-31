package dev.duardo.neotransfer.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
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
 *
 * Nothing goes out in airplane mode or without network service: the request fails at once, sent to no one. Both are
 * read without permissions: `Settings.Global.AIRPLANE_MODE_ON`, and the voice service state the system pushes to a
 * [TelephonyCallback.ServiceStateListener] (without location permissions it comes with its location fields cleared,
 * which are not read). The listener follows the default voice subscription of when the app started.
 */
class AndroidUssdModem(context: Context) : UssdModem {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    /** The voice service state last pushed by the system; null until it pushes one, and then nothing is assumed. */
    @Volatile private var service: Int? = null

    init {
        telephony().registerTelephonyCallback(this.context.mainExecutor, object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                service = serviceState.state
            }
        })
    }

    override suspend fun request(command: UssdCommand): UssdReply {
        require(command.transport == UssdTransport.REQUEST)
        unavailable()?.let { return UssdReply.Failure(it) }
        return send(command)
    }

    /** Why nothing can be sent now, ready to show; null when the phone has service or does not say. */
    internal fun unavailable(): String? = when {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 -> "Desactiva el modo avión. No se envió nada."
        service == ServiceState.STATE_OUT_OF_SERVICE || service == ServiceState.STATE_EMERGENCY_ONLY || service == ServiceState.STATE_POWER_OFF ->
            "Sin servicio. No se envió nada."
        else -> null
    }

    private suspend fun send(command: UssdCommand): UssdReply = suspendCancellableCoroutine { continuation ->
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
    override fun dial(command: UssdCommand): String? {
        require(command.transport == UssdTransport.DIAL)
        unavailable()?.let { return it }
        val uri = Uri.parse("tel:" + command.valueForTransport().replace("#", Uri.encode("#")))
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (_: SecurityException) {
            "Falta el permiso de llamadas"
        } catch (_: RuntimeException) {
            "El teléfono no pudo enviar la solicitud"
        }
    }

    private fun telephony(): TelephonyManager {
        val manager = context.getSystemService(TelephonyManager::class.java)
        val subscription = SubscriptionManager.getDefaultVoiceSubscriptionId()
        return if (SubscriptionManager.isValidSubscriptionId(subscription)) manager.createForSubscriptionId(subscription) else manager
    }
}
