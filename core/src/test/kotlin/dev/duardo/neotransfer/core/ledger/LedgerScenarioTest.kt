package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.CubacelQuery
import dev.duardo.neotransfer.core.protocol.CubacelQueryKind
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.qr.QrPayment
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** User stories of the green app's mechanics (SPEC §3), driven through the pure ledger. */
class LedgerScenarioTest {
    private val bpa = OwnCard("9205000000000001", Bank.BPA)
    private val bandec = OwnCard("9204000000000001", Bank.BANDEC)
    private val bandec2 = OwnCard("9204000000000002", Bank.BANDEC)
    private val friend = "9204990000000007"
    private fun cup(value: String) = Money.cup(value)
    private fun Phone.balance(card: OwnCard) = view().balances.getValue(card.number).value

    @Test fun `refresh, SMS 14 minutes late, balance updates`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(listOf(40, 46), phone.services)
        assertIs<OperationState.Sent>(phone.state(id))
        assertEquals(phone.now + Ledger.NO_RESPONSE_AFTER, phone.view().wakeAt)
        phone.minutes(14)
        assertIs<OperationState.Sent>(phone.state(id))
        phone.receive(Sms.balanceBpa("845.10"), Duration.ofMinutes(13))
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(cup("845.10"), phone.balance(bpa))
    }

    @Test fun `no SMS in 15 minutes is Sin respuesta, and a late result still closes it`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.minutes(15)
        assertIs<OperationState.NoResponse>(phone.state(id))
        assertNull(phone.view().next) // nothing is resent
        phone.minutes(5)
        phone.receive(Sms.balanceBpa("10.00"), Duration.ofMinutes(19))
        assertIs<OperationState.Confirmed>(phone.state(id))
    }

    @Test fun `incoming transfer adds to the balance at once when the account is an own card`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.balanceBandec(bandec.number, "1000.00"))
        phone.seconds(30)
        phone.receive(Sms.received(bandec.number, "10000.00", "AY000IN1"))
        assertEquals(cup("11000.00"), phone.balance(bandec))
        val movement = phone.view().movements.single()
        assertEquals(Direction.IN, movement.direction)
        assertEquals(bandec.number, movement.creditCard)
        // A later balance answer already includes it: no double count.
        phone.seconds(49)
        phone.receive(Sms.balanceBandec(bandec.number, "11000.00"))
        assertEquals(cup("11000.00"), phone.balance(bandec))
        // Another account of the user: shown, not added to any card.
        phone.receive(Sms.received("9204550000000003", "50.00", "AY000IN2"))
        assertEquals(cup("11000.00"), phone.balance(bandec))
        assertNull(phone.view().movements.first().creditCard)
    }

    @Test fun `BANDEC refresh sends only 46, never 60, and the balance goes to the card named in it`() {
        val phone = Phone(listOf(bandec, bandec2))
        val id = phone.submit(BalanceQuery(Bank.BANDEC, Source.forCard(bandec2, phone.cards)))
        phone.pump()
        assertEquals(listOf(40, 46), phone.services)
        assertEquals("*444*46#", phone.wire.last())
        phone.receive(Sms.balanceBandec(bandec2.number, "320.00", "350.00"))
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(cup("320.00"), phone.balance(bandec2)) // the available balance
        assertNull(phone.balance(bandec))
    }

    @Test fun `the result may arrive before the authentication SMS`() {
        val phone = Phone(listOf(bandec))
        val id = phone.submit(BalanceQuery(Bank.BANDEC))
        phone.pump()
        phone.seconds(20)
        phone.receive(Sms.balanceBandec(bandec.number, "500.00"), Duration.ofSeconds(8))
        assertIs<OperationState.Confirmed>(phone.state(id))
        phone.seconds(40)
        phone.receive(Sms.authBandec(bandec.number), Duration.ofSeconds(50))
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(Bank.BANDEC, phone.view().session?.bank)
        assertEquals(cup("500.00"), phone.balance(bandec))
    }

    @Test fun `busy bank retries a query once after 15 seconds`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.seconds(5)
        phone.receive(Sms.busy(), Duration.ofSeconds(2))
        val retry = assertIs<OperationState.Retrying>(phone.state(id))
        assertEquals(phone.now + Ledger.BUSY_RETRY_DELAY, retry.at)
        assertNull(phone.view().next)
        phone.seconds(15)
        phone.pump()
        assertEquals(listOf(40, 46, 46), phone.services) // the session is still valid: no second 40
        phone.receive(Sms.balanceBpa("20.00"))
        assertIs<OperationState.Confirmed>(phone.state(id))
    }

    @Test fun `a second busy answer leaves the query not executed`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        phone.seconds(15)
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), phone.state(id))
        phone.minutes(1)
        phone.pump()
        assertEquals(3, phone.services.size)
    }

    @Test fun `a retry that cannot authenticate stops instead of looping`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        phone.minutes(61) // the session expired while waiting
        phone.pump(ack = { null }) // the new 40 gets no acknowledgement
        phone.seconds(50)
        assertEquals(NotExecutedReason.AUTHENTICATION, assertIs<OperationState.NotExecuted>(phone.state(id)).reason)
        phone.pump()
        assertEquals(listOf(40, 46, 40), phone.services)
    }

    @Test fun `busy bank never resends money`() {
        val phone = Phone(listOf(bandec))
        val transfer = Transfer(Bank.BANDEC, Source.Default(), friend, cup("300"))
        val id = phone.submit(transfer)
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), phone.state(id))
        phone.minutes(10)
        phone.pump()
        assertEquals(listOf(40, 45), phone.services)
        assertNull(phone.view().pendingDuplicate(transfer)) // "Reintentar" is a new operation, no warning
        // If the bank did execute it after all, the receipt still closes it.
        phone.receive(Sms.sent(Bank.BANDEC, friend, "300.00", "KW000LATE"), Duration.ofMinutes(9))
        assertIs<OperationState.Confirmed>(phone.state(id))
    }

    @Test fun `two identical payments of 300 confirm separately by reference`() {
        val phone = Phone(listOf(bandec))
        val transfer = Transfer(Bank.BANDEC, Source.Default(), friend, cup("300"))
        val first = phone.submit(transfer)
        phone.pump()
        phone.seconds(60)
        val pending = assertNotNull(phone.view().pendingDuplicate(transfer)) // the UI asks before the second one
        assertEquals(first, pending.operation.id)
        val second = phone.submit(transfer)
        phone.pump()
        assertEquals(listOf(40, 45, 45), phone.services)
        phone.seconds(5)
        phone.receive(Sms.sent(Bank.BANDEC, friend, "300.00", "KW000AAA", "700.00"))
        phone.receive(Sms.copy(friend, "300.00", "KW000AAA"))
        assertIs<OperationState.Confirmed>(phone.state(first))
        assertIs<OperationState.Sent>(phone.state(second)) // the copy does not confirm the second payment
        phone.seconds(10)
        phone.receive(Sms.copy(friend, "300.00", "KW000BBB"))
        phone.receive(Sms.sent(Bank.BANDEC, friend, "300.00", "KW000BBB", "400.00"))
        assertIs<OperationState.Confirmed>(phone.state(second))
        val movements = phone.view().movements
        assertEquals(listOf("KW000BBB", "KW000AAA"), movements.map { it.reference })
        assertEquals(listOf(second, first), movements.map { it.operationId })
        assertEquals(cup("400.00"), phone.balance(bandec))
    }

    @Test fun `receipt plus its impersonal copy is one movement`() {
        val phone = Phone(listOf(bpa))
        phone.receive(Sms.copy(friend, "25.00", "BR000ONE"))
        phone.receive(Sms.sent(Bank.BPA, friend, "25.00", "BR000ONE"))
        phone.receive(Sms.sent(Bank.BPA, friend, "25.00", "BR000ONE")) // true duplicate
        val movement = phone.view().movements.single()
        assertEquals(friend, movement.counterparty) // the copy gives the full destination
        assertEquals(Direction.OUT, movement.direction)
        // A copy alone is ambiguous: not a movement.
        phone.receive(Sms.copy(friend, "40.00", "BR000LONE"))
        assertEquals(1, phone.view().movements.size)
    }

    @Test fun `BANDEC receipt that states the balance before the debit`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.balanceBandec(bandec.number, "1000.00"))
        phone.seconds(30)
        phone.receive(Sms.sent(Bank.BANDEC, friend, "300.00", "KW000PRE", balance = "1000.00"))
        assertEquals(cup("700.00"), phone.balance(bandec))
        phone.seconds(30)
        phone.receive(Sms.sent(Bank.BANDEC, friend, "100.00", "KW000POST", balance = "600.00"))
        assertEquals(cup("600.00"), phone.balance(bandec))
        // BPA receipts are taken as written.
        val other = Phone(listOf(bpa))
        other.receive(Sms.balanceBpa("1000.00"))
        other.seconds(30)
        other.receive(Sms.sent(Bank.BPA, friend, "300.00", "BR000SAME", balance = "1000.00"))
        assertEquals(cup("1000.00"), other.balance(bpa))
    }

    @Test fun `DB in a service receipt is the remaining balance`() {
        val phone = Phone(listOf(bpa))
        phone.receive(Sms.balanceBpa("800.00"))
        phone.seconds(20)
        phone.receive("Banco Popular de Ahorro:  La cuenta Nauta Hogar: usuario@nauta.com.cu ha sido pagada con 300.00 CUP. Monto Pagado: 270 CUP. \n Id Transaccion: BR000NH1. \n Saldo Restante: DB 530.00 CUP.")
        assertEquals(cup("530.00"), phone.balance(bpa))
        assertEquals(cup("270"), phone.view().movements.single().amount)
    }

    @Test fun `failed authentication rejects the operation, blocks the bank and needs the key saved again`() {
        val phone = Phone(listOf(bandec))
        val first = phone.submit(BalanceQuery(Bank.BANDEC))
        phone.pump()
        assertEquals(Bank.BANDEC, phone.view().session?.bank)
        phone.seconds(10)
        phone.receive(Sms.wrongPin(), Duration.ofSeconds(3))
        assertEquals("Clave incorrecta", assertIs<OperationState.Rejected>(phone.state(first)).reason)
        assertNull(phone.view().session)
        assertEquals(setOf(Bank.BANDEC), phone.view().blockedBanks)
        val blocked = phone.submit(BalanceQuery(Bank.BANDEC))
        phone.pump()
        assertEquals(NotExecutedReason.WRONG_PIN, assertIs<OperationState.NotExecuted>(phone.state(blocked)).reason)
        assertEquals(2, phone.services.size) // no second 40 with the same key
        phone.minutes(1)
        phone.pinSavedAt[Bank.BANDEC] = phone.now
        assertTrue(phone.view().blockedBanks.isEmpty())
        assertIs<OperationState.NotExecuted>(phone.state(blocked)) // never revived on its own
        phone.seconds(5)
        val after = phone.submit(BalanceQuery(Bank.BANDEC))
        phone.pump()
        assertEquals(listOf(40, 46, 40, 46), phone.services)
        assertIs<OperationState.Sent>(phone.state(after))
    }

    @Test fun `switching bank invalidates the session`() {
        val phone = Phone(listOf(bpa, bandec))
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.seconds(30)
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(listOf(40, 46, 46), phone.services) // same bank: session reused
        phone.submit(BalanceQuery(Bank.BANDEC))
        phone.pump()
        assertEquals(Bank.BANDEC, phone.view().session?.bank)
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(listOf(40, 46, 46, 40, 46, 40, 46), phone.services)
        phone.minutes(61)
        assertNull(phone.view().session) // 60 minutes
    }

    @Test fun `already authenticated by USSD counts as a valid session and disconnect ends it`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump(ack = { if (it.request is dev.duardo.neotransfer.core.protocol.Authenticate) "Usted ya se encuentra autenticado en el sistema." else ACK })
        assertEquals(listOf(40, 46), phone.services)
        assertIs<OperationState.Sent>(phone.state(id))
        assertEquals(Bank.BPA, phone.view().session?.bank)
        val bye = phone.submit(dev.duardo.neotransfer.core.protocol.Disconnect(Bank.BPA))
        phone.pump(ack = { "Usted se ha desconectado" })
        assertIs<OperationState.Answered>(phone.state(bye))
        assertNull(phone.view().session)
    }

    @Test fun `transfer made from Transfermovil appears as an external movement`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.balanceBandec(bandec.number, "900.00"))
        phone.minutes(2)
        phone.receive(Sms.sent(Bank.BANDEC, friend, "150.00", "KW000EXT", balance = "750.00"))
        val movement = phone.view().movements.single()
        assertTrue(movement.external)
        assertEquals(bandec.number, movement.debitCard)
        assertEquals(cup("750.00"), phone.balance(bandec))
    }

    @Test fun `attribution follows the cards and adding a card re-attributes`() {
        val phone = Phone(emptyList())
        phone.receive(Sms.received(bandec.number, "75.00", "AY000ATT"))
        assertNull(phone.view().movements.single().creditCard)
        phone.cards = listOf(bandec)
        assertEquals(bandec.number, phone.view().movements.single().creditCard)
        // With two BANDEC cards, a default-account receipt goes to the card the session is on.
        phone.cards = listOf(bandec, bandec2)
        phone.receive(Sms.authBandec(bandec2.number))
        phone.seconds(30)
        phone.receive(Sms.sent(Bank.BANDEC, friend, "10.00", "KW000DEF"))
        assertEquals(bandec2.number, phone.view().movements.first().debitCard)
    }

    @Test fun `one USSD at a time and the next leaves after the acknowledgement`() {
        val phone = Phone(listOf(bpa))
        val first = phone.submit(BalanceQuery(Bank.BPA))
        val second = phone.submit(CubacelQuery(CubacelQueryKind.BALANCE))
        phone.pump(ack = { if (it.request is CubacelQuery) "Saldo: 10.00 CUP" else null })
        assertEquals(listOf(40), phone.services) // the 40 has no acknowledgement yet
        assertEquals(OperationState.Sending, phone.state(first))
        assertEquals(OperationState.Queued, phone.state(second))
        assertNull(phone.view().next)
        phone.seconds(50) // acknowledgement never came: the authentication failed, nothing goes with it
        assertEquals(NotExecutedReason.AUTHENTICATION, assertIs<OperationState.NotExecuted>(phone.state(first)).reason)
        phone.pump(ack = { "Saldo: 10.00 CUP" })
        assertEquals("Saldo: 10.00 CUP", assertIs<OperationState.Answered>(phone.state(second)).text)
    }

    @Test fun `a late authentication SMS does not revive an operation already reported as not executed`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(Transfer(Bank.BPA, Source.Default(), friend, cup("5")))
        phone.pump(ack = { null })
        phone.seconds(60)
        assertIs<OperationState.NotExecuted>(phone.state(id))
        phone.receive(Sms.authBpa(), Duration.ofSeconds(50))
        assertIs<OperationState.NotExecuted>(phone.state(id))
        assertNull(phone.view().next)
        assertEquals(Bank.BPA, phone.view().session?.bank) // the evidence still serves the session
    }

    @Test fun `a refused acknowledgement or a modem failure means not executed`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump(ack = { if (it.request is BalanceQuery) "Problema de conexion o codigo MMI no valido" else ACK })
        assertEquals(NotExecutedReason.REFUSED, assertIs<OperationState.NotExecuted>(phone.state(id)).reason)
        val failed = phone.submit(BalanceQuery(Bank.BPA))
        val dispatch = assertNotNull(phone.view().next)
        phone.save(dispatch.operation.sending(phone.now).failed("Radio apagada", phone.now))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.SEND_FAILED, "Radio apagada"), phone.state(failed))
    }

    @Test fun `QR payment carries the key and needs no 40`() {
        val phone = Phone(listOf(bandec))
        val payment = QrPayment("TEST1", "123", cup("125"))
        val id = phone.submit(QrPay(Bank.BANDEC, Source.Default(), payment, cup("125")))
        phone.pump()
        assertEquals(listOf(30), phone.services)
        phone.receive("Banco Bandec: Pago completado. \n Fecha: 15/10/2026 \n Entidad: Tienda\n Id Compra: TEST1 \n Importe: 125.00 CUP \n Importe pagado: 125.00 CUP \n No. Transaccion: KW000QR \n Saldo disponible: CR 875.00 CUP")
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(cup("875.00"), phone.balance(bandec))
        assertEquals(MovementKind.QR_PAYMENT, phone.view().movements.single().kind)
    }

    @Test fun `bank rejection closes the operation with a human reason`() {
        val phone = Phone(listOf(bpa))
        val id = phone.submit(Transfer(Bank.BPA, Source.Default(), friend, cup("5")))
        phone.pump()
        phone.receive("Fallo la transferencia. Banco Popular de Ahorro: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA")
        assertEquals("La cuenta no está asociada a una tarjeta magnética.", assertIs<OperationState.Rejected>(phone.state(id)).reason)
        assertTrue(phone.view().movements.isEmpty())
    }

    @Test fun `history reconstructs from the inbox in any order and ignores authentications`() {
        val ordered = Phone(listOf(bandec))
        ordered.receive(Sms.authBandec(bandec.number))
        ordered.seconds(10)
        ordered.receive(Sms.balanceBandec(bandec.number, "100.00"))
        ordered.seconds(10)
        ordered.receive(Sms.received(bandec.number, "20.00", "AY000H1"))
        val view = ordered.view()
        assertEquals(1, view.movements.size)
        assertEquals(cup("120.00"), view.balances.getValue(bandec.number).value)
        assertTrue(view.operations.isEmpty())
        assertEquals("ref:AY000H1", view.movements.single().id)
    }

    @Test fun `last operations fill in what no receipt reported, once, without double counting`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.balanceBandec(bandec.number, "1000.00")) // 15/10, 06:00 in Cuba
        phone.advance(Duration.ofDays(1))
        phone.receive(Sms.sent(Bank.BANDEC, friend, "100.00", "KW000R1"))
        assertEquals(cup("900.00"), phone.balance(bandec))
        val id = phone.submit(HistoryQuery(Bank.BANDEC))
        phone.pump()
        assertEquals(listOf(40, 48), phone.services)
        val answer = Sms.history(
            Sms.row("16/10/2026", "Banca Movil Transferencia", "KW000R1", false, "100.00"), // the receipt already said it
            Sms.row("16/10/2026", "POS Extraccion", "AP000P1", false, "50.00"), // no receipt: card payment
            Sms.row("15/10/2026", "Credito: ", "EA0001", true, "1.25"), // same day as the balance: already in it
        )
        phone.receive(answer)
        assertIs<OperationState.Confirmed>(phone.state(id))
        val movements = phone.view().movements
        assertEquals(listOf("KW000R1", "AP000P1", "EA0001"), movements.map { it.reference })
        val card = movements[1]
        assertEquals(MovementKind.OTHER, card.kind)
        assertEquals("POS Extraccion", card.label)
        assertTrue(card.dateOnly && card.external)
        assertEquals(bandec.number, card.debitCard)
        assertEquals(cup("850.00"), phone.balance(bandec))
        // The next answer repeats the same rows: nothing changes.
        phone.minutes(5)
        phone.receive(answer)
        assertEquals(3, phone.view().movements.size)
        assertEquals(cup("850.00"), phone.balance(bandec))
    }

    @Test fun `a BANDEC branch alert is shown but never moves the balance, and last operations do not repeat it`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.balanceBandec(bandec.number, "18000.00"))
        phone.minutes(30)
        // Queried after the withdrawal, before its alert: the balance already includes it.
        phone.receive(Sms.balanceBandec(bandec.number, "3000.00"))
        phone.advance(Duration.ofHours(12))
        phone.receive(Sms.branchAlert(bandec.number, "15000.00", "OL000S1"))
        phone.receive(Sms.branchAlert(bandec.number, "15000.00", "OL000S1")) // resent: one movement
        assertEquals(cup("3000.00"), phone.balance(bandec))
        val alert = phone.view().movements.single()
        assertEquals(MovementKind.OTHER, alert.kind)
        assertEquals(Direction.OUT, alert.direction)
        assertEquals("Extracción en sucursal", alert.label)
        assertEquals(bandec.number, alert.debitCard)
        assertEquals(cup("15000.00"), alert.amount)
        phone.receive(Sms.history(Sms.row("15/10/2026", "Extraccion en Linea", "OL000S1", false, "15000.00")))
        assertEquals(1, phone.view().movements.size)
        assertEquals(cup("3000.00"), phone.balance(bandec))
    }

    @Test fun `last operations rows without reference dedupe by day, direction and amount`() {
        val phone = Phone(listOf(bandec))
        phone.receive(Sms.sent(Bank.BANDEC, friend, "20.00", "KW000R2"))
        val pos = Sms.row("15/10/2026", "POS Extraccion", null, false, "20.00")
        phone.receive(Sms.history(pos, pos, pos))
        // Three rows of 20 that day: one is the receipt, two are new; a later answer repeating them adds nothing.
        assertEquals(3, phone.view().movements.size)
        phone.receive(Sms.history(pos, pos, pos))
        assertEquals(3, phone.view().movements.size)
        assertEquals(2, phone.view().movements.count { it.reference == null })
    }

    @Test fun `operations survive storage without secrets`() {
        val phone = Phone(listOf(bandec))
        phone.submit(QrPay(Bank.BANDEC, Source.Card(bandec.number), QrPayment("ESTATICO-1", "9", cup("0")), cup("10"), "Compra"))
        phone.pump()
        for (operation in phone.operations) {
            val text = Operation.encode(operation)
            assertEquals(operation, Operation.decode(text))
            assertTrue("12345" !in text)
        }
    }
}
