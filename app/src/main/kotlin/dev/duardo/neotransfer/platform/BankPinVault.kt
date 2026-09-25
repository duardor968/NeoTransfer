package dev.duardo.neotransfer.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cipher instances must be authorized with BiometricPrompt.CryptoObject before finish methods. */
class BankPinVault(context: Context) {
    private val preferences = context.getSharedPreferences("bank_vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasCredentials(): Boolean = preferences.contains("ciphertext") && preferences.contains("iv")
    fun hasStoredMaterial(): Boolean = preferences.contains("ciphertext") || preferences.contains("iv")

    fun reset() {
        check(preferences.edit().clear().commit()) { "No se pudo restablecer el acceso" }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    fun prepareEncryption(): Cipher {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build())
                generateKey()
            }
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    fun finishEncryption(authorizedCipher: Cipher, plaintext: ByteArray) {
        val encrypted = authorizedCipher.doFinal(plaintext)
        check(preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(authorizedCipher.iv, Base64.NO_WRAP))
            .commit()) { "No se pudieron guardar las claves" }
    }

    fun prepareDecryption(): Cipher {
        check(hasCredentials()) { "No hay claves guardadas" }
        val iv = Base64.decode(requireNotNull(preferences.getString("iv", null)), Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }

    fun finishDecryption(authorizedCipher: Cipher): ByteArray = authorizedCipher.doFinal(
        Base64.decode(requireNotNull(preferences.getString("ciphertext", null)), Base64.NO_WRAP),
    )

    private fun key(): SecretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

    private companion object {
        const val KEY_ALIAS = "dev.duardo.neotransfer.bank-pins"
    }
}
