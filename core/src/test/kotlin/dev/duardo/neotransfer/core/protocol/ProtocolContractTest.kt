package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.qr.QrPayment
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolContractTest {
    private val codec = ParameterCodec()
    private val at = Instant.parse("2026-10-15T12:07:05Z")
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readLines().drop(1) }
    private fun wire(request: UssdRequest, seed: Int, pin: String? = null) = request.commands(pin?.toCharArray(), at, seed).map { it.valueForTransport() }
    private fun expected(service: Int, parameters: String, seed: Int) = "*444*$service*${codec.encode(parameters.split('*'), seed)}*1260416#"

    @Test fun `codec matches the 1400 reference vectors and the partial oracle`() {
        val rows = resource("codec-vectors.tsv")
        assertEquals(1400, rows.size)
        for (row in rows + resource("partial-oracle.tsv")) {
            val (case, seed, input, output) = row.split('\t')
            assertEquals(output, codec.encode(input.split('*'), seed.toInt()), "$case seed=$seed")
        }
    }

    @Test fun `800 QR vectors keep their payload and the original packetizer`() {
        val rows = resource("qr-vectors.tsv")
        assertEquals(800, rows.size)
        for (row in rows) {
            val cells = row.split('\t')
            val (name, seed, service) = Triple(cells[0], cells[1].toInt(), cells[2].toInt())
            val bank = Bank.entries.single { it.code == cells[3] }
            val p = cells[4].split('*')
            val unit = if (p[4] == "3") Currency.USD else Currency.CUP
            val amount = Money(BigDecimal(p[3]), unit)
            val static = service == 31
            val payment = QrPayment(p[2], p[6], if (name.contains("editable")) Money(BigDecimal.ZERO, unit) else amount,
                if (name.contains("special")) "11200301" else p[7], if (name.contains("fixed_description")) p[8] else "",
                qrId = if (p.size == 12) p[11] else null)
            val origin = p[if (static) 9 else 8]
            val source = if (origin == "0000") Source.Default(unit) else Source.Card(origin)
            val phone = p.getOrNull(if (static) 10 else 9)
            val request = QrPay(bank, source, payment, amount, if (static && p[8] != "0000") p[8] else "", phone)
            assertEquals(listOf(cells[6]), wire(request, seed, p[1]), "$name seed=$seed")
            assertEquals(cells[8].split('|').map { "$it#" }, partialEncodedCommands(service, bank.code, cells[5], "705").map { it.valueForTransport() })
        }
    }

    @Test fun `QR beyond the USSD limit goes in partial packets and keeps the full id`() {
        val payment = QrPayment("ESTATICO-1", "123", Money.cup("0"), description = "")
        val request = QrPay(Bank.BPA, Source.Default(), payment, Money.cup("10"), "D".repeat(60), "50000001")
        val packets = wire(request, 0, "1234")
        assertTrue(packets.size > 1 && packets.all { it.startsWith("*444*110*") && it.length <= 182 })
        assertFailsWith<IllegalArgumentException> { QrPay(Bank.BPA, Source.Default(), payment, Money.cup("10"), "D".repeat(90), "50000001").commands("1234".toCharArray(), at, 0) }
        assertTrue(packets.all { it.contains("*705*31*01*") }) // sequence = minute 7, second 05
    }

    @Test fun `QR id is shortened like Transfermovil when the call reaches 130`() {
        val id = "A".repeat(40)
        val payment = QrPayment(id, "123", Money.cup("10"))
        val request = QrPay(Bank.BPA, Source.Default(), payment, Money.cup("10"))
        assertEquals(listOf(expected(30, "01*1234*${id.takeLast(15)}*10*1*1*123*1*0000", 3)), wire(request, 3, "1234"))
    }

    @Test fun `in-scope service contracts match the oracle`() {
        val oracle = resource("service-oracle.tsv").map { it.split('\t') }.groupBy({ it[0] }, { it[1].toInt() to it[3] })
        val invoice = "0000000000001"
        val phoneInvoice = "00000000000001"
        val card = Source.Card("0000000000000123")
        val cases = mapOf(
            "BPA-electric-false" to ElectricityBill(Bank.BPA, Source.Default(), invoice),
            "BPA-electric-true" to ElectricityBill(Bank.BPA, card, invoice),
            "BANDEC-electric-false" to ElectricityBill(Bank.BANDEC, Source.Default(), invoice),
            "BANDEC-electric-true" to ElectricityBill(Bank.BANDEC, card, invoice),
            "BPA-phone-false" to PhoneBill(Bank.BPA, Source.Default(), phoneInvoice, Money.cup("10.50")),
            "BPA-phone-true" to PhoneBill(Bank.BPA, card, phoneInvoice, Money.cup("10.50")),
            "BANDEC-phone-false" to PhoneBill(Bank.BANDEC, Source.Default(), phoneInvoice, Money.cup("10.50")),
            "BANDEC-phone-true" to PhoneBill(Bank.BANDEC, card, phoneInvoice, Money.cup("10.50")),
            "BPA-water-havana" to WaterBill(Bank.BPA, Source.Default(), WaterRegion.HAVANA, "0000123"),
            "BPA-water-other" to WaterBill(Bank.BPA, Source.Default(), WaterRegion.OTHER, "0000123"),
            "BPA-water-varadero" to WaterBill(Bank.BPA, Source.Default(), WaterRegion.VARADERO, "0000123"),
            "BANDEC-water-havana" to WaterBill(Bank.BANDEC, Source.Default(), WaterRegion.HAVANA, "0000123"),
            "BANDEC-water-other" to WaterBill(Bank.BANDEC, Source.Default(), WaterRegion.OTHER, "0000123"),
            "BANDEC-water-varadero" to WaterBill(Bank.BANDEC, Source.Default(), WaterRegion.VARADERO, "0000123"),
            "BPA-mobile" to MobileRecharge(Bank.BPA, Source.Default(), "50000001", Money.cup("10.50")),
            "BANDEC-mobile" to MobileRecharge(Bank.BANDEC, Source.Default(), "50000001", Money.cup("10.50")),
        )
        assertEquals(oracle.keys, cases.keys)
        for ((case, request) in cases) for ((seed, encoded) in oracle.getValue(case)) {
            val service = request.commands(seed = seed).single().service
            assertEquals(listOf("*444*$service*$encoded*1260416#"), wire(request, seed), "$case seed=$seed")
        }
    }

    @Test fun `bank contracts follow the decompiled parameter order`() {
        val dest = "9204000000000001"
        val card = "9205000000000009"
        assertEquals(listOf("*444*40*000217*047527*1260416#"), wire(Authenticate(Bank.BPA), 0, "1234"))
        assertEquals(listOf("*444*40*000257*0545323*1260416#"), wire(Authenticate(Bank.BANDEC), 0, "12345"))
        assertEquals(listOf("*444*70#"), wire(Disconnect(Bank.BANDEC), 0))
        assertEquals(listOf("*444*46#"), wire(BalanceQuery(Bank.BANDEC), 0))
        assertEquals(listOf("*444*46*00014*0449*1260416#"), wire(BalanceQuery(Bank.BPA), 0))
        assertEquals(listOf(expected(46, "3*0000", 5)), wire(BalanceQuery(Bank.BPA, Source.Default(Currency.USD)), 5))
        assertEquals(listOf(expected(46, "0*$card", 7)), wire(BalanceQuery(Bank.BPA, Source.Card(card)), 7))
        assertEquals(listOf(expected(48, "0*1*0000", 1)), wire(HistoryQuery(Bank.BPA), 1))
        assertEquals(listOf(expected(48, "0*1*0000", 1)), wire(HistoryQuery(Bank.BANDEC), 1))
        assertEquals(listOf(expected(48, "0*0*$card", 1)), wire(HistoryQuery(Bank.BANDEC, Source.Card(card)), 1))
        assertEquals(listOf(expected(45, "$dest*10.50*1*1*0000*0000*0*0000*0", 99)),
            wire(Transfer(Bank.BPA, Source.Default(), dest, Money.cup("10.50")), 99))
        assertEquals(listOf(expected(45, "$dest*300*0*0*$card*50000001*0*0000*0", 9)),
            wire(Transfer(Bank.BANDEC, Source.Card(card), dest, Money.cup("300"), "50000001"), 9))
        // PqTzgaEYF6: the amount goes as a Java float string.
        assertEquals(listOf(expected(59, "usuario*1*100.0*1*0000000*0000*1", 4)),
            wire(NautaPayment(Bank.BPA, Source.Default(), NautaService.RECHARGE, NautaAccount.parse("usuario@nauta.com.cu"), Money.cup("100")), 4))
        assertEquals(listOf(expected(84, "usuario*2*10.5*0*50000001*0000*2", 4)),
            wire(NautaPayment(Bank.BANDEC, Source.Default(), NautaService.HOME_RECHARGE, NautaAccount.parse("usuario@nauta.co.cu"), Money.cup("10.50"), "50000001"), 4))
        assertEquals(listOf(expected(86, "usuario*1*300.0*0*0000000*$card*3", 4)),
            wire(NautaPayment(Bank.BANDEC, Source.Card(card), NautaService.HOME_DEBT, NautaAccount("usuario"), Money.cup("300")), 4))
        assertEquals(listOf(expected(42, "00000000000001*0", 2)), wire(PhoneBill(Bank.BANDEC, Source.Default(), "00000000000001"), 2))
    }

    @Test fun `Metropolitano is BPA with agency 03 and three differences`() {
        val dest = "9204000000000001"
        val card = "9225950000000001"
        assertEquals(listOf(expected(40, "03*1234", 0)), wire(Authenticate(Bank.BANMET), 0, "1234"))
        assertFailsWith<IllegalArgumentException> { Authenticate(Bank.BANMET).commands("12345".toCharArray()) }
        assertEquals(listOf(expected(46, "1*0000", 3)), wire(BalanceQuery(Bank.BANMET), 3))
        assertEquals(listOf(expected(45, "$dest*10.50*1*1*0000*0000*0*0000*0", 99)),
            wire(Transfer(Bank.BANMET, Source.Default(), dest, Money.cup("10.50")), 99))
        assertEquals(listOf(expected(19, "1*0000123*0.0*1*1*0000", 2)), wire(WaterBill(Bank.BANMET, Source.Default(), WaterRegion.HAVANA, "0000123"), 2))
        // 48 asks for a kind of operation first: 1 is all of them.
        assertEquals(listOf(expected(48, "1*1*0000", 1)), wire(HistoryQuery(Bank.BANMET), 1))
        assertEquals(listOf(expected(48, "1*0*$card", 1)), wire(HistoryQuery(Bank.BANMET, Source.Card(card)), 1))
        // Electricity: 13-digit invoices only.
        assertEquals(listOf(expected(41, "0000000000001*0*1*0000", 2)), wire(ElectricityBill(Bank.BANMET, Source.Default(), "0000000000001"), 2))
        assertFailsWith<IllegalArgumentException> { ElectricityBill(Bank.BANMET, Source.Default(), "00000000001") }
        // Landline: the invoice starts with 01, and no amount pays the total.
        assertEquals(listOf(expected(42, "01000000000001*0*1*0000", 2)), wire(PhoneBill(Bank.BANMET, Source.Default(), "01000000000001"), 2))
        assertFailsWith<IllegalArgumentException> { PhoneBill(Bank.BANMET, Source.Default(), "00000000000001", Money.cup("1")) }
    }

    @Test fun `Cubacel codes are plain and the packages menu is dialed`() {
        assertEquals(listOf("*222#", "*222*266#", "*222*328#", "*222*869#", "*222*767#"),
            CubacelQueryKind.entries.map { wire(CubacelQuery(it), 0).single() })
        assertEquals(listOf("*234*1*50000001*1234*25#"), wire(CubacelTransfer("50000001", 25), 0, "1234"))
        assertEquals("*133#", Cubacel.packages().valueForTransport())
        assertEquals("*133*1*4*4*1#", Cubacel.packages(listOf(1, 4, 4, 1)).valueForTransport())
        assertEquals(UssdTransport.DIAL, Cubacel.packages().transport)
        assertTrue(CubacelQuery(CubacelQueryKind.DATA).commands().all { it.transport == UssdTransport.REQUEST })
    }

    @Test fun `no request ever produces 60, 49, 58, 68 or 69`() {
        val card = Source.Card("9204000000000001")
        val money = Money.cup("10")
        val requests: List<UssdRequest> = Bank.entries.flatMap { bank ->
            listOf(Authenticate(bank), Disconnect(bank), BalanceQuery(bank), BalanceQuery(bank, card), HistoryQuery(bank), HistoryQuery(bank, card),
                Transfer(bank, card, "9205000000000002", money), ElectricityBill(bank, card, "0000000000001"),
                PhoneBill(bank, card, "010000000000001", money), WaterBill(bank, card, WaterRegion.OTHER, "1"),
                NautaPayment(bank, card, NautaService.HOME_DEBT, NautaAccount("a"), money), MobileRecharge(bank, card, "50000001", money),
                QrPay(bank, card, QrPayment("X", "1", money), money))
        } + CubacelQueryKind.entries.map(::CubacelQuery) + CubacelTransfer("50000001", 1)
        for (request in requests) {
            val pin = when (request.secret) { SecretKind.BANK_PIN -> "1234567".take((request as BankRequest).bank.pinLength); SecretKind.CUBACEL_PIN -> "1234"; null -> null }
            for (command in request.commands(pin?.toCharArray(), at, 0)) {
                assertFalse(command.service in setOf(60, 49, 58, 68, 69), "$request")
                assertFalse(Regex("^\\*444\\*(60|49|58|68|69)[*#]").containsMatchIn(command.valueForTransport()), "$request")
            }
        }
    }

    @Test fun `secrets never leak through toString and are validated`() {
        val command = Authenticate(Bank.BPA).commands("1234".toCharArray(), at, 0).single()
        assertEquals("UssdCommand(service=40, transport=REQUEST)", command.toString())
        assertFailsWith<IllegalArgumentException> { Authenticate(Bank.BANDEC).commands("1234".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { Authenticate(Bank.BPA).commands(null) }
        assertFailsWith<IllegalArgumentException> { Transfer(Bank.BPA, Source.Default(), "123", Money.cup("1")) }
        assertFailsWith<IllegalArgumentException> { Transfer(Bank.BPA, Source.Default(), "9204000000000001", Money.cup("0")) }
        assertFailsWith<IllegalArgumentException> { NautaAccount.parse("usuario@gmail.com") }
        assertFailsWith<IllegalArgumentException> { PhoneBill(Bank.BPA, Source.Default(), "00000000000001") }
    }

    @Test fun `one card per bank needs no explicit origin`() {
        val bpa = OwnCard("9205000000000001", Bank.BPA)
        val bandec = OwnCard("9204000000000001", Bank.BANDEC)
        val second = OwnCard("9204000000000002", Bank.BANDEC)
        assertEquals(Source.Default(Currency.CUP), Source.forCard(bpa, listOf(bpa, bandec)))
        assertEquals(Source.Card(bandec.number), Source.forCard(bandec, listOf(bpa, bandec, second)))
    }
}
