package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CashExtraOperationsTest {
    private val day = LocalDate.of(2026, 9, 24)
    private val identity = ProviderIdentity(ProviderId.BPA)
    private fun qr(amount: String = "0.00", id: String = "ESTATICO123") = QrPayment(id, "7", Money(BigDecimal(amount), Currency.CUP),
        "1", "Compra", "", null, null, day)

    @Test
    fun `cash extra preserves the original merchant fields and explicit source`() {
        val original = qr()
        val source = SourceSelector.Explicit("1234567890123456")
        val request = CashExtraOperations.fromQr(identity, source, null, original, Money(BigDecimal("20.50"), Currency.CUP), today = day)
        assertEquals(source, request.source)
        assertEquals(emptyList(), CashExtraOperations.validateOriginal(request, original, day))
        for (key in listOf("transaction", "provider", "auxiliary", "amountCurrency")) {
            val changed = ServiceRequest(request.operationId, request.identity, request.source, request.currency,
                request.values + (key to if (key == "amountCurrency") "3" else "99"))
            assertTrue(CashExtraOperations.validateOriginal(changed, original, day).isNotEmpty(), key)
        }
        assertFailsWith<IllegalArgumentException> { ServiceOperations.encode(request) }
        val authorized = ServiceRequest(request.operationId, request.identity, request.source, request.currency, request.values + ("pin" to "1234"))
        assertEquals("00", ServiceOperations.parameters(authorized)[9])
    }

    @Test
    fun `cash extra rejects dynamic expired or altered fixed QR values`() {
        val amount = Money(BigDecimal("10.00"), Currency.CUP)
        assertFailsWith<IllegalArgumentException> { CashExtraOperations.fromQr(identity, SourceSelector.Default, Currency.CUP,
            qr("10.00", "DYNAMIC123"), amount, today = day) }
        assertFailsWith<IllegalArgumentException> { CashExtraOperations.fromQr(identity, SourceSelector.Default, Currency.CUP,
            qr("10.00"), Money(BigDecimal("10.01"), Currency.CUP), today = day) }
        assertFailsWith<IllegalArgumentException> { CashExtraOperations.fromQr(identity, SourceSelector.Default, Currency.CUP,
            qr("10.00"), amount, "Otra descripción", today = day) }
        val request = CashExtraOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr("10.00"), amount, today = day)
        assertTrue(CashExtraOperations.validateOriginal(request, qr("10.00"), day.plusDays(1)).isNotEmpty())
    }
}
