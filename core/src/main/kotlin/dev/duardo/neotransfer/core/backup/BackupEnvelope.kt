package dev.duardo.neotransfer.core.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class BackupFailure(message: String) : Exception(message) {
    class PasswordRequired : BackupFailure("Introduce una contraseña válida")
    class InvalidInput : BackupFailure("El respaldo no tiene un formato válido")
    class UnsupportedVersion : BackupFailure("Esta versión del respaldo no es compatible")
    class AuthenticationFailed : BackupFailure("No se pudo abrir el respaldo")
    class CryptoUnavailable : BackupFailure("El cifrado no está disponible")
}

/** Only encrypts bytes. The caller owns serialization, password UI and secure storage. */
object BackupEnvelope {
    private val magic = "NTBACKUP".toByteArray(Charsets.US_ASCII)
    private const val version: Byte = 1
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val tagSize = 16
    private const val headerSize = 8 + 1 + saltSize + nonceSize + 4
    private const val iterations = 600_000
    const val maxPlaintextBytes = 16 * 1024 * 1024
    const val maxEnvelopeBytes = 20 * 1024 * 1024

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (payload.isEmpty() || payload.size > maxPlaintextBytes) throw BackupFailure.InvalidInput()
        val random = SecureRandom()
        val salt = ByteArray(saltSize).also { random.nextBytes(it) }
        val nonce = ByteArray(nonceSize).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(headerSize).apply {
            put(magic); put(version); put(salt); put(nonce); putInt(payload.size)
        }.array()
        var key = ByteArray(0)
        try {
            key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(payload)
            return header + encrypted
        } catch (_: GeneralSecurityException) {
            throw BackupFailure.CryptoUnavailable()
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (envelope.size < headerSize + tagSize + 1 || envelope.size > maxEnvelopeBytes) {
            throw BackupFailure.InvalidInput()
        }
        val header = envelope.copyOfRange(0, headerSize)
        val reader = ByteBuffer.wrap(header)
        val fileMagic = ByteArray(magic.size).also { reader.get(it) }
        if (!fileMagic.contentEquals(magic)) throw BackupFailure.InvalidInput()
        if (reader.get() != version) throw BackupFailure.UnsupportedVersion()
        val salt = ByteArray(saltSize).also { reader.get(it) }
        val nonce = ByteArray(nonceSize).also { reader.get(it) }
        val declaredSize = reader.int
        if (declaredSize !in 1..maxPlaintextBytes || envelope.size != headerSize + declaredSize + tagSize) {
            salt.fill(0); nonce.fill(0)
            throw BackupFailure.InvalidInput()
        }
        var key = ByteArray(0)
        try {
            key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return try {
                cipher.doFinal(envelope, headerSize, envelope.size - headerSize)
            } catch (_: AEADBadTagException) {
                throw BackupFailure.AuthenticationFailed()
            }
        } catch (_: GeneralSecurityException) {
            throw BackupFailure.CryptoUnavailable()
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
            header.fill(0)
        }
    }

    private fun validatePassword(password: CharArray) {
        if (password.isEmpty() || password.size > 1_024) throw BackupFailure.PasswordRequired()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (_: GeneralSecurityException) {
            throw BackupFailure.CryptoUnavailable()
        } finally {
            spec.clearPassword()
        }
    }
}
