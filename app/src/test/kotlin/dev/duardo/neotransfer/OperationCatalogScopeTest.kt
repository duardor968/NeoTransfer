package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.OperationRecord
import dev.duardo.neotransfer.data.OperationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationCatalogScopeTest {
    private val banks = listOf(ProviderIdentity(ProviderId.BPA), ProviderIdentity(ProviderId.BANDEC))
    private val cubacel = ProviderIdentity(ProviderId.CUBACEL)
    private val card = SourceSelector.Explicit("0000000000000123")

    private fun assertBlocked(request: ServiceRequest) {
        assertTrue(OperationCatalog.validate(request).isNotEmpty(), request.operationId)
        assertFailsWith<IllegalArgumentException>(request.operationId) { OperationCatalog.encode(request) }
    }

    @Test fun onlyPersonalBpaBandecAndCubacelIdentitiesAreSupported() {
        for (provider in ProviderId.entries) for (profile in ProfileId.entries) {
            val identity = ProviderIdentity(provider, profile)
            val expected = identity in banks || identity == cubacel
            assertEquals(expected, OperationCatalog.supports(identity), identity.id)
            if (!expected) assertBlocked(ServiceRequest("cubacel.balance", identity))
        }
        assertTrue(OperationCatalog.all.all { spec ->
            spec.identities.isNotEmpty() && spec.identities.all(OperationCatalog::supports)
        })
    }

    @Test fun sharedBillsRetainBothApprovedBanksAndTheirEncoders() {
        val bills = mapOf(
            "service.electricity" to mapOf("invoice" to "0000000000001"),
            "service.water.havana" to mapOf("account" to "0000123"),
            "service.water.other" to mapOf("account" to "0000123"),
            "service.water.varadero" to mapOf("account" to "0000123"),
            "service.mobile" to mapOf("mobile" to "50000001", "amount" to "10.50"),
            "service.nauta" to mapOf("username" to "usuario", "accountType" to "1", "amount" to "10.50"),
            "service.nauta.home" to mapOf("username" to "usuario", "accountType" to "1", "amount" to "10.50"),
            "service.nauta.debt" to mapOf("username" to "usuario", "accountType" to "1", "amount" to "10.50"),
        )
        for ((id, values) in bills) {
            val historical = ServiceOperations.all.single { it.id == id }
            val scopedSpec = assertNotNull(OperationCatalog.find(id))
            assertTrue(historical.supports(ProviderIdentity(ProviderId.BANMET)), id)
            assertEquals(banks.toSet(), scopedSpec.identities, id)
            for (identity in banks) {
                val request = ServiceRequest(id, identity, currency = Currency.CUP, values = values)
                assertEquals(emptyList(), OperationCatalog.validate(request), "$id ${identity.id}")
                assertEquals(scopedSpec.service, OperationCatalog.encode(request).service, id)
            }
            assertBlocked(ServiceRequest(id, ProviderIdentity(ProviderId.BANMET), currency = Currency.CUP, values = values))
        }
    }

    @Test fun telephonePaymentKeepsItsProviderSpecificAmountContract() {
        val invoice = mapOf("invoice" to "010000000000001")
        val bpa = ServiceRequest("service.telephone.bpa", banks[0], currency = Currency.CUP, values = invoice + ("amount" to "10"))
        val bandec = ServiceRequest("service.telephone", banks[1], values = invoice)
        for (request in listOf(bpa, bandec)) {
            assertEquals(emptyList(), OperationCatalog.validate(request))
            assertEquals(42, OperationCatalog.encode(request).service)
        }
        assertBlocked(ServiceRequest(bpa.operationId, bpa.identity, currency = Currency.CUP, values = invoice))
        assertBlocked(ServiceRequest(bandec.operationId, bpa.identity, currency = Currency.CUP, values = invoice))
        assertEquals(setOf(banks[1]), assertNotNull(OperationCatalog.find(bandec.operationId)).identities)
    }

    @Test fun genericBillQueriesRetainApprovedChoicesButCannotQueryGas() {
        val choices = setOf("telephone", "electricity", "water.havana", "water.other", "water.varadero")
        for (identity in banks) {
            val id = "service.query.${identity.provider.name.lowercase()}"
            val spec = assertNotNull(OperationCatalog.find(id))
            assertEquals(choices, spec.fields.single { it.key == "serviceType" }.options.map { it.value }.toSet())
            for (type in choices) {
                val invoice = if (type == "electricity") "0000000000001" else "010000000000001"
                val request = ServiceRequest(id, identity, values = mapOf("serviceType" to type, "invoice" to invoice))
                assertEquals(emptyList(), OperationCatalog.validate(request), "$id $type")
                assertEquals(47, OperationCatalog.encode(request).service)
            }
            val gas = ServiceRequest(id, identity, values = mapOf("serviceType" to "gas", "invoice" to "000000000001"))
            assertEquals(emptyList(), ServiceOperations.validate(gas), "The historical gas contract remains readable")
            assertTrue(OperationCatalog.validate(gas).any { it.fieldKey == "serviceType" })
            assertBlocked(gas)
        }
    }

    @Test fun excludedIdsAreUnavailableEvenWithAnApprovedIdentity() {
        val ids = listOf(
            "gas", "propia", "tfa", "jovenclub", "plans", "wallet.recharge", "onat.rc", "onat.nit",
            "ofa.contribution", "organization", "stamp", "stamp.query", "fine.contravention", "fine.traffic",
            "fine.contravention.query", "fine.traffic.query", "postal", "cash.extra", "loan", "loan.bandec",
            "loan.installment", "deposit.bpa", "deposit.bandec", "deposit.banmet", "deposit.close",
            "miturno.reserve", "miturno.change", "miturno.cancel", "miturno.query",
            "fuel", "fuel.movements", "fuel.list", "fuel.status", "fuel.refresh-key",
            "onat.query.complete", "onat.query.fiscal", "contracted.add", "contracted.query", "contracted.remove", "contracted.pay",
        ).map { "service.$it" } + banks.flatMap { identity ->
            listOf("limits", "change-limits", "digital-pin", "recover-pin", "reprint-card", "open-account",
                "statement", "fiscal-account", "loan-query", "postal-query", "exchange-rate")
                .map { "${identity.provider.name.lowercase()}.$it" }
        } + listOf("cubacel.advance", "cubacel.voucher", "cubacel.call", "cubacel.call99", "cubacel.emergency", "cubacel.infoSms")
        for (id in ids) {
            assertNull(OperationCatalog.find(id), id)
            for (identity in banks + cubacel) {
                val request = ServiceRequest(id, identity)
                assertTrue(OperationCatalog.validate(request).any { it.fieldKey == "operation" }, id)
                assertBlocked(request)
            }
        }
        val gas = ServiceRequest("service.gas", banks[0], currency = Currency.CUP,
            values = mapOf("account" to "000000000001", "paymentType" to "2"))
        assertEquals(emptyList(), ServiceOperations.validate(gas))
        assertBlocked(gas)
        val call = ServiceRequest("cubacel.call", cubacel, values = mapOf("mobile" to "50000001"))
        assertEquals(emptyList(), TelecomOperations.validate(call))
        assertBlocked(call)
    }

    @Test fun historicalWalletClassicAndBanmetSpecsCannotExecute() {
        val historical = WalletOperations.all + WalletQrOperations.all +
            (BankingOperations.all + BankManagementOperations.all + ServiceOperations.all)
                .filter { it.identities.all { identity -> identity.provider == ProviderId.BANMET } }
        for (spec in historical) {
            assertNull(OperationCatalog.find(spec.id), spec.id)
            for (identity in spec.identities + banks) assertBlocked(ServiceRequest(spec.id, identity))
        }
        assertNull(OperationCatalog.find("bfi.balance"))
        assertNull(OperationCatalog.find("enzona.transfer"))
    }

    @Test fun bankingAccessTransfersHistoryAndSourceHelpersRemainAvailable() {
        for (identity in banks) {
            val bank = requireNotNull(identity.bank)
            val prefix = bank.name.lowercase()
            val requests = listOf(
                ServiceRequest("$prefix.authenticate", identity, values = mapOf("pin" to "1".repeat(bank.pinLength))),
                ServiceRequest("$prefix.disconnect", identity),
                ServiceRequest("$prefix.balance", identity, currency = if (bank == Bank.BPA) Currency.CUP else null),
                ServiceRequest("$prefix.recent-operations", identity, currency = if (bank == Bank.BPA) Currency.CUP else null),
                ServiceRequest("$prefix.transfer", identity, card, Currency.USD,
                    mapOf("destination" to "0000000000000002", "amount" to "10.50")),
                ServiceRequest("$prefix.change-key", identity, values = mapOf("newPin" to "2".repeat(bank.pinLength))),
            )
            for (request in requests) {
                val spec = assertNotNull(OperationCatalog.find(request.operationId))
                assertEquals(emptyList(), OperationCatalog.validate(request), request.operationId)
                assertEquals(spec.service, OperationCatalog.encode(request).service)
            }
            for (suffix in listOf("register", "remove-registration", "all-accounts", "associate-account", "card-information"))
                assertNotNull(OperationCatalog.find("$prefix.$suffix"))
            assertEquals(emptyList(), OperationCatalog.validate(ServiceRequest("bank.qr", identity, card, Currency.CUP)))
        }
        for ((id, source) in listOf("bandec.card-balance" to card, "bandec.card-select" to card,
            "bandec.origin-balance" to SourceSelector.Default)) {
            assertNotNull(OperationCatalog.find(id))
            assertEquals(emptyList(), OperationCatalog.validate(ServiceRequest(id, banks[1], source)))
            assertBlocked(ServiceRequest(id, banks[0], source))
        }
        assertBlocked(ServiceRequest("bandec.card-balance", banks[1], SourceSelector.Explicit("123")))
        assertBlocked(ServiceRequest("bank.qr", cubacel))
    }

    @Test fun cubacelUtilitiesKeepPlainCommandsAndTheInteractivePlanEntry() {
        val queries = mapOf("balance" to "*222#", "postpaid" to "*111#", "bonus" to "*222*266#",
            "data" to "*222*328#", "voice" to "*222*869#", "sms" to "*222*767#", "friends" to "*222*264#", "plans" to "*133#")
        for ((suffix, wire) in queries) {
            val request = ServiceRequest("cubacel.$suffix", cubacel)
            assertEquals(emptyList(), OperationCatalog.validate(request))
            assertEquals(wire, OperationCatalog.encode(request).valueForTransport())
        }
        assertEquals(OperationTransport.INTERACTIVE_USSD, assertNotNull(OperationCatalog.find("cubacel.plans")).transport)
        val transfer = ServiceRequest("cubacel.transfer", cubacel,
            values = mapOf("mobile" to "50000001", "pin" to "1234", "amount" to "10.50"))
        assertEquals("*234*1*50000001*1234*10.50#", OperationCatalog.encode(transfer).valueForTransport())
        assertBlocked(ServiceRequest(transfer.operationId, cubacel, values = transfer.values - "pin"))
        assertEquals("*234*2*1234*5678#", OperationCatalog.encode(ServiceRequest("cubacel.pin", cubacel,
            values = mapOf("pin" to "1234", "newPin" to "5678"))).valueForTransport())
    }

    @Test fun approvedIdsStillEnforceCoreValidationAndRejectObsoleteCurrency() {
        val values = mapOf("destination" to "0000000000000002", "amount" to "10.50")
        for (identity in banks) {
            val id = "${identity.provider.name.lowercase()}.transfer"
            assertBlocked(ServiceRequest(id, identity, currency = Currency.CUC, values = values))
            assertBlocked(ServiceRequest(id, identity, currency = Currency.CUP, values = values + ("destination" to "123")))
            assertBlocked(ServiceRequest(id, identity, currency = Currency.CUP, values = values + ("parameters" to "40*01*1234")))
            assertEquals(emptyList(), OperationCatalog.validate(ServiceRequest(id, identity, currency = Currency.USD, values = values)))
        }
        assertTrue(OperationCatalog.all.none { Currency.CUC in it.currencies })
    }

    @Test fun excludedHistoricalKeyChangesStillInvalidateOnlyTheirAccessGeneration() {
        fun record(id: String, status: OperationStatus = OperationStatus.CONFIRMED, generation: String? = "7") =
            OperationRecord("op", "SERVICE", "03", 1, "", null, null, 1L, registrationId = "reg", status = status,
                specId = id, parameters = generation?.let { mapOf("accessGeneration" to it) } ?: emptyMap())
        for (id in listOf("bpa.change-key", "bandec.change-key", "banmet.change-key", "wallet.pin")) {
            for (status in OperationStatus.entries) {
                val changed = record(id, status)
                val expected = status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION,
                    OperationStatus.UNCERTAIN, OperationStatus.CONFIRMED)
                assertEquals(expected, OperationCatalog.needsUpdatedAccess(listOf(changed), "reg", 7), "$id $status")
                assertFalse(OperationCatalog.needsUpdatedAccess(listOf(changed), "other-reg", 7))
                assertFalse(OperationCatalog.needsUpdatedAccess(listOf(changed), "reg", 8))
            }
            for (generation in listOf(null, "invalid"))
                assertTrue(OperationCatalog.needsUpdatedAccess(listOf(record(id, generation = generation)), "reg", 0))
        }
        for (id in listOf("cubacel.pin", "bandec.change-multibank-pin", "bpa.balance", "unknown.change-key"))
            assertFalse(OperationCatalog.needsUpdatedAccess(listOf(record(id)), "reg", 7), id)
    }
}
