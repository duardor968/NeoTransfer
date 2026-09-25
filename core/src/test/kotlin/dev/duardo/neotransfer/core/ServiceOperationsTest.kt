package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceOperationsTest {
    @Test
    fun `published catalog excludes orphan conversion and Nauta Plus preserves active native route`() {
        assertTrue(BankManagementOperations.all.none { it.service == 87 })
        assertFalse(ServiceOperations.all.single { it.id == "service.ofa.contribution" }.supports(ProviderIdentity(ProviderId.BPA)))
        val wallet = ProviderIdentity(ProviderId.MITRANSFER)
        for (id in listOf("wallet.nauta.plus", "wallet.nauta.plus.new"))
            assertEquals(OperationTransport.INTERACTIVE_USSD, WalletOperations.all.single { it.id == id }.transport)
        val username = "Cuenta.LargaSinDominio123456789"
        val request = ServiceRequest("wallet.nauta.plus", wallet, currency = Currency.USD, values = mapOf("username" to username, "mobile" to "60000001"))
        assertEquals(emptyList(), WalletOperations.validate(request))
        assertEquals(listOf(username, "60000001", "1"), WalletOperations.parameters(request))
        for (mobile in listOf("5350000001", "40000001", ""))
            assertTrue(WalletOperations.validate(ServiceRequest(request.operationId, wallet, currency = Currency.USD, values = request.values + ("mobile" to mobile))).isNotEmpty())
        assertTrue(WalletOperations.validate(ServiceRequest(request.operationId, wallet, currency = Currency.USD,
            values = request.values + ("username" to "usuario@nauta.com.cu"))).isNotEmpty())
    }

    @Test
    fun `service inquiries retain provider specific types and fiscal restrictions`() {
        val invoice = mapOf("serviceType" to "gas", "invoice" to "000000000001")
        for ((bank, wire) in listOf(ProviderId.BPA to "4", ProviderId.BANDEC to "5", ProviderId.BANMET to "11")) {
            val request = ServiceRequest("service.query.${bank.name.lowercase()}", ProviderIdentity(bank), values = invoice)
            assertEquals(listOf(wire, "000000000001"), ServiceOperations.parameters(request))
        }
        val bpaWater = ServiceRequest("service.query.bpa", ProviderIdentity(ProviderId.BPA), values = mapOf("serviceType" to "water.havana", "invoice" to "00000000001"))
        assertEquals(listOf("11", "00000000001"), ServiceOperations.parameters(bpaWater))
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest("service.query.bpa", ProviderIdentity(ProviderId.BPA),
            values = mapOf("serviceType" to "telephone", "invoice" to "020000000000001"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest("service.onat.query.complete", ProviderIdentity(ProviderId.BANMET), values = mapOf("rc05" to "00000000001"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest("service.onat.query.fiscal", ProviderIdentity(ProviderId.BANDEC), values = mapOf("rc05" to "00000000001", "rc04" to "1234567"))) }
    }

    @Test
    fun `active contracted routes do not invent a source`() {
        val bandec = ProviderIdentity(ProviderId.BANDEC)
        val add = ServiceRequest("service.contracted.add", bandec, values = mapOf("serviceType" to "1", "invoice" to "010000000000001"))
        assertEquals(listOf("1", "1", "010000000000001"), ServiceOperations.parameters(add))
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest(add.operationId, bandec,
            SourceSelector.Explicit("1234567890123456"), values = add.values)) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest(add.operationId, ProviderIdentity(ProviderId.BPA), values = add.values)) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest(add.operationId, bandec,
            values = add.values + ("invoice" to "020000000000001"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest("service.fuel.status", bandec, values = mapOf("serial" to "123"))) }
    }

    private data class Fixture(val name: String, val request: ServiceRequest, val parameters: List<String>)
    private fun fixtureRows(): List<Fixture> = javaClass.getResourceAsStream("/service-contracts.tsv")!!.bufferedReader().useLines { lines ->
        lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            Fixture(p[0], ServiceRequest(p[1], ProviderIdentity(ProviderId.valueOf(p[2]), ProfileId.valueOf(p[3])),
                if (p[4] == "0000") SourceSelector.Default else SourceSelector.Explicit(p[4]),
                Currency.valueOf(p[5]), if (p[6].isEmpty()) emptyMap() else p[6].split(';').associate { pair ->
                    val parts = pair.split('=', limit = 2); parts[0] to parts[1]
                }), p[7].split('*'))
        }.toList()
    }

    @Test
    fun `source backed requests retain exact field order sentinels and provider variants`() {
        for (fixture in fixtureRows()) {
            val wallet = fixture.request.operationId.startsWith("wallet.")
            val errors = if (wallet) WalletOperations.validate(fixture.request) else ServiceOperations.validate(fixture.request)
            assertEquals(emptyList(), errors, fixture.name)
            val actual = if (wallet) WalletOperations.parameters(fixture.request) else ServiceOperations.parameters(fixture.request)
            assertEquals(fixture.parameters, actual, fixture.name)
        }
        val covered = fixtureRows().map { it.request.operationId }.toSet()
        val encoded = (ServiceOperations.all + WalletOperations.all).filter { it.transport in setOf(OperationTransport.ENCODED_USSD, OperationTransport.INTERACTIVE_USSD) }.map { it.id }.toSet()
        assertEquals(encoded, covered, "Every implemented encoder needs an independently specified request")
    }

    @Test
    fun `deterministic envelopes match independent original codec oracle`() {
        // Oracle: local isolated OriginalCodec, derived from APK 1.260416 and DEX-corrected table reset.
        // ServiceOracle ran seeds 0, 37 and 99 over the manually specified parameter fixtures.
        val fixtures = fixtureRows().associateBy { it.name }
        val specs = (ServiceOperations.all + WalletOperations.all).associateBy { it.id }
        javaClass.getResourceAsStream("/service-oracle.tsv")!!.bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                val p = line.split('\t')
                val request = fixtures.getValue(p[0]).request
                assertEquals(fixtures.getValue(p[0]).parameters.joinToString("*"), p[2], p[0])
                val actual = if (request.operationId.startsWith("wallet.")) WalletOperations.encode(request, p[1].toInt())
                    else ServiceOperations.encode(request, p[1].toInt())
                assertEquals("*444*${specs.getValue(request.operationId).service}*${p[3]}*1260416#", actual.valueForTransport(), "${p[0]} seed ${p[1]}")
            }
        }
    }

    @Test
    fun `cross profile currency raw parameters and malformed identifiers are rejected`() {
        fun invalid(id: String, provider: ProviderId = ProviderId.BPA, profile: ProfileId = ProfileId.PERSONAL,
                    currency: Currency? = Currency.CUP, values: Map<String, String>) =
            ServiceRequest(id, ProviderIdentity(provider, profile), currency = currency, values = values)
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(invalid("service.electricity", values = mapOf("invoice" to "50000001"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(invalid("service.mobile", values = mapOf("mobile" to "50000001", "amount" to "0"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(invalid("service.fuel", values = mapOf("amount" to "0.50"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(invalid("service.mobile", currency = Currency.EUR, values = mapOf("mobile" to "50000001", "amount" to "10"))) }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(invalid("service.nauta", values = mapOf("username" to "a*70#", "accountType" to "1", "amount" to "10"))) }
        assertFailsWith<IllegalArgumentException> { WalletOperations.encode(invalid("wallet.agent.mobile", ProviderId.MITRANSFER, values = mapOf("mobile" to "50000001", "amount" to "10"))) }
        val normal = fixtureRows().first { it.name == "BPA-electric-false" }.request
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(ServiceRequest(normal.operationId, normal.identity, currency = Currency.CUP,
            values = normal.values + ("parameters" to "40*01*1234"))) }
        val dateRequest = fixtureRows().first { it.name == "fine-traffic" }.request
        assertTrue(ServiceOperations.validate(ServiceRequest(dateRequest.operationId, dateRequest.identity, currency = Currency.CUP,
            values = dateRequest.values + ("date" to "31/02/2026"))).any { it.fieldKey == "date" })
    }

    @Test
    fun `vault PIN is absent from the form but required by the encoder`() {
        val fixture = fixtureRows().first { it.name == "cash-extra" }.request
        val withoutPin = ServiceRequest(fixture.operationId, fixture.identity, currency = fixture.currency, values = fixture.values - "pin")
        assertEquals(emptyList(), ServiceOperations.validate(withoutPin))
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(withoutPin, 0) }
        assertTrue(ServiceOperations.all.first { it.id == fixture.operationId }.fields.first { it.key == "pin" }.suppliedByAccess)
        val auth = ServiceRequest("wallet.authenticate", ProviderIdentity(ProviderId.MITRANSFER))
        assertEquals(emptyList(), WalletOperations.validate(auth))
        assertFailsWith<IllegalArgumentException> { WalletOperations.encode(auth) }
        assertFalse(WalletOperations.all.first { it.id == "wallet.register" }.fields.first { it.key == "pin" }.suppliedByAccess)
        assertFalse(WalletOperations.all.first { it.id == "wallet.pin" }.fields.first { it.key == "newPin" }.suppliedByAccess)
        assertTrue(WalletOperations.all.filter { it.id.endsWith(".associate") }.flatMap { it.fields }.filter { it.key == "authorization" }.all { it.sensitive })
    }

    @Test
    fun `explicit source identifiers have contract specific lengths and financial queries require review`() {
        val bpa = ServiceRequest("service.electricity", ProviderIdentity(ProviderId.BPA), SourceSelector.Explicit("12345678901"),
            values = mapOf("invoice" to "0000000000001"))
        assertEquals(emptyList(), ServiceOperations.validate(bpa))
        assertEquals(listOf("0000000000001", "0", "0", "12345678901"), ServiceOperations.parameters(bpa))
        val classic = ServiceRequest("wallet.classic.balance", ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC), SourceSelector.Explicit("12345678901"))
        assertTrue(WalletOperations.validate(classic).any { it.fieldKey == "source" })
        assertTrue(WalletOperations.all.first { it.id == "wallet.statement" }.requiresConfirmation)
        assertTrue(ServiceOperations.all.first { it.id == "service.miturno.reserve" }.requiresConfirmation)
        assertFalse(WalletOperations.all.first { it.id == "wallet.balance" }.requiresConfirmation)
    }

    @Test
    fun `provider choices prevent invented plan versions and mismatched withdrawal currency`() {
        val wallet = ProviderIdentity(ProviderId.MITRANSFER)
        assertFailsWith<IllegalArgumentException> { WalletOperations.encode(ServiceRequest("wallet.plans", wallet, currency = Currency.CUP,
            values = mapOf("mobile" to "50000001", "planId" to "12"))) }
        assertFailsWith<IllegalArgumentException> { WalletOperations.encode(ServiceRequest("wallet.plans", wallet, currency = Currency.CUP,
            values = mapOf("mobile" to "50000001", "planId" to "3", "planVersion" to "9"))) }
        assertFailsWith<IllegalArgumentException> { WalletOperations.encode(ServiceRequest("wallet.withdrawal", wallet, currency = Currency.USD,
            values = mapOf("entity" to "22001", "amount" to "10"))) }
        assertTrue(ProviderOptions.cubacelPlans.all { it.version == "2" })
    }

    @Test
    fun `personal linked cards use their MiTransfer registration rather than a plastic PIN`() {
        val personal = ProviderIdentity(ProviderId.MITRANSFER)
        assertTrue(WalletOperations.all.all { it.authenticationIdentity == personal })
        for (id in listOf("wallet.classic.associate", "wallet.classic.remove"))
            assertEquals(setOf(personal), WalletOperations.all.single { it.id == id }.identities)
        for (id in listOf("wallet.classic.transfer")) {
            val spec = WalletOperations.all.single { it.id == id }
            assertTrue(spec.requiresSession)
            assertTrue(spec.fields.none { it.key == "pin" })
        }
        assertTrue(WalletQrOperations.all.filter { it.identities.any { it.provider == ProviderId.MITRANSFER } }.all { it.authenticationIdentity == personal })
    }
}
