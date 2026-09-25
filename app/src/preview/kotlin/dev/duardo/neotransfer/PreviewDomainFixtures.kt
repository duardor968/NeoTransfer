package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelProviderEnvelope
import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope
import dev.duardo.neotransfer.data.OperationRecord
import dev.duardo.neotransfer.data.OperationStatus
import dev.duardo.neotransfer.fuel.FuelSecretStore
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec

/** Preview source set only. All values are synthetic; no telephony, provider or AndroidKeyStore access. */
internal data class PreviewDomainFixtures(
    val fuelCoupons: List<FuelCoupon>,
    val protectedFuelEnvelopes: Map<String, ProtectedFuelEnvelope>,
    val fuelSecretStore: FuelSecretStore,
    val miTurnoRequests: List<OperationRecord>,
)

internal fun previewDomainFixtures(now: Instant = Instant.now()): PreviewDomainFixtures {
    // The test vector decodes to synthetic PIN 1234 and TOKEN987; it is provider ciphertext, not a real coupon.
    val providerEnvelope = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
    val store = FuelSecretStore { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
    val observed = now.minusSeconds(3_600).toEpochMilli()
    val reportedDate = now.atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun coupon(serial: String, label: String, paid: String, balance: String?, bankRef: String,
               tmRef: String, secret: Boolean, archived: Boolean = false, hoursAgo: Long = 1): FuelCoupon {
        val revision = if (secret) FuelProviderEnvelope(providerEnvelope.toCharArray()).use {
            it.revision(serial, bankRef, tmRef)
        } else null
        return FuelCoupon(FuelCoupon.idFor(serial), serial, "CUP", paidAmount = paid, balance = balance,
            bankCode = "01", bankReference = bankRef, tmReference = tmRef,
            observedAt = now.minusSeconds(hoursAgo * 3_600).toEpochMilli(), reportedDate = reportedDate,
            balanceObservedAt = balance?.let { observed }, sourceEventId = "preview-fuel-$serial",
            subscriptionId = 1, secretRevision = revision, evidenceEligible = true,
            label = label, archived = archived)
    }

    val coupons = listOf(
        coupon("0000000000001", "Cupón disponible", "10.00", "7.50", "BANK1", "TM1", secret = true),
        coupon("0000000000002", "QR pendiente", "25.00", "25.00", "BANK2", "TM2", secret = false, hoursAgo = 2),
        coupon("0000000000003", "Cupón archivado", "40.00", "18.00", "BANK1", "TM1", secret = true, archived = true, hoursAgo = 24),
        coupon("0000000000004", "Saldo no reportado", "60.00", null, "BANK1", "TM1", secret = true, hoursAgo = 3),
    )
    val envelopes = coupons.filter { it.secretRevision != null }.associate { coupon ->
        coupon.id to store.protect(coupon, providerEnvelope.toCharArray())
    }

    fun turn(id: String, provider: String, bankCode: String, entity: String, branch: String,
             beneficiary: String, status: OperationStatus, hoursAgo: Long): OperationRecord = OperationRecord(
        id = id, kind = "service.miturno.reserve", bankCode = bankCode, subscriptionId = 1,
        destination = beneficiary, amount = null, currency = "CUP",
        startedAt = now.minusSeconds(hoursAgo * 3_600).toEpochMilli(), registrationId = provider,
        source = "0000", phone = "00000000", status = status, specId = "service.miturno.reserve",
        providerId = provider, profileId = "PERSONAL", parameters = mapOf(
            "identity" to beneficiary, "entity" to entity, "branch" to branch,
            "phone" to "00000000", "sourceCurrency" to "CUP"),
    )
    val requests = listOf(
        turn("preview-turn-confirmed", "BPA", "01", "1", "030313", "00000000000", OperationStatus.CONFIRMED, 6),
        turn("preview-turn-uncertain", "BANMET", "03", "4", "4289476", "00000000000", OperationStatus.UNCERTAIN, 2),
    )
    return PreviewDomainFixtures(coupons, envelopes, store, requests)
}
