package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.enzona.EnzonaInvoiceService
import dev.duardo.neotransfer.enzona.data.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EnzonaInvoiceBackupTest {
    private val invoiceFields = setOf("invoiceService", "invoiceOwner", "invoiceFolio", "invoiceRoute", "invoicePeriod")
    private val preferences = BackupPreferences(BackupTheme.SYSTEM, true)

    @Test fun `all invoice services round trip every owner and exact reviewed fields`() {
        val owners = listOf("subject-a@exact", "subject-b@exact").map { owner ->
            EnzonaLocalExport(owner, listOf(EnzonaCardLink(owner, "same-card", alias = "Alias sintético")),
                EnzonaInvoiceService.entries.map { service -> EnzonaJournalEntry(invoice(service, owner), EnzonaIntentState.UNKNOWN) })
        }
        val bytes = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = owners)
        try {
            BackupPayload.decode(bytes).use { decoded ->
                assertEquals(owners, decoded.enzona)
                decoded.enzona.forEach { owner -> owner.journal.forEach {
                    assertEquals("000-client-id", it.intent.destination.value)
                    assertNull(it.intent.destination.username)
                    assertEquals("00010.000100", it.intent.amount)
                    assertEquals("literal-currency", it.intent.currency)
                } }
            }
        } finally { bytes.fill(0) }
    }

    @Test fun `existing positional intents and pre-invoice v3 backups still decode`() {
        val transfer = EnzonaIntent(UUID.randomUUID().toString(), "subject-a@exact", "same-card",
            EnzonaIntentType.CARD_TRANSFER, EnzonaDestination("0000000000000002"), "10.00", "CUP", null, null, 1000)
        val owners = listOf(export(transfer))
        val section = EnzonaBackupCodec.encode(owners)
        val oldSection = rewriteIntent(section) { intent -> JsonObject(intent - invoiceFields) }
        assertEquals(owners, EnzonaBackupCodec.decode(oldSection))
        assertNull(transfer.invoiceService)
        assertNull(transfer.invoicePeriod)
        // INVOICE_PAYMENT cannot use the older absent-field shape to bypass required values.
        val invalidInvoice = rewriteIntent(oldSection) { intent ->
            JsonObject(intent + ("type" to JsonPrimitive(EnzonaIntentType.INVOICE_PAYMENT.name)))
        }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(invalidInvoice) }
    }

    @Test fun `missing invoice identity electricity metadata and extra metadata reject`() {
        val electricity = invoice(EnzonaInvoiceService.ELECTRICITY)
        val invalid = listOf(
            electricity.copy(invoiceService = null), electricity.copy(invoiceService = "2222"),
            electricity.copy(invoiceService = "electricity"), electricity.copy(invoiceOwner = null),
            electricity.copy(invoiceOwner = " "), electricity.copy(invoiceFolio = null),
            electricity.copy(invoiceRoute = ""), electricity.copy(invoicePeriod = null),
            electricity.copy(destination = EnzonaDestination("", null)),
            electricity.copy(destination = electricity.destination.copy(username = "unexpected")),
        ) + EnzonaInvoiceService.entries.filter { it != EnzonaInvoiceService.ELECTRICITY }.flatMap { service ->
            val other = invoice(service)
            listOf(other.copy(invoiceFolio = "extra"), other.copy(invoiceRoute = "extra"), other.copy(invoicePeriod = "extra"))
        }
        invalid.forEach { intent ->
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = listOf(export(intent)))
            }
        }
    }

    @Test fun `invoice fields are rejected for every other intent type`() {
        EnzonaIntentType.entries.filter { it != EnzonaIntentType.INVOICE_PAYMENT }.forEach { type ->
            val plain = EnzonaIntent(UUID.randomUUID().toString(), "owner", "card", type,
                EnzonaDestination("destination", if (type == EnzonaIntentType.ACCOUNT_TRANSFER) "recipient" else null),
                "10.00", "CUP", preparedAt = 1000)
            val extras = listOf(plain.copy(invoiceService = "ELECTRICITY"), plain.copy(invoiceOwner = "owner"),
                plain.copy(invoiceFolio = "folio"), plain.copy(invoiceRoute = "route"), plain.copy(invoicePeriod = "period"))
            extras.forEach { intent ->
                assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                    BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = listOf(export(intent)))
                }
            }
        }
    }

    @Test fun `invoice backup decoder rejects numeric metadata and unapproved request fields`() {
        val section = EnzonaBackupCodec.encode(listOf(export(invoice(EnzonaInvoiceService.ELECTRICITY))))
        invoiceFields.forEach { field ->
            val numeric = rewriteIntent(section) { intent -> JsonObject(intent + (field to JsonPrimitive(123))) }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(numeric) }
        }
        listOf("payment_password", "ciphertext", "rawRequest", "fingerprint", "access_token").forEach { field ->
            val extra = rewriteIntent(section) { intent -> JsonObject(intent + (field to JsonPrimitive("synthetic-secret"))) }
            val failure = assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(extra) }
            assertNull(failure.cause)
        }
    }

    private fun invoice(service: EnzonaInvoiceService, owner: String = "subject-a@exact") = EnzonaIntent(
        UUID.randomUUID().toString(), owner, "same-card", EnzonaIntentType.INVOICE_PAYMENT,
        EnzonaDestination("000-client-id"), "00010.000100", "literal-currency", preparedAt = 1000,
        invoiceService = service.name, invoiceOwner = "Titular sintético",
        invoiceFolio = if (service == EnzonaInvoiceService.ELECTRICITY) "000-folio" else null,
        invoiceRoute = if (service == EnzonaInvoiceService.ELECTRICITY) "000-route" else null,
        invoicePeriod = if (service == EnzonaInvoiceService.ELECTRICITY) "2026-10" else null,
    )

    private fun export(intent: EnzonaIntent) = EnzonaLocalExport(intent.ownerSubject, emptyList(), listOf(EnzonaJournalEntry(intent)))

    private fun rewriteIntent(section: ByteArray, transform: (JsonObject) -> JsonObject): ByteArray {
        val root = Json.parseToJsonElement(section.decodeToString()) as JsonObject
        val owners = root.getValue("owners") as JsonArray
        val owner = owners.single() as JsonObject
        val entry = (owner.getValue("journal") as JsonArray).single() as JsonObject
        val changed = JsonObject(entry + ("intent" to transform(entry.getValue("intent") as JsonObject)))
        return JsonObject(root + ("owners" to JsonArray(listOf(JsonObject(owner + ("journal" to JsonArray(listOf(changed))))))))
            .toString().toByteArray()
    }
}
