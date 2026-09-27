package dev.duardo.neotransfer.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagementAndWalletQrTest {
    @Test fun `obsolete CUC QR remains parseable but cannot become a wallet payment`() {
        val qr = QrPayment.parse(mapOf("id_transaccion" to "TEST", "importe" to "10.50", "moneda" to "CUC", "numero_proveedor" to "12"))
        assertEquals(Currency.CUC, qr.amount.currency)
        val identity = ProviderIdentity(ProviderId.MITRANSFER)
        assertFailsWith<IllegalArgumentException> {
            WalletQrOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr, qr.amount)
        }
        val valid = load("wallet-qr").first { it.request.operationId == "wallet.qr.dynamic" }.request
        val direct = ServiceRequest(valid.operationId, valid.identity, valid.source, valid.currency,
            valid.values + ("amountCurrency" to "2"))
        assertTrue(WalletQrOperations.validate(direct).any { it.fieldKey == "amountCurrency" })
        assertFailsWith<IllegalArgumentException> { WalletQrOperations.encode(direct, 0) }
    }

    @Test
    fun `metro association update and direct inquiries remain distinct contracts`() {
        val metro = ProviderIdentity(ProviderId.BANMET)
        assertEquals(60, BankManagementOperations.all.single { it.id == "banmet.associate-account" }.service)
        assertEquals(53, BankManagementOperations.all.single { it.id == "banmet.update-account" }.service)
        assertEquals("*444*72#", BankManagementOperations.encode(ServiceRequest("banmet.loan-query", metro)).valueForTransport())
        assertEquals("*444*73#", BankManagementOperations.encode(ServiceRequest("banmet.locate-transfers", metro)).valueForTransport())
        assertFailsWith<IllegalArgumentException> { BankManagementOperations.encode(ServiceRequest("banmet.update-account", metro,
            values = mapOf("holderIdentity" to "00000000001", "telebankCard" to "9200000001"))) }
        assertFailsWith<IllegalArgumentException> { BankManagementOperations.encode(ServiceRequest("bandec.card-information", ProviderIdentity(ProviderId.BANDEC),
            values = mapOf("card" to "123456"))) }
    }

    private data class Fixture(val name: String, val request: ServiceRequest, val parameters: List<String>)
    private fun load(prefix: String): List<Fixture> = javaClass.getResourceAsStream("/$prefix-contracts.tsv")!!.bufferedReader().useLines { lines ->
        lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split('\t')
            Fixture(p[0], ServiceRequest(p[1], ProviderIdentity(ProviderId.valueOf(p[2]), ProfileId.valueOf(p[3])),
                if (p[4] == "0000") SourceSelector.Default else SourceSelector.Explicit(p[4]), Currency.valueOf(p[5]),
                if (p[6].isEmpty()) emptyMap() else p[6].split(';').associate { val pair = it.split('=', limit = 2); pair[0] to pair[1] }), p[7].split('*'))
        }.toList()
    }

    @Test
    fun `registration management and wallet QR match manual contracts and independent oracle vectors`() {
        for (prefix in listOf("management", "wallet-qr")) {
            val qr = prefix == "wallet-qr"
            val fixtures = load(prefix).associateBy { it.name }
            val specs = (if (qr) WalletQrOperations.all else BankManagementOperations.all).associateBy { it.id }
            for (f in fixtures.values) {
                assertEquals(emptyList(), if (qr) WalletQrOperations.validate(f.request) else BankManagementOperations.validate(f.request), f.name)
                assertEquals(f.parameters, if (qr) WalletQrOperations.parameters(f.request) else BankManagementOperations.parameters(f.request), f.name)
            }
            assertEquals(specs.values.filter { it.transport == OperationTransport.ENCODED_USSD }.map { it.id }.toSet(), fixtures.values.map { it.request.operationId }.toSet())
            javaClass.getResourceAsStream("/$prefix-oracle.tsv")!!.bufferedReader().useLines { lines ->
                lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val p = line.split('\t'); val f = fixtures.getValue(p[0]); val seed = p[1].toInt()
                    val command = if (qr) WalletQrOperations.encode(f.request, seed) else BankManagementOperations.encode(f.request, seed)
                    assertEquals(f.parameters.joinToString("*"), p[2])
                    assertEquals("*444*${specs.getValue(f.request.operationId).service}*${p[3]}*1260416#", command.valueForTransport(), "${f.name}:$seed")
                }
            }
        }
    }

    @Test
    fun `registration fields do not assume a universal account length or mislabel expiry`() {
        val bandec = BankManagementOperations.all.single { it.id == "bandec.register" }
        assertFalse(bandec.requiresSession)
        assertEquals(listOf("card", "name", "expiry"), bandec.fields.map { it.key })
        val metro = load("management").single { it.name == "BANMET-register" }.request
        assertEquals(emptyList(), BankManagementOperations.validate(metro))
        assertTrue(BankManagementOperations.validate(ServiceRequest(metro.operationId, metro.identity,
            values = metro.values + ("telebankCard" to "0000000000000001"))).any { it.fieldKey == "telebankCard" })
        assertEquals("*444*68*01#", BankManagementOperations.encode(ServiceRequest("bpa.remove-registration", ProviderIdentity(ProviderId.BPA))).valueForTransport())
        assertTrue(BankManagementOperations.all.flatMap { it.fields }.filter { it.key.startsWith("matrix") || it.key.startsWith("coordinate") }.all { it.sensitive })
    }

    @Test
    fun `wallet QR retains merchant locked amount description and date rules`() {
        val qr = QrPayment.parse(mapOf("id_transaccion" to "TEST", "importe" to "10.50", "moneda" to "CUP", "numero_proveedor" to "12", "version" to "1"))
        val identity = ProviderIdentity(ProviderId.MITRANSFER)
        val request = WalletQrOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr, Money(BigDecimal("10.50"), Currency.CUP))
        assertTrue(WalletQrOperations.validate(request).isEmpty())
        assertFailsWith<IllegalArgumentException> { WalletQrOperations.encode(request) }
        assertFailsWith<IllegalArgumentException> { WalletQrOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr, Money(BigDecimal("11"), Currency.CUP)) }
        assertFailsWith<IllegalArgumentException> { WalletQrOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr, Money(BigDecimal("10.50"), Currency.USD)) }
        val special = QrPayment.parse(mapOf("id_transaccion" to "TEST", "importe" to "10.50", "moneda" to "CUP", "numero_proveedor" to "12", "version" to "11200301"))
        val classic = ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC)
        assertFailsWith<IllegalArgumentException> { WalletQrOperations.fromQr(classic, SourceSelector.Explicit("0000000000000123"), null, special, qr.amount) }
        val linkedCard = WalletQrOperations.fromQr(classic, SourceSelector.Default, null, special, qr.amount)
        assertEquals("wallet.classic.qr.special", linkedCard.operationId)
        assertEquals(SourcePolicy.NONE, WalletQrOperations.all.single { it.id == linkedCard.operationId }.sourcePolicy)
    }

    @Test
    fun `partial envelopes preserve every encoded atom and correct wallet agency`() {
        val fixture = load("wallet-qr").single { it.name == "CLASSIC-true-true" }.request
        val request = ServiceRequest(fixture.operationId, fixture.identity, fixture.source, fixture.currency,
            fixture.values + ("description" to "Compra 1234567890"))
        val command = WalletQrOperations.encode(request, 37)
        val parts = command.commandsForTransport(request.identity, "1234", 90)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.valueForTransport().length <= 90 })
        assertTrue(parts.all { it.valueForTransport().contains("*31*04*") })
        val reassembled = parts.joinToString("*") { it.valueForTransport().substringAfter("*31*04*").substringBeforeLast("*1260416#") }
        assertEquals(command.valueForTransport().removePrefix("*444*31*").removeSuffix("*1260416#"), reassembled)
        val impossible = UssdCommand(31, "*444*31*" + "1".repeat(160) + "*1260416#")
        assertFailsWith<IllegalArgumentException> { impossible.commandsForTransport(request.identity, "1234") }
        val vote = UssdCommand(215, "*454*215*" + "1*".repeat(80) + "1*1260416#")
        assertFailsWith<IllegalArgumentException> { vote.commandsForTransport(ProviderIdentity(ProviderId.MITRANSFER), "1234") }
    }

    @Test
    fun `stamp partial path preserves DEX parameter swap with original random seed`() {
        val request = ServiceRequest("service.stamp", ProviderIdentity(ProviderId.BPA), currency = Currency.CUP, values = mapOf(
            "municipality" to "2301", "taxpayer" to "00000000001", "taxCode" to "456", "period" to "9/2026", "amount" to "10.50", "entity" to "95016"))
        val oracle = javaClass.getResourceAsStream("/partial-oracle.tsv")!!.bufferedReader().readLines().drop(1).map { it.split('\t') }
        for (vector in oracle) {
            val command = ServiceOperations.encode(request, vector[1].toInt())
            assertEquals(vector[3], command.partialPayload)
            val parts = command.commandsForTransport(request.identity, "1234", 80)
            assertTrue(parts.size > 1)
            val reconstructed = parts.joinToString("*") { it.valueForTransport().substringAfter("*43*01*").substringBeforeLast("*1260416#") }
            assertEquals(vector[3], reconstructed)
        }
    }
}
