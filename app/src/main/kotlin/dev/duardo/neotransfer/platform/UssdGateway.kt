package dev.duardo.neotransfer.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dev.duardo.neotransfer.core.UssdCommand

sealed interface UssdResult {
    data class Response(val text: String) : UssdResult
    data class NetworkFailure(val code: Int) : UssdResult
    data object Busy : UssdResult
    data object PermissionRequired : UssdResult
    data object InvalidSubscription : UssdResult
    data object Unsupported : UssdResult
    /** Local deadline elapsed; the network may still complete the request. */
    data object TimedOut : UssdResult
}

interface UssdTransport {
    fun isIdle(): Boolean
    fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit)
    /** Expire only a still-pending local send. Each send needs a distinct callback instance. */
    fun abandon(result: (UssdResult) -> Unit): Boolean = false
}

/** Serializes access to the modem. A USSD response is not a banking receipt. */
class UssdGateway(context: Context) : UssdTransport {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lease = UssdRequestLease(
        schedule = { task, delay -> handler.postDelayed(task, delay) },
        cancel = handler::removeCallbacks,
    )

    override fun isIdle(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return lease.isIdle()
    }

    override fun abandon(result: (UssdResult) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return lease.abandon(result)
    }

    override fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!lease.isIdle()) return result(UssdResult.Busy)
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return result(UssdResult.PermissionRequired)
        }
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            return result(UssdResult.InvalidSubscription)
        }
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subscriptionId) ?: return result(UssdResult.Unsupported)
        val ticket = lease.begin(result) ?: return result(UssdResult.Busy)
        try {
            telephony.sendUssdRequest(command.valueForTransport(), object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(manager: TelephonyManager, request: String, response: CharSequence) {
                    lease.complete(ticket, UssdResult.Response(response.toString()))
                }

                override fun onReceiveUssdResponseFailed(manager: TelephonyManager, request: String, code: Int) {
                    lease.complete(ticket, UssdResult.NetworkFailure(code))
                }
            }, handler)
        } catch (_: SecurityException) {
            lease.complete(ticket, UssdResult.PermissionRequired)
        } catch (_: UnsupportedOperationException) {
            lease.complete(ticket, UssdResult.Unsupported)
        } catch (_: RuntimeException) {
            lease.complete(ticket, UssdResult.NetworkFailure(-1))
        }
    }
}
