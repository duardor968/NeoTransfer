package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.Direction
import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.MovementKind
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoticesTest {
    private fun credit(phone: String) = Movement("ref:AY1", MovementKind.TRANSFER, null, Direction.IN, Money.cup("300.00"),
        Instant.EPOCH, Bank.BANDEC, null, "9204000000000001", phone, "AY1", null, null)

    @Test fun `credit names the sender only when exactly one contact has the phone`() {
        val juan = Contact("1", "Juan", listOf("50000009"))
        assertEquals("Juan te envió 300 CUP", Notices.credit(credit("5350000009"), listOf(juan)).title)
        assertEquals("50000009 te envió 300 CUP", Notices.credit(credit("5350000009"), listOf(juan, Contact("2", "Otro", listOf("50000009")))).title)
        assertEquals("50000009 te envió 300 CUP", Notices.credit(credit("5350000009"), emptyList()).title)
    }

    @Test fun amounts() {
        assertEquals("300 CUP", Notices.amount(Money.cup("300.00")))
        assertEquals("10,000 CUP", Notices.amount(Money.cup("10000"))) // es-CU follows es-419: comma groups, point decimals
        assertEquals("300.50 CUP", Notices.amount(Money.cup("300.5")))
    }

    @Test fun `bank keys encode and decode without strings`() {
        val keys = mapOf(Bank.BPA to "1234".toCharArray(), Bank.BANDEC to "12345".toCharArray())
        val decoded = BankKeys.decode(BankKeys.encode(keys))
        assertEquals(keys.keys, decoded.keys)
        keys.forEach { (bank, key) -> assertContentEquals(key, decoded.getValue(bank)) }
        assertFailsWith<IllegalArgumentException> { BankKeys.decode("BPA=12\n".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { BankKeys.decode("BPA=1234".toByteArray()) }
    }
}
