package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceCatalogDataTest {
    @Test
    fun `bank branches stay inside their bank and DPA municipality`() {
        val catalog = ServiceCatalogData
        assertEquals(16, catalog.bankProvinces(Bank.BPA).size)
        assertEquals(16, catalog.bankProvinces(Bank.BANDEC).size)
        assertEquals(listOf("23"), catalog.bankProvinces(Bank.BANMET).map { it.code })
        assertTrue(catalog.bankProvinces(Bank.BFI).isEmpty())
        assertEquals(231, Bank.BPA.countBranches())
        assertEquals(230, Bank.BANDEC.countBranches())
        assertEquals(70, Bank.BANMET.countBranches())
        assertEquals(15, catalog.bankMunicipalities(Bank.BANMET, "23").size)
        assertEquals("SANDINO", catalog.bankBranch(Bank.BPA, "21", "2101", "101")?.name)
        assertTrue(catalog.bankBranches(Bank.BPA, "22", "2101").isEmpty())
        assertNull(catalog.bankBranch(Bank.BANDEC, "21", "2101", "101"))
        assertTrue(catalog.bankBranches(Bank.BANMET, "21", "2101").isEmpty())
    }

    @Test
    fun `MiTurno keeps provider branches and municipal variants separate`() {
        val catalog = ServiceCatalogData
        assertEquals(61, MiTurnoType.CADECA.countBranches())
        assertEquals(44, MiTurnoType.GAS.countBranches())
        assertEquals(590, MiTurnoType.BALITA.countBranches())
        assertEquals(12, MiTurnoType.BANK.countBranches())
        assertEquals(listOf("23"), catalog.miturnoProvinces(MiTurnoType.BANK).map { it.code })
        assertTrue(catalog.miturnoMunicipalities(MiTurnoType.CADECA, "23").isEmpty())
        assertEquals(13, catalog.miturnoMunicipalities(MiTurnoType.BALITA, "23").size)
        assertEquals("2306", catalog.miturnoMunicipalities(MiTurnoType.BALITA, "23")
            .first { it.code == "06" }.dpaCode)
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "23").isEmpty())
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "23", "2306")
            .any { it.code == "4226326" })
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "21", "2306").isEmpty())
        // APK assets/municipios_la_habana.json: Santiago 10–12 are casas comerciales, not DPA municipalities.
        val santiagoAreas = catalog.miturnoMunicipalities(MiTurnoType.BALITA, "34")
        assertEquals(listOf("10", "11", "12"), santiagoAreas.takeLast(3).map { it.code })
        assertTrue(santiagoAreas.takeLast(3).all { it.dpaCode == null })
        assertEquals(listOf(25, 25, 23), listOf("10", "11", "12").map {
            catalog.miturnoBranches(MiTurnoType.BALITA, "34", it).size
        })
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "23", "3410").isEmpty())
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "34", "3410").isEmpty())
        assertEquals(18, catalog.miturnoBranches(MiTurnoType.BALITA, "25").size)
        assertTrue(catalog.miturnoBranches(MiTurnoType.BALITA, "25", "2501").isEmpty())
        assertEquals(108, catalog.miturnoProvinces(MiTurnoType.BANK).flatMap { province ->
            catalog.miturnoBranches(MiTurnoType.BANK, province.code).flatMap { catalog.miturnoServices(it.code) }
        }.size)
        assertEquals(10, catalog.miturnoServices("4289476").size)
        assertTrue(catalog.miturnoServices("030313").isEmpty())
    }

    @Test
    fun `stamp withdrawal and traffic options preserve exact provider codes`() {
        val catalog = ServiceCatalogData
        assertEquals(16, catalog.dpaProvinces.size)
        assertEquals(168, catalog.dpaProvinces.flatMap { catalog.dpaMunicipalities(it.code) }.size)
        assertEquals("2306", catalog.municipalityDpa("23", "06"))
        assertNull(catalog.municipalityDpa("22", "2306"))
        assertEquals(setOf("95016", "95013"), catalog.stampEntities.map { it.code }.toSet())
        assertEquals(26, catalog.stampDenominations.size)
        assertEquals("Sello de 5 CUP", catalog.stampDenomination("5", "CUP")?.name)
        assertNull(catalog.stampDenomination("5", "USD"))
        assertEquals(setOf("22001", "22002"), catalog.withdrawalEntities.map { it.code }.toSet())
        assertEquals("USD", catalog.withdrawalEntity("22002")?.currency)
        assertNull(catalog.withdrawalEntity("22003"))
        assertEquals(95, catalog.trafficArticles.size)
        assertEquals(430, catalog.trafficArticles.sumOf { catalog.trafficSections(it.code).size })
        assertEquals("3", catalog.trafficSection("61", "2")?.dangerLevel)
        assertTrue(catalog.trafficSections("999").isEmpty())
        assertNull(catalog.trafficSection("61", "999"))
        assertFalse(catalog.trafficSections("63").any { it.articleCode != "63" })
    }

    private fun Bank.countBranches(): Int = ServiceCatalogData.bankProvinces(this).sumOf { province ->
        ServiceCatalogData.bankMunicipalities(this, province.code).sumOf { municipality ->
            ServiceCatalogData.bankBranches(this, province.code, municipality.code).size
        }
    }

    private fun MiTurnoType.countBranches(): Int = ServiceCatalogData.miturnoProvinces(this).sumOf { province ->
        val municipalities = ServiceCatalogData.miturnoMunicipalities(this, province.code)
        if (municipalities.isEmpty()) ServiceCatalogData.miturnoBranches(this, province.code).size
        else municipalities.sumOf { ServiceCatalogData.miturnoBranches(this, province.code, it.code).size }
    }
}
