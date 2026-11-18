package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.sms.SmsMessage
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone whose clock is not the SMSC's, and a line with several cards of one bank, as seen on a phone in November
 * 2026: two SIMs, the clock set by hand an hour ahead and five BPA cards.
 */
class ClockAndCardsScenarioTest {
    private val bpa = OwnCard("9205000000000001", Bank.BPA)
    private val bpa2 = OwnCard("9205000000000002", Bank.BPA)
    private val friend = "9204990000000007"
    private fun cup(value: String) = Money.cup(value)
    private fun Phone.balance(card: OwnCard) = view().balances.getValue(card.number).value

    /** How far the phone's clock runs from the SMSC's: on time, an hour ahead, an hour behind, four minutes ahead. */
    private val clocks = listOf(Duration.ZERO, Duration.ofHours(1), Duration.ofHours(-1), Duration.ofMinutes(4))

    @Test fun `answers are taken whatever the phone's clock says`() {
        for (ahead in clocks) {
            val phone = Phone(listOf(bpa))
            val id = phone.submit(BalanceQuery(Bank.BPA))
            phone.pump()
            phone.seconds(4)
            phone.receive(Sms.authBpa(), ahead + Duration.ofSeconds(3))
            phone.seconds(2)
            phone.receive(Sms.balanceBpa("845.10"), ahead + Duration.ofSeconds(2))
            assertIs<OperationState.Confirmed>(phone.state(id), "clock $ahead")
            assertEquals(cup("845.10"), phone.balance(bpa))
            assertEquals(Bank.BPA, phone.view().session?.bank)
            // The session runs on the phone's clock: an hour after the authentication, not after its stamp.
            phone.minutes(58)
            assertEquals(Bank.BPA, phone.view().session?.bank, "clock $ahead")
            phone.minutes(3)
            assertNull(phone.view().session)
        }
    }

    @Test fun `a lone answer is taken on a phone an hour ahead, and a busy bank is still told`() {
        val ahead = Duration.ofHours(1)
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.seconds(5)
        phone.receive(Sms.busy(), ahead + Duration.ofSeconds(2))
        assertIs<OperationState.Retrying>(phone.state(id))
        phone.seconds(15)
        phone.pump()
        phone.seconds(40)
        phone.receive(Sms.balanceBpa("20.00"), ahead + Duration.ofSeconds(30))
        assertIs<OperationState.Confirmed>(phone.state(id))
    }

    @Test fun `an answer that left before the request is not its answer, whatever the phone's clock says`() {
        for (ahead in clocks) {
            val phone = Phone(listOf(bpa))
            // A session opened from another app: its SMS arrives in three seconds and tells both clocks apart.
            phone.receive(Sms.authBpa(), ahead + Duration.ofSeconds(3))
            phone.seconds(10)
            val id = phone.submit(BalanceQuery(Bank.BPA))
            phone.pump()
            assertEquals(listOf(46), phone.services)
            // The answer to a query made from that app five minutes ago, held up on its way.
            phone.receive(Sms.balanceBpa("111.00"), ahead + Duration.ofMinutes(5))
            assertIs<OperationState.Sent>(phone.state(id), "clock $ahead")
            phone.seconds(3)
            phone.receive(Sms.balanceBpa("845.10"), ahead + Duration.ofSeconds(2))
            assertEquals(cup("845.10"), assertIs<SmsMessage.Balance>(assertIs<OperationState.Confirmed>(phone.state(id)).result).rows.single().available)
        }
    }

    @Test fun `what the first SMS told about the clocks is not undone by a later one`() {
        val ahead = Duration.ofHours(1)
        val phone = Phone(listOf(bpa))
        val id = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.seconds(5)
        phone.receive(Sms.balanceBpa("845.10"), ahead + Duration.ofSeconds(2))
        assertIs<OperationState.Confirmed>(phone.state(id))
        // The clock is put right ten minutes later: SMS stamped on time arrive, and the answer stays an answer.
        phone.minutes(10)
        phone.receive(Sms.received(bpa.number, "50.00", "AY000IN1"), Duration.ofSeconds(2))
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(cup("895.10"), phone.balance(bpa))
    }

