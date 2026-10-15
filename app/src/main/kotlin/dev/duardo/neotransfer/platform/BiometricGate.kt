package dev.duardo.neotransfer.platform

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface BiometricResult {
    /** [cipher] is the authorized one when the prompt carried a cipher. */
    data class Passed(val cipher: Cipher?) : BiometricResult
    data object Cancelled : BiometricResult
    /** Too many failed attempts: the sensor rests for a while. */
    data object LockedOut : BiometricResult
    /** No sensor or no fingerprint enrolled. */
    data object Unavailable : BiometricResult
    data class Failed(val message: String) : BiometricResult
}

/** The system fingerprint prompt (strong biometrics), with or without a Keystore cipher to authorize. */
class BiometricGate(private val activity: Activity) {
    fun available(): Boolean = activity.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    suspend fun prompt(title: String, subtitle: String?, cipher: Cipher?): BiometricResult {
        if (!available()) return BiometricResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            fun finish(result: BiometricResult) {
                if (continuation.isActive) continuation.resume(result)
            }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .apply { subtitle?.let(::setSubtitle) }
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> finish(BiometricResult.Cancelled) }
                .build()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    finish(if (cipher == null || authorized === cipher) BiometricResult.Passed(authorized) else BiometricResult.Failed("No se pudo verificar la huella"))
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) = finish(when (code) {
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                    BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> BiometricResult.Cancelled
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> BiometricResult.LockedOut
                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricResult.Unavailable
                    else -> BiometricResult.Failed(message.toString())
                })
            }
            if (cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor, callback)
            else prompt.authenticate(cancellation, activity.mainExecutor, callback)
            continuation.invokeOnCancellation { cancellation.cancel() }
        }
    }
}
