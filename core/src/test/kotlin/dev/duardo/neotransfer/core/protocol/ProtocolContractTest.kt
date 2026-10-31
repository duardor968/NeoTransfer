package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.qr.QrPayment
import java.math.BigDecimal
import java.time.Instant
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolContractTest {
    private val codec = ParameterCodec()
    private val at = Instant.parse("2026-10-15T12:07:05Z")
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readLines().drop(1) }
    private fun wire(request: UssdRequest, seed: Int, pin: String? = null, newKey: String? = null) =
        request.commands(pin?.toCharArray(), at, seed, newKey?.toCharArray()).map { it.valueForTransport() }
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
        // BANDEC asks "Indique 1 para Confirmar" in the same session: only the system dialog can answer it.
        val transfer = Transfer(Bank.BANDEC, Source.Card(card), dest, Money.cup("300"))
        assertTrue(transfer.dialed && transfer.commands(null, at, 9).single().transport == UssdTransport.DIAL)
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

    @Test fun `line services follow each bank's form`() {
        val card = "9212000000000001"
        assertEquals(listOf(expected(49, "01*$card*Ana*Prueba*Ejemplo*00000000000", 7)),
            wire(BankRegistration(Bank.BPA, card, "Ana", "Prueba", "Ejemplo", "00000000000"), 7))
        // BANDEC: the full name and the card's expiry as year and month (wLHKkaC4Ha:94,129).
        assertEquals(listOf(expected(49, "02*9204000000000001*Ana Prueba Ejemplo*2803", 7)),
            wire(BankRegistration(Bank.BANDEC, "9204000000000001", "Ana Prueba Ejemplo", expiry = "2803"), 7))
        // Metropolitano: the 10-digit Telebanca card (RFBEnXW0Xr:95).
        assertEquals(listOf(expected(49, "03*9500000001*José*Muñoz*Ejemplo*00000000000", 7)),
            wire(BankRegistration(Bank.BANMET, "9500000001", "José", "Muñoz", "Ejemplo", "00000000000"), 7))
        assertEquals(listOf("*444*58#"), wire(AccountsQuery(Bank.BANDEC), 0))
        assertEquals(listOf(expected(60, "9204000000000002", 3)), wire(CardAssociation(Bank.BANDEC, "9204000000000002"), 3))
        assertEquals(listOf(expected(60, "9212000000000002", 3)), wire(CardAssociation(Bank.BPA, "9212000000000002"), 3))
        assertEquals(listOf(expected(60, "9205950000000002*B7*42", 3)), wire(CardAssociation(Bank.BANMET, "9205950000000002", "B7"), 3, "42"))
        assertEquals(listOf("*444*68*01#"), wire(RegistrationRemoval(Bank.BPA), 0))
        assertEquals(listOf("*444*68*02#"), wire(RegistrationRemoval(Bank.BANDEC), 0))
        assertEquals(listOf(expected(68, "03*00000000000*1234*9500000001", 5)),
            wire(RegistrationRemoval(Bank.BANMET, "00000000000", "9500000001"), 5, "1234"))
        assertEquals(listOf(expected(69, "5678", 2)), wire(PinChange(Bank.BPA), 2, newKey = "5678"))
        assertEquals(listOf(expected(69, "56789", 2)), wire(PinChange(Bank.BANDEC), 2, newKey = "56789"))
        assertEquals(listOf(expected(69, "03*1234*5678", 2)), wire(PinChange(Bank.BANMET), 2, "1234", "5678"))
    }

    @Test fun `line services validate what each bank asks`() {
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BANMET, "9204000000000001", "Ana", "Prueba", "Ejemplo", "00000000000") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BANMET, "9600000001", "Ana", "Prueba", "Ejemplo", "00000000000") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BANDEC, "9204000000000001", "Ana", expiry = "2813") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BANDEC, "9204000000000001", "Ana", "Prueba", expiry = "2803") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BPA, "9212000000000001", "Ana", "Prueba", null, "00000000000") }
        // Every field must travel: no separators, no characters outside the protocol's dictionary, no stray spaces.
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BPA, "9212000000000001", "Ana*1", "Prueba", "Ejemplo", "00000000000") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BPA, "9212000000000001", "O'Neill", "Prueba", "Ejemplo", "00000000000") }
        assertFailsWith<IllegalArgumentException> { BankRegistration(Bank.BPA, "9212000000000001", " Ana", "Prueba", "Ejemplo", "00000000000") }
        assertFailsWith<IllegalArgumentException> { CardAssociation(Bank.BPA, "9212000000000001", "B7") }
        assertFailsWith<IllegalArgumentException> { CardAssociation(Bank.BANMET, "9205950000000002") }
        assertFailsWith<IllegalArgumentException> { CardAssociation(Bank.BANMET, "9205950000000002", "K1") }
        assertFailsWith<IllegalArgumentException> { CardAssociation(Bank.BANMET, "9205950000000002", "B7").commands("4".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { RegistrationRemoval(Bank.BPA, "00000000000") }
        assertFailsWith<IllegalArgumentException> { RegistrationRemoval(Bank.BANMET, "00000000000") }
        // The new key has the bank's length; Metropolitano also needs the current one.
        assertFailsWith<IllegalArgumentException> { PinChange(Bank.BANDEC).commands(newKey = "5678".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { PinChange(Bank.BANMET).commands(null, newKey = "5678".toCharArray()) }
        assertEquals(listOf(null, null, null, 4, 5, 4), listOf(BankRegistration(Bank.BPA, "9212000000000001", "Ana", "Prueba", "Ejemplo", "1").newKeyLength,
            AccountsQuery(Bank.BPA).newKeyLength, CardAssociation(Bank.BPA, "9212000000000001").newKeyLength,
            PinChange(Bank.BPA).newKeyLength, PinChange(Bank.BANDEC).newKeyLength, PinChange(Bank.BANMET).newKeyLength))
        val random = Random(1)
        val cells = (0..3000).map { CardAssociation.randomCoordinate(random) }.toSet()
        assertEquals(100, cells.size)
        assertTrue(cells.all { CardAssociation(Bank.BANMET, "9205950000000002", it).coordinate == it })
    }

    @Test fun `MiTransfer and the Clasica follow Transfermovil 1260416`() {
        val card = "9760030000000001"
        val other = "9760030000000002"
        val usd = Money(BigDecimal("10.50"), Currency.USD)
        assertEquals(listOf(expected(40, "04*1234", 0)), wire(MiTransferAuthenticate, 0, "1234"))
        assertEquals(listOf(expected(49, "04*0000*00000000000*5678", 4)), wire(MiTransferRegistration("00000000000"), 4, newKey = "5678"))
        assertEquals(listOf(expected(49, "04*0000*K000000*5678", 4)), wire(MiTransferRegistration("K000000"), 4, newKey = "5678")) // passport
        assertEquals(listOf(expected(111, "08*$card*123456*12345678", 6)), wire(ClassicAssociation(card, "123456", "12345678"), 6))
        assertEquals(listOf(expected(46, "1*$card*1", 8)), wire(ClassicBalanceQuery(card), 8))
        assertEquals(listOf(expected(48, "1*$card*3", 8)), wire(ClassicHistoryQuery(card), 8))
        assertEquals(listOf(expected(45, "1*$card*$other*10.50*0*0*0*1", 9)), wire(ClassicTransfer(card, other, usd), 9))
        assertEquals(listOf(expected(45, "1*$card*$other*10.50*0*50000001*0*1", 9)), wire(ClassicTransfer(card, other, usd, "50000001"), 9))
        assertEquals(listOf(expected(68, "08*00000000000*1234*$card", 1)), wire(ClassicRemoval(card, "00000000000"), 1, "1234"))
        // 30/31: the bank form with 04 and the PIN, 3 where banks put the card currency, 0000 as origin, the card last (s9CT6pjRyD:290-359).
        val dynamic = QrPayment("TX1", "123", usd)
        assertEquals(listOf(expected(30, "04*1234*TX1*10.50*3*3*123*1*0000*0*$card", 2)), wire(ClassicQrPay(card, dynamic, usd), 2, "1234"))
        assertEquals(listOf(expected(30, "04*1234*TX1*10.50*3*3*123*1*0000*50000001*$card", 2)),
            wire(ClassicQrPay(card, dynamic, usd, notifyPhone = "50000001"), 2, "1234"))
        val static = QrPayment("ESTATICO-1", "123", Money.cup("0"))
        assertEquals(listOf(expected(31, "04*1234*ESTATICO-1*10*1*3*123*1*Compra*0000*$card", 2)),
            wire(ClassicQrPay(card, static, Money.cup("10"), "Compra"), 2, "1234"))
        assertEquals(listOf(expected(31, "04*1234*ESTATICO-1*10*1*3*123*1*0000*0000*0000*QR1*$card", 2)),
            wire(ClassicQrPay(card, static.copy(qrId = "QR1"), Money.cup("10")), 2, "1234"))
        // Unlike the banks', the id is never shortened; a call over the USSD limit goes in partial packets as agency 04.
        val long = QrPayment("A".repeat(60), "123", usd)
        val packets = wire(ClassicQrPay(card, long, usd), 3, "1234")
        assertTrue(packets.size > 1 && packets.all { it.startsWith("*444*110*") && it.contains("*705*30*04*") && it.length <= 182 })
        val payload = codec.encode(listOf("04", "1234", "A".repeat(60), "10.50", "3", "3", "123", "1", "0000", "0", card), 3)
        assertEquals("*" + payload, packets.joinToString("") { it.substringAfter("*30*04").substringBeforeLast("*1260416#") })
    }

    @Test fun `the Clasica takes only Clasicas, dollars and the payments that name it`() {
        val card = "9760030000000001"
        val usd = Money(BigDecimal("10"), Currency.USD)
        assertFailsWith<IllegalArgumentException> { ClassicBalanceQuery("9760040000000001") } // business Clásica
        assertFailsWith<IllegalArgumentException> { ClassicBalanceQuery("9204000000000001") }
        assertFailsWith<IllegalArgumentException> { ClassicTransfer(card, "9204000000000001", usd) }
        assertFailsWith<IllegalArgumentException> { ClassicTransfer(card, "9760030000000002", Money.cup("10")) }
        assertFailsWith<IllegalArgumentException> { ClassicAssociation(card, "12345", "12345678") }
        assertFailsWith<IllegalArgumentException> { ClassicAssociation(card, "123456", "1234567") }
        assertFailsWith<IllegalArgumentException> { ClassicAssociation("9760040000000001", "123456", "12345678") }
        // The special payment leaves the card out in Transfermóvil: refused.
        assertFailsWith<IllegalArgumentException> { ClassicQrPay(card, QrPayment("TX1", "123", usd, auxiliary = "11200301"), usd) }
        assertFailsWith<IllegalArgumentException> { MiTransferAuthenticate.commands("12345".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { MiTransferRegistration("00000000000").commands(newKey = "12345".toCharArray()) }
        assertEquals(MiTransfer, ClassicTransfer(card, "9760030000000002", usd).agency)
        assertEquals(4, MiTransferRegistration("1").newKeyLength)
    }

    @Test fun `line services and the Clasica's go out only as their own request`() {
        val card = Source.Card("9204000000000001")
        val money = Money.cup("10")
        val classic = "9760030000000001"
        val usd = Money(BigDecimal("10"), Currency.USD)
        val everyday: List<UssdRequest> = Bank.entries.flatMap { bank ->
            listOf(Authenticate(bank), Disconnect(bank), BalanceQuery(bank), BalanceQuery(bank, card), HistoryQuery(bank), HistoryQuery(bank, card),
                Transfer(bank, card, "9205000000000002", money), ElectricityBill(bank, card, "0000000000001"),
                PhoneBill(bank, card, "010000000000001", money), WaterBill(bank, card, WaterRegion.OTHER, "1"),
                NautaPayment(bank, card, NautaService.HOME_DEBT, NautaAccount("a"), money), MobileRecharge(bank, card, "50000001", money),
                QrPay(bank, card, QrPayment("X", "1", money), money))
        } + CubacelQueryKind.entries.map(::CubacelQuery) + CubacelTransfer("50000001", 1) + listOf(
            MiTransferAuthenticate, ClassicBalanceQuery(classic), ClassicHistoryQuery(classic), ClassicTransfer(classic, "9760030000000002", usd),
            ClassicQrPay(classic, QrPayment("X", "1", usd), usd),
        )
        val explicit: List<Pair<UssdRequest, Int>> = Bank.entries.flatMap { bank ->
            val telebanca = bank == Bank.BANMET
            listOf(
                (if (bank == Bank.BANDEC) BankRegistration(bank, "9204000000000001", "Ana Prueba", expiry = "2803")
                else BankRegistration(bank, if (telebanca) "9500000001" else "9212000000000001", "Ana", "Prueba", "Ejemplo", "1")) to 49,
                AccountsQuery(bank) to 58,
                CardAssociation(bank, "9204000000000001", if (telebanca) "A1" else null) to 60,
                (if (telebanca) RegistrationRemoval(bank, "1", "9500000001") else RegistrationRemoval(bank)) to 68,
                PinChange(bank) to 69,
            )
        } + listOf(MiTransferRegistration("1") to 49, ClassicAssociation(classic, "123456", "12345678") to 111, ClassicRemoval(classic, "1") to 68)
        val managed = setOf(49, 58, 60, 68, 69, 111)
        fun sent(request: UssdRequest): List<UssdCommand> {
            val pin = when (request.secret) {
                SecretKind.BANK_PIN -> "1234567".take(request.agency!!.pinLength)
                SecretKind.CUBACEL_PIN -> "1234"
                SecretKind.COORDINATE -> "42"
                null -> null
            }
            return request.commands(pin?.toCharArray(), at, 0, request.newKeyLength?.let { "9".repeat(it).toCharArray() })
        }
        for (request in everyday) for (command in sent(request)) {
            assertFalse(command.service in managed, "$request")
            assertFalse(Regex("^\\*444\\*(49|58|60|68|69|111)[*#]").containsMatchIn(command.valueForTransport()), "$request")
        }
        for ((request, service) in explicit) assertEquals(listOf(service), sent(request).map { it.service }, "$request")
    }

    @Test fun `secrets never leak through toString and are validated`() {
        val command = Authenticate(Bank.BPA).commands("1234".toCharArray(), at, 0).single()
        assertEquals("UssdCommand(service=40, transport=REQUEST)", command.toString())
        assertFailsWith<IllegalArgumentException> { Authenticate(Bank.BANDEC).commands("1234".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { Authenticate(Bank.BPA).commands(null) }
        assertEquals("UssdCommand(service=40, transport=REQUEST)", MiTransferAuthenticate.commands("1234".toCharArray(), at, 0).single().toString())
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