    @Test fun `anonymous replies to overlapping queries preserve each last identified BPA balance`() {
        val phone = Phone(listOf(bpa, bpa2))
        fun query(card: OwnCard): String {
            val source = Source.forCard(card, phone.cards)
            assertEquals(Source.Card(card.number), source)
            return phone.submit(BalanceQuery(Bank.BPA, source)).also { phone.pump() }
        }
        query(bpa)
        phone.receive(Sms.balanceBpa("100.00"))
        phone.minutes(1)
        query(bpa2)
        phone.receive(Sms.balanceBpa("200.00"))
        phone.minutes(1)
        val first = query(bpa)
        val second = query(bpa2)
        phone.seconds(10)
        phone.receive(Sms.balanceBpa("205.00"))
        phone.seconds(3)
        phone.receive(Sms.balanceBpa("105.00"))
        assertIs<OperationState.Sent>(phone.state(first))
        assertIs<OperationState.Sent>(phone.state(second))
        assertEquals(cup("100.00"), phone.balance(bpa))
        assertEquals(cup("200.00"), phone.balance(bpa2))
        phone.minutes(125)
        val fresh = query(bpa2)
        phone.receive(Sms.balanceBpa("210.00"))
        assertIs<OperationState.Confirmed>(phone.state(fresh))
        assertEquals(cup("210.00"), phone.balance(bpa2))
        assertEquals(cup("100.00"), phone.balance(bpa))
    }

    @Test fun `a line with more BPA cards than the wallet sends its card explicitly, and what names no card is no one's`() {
        val phone = Phone(listOf(bpa))
        // One card and nothing known of the line: the default account, and a balance asked from another app is its balance.
        assertEquals(Source.Default(), Source.forCard(bpa, phone.cards, phone.view().movements.paying(Bank.BPA)))
        phone.receive(Sms.balanceBpa("100.00"))
        assertEquals(cup("100.00"), phone.balance(bpa))
        // The receipt of a transfer made from another app names another card as the one that paid.
        phone.minutes(1)
        phone.receive(Sms.sent(Bank.BPA, friend, "50.00", "BR000OTHER", balance = "650.00", from = "9224000000000009"))
        val source = Source.forCard(bpa, phone.cards, phone.view().movements.paying(Bank.BPA))
        assertEquals(Source.Card(bpa.number), source)
        assertNull(phone.view().movements.single().debitCard)
        // That balance is no longer this card's for sure, nor is the next one asked from another app.
        assertNull(phone.balance(bpa))
        phone.minutes(1)
        phone.receive(Sms.balanceBpa("999.00"))
        assertNull(phone.balance(bpa))
        // The app's own query names the card: its answer is the card's.
        phone.minutes(1)
        val id = phone.submit(BalanceQuery(Bank.BPA, source))
        phone.pump()
        phone.receive(Sms.balanceBpa("845.10"), Duration.ofSeconds(2))
        assertIs<OperationState.Confirmed>(phone.state(id))
        assertEquals(cup("845.10"), phone.balance(bpa))
    }

