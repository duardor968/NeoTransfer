package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.core.backup.BackupEnvelope
import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.data.IdentityRecord
import dev.duardo.neotransfer.data.RegistrationRecord
import dev.duardo.neotransfer.data.SnapshotCodec
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.platform.AccessCredentials
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.CharBuffer
import dev.duardo.neotransfer.core.fuel.FuelBackupEnvelope
import dev.duardo.neotransfer.core.fuel.FuelProviderEnvelope
import java.security.MessageDigest

enum class BackupTheme { SYSTEM, LIGHT, DARK }

data class BackupPreferences(val theme: BackupTheme, val notificationsEnabled: Boolean)

/** Owns credential bytes until restore completes or is cancelled. */
class DecodedBackup internal constructor(
    val snapshot: WalletSnapshot,
    val credentials: ByteArray,
    val preferences: BackupPreferences,
    val fuelEnvelopes: List<FuelBackupEnvelope> = emptyList(),
) : AutoCloseable {
    override fun close() { credentials.fill(0); fuelEnvelopes.forEach(FuelBackupEnvelope::close) }
}

sealed class BackupPayloadFailure(message: String) : Exception(message) {
    class InvalidPayload : BackupPayloadFailure("El contenido del respaldo no es válido")
    class UnsupportedVersion : BackupPayloadFailure("Esta versión del respaldo no es compatible")
}

/** V2 adds portable fuel capsules; V1 wallet and bank access backups remain readable. */
object BackupPayload {
    private val magic = "NTPAYLD".toByteArray(Charsets.US_ASCII)
    private const val headerSize = 18
    private const val maxCredentialBytes = 65_536
    private const val maxFuelEntries = 10_000
    private const val maxProviderBytes = 8_192

