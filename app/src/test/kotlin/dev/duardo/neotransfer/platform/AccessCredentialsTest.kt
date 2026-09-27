package dev.duardo.neotransfer.platform

import dev.duardo.neotransfer.core.Bank
import kotlin.test.*

class AccessCredentialsTest {
    @Test fun explicitEmptyContainerIsValidButMissingLegacyKeysAreCorruption() {
        AccessCredentials.empty().use { empty ->
            AccessCredentials.decode(empty.encode()).use { assertTrue(it.legacy.isEmpty() && it.scoped.isEmpty()) }
        }
        assertFailsWith<IllegalArgumentException> { AccessCredentials.decode("{}".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { AccessCredentials.decode("{\"unrelated\":\"1234\"}".toByteArray()) }
    }
    @Test fun legacyPinKeepsItsBankAndCloseWipesOwnedMaterial() {
        val decoded = AccessCredentials.decode("{\"BANDEC\":\"12345\"}".toByteArray())
        val material = decoded.legacy.getValue(Bank.BANDEC)
        assertEquals("12345", String(material))
        decoded.close()
        assertTrue(material.all { it == '\u0000' })
        assertTrue(decoded.legacy.isEmpty())
        assertFailsWith<IllegalArgumentException> { AccessCredentials.decode("{\"BPA\":\"12345\"}".toByteArray()) }
    }
    @Test fun scopedAccessesDoNotExposeTheirPinsInLogs() {
        AccessCredentials.empty().use {
            it.scoped["access:fixture"] = "54321".toCharArray()
            assertFalse(it.toString().contains("54321"))
            AccessCredentials.decode(it.encode()).use { restored -> assertEquals("54321", String(restored.scoped.getValue("access:fixture"))) }
        }
    }
}
