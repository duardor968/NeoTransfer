package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.data.IdentityRecord
import dev.duardo.neotransfer.data.RegistrationRecord
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.platform.AccessCredentials
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CredentialMergeTest {
    private val owner = IdentityRecord("person", "Persona de prueba")
    private fun registration(id: String, alias: String?) = RegistrationRecord(
        id, owner.id, "BANDEC", "PERSONAL", "02", null, null, id, credentialAlias = alias,
    )

    @Test fun `existing alias and PIN survive import with a new exclusive alias`() {
        val current = AccessCredentials.empty().apply { scoped["shared"] = "99999".toCharArray() }
        val incoming = AccessCredentials.empty().apply { scoped["shared"] = "12345".toCharArray() }
        try {
            val before = WalletSnapshot(identities = listOf(owner), registrations = listOf(registration("old", "shared")))
            val source = WalletSnapshot(identities = listOf(owner), registrations = listOf(
                registration("old", "shared"), registration("new", "shared"),
            ))
            CredentialMerge.plan(before, current, source, incoming).use { plan ->
                val newAlias = plan.snapshotForImport.registrations.single { it.id == "new" }.credentialAlias
                assertNotNull(newAlias)
                assertFalse(newAlias == "shared")
                val encoded = assertNotNull(plan.encodeMerged(current, setOf("new")))
                AccessCredentials.decode(encoded).use { merged ->
                    assertContentEquals("99999".toCharArray(), merged.scoped["shared"])
                    assertContentEquals("12345".toCharArray(), merged.scoped[newAlias])
                }
                encoded.fill(0)
            }
        } finally { current.close(); incoming.close() }
    }

    @Test fun `ambiguous bank PIN becomes one disabled access and cancellation can be retried`() {
        val empty = AccessCredentials.empty()
        val imported = AccessCredentials.empty().apply { legacy[Bank.BANDEC] = "12345".toCharArray() }
        try {
            val source = WalletSnapshot(identities = listOf(owner), registrations = listOf(
                registration("one", null), registration("two", null),
            ))
            val pendingSnapshot = CredentialMerge.plan(WalletSnapshot(), empty, source, imported).use { plan ->
                assertEquals(1, plan.pendingRegistrationIds.size)
                assertTrue(plan.snapshotForImport.registrations.count { it.credentialAlias != null } == 1)
                assertFalse(plan.snapshotForImport.registrations.single { it.credentialAlias != null }.enabled)
                plan.snapshotForImport
            }
            CredentialMerge.plan(pendingSnapshot, empty, source, imported).use { retry ->
                assertEquals(1, retry.recoverableRegistrationIds.size)
                val encoded = assertNotNull(retry.encodeMerged(empty, emptySet()))
                AccessCredentials.decode(encoded).use { merged -> assertEquals(1, merged.scoped.size) }
                encoded.fill(0)
            }
        } finally { empty.close(); imported.close() }
    }
}
