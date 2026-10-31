package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.qr.QrPayment
import dev.duardo.neotransfer.core.sms.SmsMessage
import java.math.BigDecimal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The line's services with the banks and the Clásica through MiTransfer, driven through the pure ledger. */
class LineAndClassicScenarioTest {
    private val bpa = OwnCard("9205000000000001", Bank.BPA)
    private val classic = "9760030000000001"
    private val friend = "9760030000000002"
    private fun usd(value: String) = Money(BigDecimal(value), Currency.USD)

    @Test fun `a Classic query opens MiTransfer's session, which takes the bank's slot`() {
        val phone = Phone(listOf(bpa))
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(Bank.BPA, phone.view().session?.bank)
        val id = phone.submit(ClassicBalanceQuery(classic))
        phone.pump()
        assertEquals(listOf(40, 46, 40, 46), phone.services)
        val session = phone.view().session
        assertEquals(MiTransfer, session?.agency)
        assertNull(session?.bank)
        // Its answer is unknown wording: shown as it came, neither confirmed nor refused.
        phone.receive("Monedero Mi Transfer: Su saldo es 120.00 USD")
        assertEquals("Monedero Mi Transfer: Su saldo es 120.00 USD", assertIs<OperationState.Answered>(phone.state(id)).text)
        phone.submit(ClassicHistoryQuery(classic))
        phone.pump()
        assertEquals(listOf(40, 46, 40, 46, 48), phone.services) // same session
        phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(listOf(40, 46, 40, 46, 48, 40, 46), phone.services)
    }

    @Test fun `unknown MiTransfer wording answers only a lone MiTransfer request, never money`() {
        val phone = Phone(emptyList())
        val balance = phone.submit(ClassicBalanceQuery(classic))
        val history = phone.submit(ClassicHistoryQuery(classic))
        phone.pump()
        phone.receive("Monedero Mi Transfer: texto sin plantilla conocida")
        assertIs<OperationState.Sent>(phone.state(balance))
        assertIs<OperationState.Sent>(phone.state(history))
        // A Clásica payment needs no session, and it waits for its receipt: unknown wording never closes it.
        val other = Phone(emptyList())
        val pay = other.submit(ClassicQrPay(classic, QrPayment("TX1", "123", usd("5")), usd("5")))
        other.pump()
        assertEquals(listOf(30), other.services)
        other.receive("Monedero Mi Transfer: texto sin plantilla conocida")
        assertIs<OperationState.Sent>(other.state(pay))
        other.receive("Monedero Mi Transfer: Pago completado. Id Compra: TX1 Importe: 5.00 USD No. Transaccion: MT000P1")
        assertIs<OperationState.Confirmed>(other.state(pay))
        assertEquals(classic, other.view().movements.single().debitCard)
    }

