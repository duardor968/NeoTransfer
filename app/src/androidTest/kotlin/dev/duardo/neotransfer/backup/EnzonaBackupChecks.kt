package dev.duardo.neotransfer.backup

import android.content.Context
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.RoomWalletRepository
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.enzona.EnzonaInvoiceService
import dev.duardo.neotransfer.enzona.data.*
import java.util.UUID

/** Synthetic databases only. Root adds run(context, onPassed) to its instrumentation worker. */
object EnzonaBackupChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, body: (RoomEnzonaRepository) -> Unit) {
            RoomEnzonaRepository.openInMemory(context).use(body)
            passed++
            onPassed(name)
        }

        case("ENZONA backup exports every local owner, omits cache and restores pending entries inert") { target ->
            RoomEnzonaRepository.openInMemory(context).use { source ->
                val owners = listOf("first-owner@exact", "second-owner@exact")
                owners.forEach { owner ->
                    source.putCardLink(EnzonaCardLink(owner, "shared-card", "0000000000000001", "Alias sintético"))
                    EnzonaInvoiceService.entries.forEach { service -> EnzonaIntentState.entries.forEach { state ->
                        val intent = fixture(owner).copy(type = EnzonaIntentType.INVOICE_PAYMENT,
                            destination = EnzonaDestination("client-id-fixture"), invoiceService = service.name,
                            invoiceOwner = "Titular sintético",
                            invoiceFolio = if (service == EnzonaInvoiceService.ELECTRICITY) "000-folio" else null,
                            invoiceRoute = if (service == EnzonaInvoiceService.ELECTRICITY) "000-route" else null,
                            invoicePeriod = if (service == EnzonaInvoiceService.ELECTRICITY) "2026-10" else null)
                        source.prepareIntent(intent)
                        if (state != EnzonaIntentState.PREPARED) check(source.tryBeginSubmission(owner, intent.id))
                        when (state) {
                            EnzonaIntentState.UNKNOWN -> check(source.markTimedOut(owner, intent.id))
                            EnzonaIntentState.CONFIRMED, EnzonaIntentState.REJECTED -> check(source.recordResult(owner, intent.id, state))
                            else -> Unit
                        }
                    } }
                }
                source.cacheRead("cache-only-owner", EnzonaReadKind.ACCOUNT, "account", """{"account":{"enabled":false}}""", 1)
                val exported = source.exportAllLocal()
                check(exported.map { it.ownerSubject } == owners)
                val bytes = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), BackupPreferences(BackupTheme.SYSTEM, true), enzona = exported)
                try {
                    BackupPayload.decode(bytes).use { decoded ->
                        check(target.restoreAllLocal(decoded.enzona) == owners.size * (1 + EnzonaInvoiceService.entries.size * EnzonaIntentState.entries.size))
                        check(target.restoreAllLocal(decoded.enzona) == 0)
                    }
                } finally { bytes.fill(0) }
                owners.forEach { owner ->
                    check(target.cardLinks(owner) == source.cardLinks(owner))
                    check(target.journal(owner) == source.journal(owner).map { it.copy(restored = true) })
                    target.journal(owner).forEach { entry ->
                        check(!target.tryBeginSubmission(owner, entry.intent.id))
                        check(target.prepareIntent(entry.intent).restored)
                    }
                }
                check(target.cachedReads("cache-only-owner", EnzonaReadKind.ACCOUNT).isEmpty())
                check(source.exportAllLocal() == exported)
            }
        }

        case("ENZONA restore rejects a late owner conflict before callback or any persistent change") { target ->
            val existing = fixture("existing-owner")
            target.prepareIntent(existing)
            target.putCardLink(EnzonaCardLink("existing-owner", "shared-card", "0000000000000001", "Current alias"))
            val before = target.exportAllLocal()
            val fresh = export("new-owner")
            val collision = export("other-owner").let { owner -> owner.copy(journal = listOf(
                owner.journal.single().let { it.copy(intent = it.intent.copy(id = existing.id)) },
            )) }
            var callback = false
            check(runCatching { target.restoreAllLocal(listOf(fresh, collision)) { callback = true } }.isFailure)
            check(!callback && target.exportAllLocal() == before)
            val badOwner = export("bad-owner").let { owner -> owner.copy(journal = listOf(
                owner.journal.single().let { it.copy(intent = it.intent.copy(amount = "invalid")) },
            )) }
            check(runCatching { target.restoreAllLocal(listOf(fresh, badOwner)) { callback = true } }.isFailure)
            check(!callback && target.exportAllLocal() == before)
            val conflictingLink = EnzonaLocalExport("existing-owner", listOf(
                EnzonaCardLink("existing-owner", "shared-card", "0000000000000002", "Old alias")), emptyList())
            check(runCatching { target.restoreAllLocal(listOf(fresh, conflictingLink)) { callback = true } }.isFailure)
            check(!callback && target.exportAllLocal() == before)
            val nonDestructive = EnzonaLocalExport("existing-owner", listOf(EnzonaCardLink("existing-owner", "shared-card")), emptyList())
            check(target.restoreAllLocal(listOf(nonDestructive)) == 0)
            check(target.exportAllLocal() == before)
            check(target.tryBeginSubmission("existing-owner", existing.id))
        }

        case("ENZONA restore rolls back all owners when the wallet callback fails and preserves old wallet data") { target ->
            val databaseName = "enzona_backup_wallet_test_${UUID.randomUUID()}.db"
            var bodyFailure: Throwable? = null
            try {
                RoomWalletRepository.open(context, databaseName).use { wallet ->
                    val existing = ContactRecord("existing-contact", "Contacto anterior")
                    wallet.importSnapshot(WalletSnapshot(contacts = listOf(existing)))
                    val walletBefore = wallet.snapshot()
                    val incoming = listOf(export("owner-a"), export("owner-b"))
                    val duplicate = ContactRecord("duplicated", "Duplicado sintético")
                    check(runCatching {
                        target.restoreAllLocal(incoming) {
                            wallet.importSnapshot(WalletSnapshot(contacts = listOf(duplicate, duplicate)))
                        }
                    }.isFailure)
                    check(target.exportAllLocal().isEmpty())
                    check(wallet.snapshot() == walletBefore)
                    val result = target.restoreAllLocal(incoming) { imported ->
                        wallet.importSnapshot(WalletSnapshot(contacts = listOf(ContactRecord("new-contact", "Contacto nuevo"))))
                            .let { it.copy(importedEntries = it.importedEntries + imported) }
                    }
                    check(result.importedEntries == 5)
                    check(wallet.snapshot().contacts.any { it == existing })
                    incoming.forEach { owner ->
                        check(target.intent(owner.ownerSubject, owner.journal.single().intent.id)?.restored == true)
                        check(!target.tryBeginSubmission(owner.ownerSubject, owner.journal.single().intent.id))
                    }
                }
            } catch (failure: Throwable) { bodyFailure = failure; throw failure }
            finally {
                val cleanupFailure = runCatching {
                    if (context.getDatabasePath(databaseName).exists()) check(context.deleteDatabase(databaseName))
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    val original = bodyFailure ?: throw cleanupFailure
                    original.addSuppressed(cleanupFailure)
                }
            }
        }
        case("ENZONA history-only owners round trip and restore never drops absent or newer rows") { target ->
            fun page(id: String, status: String) =
                """{"transaction":[{"transaction_uuid":"$id","amount":10.0000,"currency":"1","transaction_status":"$status"}],"pagination":{}}"""
            RoomEnzonaRepository.openInMemory(context).use { source ->
                source.cacheRead("owner-a", EnzonaReadKind.HISTORY, "activity", page("shared-uuid", "old"), 100)
                source.cacheRead("owner-b", EnzonaReadKind.HISTORY, "activity", page("shared-uuid", "other-owner"), 100)
                val exported = source.exportAllLocal()
                check(exported.size == 2 && exported.all { it.cardLinks.isEmpty() && it.journal.isEmpty() && it.transactions.size == 1 })
                target.cacheRead("owner-a", EnzonaReadKind.HISTORY, "activity", page("shared-uuid", "newer"), 200)
                target.cacheRead("owner-a", EnzonaReadKind.HISTORY, "activity", page("local-only", "retained"), 200)
                val bytes = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), BackupPreferences(BackupTheme.SYSTEM, true), enzona = exported)
                try {
                    BackupPayload.decode(bytes).use { decoded ->
                        check(target.restoreAllLocal(decoded.enzona) == 1)
                        check(target.restoreAllLocal(decoded.enzona) == 0)
                    }
                } finally { bytes.fill(0) }
                check(target.transactions("owner-a").size == 2)
                check(target.transactions("owner-a").single { it.transactionUuid == "shared-uuid" }.transactionStatus == "newer")
                check(target.transactions("owner-b").single().transactionStatus == "other-owner")
                val before = target.exportAllLocal()
                val corrupt = exported.last().copy(transactions = listOf(exported.last().transactions.single().copy(
                    json = """{"transaction_uuid":"shared-uuid","refresh_token":"synthetic-secret"}""")))
                var callback = false
                check(runCatching { target.restoreAllLocal(listOf(export("new-owner"), corrupt)) { callback = true } }.isFailure)
                check(!callback && target.exportAllLocal() == before)
            }
        }
        return passed
    }

    private fun export(owner: String) = EnzonaLocalExport(owner,
        listOf(EnzonaCardLink(owner, "shared-card", alias = "Alias sintético")), listOf(EnzonaJournalEntry(fixture(owner))))

    private fun fixture(owner: String) = EnzonaIntent(UUID.randomUUID().toString(), owner, "shared-card",
        EnzonaIntentType.CARD_TRANSFER, EnzonaDestination("0000000000000002"), "00010.0000", "literal-currency", preparedAt = 1000)
}