    @Test fun `last operations asked for a card give it what names no card, receipts and earlier answers included`() {
        val phone = Phone(listOf(bpa, bpa2))
        // A credit told only by its impersonal copy, and a QR payment whose receipt names no card: with two cards, no one's.
        phone.receive(Sms.copy(bpa.number, "2500.00", "BR000CRED1"))
        phone.minutes(1)
        phone.receive(Sms.qrBpa("40.00", "BR000PAY01", balance = "960.00"))
        // Last operations asked from another app list both, and still name no card.
        phone.minutes(1)
        phone.receive(Sms.historyBpa(Sms.rowBpa("05/09/2026", "", true, "2500.00", "BR000CRED1"), Sms.rowBpa("07/09/2026", "PLI", false, "40.00", "BR000PAY01")))
        assertNull(phone.view().movements.first { it.reference == "BR000CRED1" }.creditCard)
        assertNull(phone.view().movements.first { it.reference == "BR000PAY01" }.debitCard)
        // The app asks them for the first card: the bank lists them as that card's.
        phone.minutes(1)
        val id = phone.submit(HistoryQuery(Bank.BPA, Source.forCard(bpa, phone.cards)))
        phone.pump()
        phone.receive(Sms.historyBpa(Sms.rowBpa("05/09/2026", "", true, "2500.00", "BR000CRED1"), Sms.rowBpa("07/09/2026", "PLI", false, "40.00", "BR000PAY01")), Duration.ofSeconds(3))
        assertIs<OperationState.Confirmed>(phone.state(id))
        val movements = phone.view().movements
        assertEquals(1, movements.count { it.reference == "BR000CRED1" })
        assertEquals(bpa.number, movements.single { it.reference == "BR000CRED1" }.creditCard)
        assertEquals(1, movements.count { it.reference == "BR000PAY01" })
        assertEquals(bpa.number, movements.single { it.reference == "BR000PAY01" }.debitCard)
        assertTrue(movements.none { it.debitCard == bpa2.number || it.creditCard == bpa2.number })
    }

    @Test fun `a receipt goes to the transfer sent from the card it names`() {
        val phone = Phone(listOf(bpa, bpa2))
        val fromFirst = phone.submit(Transfer(Bank.BPA, Source.Card(bpa.number), friend, cup("300")))
        val fromSecond = phone.submit(Transfer(Bank.BPA, Source.Card(bpa2.number), friend, cup("300")))
        phone.pump()
        phone.receive(Sms.sent(Bank.BPA, friend, "300.00", "BR000SECOND", balance = "700.00", from = bpa2.number))
        assertIs<OperationState.Sent>(phone.state(fromFirst))
        assertIs<OperationState.Confirmed>(phone.state(fromSecond))
        val movement = phone.view().movements.single()
        assertEquals(fromSecond, movement.operationId)
        assertEquals(bpa2.number, movement.debitCard)
        assertEquals(cup("700.00"), phone.balance(bpa2))
        assertNull(phone.balance(bpa))
        // Made from another app, the card named in the receipt is still the one debited.
        phone.minutes(20)
        phone.receive(Sms.sent(Bank.BPA, friend, "50.00", "BR000OTHER", balance = "650.00", from = bpa2.number))
        assertEquals(cup("650.00"), phone.balance(bpa2))
    }

    @Test fun `a receipt from another card is recorded without confirming the only pending transfer`() {
        val phone = Phone(listOf(bpa, bpa2))
        val id = phone.submit(Transfer(Bank.BPA, Source.Card(bpa.number), friend, cup("300")))
        phone.pump()
        phone.seconds(15)
        phone.receive(Sms.sent(Bank.BPA, friend, "300.00", "BR000OTHER", balance = "700.00", from = bpa2.number))
        assertIs<OperationState.Sent>(phone.state(id))
        val movement = phone.view().movements.single()
        assertNull(movement.operationId)
        assertEquals(bpa2.number, movement.debitCard)
        assertNull(phone.balance(bpa))
    }

    @Test fun `a late receipt cannot distinguish identical transfers dialed twelve minutes apart`() {
        val phone = Phone(listOf(bpa, bpa2))
        // Either bank dialog could have been confirmed; the phone recorded no answer for either.
        val unanswered = phone.submit(Transfer(Bank.BPA, Source.Card(bpa.number), bpa2.number, cup("1")))
        phone.pump(ack = { if (it.request.dialed) null else ACK })
        phone.minutes(12)
        val later = phone.submit(Transfer(Bank.BPA, Source.Card(bpa.number), bpa2.number, cup("1")))
        phone.pump(ack = { if (it.request.dialed) null else ACK })
        phone.seconds(10)
        phone.receive(Sms.sent(Bank.BPA, bpa2.number, "1.00", "BR000AGAIN", balance = "1999.00", from = bpa.number), Duration.ofSeconds(3))
        assertIs<OperationState.Sent>(phone.state(later))
        assertIs<OperationState.Sent>(phone.state(unanswered))
        assertNull(phone.view().movements.single().operationId)
    }

