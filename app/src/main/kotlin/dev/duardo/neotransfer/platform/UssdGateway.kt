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
}

interface UssdTransport {
    fun isIdle(): Boolean
    fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit)
}

/** Serializes access to the modem. A USSD response is not a banking receipt. */
class UssdGateway(context: Context) : UssdTransport {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    override fun isIdle(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return !active
    }

    override fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active) return result(UssdResult.Busy)
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return result(UssdResult.PermissionRequired)
        }
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            return result(UssdResult.InvalidSubscription)
        }
        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subscriptionId) ?: return result(UssdResult.Unsupported)
        active = true
        try {
            telephony.sendUssdRequest(command.valueForTransport(), object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(manager: TelephonyManager, request: String, response: CharSequence) {
                    active = false
                    result(UssdResult.Response(response.toString()))
                }

                override fun onReceiveUssdResponseFailed(manager: TelephonyManager, request: String, code: Int) {
                    active = false
                    result(UssdResult.NetworkFailure(code))
                }
            }, handler)
        } catch (_: SecurityException) {
            active = false
            result(UssdResult.PermissionRequired)
        } catch (_: UnsupportedOperationException) {
            active = false
            result(UssdResult.Unsupported)
        } catch (_: RuntimeException) {
            active = false
            result(UssdResult.NetworkFailure(-1))
        }
    }
}
