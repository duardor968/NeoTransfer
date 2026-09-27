package dev.duardo.neotransfer.core.fuel

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ephemeral coupon PIN and token. Neither value is the bank access PIN. */
class FuelCredential private constructor(private val pin: CharArray, private val token: CharArray) : AutoCloseable {
    fun copyPinForDisplay(): CharArray { checkOpen(); return pin.copyOf() }
    fun copyTokenForDisplay(): CharArray { checkOpen(); return token.copyOf() }
    fun qrPayload(): String {
        checkOpen()
        // Provider output is limited to ASCII letters, digits and spaces by its documented decoding step.
        return "{\"Pin\":\"${String(pin)}\",\"Token\":\"${String(token)}\"}"
    }
    private fun checkOpen() { check(pin.any { it != '\u0000' } && token.any { it != '\u0000' }) }
    override fun close() { pin.fill('\u0000'); token.fill('\u0000') }
    override fun toString() = "FuelCredential"

    companion object {
        /** cH contract: SHA256(bank reference + TM reference), two SHA256 derivations, CBC without padding. */
        fun fromProviderEnvelope(coupon: FuelCoupon, envelope: FuelProviderEnvelope): FuelCredential {
            require(coupon.id == FuelCoupon.idFor(coupon.serial) && coupon.bankReference.isNotBlank() && coupon.tmReference.isNotBlank())
            require(coupon.secretRevision == envelope.revision(coupon.serial, coupon.bankReference, coupon.tmReference)) {
                "Los datos del cupón no corresponden a sus referencias"
            }
            val encoded = envelope.copyForProtection()
            val material = MessageDigest.getInstance("SHA-256").digest((coupon.bankReference + coupon.tmReference).toByteArray(Charsets.UTF_8))
            val password = material.copyOfRange(0, 16)
            val iv = material.copyOfRange(16, 32)
            val salt = "1927271231".toByteArray(Charsets.US_ASCII)
            var derived = ByteArray(0)
            var first: CharArray? = null
            try {
                repeat(2) {
                    val input = derived + password + salt
                    val next = MessageDigest.getInstance("SHA-256").digest(input)
                    input.fill(0); derived.fill(0); derived = next
                }
                val components = String(encoded).split('|')
                require(components.size == 2)
                val key = derived.copyOfRange(0, 16)
                try {
                    first = decode(components[0], key, iv)
                    val second = decode(components[1], key, iv)
                    return FuelCredential(requireNotNull(first), second).also { first = null }
                } finally { key.fill(0) }
            } finally { encoded.fill('\u0000'); material.fill(0); password.fill(0); iv.fill(0); derived.fill(0); first?.fill('\u0000') }
        }

        private fun decode(encoded: String, key: ByteArray, iv: ByteArray): CharArray {
            val ciphertext = Base64.getDecoder().decode(encoded)
            require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                // Reproduce the provider's ASCII output filter; do not confuse this unauthenticated format with local GCM storage.
                val chars = plaintext.filter { byte -> val n = byte.toInt() and 255; n in 48..57 || n in 65..90 || n in 97..122 || n == 32 }
                    .map { (it.toInt() and 255).toChar() }.toCharArray()
                require(chars.any { it != ' ' }) { "No se pudo leer la credencial del cupón" }
                return chars
            } finally { plaintext.fill(0) }
        }
    }
}