    fun encode(snapshot: WalletSnapshot, credentials: ByteArray, preferences: BackupPreferences,
               fuelEnvelopes: List<FuelBackupEnvelope> = emptyList()): ByteArray {
        if (credentials.size > maxCredentialBytes) invalid()
        try { validateFuel(snapshot, fuelEnvelopes) } catch (_: IllegalArgumentException) { invalid() }
        val wallet = SnapshotCodec.encode(snapshot)
        val fuel = mutableListOf<ByteArray>()
        try {
            for (entry in fuelEnvelopes) {
                fuel += entry.couponId.toByteArray(Charsets.UTF_8)
                fuel += entry.revision.toByteArray(Charsets.UTF_8)
                val chars = entry.copyForEncryptedBackup()
                try {
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(chars))
                    fuel += ByteArray(encoded.remaining()).also { encoded.get(it); if (encoded.hasArray()) encoded.array().fill(0) }
                } finally { chars.fill('\u0000') }
            }
            val size = headerSize + 4L + wallet.size + credentials.size + fuel.sumOf { 4L + it.size }
            if (wallet.isEmpty() || size > BackupEnvelope.maxPlaintextBytes) invalid()
            return ByteBuffer.allocate(size.toInt()).apply {
                put(magic); put(2.toByte()); putInt(wallet.size); putInt(credentials.size)
                put(preferences.theme.ordinal.toByte()); put((if (preferences.notificationsEnabled) 1 else 0).toByte())
                putInt(fuelEnvelopes.size); put(wallet); put(credentials)
                fuel.forEach { putInt(it.size); put(it) }
            }.array()
        } finally { wallet.fill(0); fuel.forEach { it.fill(0) } }
    }

    fun decode(bytes: ByteArray): DecodedBackup {
        if (bytes.size < headerSize + 1 || bytes.size > BackupEnvelope.maxPlaintextBytes) invalid()
        val reader = ByteBuffer.wrap(bytes)
        if (!ByteArray(magic.size).also { reader.get(it) }.contentEquals(magic)) invalid()
        val version = reader.get().toInt()
        if (version !in 1..2) throw BackupPayloadFailure.UnsupportedVersion()
        var wallet: ByteArray? = null
        var credentials: ByteArray? = null
        val fuel = mutableListOf<FuelBackupEnvelope>()
        try {
            val walletSize = reader.int
            val credentialSize = reader.int
            val theme = BackupTheme.entries.getOrNull(reader.get().toInt()) ?: invalid()
            val notify = when (reader.get().toInt()) { 0 -> false; 1 -> true; else -> invalid() }
            val count = if (version == 2) reader.int else 0
            if (walletSize <= 0 || credentialSize !in 0..maxCredentialBytes || count !in 0..maxFuelEntries ||
                walletSize.toLong() + credentialSize + count * 12L > reader.remaining()) invalid()
            wallet = ByteArray(walletSize).also { reader.get(it) }
            credentials = ByteArray(credentialSize).also { reader.get(it) }
            strictChars(wallet).fill('\u0000')
            val snapshot = SnapshotCodec.decode(wallet)
            repeat(count) {
                val id = readText(reader, 64)
                val revision = readText(reader, 64)
                val chars = readChars(reader, maxProviderBytes)
                try { fuel += FuelBackupEnvelope(id, revision, chars) } finally { chars.fill('\u0000') }
            }
            if (reader.hasRemaining()) invalid()
            validateFuel(snapshot, fuel)
            return DecodedBackup(snapshot, credentials, BackupPreferences(theme, notify), fuel)
        } catch (error: Exception) {
            credentials?.fill(0); fuel.forEach(FuelBackupEnvelope::close)
            if (error is BackupPayloadFailure) throw error
            throw BackupPayloadFailure.InvalidPayload()
        } finally { wallet?.fill(0) }
    }

    private fun validateFuel(snapshot: WalletSnapshot, entries: List<FuelBackupEnvelope>) {
        val coupons = snapshot.fuelCoupons
        val byId = coupons.associateBy { it.id }
        if (byId.size != coupons.size || entries.size > maxFuelEntries ||
            entries.map { it.couponId }.distinct().size != entries.size ||
            coupons.filter { it.secretRevision != null }.map { it.id }.toSet() != entries.map { it.couponId }.toSet()) invalid()
        for (entry in entries) {
            val coupon = byId[entry.couponId] ?: invalid()
            if (entry.couponId.length > 64 || !entry.revision.matches(Regex("[0-9a-f]{64}")) ||
                coupon.secretRevision != entry.revision || coupon.id != "fuel:${coupon.serial}" ||
                !coupon.serial.matches(Regex("[0-9]{13}")) || coupon.bankReference.isBlank() || coupon.tmReference.isBlank()) invalid()
            val chars = entry.copyForEncryptedBackup()
            try {
                // Provider capsules are ASCII base64 pairs, never local Keystore blobs or decoded values.
                if (chars.size !in 1..maxProviderBytes || chars.count { it == '|' } != 1 ||
                    chars.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=|") }) invalid()
                val parts = String(chars).split('|')
                for (part in parts) {
                    val decoded = java.util.Base64.getDecoder().decode(part)
                    try { if (decoded.isEmpty() || decoded.size % 16 != 0) invalid() } finally { decoded.fill(0) }
                }
                FuelProviderEnvelope(chars).use {
                    if (it.revision(coupon.serial, coupon.bankReference, coupon.tmReference) != entry.revision) invalid()
                }
            } finally { chars.fill('\u0000') }
        }
    }

    private fun readText(reader: ByteBuffer, max: Int): String {
        val chars = readChars(reader, max)
        return try { String(chars) } finally { chars.fill('\u0000') }
    }
    private fun readChars(reader: ByteBuffer, max: Int): CharArray {
        val length = reader.int
        if (length !in 1..max || length > reader.remaining()) invalid()
        val bytes = ByteArray(length).also { reader.get(it) }
        return try { strictChars(bytes) } finally { bytes.fill(0) }
    }
    private fun strictChars(bytes: ByteArray): CharArray {
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
        return CharArray(decoded.remaining()).also { decoded.get(it); if (decoded.hasArray()) decoded.array().fill('\u0000') }
    }
    private fun invalid(): Nothing = throw BackupPayloadFailure.InvalidPayload()
}
/** Candidate PINs stay in memory until the caller finishes or cancels this restore session. */
class CredentialMergePlan internal constructor(
    val snapshotForImport: WalletSnapshot,
    val conflicts: List<String>,
    val pendingRegistrationIds: List<String>,
    val recoverableRegistrationIds: Set<String>,
    private val candidates: Map<String, Pair<String, CharArray>>,
) : AutoCloseable {
    fun encodeMerged(current: AccessCredentials, acceptedRegistrationIds: Set<String>): ByteArray? {
        val merged = AccessCredentials.empty()
        return try {
            current.legacy.forEach { (bank, pin) -> merged.legacy[bank] = pin.copyOf() }
            current.scoped.forEach { (alias, pin) -> merged.scoped[alias] = pin.copyOf() }
            candidates.forEach { (registrationId, entry) ->
                if (registrationId in acceptedRegistrationIds || registrationId in recoverableRegistrationIds) {
                    val (alias, pin) = entry
                    check(alias !in merged.scoped) { "Alias de acceso duplicado" }
                    merged.scoped[alias] = pin.copyOf()
                }
            }
            if (merged.legacy.isEmpty() && merged.scoped.isEmpty()) null else merged.encode()
        } finally {
            merged.close()
        }
    }

    override fun close() { candidates.values.forEach { it.second.fill('\u0000') } }
}

