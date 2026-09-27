package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerritoryCatalogTest {
    @Test
    fun `territories preserve provider codes and reject crossed province or unknown values`() {
        assertEquals(16, TerritoryCatalog.provinces.size)
        val municipalities = TerritoryCatalog.provinces.flatMap { TerritoryCatalog.municipalities(it.code) }
        assertEquals(168, municipalities.size)
        assertEquals(168, municipalities.map { it.code }.toSet().size)
        assertTrue(municipalities.all { it.code.startsWith(it.provinceCode) && it.code.length == 4 })
        assertEquals("Sandino", TerritoryCatalog.municipalities("21").first { it.code == "2101" }.name)
        assertEquals("2101", TerritoryCatalog.normalizeMunicipality("21", "01"))
        assertEquals("2101", TerritoryCatalog.normalizeMunicipality("21", "2101"))
        assertNull(TerritoryCatalog.normalizeMunicipality("22", "2101"))
        assertNull(TerritoryCatalog.normalizeMunicipality("21", "99"))
        assertEquals(emptyList(), TerritoryCatalog.municipalities("99"))
    }
}
