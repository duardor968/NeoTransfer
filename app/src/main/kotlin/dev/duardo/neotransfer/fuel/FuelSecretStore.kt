package dev.duardo.neotransfer.fuel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.duardo.neotransfer.core.fuel.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts provider ciphertext during background reception; revealing/exporting requires app authorization. */
class FuelSecretStore internal constructor(private val keyProvider: () -> SecretKey) : FuelEnvelopeProtector {
    constructor(context: Context) : this(AndroidFuelKey(context.applicationContext.packageName)::get)

    override fun protect(coupon: FuelCoupon, envelope: CharArray): ProtectedFuelEnvelope {
        var bytes: ByteArray? = null
        try {
            checkIdentity(coupon)
            val revision = FuelProviderEnvelope(envelope).use { it.revision(coupon.serial, coupon.bankReference, coupon.tmReference) }
            require(revision == coupon.secretRevision) { "Las referencias no corresponden a los datos del cupón" }
            bytes = utf8(envelope)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            cipher.updateAAD(aad(coupon.id, revision))
            val encrypted = cipher.doFinal(bytes)
            val base64 = Base64.getEncoder()
            return ProtectedFuelEnvelope(coupon.id, revision, "1:${base64.encodeToString(cipher.iv)}:${base64.encodeToString(encrypted)}")
        } finally { bytes?.fill(0); envelope.fill('\u0000') }
    }

    /** Call after the UI's fresh authorization; this returns the provider ciphertext, not decoded PIN/token. */
    fun open(coupon: FuelCoupon, stored: ProtectedFuelEnvelope): FuelProviderEnvelope {
        checkIdentity(coupon)
        require(stored.couponId == coupon.id && stored.revision == coupon.secretRevision)
        val parts = stored.blob.split(':')
        require(parts.size == 3 && parts[0] == "1")
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[1])
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(coupon.id, stored.revision))
        val bytes = cipher.doFinal(decoder.decode(parts[2]))
        try {
            val chars = decodeUtf8(bytes)
            try {
                val envelope = FuelProviderEnvelope(chars)
                if (envelope.revision(coupon.serial, coupon.bankReference, coupon.tmReference) != stored.revision) {
                    envelope.close(); error("Las referencias del cupón cambiaron")
                }
                return envelope
            } finally { chars.fill('\u0000') }
        } finally { bytes.fill(0) }
    }

    /** Only the already-authorized encrypted backup writer may receive these portable provider envelopes. */
    fun exportForBackup(coupons: List<FuelCoupon>, envelopes: List<ProtectedFuelEnvelope>): List<FuelBackupEnvelope> {
        val byId = coupons.associateBy { it.id }
        require(byId.size == coupons.size && envelopes.map { it.couponId }.distinct().size == envelopes.size)
        require(coupons.filter { it.secretRevision != null }.map { it.id }.toSet() == envelopes.map { it.couponId }.toSet())
        val result = mutableListOf<FuelBackupEnvelope>()
        try {
            for (entry in envelopes) open(byId.getValue(entry.couponId), entry).use { envelope ->
                val chars = envelope.copyForProtection()
                try { result += FuelBackupEnvelope(entry.couponId, entry.revision, chars) }
                finally { chars.fill('\u0000') }
            }
            return result
        } catch (failure: Exception) {
            result.forEach(FuelBackupEnvelope::close)
            throw failure
        }
    }

    /** Prepare all destination-key ciphertext before the repository's atomic import. */
    fun protectAfterRestore(coupons: List<FuelCoupon>, entries: List<FuelBackupEnvelope>): List<ProtectedFuelEnvelope> {
        val byId = coupons.associateBy { it.id }
        require(byId.size == coupons.size && entries.map { it.couponId }.distinct().size == entries.size)
        require(coupons.filter { it.secretRevision != null }.map { it.id }.toSet() == entries.map { it.couponId }.toSet())
        return entries.map { entry ->
            val coupon = byId.getValue(entry.couponId)
            require(coupon.secretRevision == entry.revision)
            val chars = entry.copyForEncryptedBackup()
            try { protect(coupon, chars) } finally { chars.fill('\u0000') }
        }
    }

    private fun checkIdentity(coupon: FuelCoupon) {
        require(coupon.id == FuelCoupon.idFor(coupon.serial) && coupon.bankReference.isNotBlank() && coupon.tmReference.isNotBlank())
    }
    private fun aad(id: String, revision: String) = "fuel:${id.length}:$id:${revision.length}:$revision".toByteArray(Charsets.UTF_8)
    private fun utf8(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(buffer.remaining()).also { buffer.get(it); if (buffer.hasArray()) buffer.array().fill(0) }
    }
    private fun decodeUtf8(bytes: ByteArray): CharArray {
        val buffer = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        return CharArray(buffer.remaining()).also { buffer.get(it); if (buffer.hasArray()) buffer.array().fill('\u0000') }
    }

    private class AndroidFuelKey(packageName: String) {
        private val alias = "$packageName.fuel.coupons.v1"
        fun get(): SecretKey = synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(false).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
    }
    private companion object { val keyLock = Any() }
}
