package dev.duardo.neotransfer.platform

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import javax.crypto.Cipher

class BiometricGate(private val activity: Activity) {
    fun availability(): Int = activity.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

    /** Explicit confirmation for an initial registration which has no saved credential yet. */
    fun confirm(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal {
        val cancellation = CancellationSignal()
        var finished = false
        fun cancelOnce() { if (!finished) { finished = true; onCancelled() } }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> cancelOnce() }
            .build()
        prompt.authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (finished) return
                if (cancellation.isCanceled) return cancelOnce()
                finished = true
                onSuccess()
            }
            override fun onAuthenticationError(code: Int, message: CharSequence) {
                if (finished) return
                if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                    cancelOnce()
                else { finished = true; onError(message.toString()) }
            }
        })
        return cancellation
    }

    fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String,
        onSuccess: (Cipher) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit,
    ): CancellationSignal {
        val cancellation = CancellationSignal()
        var finished = false
        fun cancelOnce() {
            if (!finished) {
                finished = true
                onCancelled()
            }
        }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> cancelOnce() }
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (finished) return
                    if (cancellation.isCanceled) return cancelOnce()
                    finished = true
                    val authorized = result.cryptoObject?.cipher
                    if (authorized === cipher) onSuccess(authorized)
                    else onError("No se pudo autorizar el acceso a las claves")
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (finished) return
                    if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) cancelOnce()
                    else {
                        finished = true
                        onError(message.toString())
                    }
                }
            })
        return cancellation
    }
}
