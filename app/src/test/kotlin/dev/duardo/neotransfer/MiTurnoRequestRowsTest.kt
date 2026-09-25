package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.miturno.MiTurnoAction
import dev.duardo.neotransfer.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiTurnoRequestRowsTest {
    private val registration = RegistrationRecord("reg", "owner", "BPA", "PERSONAL", "01", 7, "50123456", "BPA")
    private fun operation(id: String = "request", status: OperationStatus = OperationStatus.CONFIRMED,
                          restored: Boolean = false, entity: String = "1") = OperationRecord(
        id = id, kind = "service.miturno.reserve", bankCode = "01", subscriptionId = 7,
        destination = "P00000001", amount = null, currency = "CUP", startedAt = 123L,
        registrationId = "reg", status = status, restored = restored, specId = "service.miturno.reserve",
        providerId = "BPA", parameters = mapOf("identity" to "P00000001", "entity" to entity,
            "branch" to "030313", "phone" to "50123456", "sourceCurrency" to "CUP", "accessGeneration" to "2"))

    private fun snapshot(vararg operations: OperationRecord) = WalletSnapshot(registrations = listOf(registration),
        operations = operations.toList())

    @Test fun `only current recorded requests with allowed service are selectable by ID`() {
        val valid = operation()
        val rows = miTurnoRequestRows(snapshot(valid, operation("restored", restored = true),
            operation("prepared", status = OperationStatus.PREPARED),
            operation("cancelled", status = OperationStatus.CANCELLED),
            operation("rejected", status = OperationStatus.REJECTED),
            operation("unsupported", entity = "4")), setOf("reg"), setOf(7))
        assertEquals(listOf("request"), rows.map { it.operation.id })
        val row = rows.single()
        assertTrue(row.canQuery)
        assertTrue(row.canManage)
        assertEquals("Compra de divisas en CADECA", row.selection.service.label)
        assertEquals("030313", row.selection.branchCode)
        assertEquals("Mónaco", row.branchName)
        assertNull(miTurnoPrefill(snapshot(valid), setOf("reg"), setOf(7), "other", MiTurnoAction.QUERY))
    }

    @Test fun `changed registration or SIM blocks every follow-up without hiding the request`() {
        val valid = operation()
        val changedLine = snapshot(valid).copy(registrations = listOf(registration.copy(subscriptionId = 8)))
        val row = miTurnoRequestRows(changedLine, setOf("reg"), setOf(7)).single()
        assertFalse(row.canQuery)
        assertFalse(row.canManage)
        assertNull(miTurnoPrefill(changedLine, setOf("reg"), setOf(7), valid.id, MiTurnoAction.CANCEL))
        assertFalse(miTurnoRequestRows(snapshot(valid), emptySet(), setOf(7)).single().canQuery)
        assertFalse(miTurnoRequestRows(snapshot(valid), setOf("reg"), emptySet()).single().canManage)
        val uncertain = miTurnoRequestRows(snapshot(operation(status = OperationStatus.UNCERTAIN)), setOf("reg"), setOf(7)).single()
        assertTrue(uncertain.canQuery)
        assertFalse(uncertain.canManage)
        assertNotNull(miTurnoPrefill(snapshot(operation(status = OperationStatus.UNCERTAIN)), setOf("reg"), setOf(7), "request", MiTurnoAction.QUERY))
        assertTrue(miTurnoRequestRows(snapshot(operation(status = OperationStatus.AWAITING_CONFIRMATION)), setOf("reg"), setOf(7)).single().canQuery)
        val sending = miTurnoRequestRows(snapshot(operation(status = OperationStatus.SUBMITTING)), setOf("reg"), setOf(7)).single()
        assertFalse(sending.canQuery)
        assertFalse(sending.canManage)
        assertNull(miTurnoPrefill(snapshot(valid), setOf("reg"), setOf(7), "request", MiTurnoAction.QUERY, executionBusy = true))
    }

    @Test fun `prefill keeps beneficiary and service but creates no appointment date or ticket`() {
        val current = snapshot(operation())
        val changed = assertNotNull(miTurnoPrefill(current, setOf("reg"), setOf(7), "request", MiTurnoAction.CHANGE_DATE))
        assertEquals("reg", changed.second)
        assertEquals("service.miturno.change", changed.first.operationId)
        assertEquals(mapOf("identity" to "P00000001", "serviceType" to "1"), changed.first.values)
        assertFalse("date" in changed.first.values)
        assertFalse("branch" in changed.first.values)
        val queried = assertNotNull(miTurnoPrefill(current, setOf("reg"), setOf(7), "request", MiTurnoAction.QUERY))
        assertEquals(mapOf("identity" to "P00000001"), queried.first.values)
        assertNull(miTurnoPrefill(current, setOf("reg"), setOf(7), "request", MiTurnoAction.REQUEST))
    }
}
