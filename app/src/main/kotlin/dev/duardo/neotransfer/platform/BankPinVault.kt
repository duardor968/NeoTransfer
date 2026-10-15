package dev.duardo.neotransfer.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.duardo.neotransfer.engine.KeyVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under a Keystore key that needs strong biometrics on every use and dies when a new
 * fingerprint is enrolled. Ciphers must be authorized with `BiometricPrompt.CryptoObject` before
 * [encrypt] / [decrypt].
 */
class BankPinVault(context: Context) : KeyVault {
    private val preferences = context.getSharedPreferences("key_vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun hasKeys(): Boolean = preferences.contains("ciphertext") && preferences.contains("iv")

    override fun reset() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar la clave" }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    override fun encryptCipher(): Cipher {
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
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            reset()
            encryptCipher()
        }
    }

    override fun encrypt(authorized: Cipher, plaintext: ByteArray) {
        val encrypted = authorized.doFinal(plaintext)
        check(preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(authorized.iv, Base64.NO_WRAP))
            .commit()) { "No se pudo guardar la clave" }
    }

    override fun decryptCipher(): Cipher? {
        check(hasKeys()) { "No hay claves guardadas" }
        val iv = Base64.decode(requireNotNull(preferences.getString("iv", null)), Base64.NO_WRAP)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            reset()
            null
        }
    }

    override fun decrypt(authorized: Cipher): ByteArray =
        authorized.doFinal(Base64.decode(requireNotNull(preferences.getString("ciphertext", null)), Base64.NO_WRAP))

    private fun key(): SecretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

    private companion object {
        const val KEY_ALIAS = "dev.duardo.neotransfer.bank-keys"
    }
}