    @Test fun `a transfer sent to an own card credits it, though the receipt only masks it`() {
        val phone = Phone(listOf(bpa, bpa2))
        val query = phone.submit(BalanceQuery(Bank.BPA, Source.Card(bpa2.number)))
        phone.pump()
        phone.receive(Sms.balanceBpa("100.00"), Duration.ofSeconds(3))
        assertIs<OperationState.Confirmed>(phone.state(query))
        phone.minutes(1)
        val id = phone.submit(Transfer(Bank.BPA, Source.Card(bpa.number), bpa2.number, cup("1")))
        phone.pump(ack = { if (it.request.dialed) null else ACK })
        phone.seconds(10)
        phone.receive(Sms.sent(Bank.BPA, bpa2.number, "1.00", "BR000OWN01", balance = "1999.00", from = bpa.number), Duration.ofSeconds(3))
        assertIs<OperationState.Confirmed>(phone.state(id))
        val movement = phone.view().movements.single()
        assertEquals(Direction.BETWEEN_OWN, movement.direction)
        assertEquals(bpa.number, movement.debitCard)
        assertEquals(bpa2.number, movement.creditCard)
        assertEquals(cup("1999.00"), phone.balance(bpa))
        assertEquals(cup("101.00"), phone.balance(bpa2))
    }

    @Test fun `an association completed closes its request, and a registration's refusal only a registration`() {
        val phone = Phone(listOf(bpa))
        val association = phone.submit(CardAssociation(Bank.BPA, bpa2.number))
        phone.pump()
        assertEquals(listOf(40, 60), phone.services)
        phone.receive("Banco Popular de Ahorro: La asociacion de la tarjeta fue completada.")
        assertIs<SmsMessage.CardAssociated>(assertIs<OperationState.Confirmed>(phone.state(association)).result)
        val balance = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.receive("Fallo el registro. Banco Popular de Ahorro: EL TELEFONO YA TIENE UNA CUENTA ASOCIADA")
        phone.receive("Fallo el registro. Banco Popular de Ahorro: Error en registro, Nombre y Apellidos incorrectos segun CI , por favor dirijase a la sucursal bancaria.")
        assertIs<OperationState.Sent>(phone.state(balance))
        phone.minutes(1)
        val registration = phone.submit(BankRegistration(Bank.BPA, bpa2.number, "Ana", "Prueba", "Ejemplo", "00000000000"))
        phone.pump()
        phone.receive("Fallo el registro. vuelva a intentarlo, si persiste el problema, contacte con ETECSA")
        assertEquals("Fallo el registro.", assertIs<OperationState.Rejected>(phone.state(registration)).reason)
        assertIs<OperationState.Sent>(phone.state(balance))
    }

    @Test fun `a line the bank does not know says so, and blocks nothing`() {
        val noBanking = "Banco Popular de Ahorro: CLIENTE SIN SERVICIO DE BANCA MOVIL Error de autenticacion, intente nuevamente. " +
            "Si persiste el problema, contacte con su proveedor de servicios."
        val phone = Phone(listOf(bpa))
        // The refusal arrives before the request left: it never does.
        val held = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump(ack = { null })
        phone.seconds(5)
        phone.receive(noBanking, Duration.ofSeconds(2))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.NO_BANKING, "Esta línea no tiene banca móvil en el banco"), phone.state(held))
        // The request left right after the `40` was acknowledged: the refusal is reported on it.
        phone.minutes(2)
        val sent = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        phone.receive(noBanking, Duration.ofSeconds(2))
        assertEquals("Esta línea no tiene banca móvil en el banco", assertIs<OperationState.Rejected>(phone.state(sent)).reason)
        // It is not a wrong key: nothing is blocked, and the next one authenticates again.
        assertTrue(phone.view().blocked.isEmpty())
        assertNull(phone.view().session)
        phone.minutes(2)
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(listOf(40, 40, 46, 40, 46), phone.services)
    }
}