    @Test fun `a Classic transfer is confirmed by MiTransfer's receipt and never resent`() {
        val phone = Phone(emptyList())
        val transfer = ClassicTransfer(classic, friend, usd("10.50"))
        val busy = phone.submit(transfer)
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), phone.state(busy))
        phone.minutes(1)
        phone.pump()
        assertEquals(listOf(40, 45), phone.services)
        val id = phone.submit(transfer)
        phone.pump()
        phone.receive("Monedero Mi Transfer:  La Transferencia fue completada. Beneficiario: $friend Monto: 10.50 USD Nro. Transaccion: MT000T1")
        assertIs<OperationState.Confirmed>(phone.state(id))
        val movement = phone.view().movements.single()
        assertEquals(classic, movement.debitCard)
        assertEquals(friend, movement.counterparty)
        assertNull(movement.bank)
        assertEquals(id, movement.operationId)
    }

    @Test fun `a wrong MiTransfer PIN blocks MiTransfer only`() {
        val phone = Phone(listOf(bpa))
        val first = phone.submit(ClassicBalanceQuery(classic))
        phone.pump()
        phone.receive("Monedero Mi Transfer: Clave incorrecta Error de autenticacion, intente nuevamente.", Duration.ofSeconds(1))
        assertEquals("Clave incorrecta", assertIs<OperationState.Rejected>(phone.state(first)).reason)
        assertEquals(setOf(MiTransfer), phone.view().blocked)
        assertTrue(phone.view().blockedBanks.isEmpty())
        val blocked = phone.submit(ClassicBalanceQuery(classic))
        val bank = phone.submit(BalanceQuery(Bank.BPA))
        phone.pump()
        assertEquals(NotExecutedReason.WRONG_PIN, assertIs<OperationState.NotExecuted>(phone.state(blocked)).reason)
        assertIs<OperationState.Sent>(phone.state(bank))
        phone.pinSavedAt[MiTransfer] = phone.now
        assertTrue(phone.view().blocked.isEmpty())
    }

    @Test fun `a registration needs no session and its answer carries the key once`() {
        val phone = Phone(emptyList())
        val id = phone.submit(BankRegistration(Bank.BANDEC, "9204000000000001", "Ana Prueba", expiry = "2803"))
        phone.pump()
        assertEquals(listOf(49), phone.services)
        phone.receive("Banco Bandec:  La operacion de registro fue completada. Bienvenido a Transfermovil. Clave de Autenticacion: 98765 " +
            "El PIN asociado a su tarjeta 920400XXXXXX0001 es 43210.")
        val result = assertIs<SmsMessage.Registration>(assertIs<OperationState.Confirmed>(phone.state(id)).result)
        assertEquals("98765", String(result.key!!.take()!!))
        assertNull(assertIs<SmsMessage.Registration>((phone.state(id) as OperationState.Confirmed).result).key!!.take())
        // MiTransfer's registration: the PIN is the person's own, and a bank's refusal never closes it.
        val wallet = phone.submit(MiTransferRegistration("00000000000"))
        phone.pump()
        phone.receive("Banco Bandec: EL TELEFONO YA TIENE UNA CUENTA ASOCIADA")
        assertIs<OperationState.Sent>(phone.state(wallet))
        assertEquals(listOf(49, 49), phone.services)
    }

    @Test fun `line services leave once, as asked, and only the query is retried`() {
        val phone = Phone(listOf(bpa))
        val removal = phone.submit(RegistrationRemoval(Bank.BPA))
        phone.pump()
        assertEquals(listOf(68), phone.services) // no 40 before it
        assertEquals("*444*68*01#", phone.wire.last())
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), phone.state(removal))
        phone.minutes(1)
        val change = phone.submit(PinChange(Bank.BPA))
        phone.pump()
        assertEquals(listOf(68, 40, 69), phone.services) // the change goes inside a session opened with the current key
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), phone.state(change))
        phone.minutes(1)
        val accounts = phone.submit(AccountsQuery(Bank.BPA))
        phone.pump()
        phone.receive(Sms.busy(), Duration.ofSeconds(1))
        assertIs<OperationState.Retrying>(phone.state(accounts))
        phone.seconds(15)
        phone.pump()
        assertEquals(listOf(68, 40, 69, 58, 58), phone.services)
        phone.receive(Sms.balanceBpa("845.10"))
        assertIs<OperationState.Confirmed>(phone.state(accounts))
        phone.minutes(30)
        phone.pump()
        assertEquals(5, phone.services.size) // nothing else leaves on its own
    }

    @Test fun `unknown bank wording answers a pending key change of the bank it names, or the only one, and nothing else`() {
        val phone = Phone(listOf(bpa))
        val balance = phone.submit(BalanceQuery(Bank.BPA))
        val change = phone.submit(PinChange(Bank.BANMET))
        phone.pump()
        phone.receive(" Banco Metropolitano: Texto sin plantilla 9999 ")
        assertEquals("Banco Metropolitano: Texto sin plantilla 9999", assertIs<OperationState.Answered>(phone.state(change)).text)
        assertIs<OperationState.Sent>(phone.state(balance))
        // Two changes of different banks: wording that names neither answers neither.
        val other = Phone(emptyList())
        val bpaChange = other.submit(PinChange(Bank.BPA))
        val bandecChange = other.submit(PinChange(Bank.BANDEC))
        other.pump()
        other.receive("Su operacion fue procesada")
        assertIs<OperationState.Sent>(other.state(bpaChange))
        assertIs<OperationState.Sent>(other.state(bandecChange))
        other.receive("Banco Bandec: Su operacion fue procesada")
        assertIs<OperationState.Answered>(other.state(bandecChange))
        assertIs<OperationState.Sent>(other.state(bpaChange))
    }

    @Test fun `association refusals close their own request`() {
        val phone = Phone(emptyList())
        val bank = phone.submit(CardAssociation(Bank.BANDEC, "9204000000000002"))
        phone.pump()
        phone.receive("Error en la asociacion de tarjeta. Banco Bandec: Esta tarjeta ya esta asociada a un celular")
        assertEquals("Esta tarjeta ya está asociada a un celular.", assertIs<OperationState.Rejected>(phone.state(bank)).reason)
        val wallet = phone.submit(ClassicAssociation(classic, "123456", "12345678"))
        phone.pump()
        assertEquals(listOf(40, 60, 40, 111), phone.services)
        phone.receive("Error en la asociación de tarjeta Fincimex: No existe registro de la tarjeta.")
        assertEquals("No existe registro de la tarjeta.", assertIs<OperationState.Rejected>(phone.state(wallet)).reason)
    }

    @Test fun `new requests survive storage without secrets`() {
        val phone = Phone(emptyList())
        listOf(
            BankRegistration(Bank.BPA, "9212000000000001", "Ana", "Prueba", "Ejemplo", "00000000000"),
            AccountsQuery(Bank.BANDEC), CardAssociation(Bank.BANMET, "9205950000000002", "B7"),
            RegistrationRemoval(Bank.BANMET, "00000000000", "9500000001"), PinChange(Bank.BANMET),
            MiTransferRegistration("00000000000"), ClassicAssociation(classic, "654321", "87654321"), ClassicBalanceQuery(classic),
            ClassicHistoryQuery(classic), ClassicTransfer(classic, friend, usd("1")), ClassicQrPay(classic, QrPayment("TX1", "123", usd("1")), usd("1")),
            ClassicRemoval(classic, "00000000000"),
        ).forEach(phone::submit)
        phone.pump()
        assertTrue(phone.operations.any { it.request is dev.duardo.neotransfer.core.protocol.MiTransferAuthenticate })
        for (operation in phone.operations) {
            val text = Operation.encode(operation)
            assertEquals(operation, Operation.decode(text))
            assertTrue("1234" !in text && "9999" !in text && "\"42\"" !in text, text)
        }
    }
}
