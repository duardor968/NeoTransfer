package dev.duardo.neotransfer.core.miturno

import dev.duardo.neotransfer.core.*
import java.time.LocalDate
import kotlin.test.*

class MiTurnoTest {
    @Test
    fun `management choices come from active selectors and keep bank services provider specific`() {
        val bpa = ProviderIdentity(ProviderId.BPA)
        val metro = ProviderIdentity(ProviderId.BANMET)
        assertEquals(listOf("1", "2", "3"), MiTurnoContracts.servicesFor(bpa).map { it.code })
        assertEquals((1..14).map(Int::toString), MiTurnoContracts.servicesFor(metro).map { it.code })
        assertEquals(MiTurnoContracts.servicesFor(metro), MiTurnoContracts.servicesFor(ProviderIdentity(ProviderId.MITRANSFER)))
        assertTrue(MiTurnoContracts.servicesFor(ProviderIdentity(ProviderId.BFI)).isEmpty())
        assertTrue(ServiceOperations.validate(ServiceRequest("service.miturno.cancel", bpa,
            values = mapOf("identity" to "00000000001", "serviceType" to "4"))).any { it.fieldKey == "serviceType" })
        assertEquals(emptyList(), ServiceOperations.validate(ServiceRequest("service.miturno.cancel", metro,
            values = mapOf("identity" to "00000000001", "serviceType" to "14"))))
    }

    @Test
    fun `recorded request prefills provider service and beneficiary without manufacturing an appointment`() {
        val request = ServiceRequest("service.miturno.reserve", ProviderIdentity(ProviderId.BPA), currency = Currency.CUP,
            values = mapOf("identity" to "P00000001", "entity" to "1", "branch" to "030313"))
        val selection = assertNotNull(MiTurnoContracts.selectionFromRequest(request))
        assertEquals("030313", selection.branchCode)
        val change = MiTurnoContracts.prefill(selection, MiTurnoAction.CHANGE_DATE)
        assertEquals(request.identity, change.identity)
        assertEquals(mapOf("identity" to "P00000001", "serviceType" to "1"), change.values)
        assertTrue(ServiceOperations.validate(change).any { it.fieldKey == "date" })
        val cancel = MiTurnoContracts.prefill(selection, MiTurnoAction.CANCEL)
        assertEquals(listOf("1", "1", "P00000001"), ServiceOperations.parameters(cancel))
        assertEquals(mapOf("identity" to "P00000001"), MiTurnoContracts.prefill(selection, MiTurnoAction.QUERY).values)
        assertNull(MiTurnoContracts.selectionFromRequest(cancel))
        val day = LocalDate.of(2026, 9, 24)
        fun dated(value: String) = ServiceRequest(change.operationId, change.identity, values = change.values + ("date" to value))
        assertEquals(emptyList(), MiTurnoContracts.validateForExecution(dated("24/03/2027"), day))
        assertTrue(MiTurnoContracts.validateForExecution(dated("25/03/2027"), day).isNotEmpty())
        assertTrue(MiTurnoContracts.validateForExecution(dated("23/09/2026"), day).isNotEmpty())
    }

    private val receipt = "La solicitud de Turno fue completada.\nCarnet de Identidad: P00000001.\nComision Pagada: 10.00 CUP.\nNro. Transaccion de Banco: TESTTURN001."

    @Test
    fun `literal request receipt reports only identity fee and reference`() {
        // Synthetic fixture made from labels demonstrated in the APK, not a captured bank response.
        val parsed = assertNotNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", receipt))
        assertEquals("P00000001", parsed.beneficiaryIdentity)
        assertEquals(Money("10.00".toBigDecimal(), Currency.CUP), parsed.commissionPaid)
        assertEquals("TESTTURN001", parsed.bankTransactionReference)
        assertFalse(parsed.toString().contains("P00000001"))
        assertNull(MiTurnoReceiptParser.parse("Other", receipt))
        assertNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", receipt.replace("fue completada", "no fue completada")))
        assertNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", receipt.replace("Comision Pagada: 10.00 CUP.", "")))
        assertNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", receipt + "\nComision Pagada: 20.00 CUP."))
        assertNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", "Su turno es mañana a las 10:00"))
        assertNull(MiTurnoReceiptParser.parse("PAGOxMOVIL", "Su reserva fue cancelada"))
    }
}
