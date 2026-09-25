package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceFieldOptionsTest {
    private fun spec(id: String): OperationSpec =
        (BankManagementOperations.all + ServiceOperations.all + WalletOperations.all).single { it.id == id }

    private fun OperationSpec.field(key: String, identity: ProviderIdentity, values: Map<String, String> = emptyMap()): OperationField =
        ServiceFieldOptions.fields(this, identity, values).single { it.key == key }

    @Test
    fun `bank municipality and branch depend on province while account remains`() {
        val operation = spec("banmet.reprint-card")
        val identity = ProviderIdentity.forBank(Bank.BANMET)
        val province = operation.field("province", identity)
        assertEquals(listOf("23"), ServiceFieldOptions.options(operation, province, identity, emptyMap())?.map { it.value })
        assertEquals(emptyList(), ServiceFieldOptions.options(operation, operation.field("branch", identity), identity, emptyMap()))
        val municipality = operation.field("municipality", identity)
        val branchCode = ServiceCatalogData.bankBranches(Bank.BANMET, "23", "2301").first().code
        val values = mapOf("province" to "23", "municipality" to "2301", "branch" to branchCode,
            "commissionAccount" to "1234567890123456")
        assertTrue(ServiceFieldOptions.validate(operation, identity, values).isEmpty())
        assertEquals("01", ServiceFieldOptions.requestValues(operation, values)["municipality"])
        assertFalse("province" in ServiceFieldOptions.requestValues(operation, values))
        val changed = ServiceFieldOptions.change(operation, identity, values, "province", "21")
        assertFalse("municipality" in changed)
        assertFalse("branch" in changed)
        assertEquals("1234567890123456", changed["commissionAccount"])
        assertTrue(ServiceFieldOptions.validate(operation, identity, changed).any { it.fieldKey == "province" })
        val fiscal = spec("banmet.fiscal-account")
        assertEquals("2301", ServiceFieldOptions.requestValues(fiscal, values)["municipality"])
    }

    @Test
    fun `MiTurno balita area leads to branch and service without a fabricated DPA`() {
        val operation = spec("wallet.miturno.reserve")
        val identity = ProviderIdentity(ProviderId.MITRANSFER)
        val parents = mapOf("miturnoType" to "BALITA", "province" to "34")
        assertTrue(ServiceFieldOptions.fields(operation, identity, parents).any { it.key == "municipality" })
        assertTrue(ServiceFieldOptions.options(operation, operation.field("municipality", identity, parents), identity, parents)
            .orEmpty().any { it.value == "10" && it.label == "Casa Comercial José Martí" })
        val withArea = parents + ("municipality" to "10")
        val branches = ServiceFieldOptions.options(operation, operation.field("branch", identity, withArea), identity, withArea).orEmpty()
        assertEquals(25, branches.size)
        val selected = withArea + mapOf("branch" to branches.first().value, "entity" to "3", "identity" to "12345678901", "phone" to "50123456")
        assertTrue(ServiceFieldOptions.validate(operation, identity, selected).isEmpty())
        assertEquals(listOf("3"), ServiceFieldOptions.options(operation, operation.field("entity", identity, selected), identity, selected)?.map { it.value })
        val wire = ServiceFieldOptions.requestValues(operation, selected)
        assertEquals(branches.first().value, wire["branch"])
        assertFalse("municipality" in wire)
        assertFalse("miturnoType" in wire)
        val moved = ServiceFieldOptions.change(operation, identity, selected, "province", "23")
        assertFalse("municipality" in moved)
        assertFalse("branch" in moved)
        assertFalse("entity" in moved)
        assertEquals("12345678901", moved["identity"])
        assertEquals("50123456", moved["phone"])
        val bankParents = mapOf("miturnoType" to "BANK", "province" to "23")
        assertFalse(ServiceFieldOptions.fields(operation, identity, bankParents).any { it.key == "municipality" })
    }

    @Test
    fun `MiTurno selectors expose only services supported by the selected provider`() {
        val bpa = ProviderIdentity.forBank(Bank.BPA)
        val bank = ProviderIdentity.forBank(Bank.BANMET)
        val reserve = spec("service.miturno.reserve")
        val typeField = reserve.field("miturnoType", bpa)
        assertEquals(listOf("CADECA", "GAS", "BALITA"),
            ServiceFieldOptions.options(reserve, typeField, bpa, emptyMap())?.map { it.value })
        assertTrue(ServiceFieldOptions.options(reserve, typeField, bank, emptyMap()).orEmpty()
            .any { it.value == "BANK" && it.label == "Banco Metropolitano" })
        val management = spec("service.miturno.cancel")
        val field = management.field("serviceType", bpa)
        assertEquals(listOf("1", "2", "3"), ServiceFieldOptions.options(management, field, bpa, emptyMap())?.map { it.value })
        assertEquals((1..14).map(Int::toString), ServiceFieldOptions.options(management, field, bank, emptyMap())?.map { it.value })
    }

    @Test
    fun `traffic fine has dependent article and territory while contravention stays uncatalogued`() {
        val identity = ProviderIdentity.forBank(Bank.BPA)
        val traffic = spec("service.fine.traffic")
        val article = traffic.field("article", identity)
        val section = traffic.field("section", identity)
        assertEquals(95, ServiceFieldOptions.options(traffic, article, identity, emptyMap())?.size)
        assertEquals(emptyList(), ServiceFieldOptions.options(traffic, section, identity, emptyMap()))
        assertTrue(ServiceFieldOptions.options(traffic, section, identity, mapOf("article" to "61"))
            .orEmpty().any { it.value == "2" })
        val values = mapOf("article" to "61", "section" to "2", "province" to "23", "municipality" to "2306",
            "fine" to "123", "date" to "24/09/2026")
        assertTrue(ServiceFieldOptions.validate(traffic, identity, values).isEmpty())
        assertEquals("06", ServiceFieldOptions.requestValues(traffic, values)["municipality"])
        assertEquals("2306", ServiceFieldOptions.requestValues(traffic, values + ("province" to "21"))["municipality"])
        val newArticle = ServiceFieldOptions.change(traffic, identity, values, "article", "63")
        assertFalse("section" in newArticle)
        assertEquals("2306", newArticle["municipality"])
        val newProvince = ServiceFieldOptions.change(traffic, identity, values, "province", "21")
        assertFalse("municipality" in newProvince)
        assertEquals("2", newProvince["section"])
        assertNull(ServiceFieldOptions.options(spec("service.fine.contravention"), article, identity, values))
        assertNull(ServiceFieldOptions.options(spec("service.fine.contravention"), section, identity, values))
    }

    @Test
    fun `stamp entity and municipality are choices but amount remains manual`() {
        val identity = ProviderIdentity.forBank(Bank.BPA)
        val operation = spec("service.stamp")
        assertEquals(2, ServiceFieldOptions.options(operation, operation.field("entity", identity), identity, emptyMap())?.size)
        assertEquals(emptyList(), ServiceFieldOptions.options(operation, operation.field("municipality", identity), identity, emptyMap()))
        assertEquals(15, ServiceFieldOptions.options(operation, operation.field("municipality", identity), identity,
            mapOf("province" to "23"))?.size)
        assertNull(ServiceFieldOptions.options(operation, operation.field("amount", identity), identity, emptyMap()))
        assertEquals(26, ServiceFieldOptions.suggestions(operation, operation.field("amount", identity))?.size)
        val values = mapOf("province" to "23", "municipality" to "2306", "entity" to "95016", "amount" to "75",
            "taxpayer" to "12345678901")
        val changed = ServiceFieldOptions.change(operation, identity, values, "province", "21")
        assertFalse("municipality" in changed)
        assertEquals("95016", changed["entity"])
        assertEquals("75", changed["amount"])
        assertEquals("12345678901", changed["taxpayer"])
    }
}
