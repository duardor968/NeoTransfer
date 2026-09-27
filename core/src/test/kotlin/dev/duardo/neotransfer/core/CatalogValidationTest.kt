package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogValidationTest {
    private fun request(id: String, bank: ProviderId = ProviderId.BPA, vararg values: Pair<String, String>) =
        ServiceRequest(id, ProviderIdentity(bank), values = values.toMap())

    @Test
    fun `bank municipality wire components remain distinct from fiscal DPA`() {
        val open = request("banmet.open-account", ProviderId.BANMET, "municipality" to "01", "branch" to "238")
        assertEquals(emptyList(), CatalogValidation.validate(open))
        assertTrue(CatalogValidation.validate(request(open.operationId, ProviderId.BANMET, "municipality" to "2301", "branch" to "238")).isNotEmpty())
        assertEquals(emptyList(), CatalogValidation.validate(request("banmet.fiscal-account", ProviderId.BANMET, "municipality" to "2301", "branch" to "238")))
        assertTrue(CatalogValidation.validate(request("bpa.fiscal-account", values = arrayOf("municipality" to "2301", "branch" to "101"))).isNotEmpty())
    }

    @Test
    fun `MiTurno service cannot be combined with a branch of another family`() {
        assertEquals(emptyList(), CatalogValidation.validate(request("service.miturno.reserve", values = arrayOf("entity" to "1", "branch" to "030313"))))
        assertTrue(CatalogValidation.validate(request("service.miturno.reserve", values = arrayOf("entity" to "2", "branch" to "030313"))).isNotEmpty())
        assertEquals(emptyList(), CatalogValidation.validate(request("service.miturno.reserve", ProviderId.BANMET, "entity" to "4", "branch" to "4289476")))
        assertTrue(CatalogValidation.validate(request("service.miturno.reserve", values = arrayOf("entity" to "4", "branch" to "4289476"))).isNotEmpty())
        assertTrue(CatalogValidation.validate(request("service.miturno.reserve", values = arrayOf("entity" to "99", "branch" to "4289476"))).isNotEmpty())
    }

    @Test
    fun `traffic and stamps reject invented codes while permitting manual stamp amounts`() {
        assertEquals(emptyList(), CatalogValidation.validate(request("service.fine.traffic", values = arrayOf("article" to "61", "section" to "2", "province" to "23", "municipality" to "01"))))
        assertTrue(CatalogValidation.validate(request("service.fine.traffic", values = arrayOf("article" to "61", "section" to "999", "province" to "23", "municipality" to "01"))).isNotEmpty())
        assertEquals(emptyList(), CatalogValidation.validate(request("service.stamp", values = arrayOf("entity" to "95016", "municipality" to "2301", "amount" to "10.50"))))
        assertTrue(CatalogValidation.validate(request("service.stamp", values = arrayOf("entity" to "5", "municipality" to "2301"))).isNotEmpty())
    }
}