/** Never reuses imported aliases or assigns one unscoped PIN to several registrations. */
object CredentialMerge {
    fun plan(
        currentSnapshot: WalletSnapshot,
        current: AccessCredentials,
        incomingSnapshot: WalletSnapshot,
        incoming: AccessCredentials,
    ): CredentialMergePlan {
        val existingById = currentSnapshot.registrations.associateBy(RegistrationRecord::id)
        val existingIds = existingById.keys
        fun recoverable(id: String): Boolean = existingById[id]?.let { registration ->
            val alias = registration.credentialAlias
            !registration.enabled && alias?.startsWith("restored:") == true && alias !in current.scoped
        } == true
        val fresh = incomingSnapshot.registrations.filter { it.id !in existingIds || recoverable(it.id) }
        val freshByAlias = fresh.filter { it.credentialAlias != null }.groupBy { it.credentialAlias }
        val allIds = (existingIds + incomingSnapshot.registrations.map(RegistrationRecord::id)).toMutableSet()
        val usedAliases = (current.scoped.keys + currentSnapshot.registrations.mapNotNull(RegistrationRecord::credentialAlias)).toMutableSet()
        val assignments = linkedMapOf<String, Pair<String, CharArray>>()
        val holders = mutableListOf<RegistrationRecord>()
        val issues = mutableListOf<String>()
        val recoverableIds = mutableSetOf<String>()
        val holderIdentityId = stableKey("holder", incomingSnapshot.registrations.map(RegistrationRecord::id).sorted().joinToString("|"))

        fun aliasFor(id: String): String {
            val base = "restored:$id"
            var alias = base
            var index = 1
            while (!usedAliases.add(alias)) alias = "$base:${index++}"
            return alias
        }
        fun assign(id: String, pin: CharArray) {
            if (id in assignments) return
            val previous = existingById[id]?.credentialAlias?.takeIf { recoverable(id) }
            if (previous != null) recoverableIds += id
            assignments[id] = (previous ?: aliasFor(id)) to pin.copyOf()
        }
        fun hold(source: String, bank: Bank?, pin: CharArray) {
            val id = stableKey("access", source)
            if (!allIds.add(id)) {
                if (recoverable(id)) assign(id, pin)
                else issues += "Acceso importado ya presente: $id"
                return
            }
            val provider = bank?.name ?: "MITRANSFER"
            val alias = aliasFor(id)
            holders += RegistrationRecord(id, holderIdentityId, provider, "PERSONAL", bank?.code,
                null, null, "Acceso por reasociar", credentialAlias = alias, enabled = false)
            assignments[id] = alias to pin.copyOf()
            issues += "Acceso por reasociar: $id"
        }

        incoming.scoped.forEach { (oldAlias, pin) ->
            val linked = freshByAlias[oldAlias].orEmpty()
            if (linked.size == 1) assign(linked.single().id, pin)
            else {
                val banks = linked.map { registration -> Bank.entries.firstOrNull { it.code == registration.bankCode } }
                val bank = banks.firstOrNull()?.takeIf { candidate -> banks.isNotEmpty() && banks.all { it == candidate } }
                hold("scoped:$oldAlias", bank, pin)
            }
        }
        incoming.legacy.forEach { (bank, pin) ->
            val sameScoped = fresh.filter { it.bankCode == bank.code &&
                assignments[it.id]?.second?.contentEquals(pin) == true }
            if (sameScoped.size == 1 && fresh.count { it.bankCode == bank.code } == 1) return@forEach
            val linked = fresh.filter { it.bankCode == bank.code && it.id !in assignments }
            if (linked.size == 1) assign(linked.single().id, pin)
            else hold("legacy:${bank.name}", bank, pin)
        }
        val rewritten = incomingSnapshot.registrations.map { registration ->
            if (recoverable(registration.id)) requireNotNull(existingById[registration.id])
            else if (registration.id in existingIds) registration
            else registration.copy(credentialAlias = assignments[registration.id]?.first,
                enabled = false)
        }
        val identities = if (holders.isEmpty() || incomingSnapshot.identities.any { it.id == holderIdentityId } ||
            currentSnapshot.identities.any { it.id == holderIdentityId }) incomingSnapshot.identities
            else incomingSnapshot.identities + IdentityRecord(holderIdentityId, "Accesos importados")
        return CredentialMergePlan(
            incomingSnapshot.copy(identities = identities, registrations = rewritten + holders),
            issues, assignments.keys.toList(), recoverableIds, assignments,
        )
    }

    private fun stableKey(kind: String, value: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("$kind:$value".toByteArray(Charsets.UTF_8))
        return "restored-$kind-" + hash.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
