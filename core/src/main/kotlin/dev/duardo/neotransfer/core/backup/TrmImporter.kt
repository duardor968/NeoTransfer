package dev.duardo.neotransfer.core.backup

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

sealed class TrmFailure(message: String) : Exception(message) {
    class InvalidFile : TrmFailure("El archivo de Transfermóvil no tiene un formato válido")
    class IntegrityFailed : TrmFailure("No se pudo comprobar el archivo de Transfermóvil")
}

/** What a Transfermóvil backup gave: content ready to merge, plus counts by table for the summary. */
data class TrmImport(val content: BackupContent, val imported: Map<String, Int>, val skipped: Map<String, Int>)

/**
 * Reads Transfermóvil's `.trm` (hex key, SHA-512 of the JSON, then AES/ECB base64). The embedded
 * key is not a user password. Keys (`AOperKey`), recharge codes and receipts are never imported.
 */
object TrmImporter {
    private const val HEX = 128
    const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_JSON_BYTES = 16 * 1024 * 1024
    private const val MAX_ROWS = 50_000

    fun import(bytes: ByteArray): TrmImport = map(read(bytes))

    private class Row(val table: String, val fields: Map<String, String?>) {
        operator fun get(key: String): String? = fields[key]?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun read(bytes: ByteArray): List<Row> {
        if (bytes.size <= HEX * 2 || bytes.size > MAX_INPUT_BYTES) throw TrmFailure.InvalidFile()
        val keyHex = ascii(bytes, 0)
        val expectedHash = hex(ascii(bytes, HEX))
        val ciphertext = try { Base64.getDecoder().decode(bytes.copyOfRange(HEX * 2, bytes.size)) } catch (_: IllegalArgumentException) { throw TrmFailure.InvalidFile() }
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0 || ciphertext.size > MAX_JSON_BYTES + 16) throw TrmFailure.InvalidFile()
        val key = MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray(StandardCharsets.US_ASCII))
        val plaintext = try {
            Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(ciphertext) }
        } catch (_: Exception) {
            throw TrmFailure.IntegrityFailed()
        } finally {
            key.fill(0)
        }
        try {
            if (!MessageDigest.isEqual(expectedHash, MessageDigest.getInstance("SHA-512").digest(plaintext))) throw TrmFailure.IntegrityFailed()
            val text = try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString()
            } catch (_: Exception) { throw TrmFailure.InvalidFile() }
            return rows(text)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun rows(text: String): List<Row> = try {
        val root = Json.parseToJsonElement(text).jsonObject
        root.getValue("scheme")
        root.getValue("datos").jsonArray.flatMap { tableElement ->
            val table = tableElement.jsonObject
            val name = table.getValue("tabla").jsonPrimitive.content
            val data = when (val raw = table.getValue("dataJSON")) {
                is JsonArray -> raw
                is JsonPrimitive -> Json.parseToJsonElement(raw.content).jsonArray
                else -> throw TrmFailure.InvalidFile()
            }
            if (name == "AOperKey") emptyList() // banking keys: never read
            else data.map { row -> Row(name, row.jsonObject.mapValues { (_, value) -> if (value is JsonNull) null else (value as? JsonPrimitive)?.content ?: value.toString() }) }
        }.also { if (it.size > MAX_ROWS) throw TrmFailure.InvalidFile() }
    } catch (error: TrmFailure) {
        throw error
    } catch (_: Exception) {
        throw TrmFailure.InvalidFile()
    }

    private fun map(rows: List<Row>): TrmImport {
        val imported = linkedMapOf<String, Int>()
        val skipped = linkedMapOf<String, Int>()
        fun count(map: MutableMap<String, Int>, row: Row) { map[row.table] = (map[row.table] ?: 0) + 1 }
        val byTable = rows.groupBy { it.table }
        fun table(name: String) = byTable[name].orEmpty()

        val cards = linkedMapOf<String, OwnCard>()
        for (row in table("MCBank")) {
            val bank = when (row["agencia"]) { "BPA" -> Bank.BPA; "BANDEC" -> Bank.BANDEC; else -> null }
            val number = row["cuenta"]?.let(Cards::normalize)
            if (bank == null || number == null || number in cards) { count(skipped, row); continue }
            cards[number] = OwnCard(number, bank, if (row["tipo_moneda"] == "USD") Currency.USD else Currency.CUP, row["alias"])
            count(imported, row)
        }

        class Draft(val name: String, val phones: MutableList<String> = mutableListOf(), val cards: MutableList<String> = mutableListOf())
        val contacts = linkedMapOf<String, Draft>()
        for (row in table("Client")) {
            val id = row["id"]
            val name = row["name"]
            if (id == null || name == null || id in contacts) { count(skipped, row); continue }
            contacts[id] = Draft(name).also { draft -> row["phone"]?.let(Phones::normalize)?.let(draft.phones::add) }
            count(imported, row)
        }
        fun owner(row: Row) = (row["id_client"] ?: row["idClient"])?.let(contacts::get)
        for (row in table("TeflMovil") + table("TelfFijo")) {
            val phone = row["number"]?.let(Phones::normalize)
            val owner = owner(row)
            if (phone == null || owner == null) { count(skipped, row); continue }
            owner.phones += phone
            count(imported, row)
        }
        for (row in table("CuentaBanco")) {
            val card = row["cuenta"]?.let(Cards::normalize)
            val owner = owner(row)
            if (card == null || owner == null) { count(skipped, row); continue }
            owner.cards += card
            count(imported, row)
        }

        val services = mutableListOf<ServiceReference>()
        for (row in table("Nauta")) {
            val account = row["cuenta"]?.lowercase()
            if (account == null) { count(skipped, row); continue }
            val address = if ('@' in account) account else "$account@${if (row["tipo_cuenta"] == "2") "nauta.co.cu" else "nauta.com.cu"}"
            services += ServiceReference(ServiceKind.NAUTA, address, owner(row)?.name)
            count(imported, row)
        }
        for (row in table("Factura")) {
            val invoice = row["factura"]?.filter { it in '0'..'9' }
            // Electricity invoices have 11 or 13 digits; landline ones 14 or 15.
            val kind = when (invoice?.length) { 11, 13 -> ServiceKind.ELECTRICITY; 14, 15 -> ServiceKind.PHONE; else -> null }
            if (invoice == null || kind == null) { count(skipped, row); continue }
            services += ServiceReference(kind, invoice, owner(row)?.name)
            count(imported, row)
        }
        rows.filter { it.table !in setOf("MCBank", "Client", "TeflMovil", "TelfFijo", "CuentaBanco", "Nauta", "Factura") }.forEach { count(skipped, it) }

        val content = BackupContent(
            cards = cards.values.map { BackupCard(it) },
            contacts = contacts.map { (id, draft) -> Contact("trm-$id", draft.name, draft.phones.distinct(), draft.cards.distinct()) },
            services = services.distinctBy { it.kind to it.value },
        )
        return TrmImport(content, imported, skipped)
    }

    private fun ascii(bytes: ByteArray, offset: Int): String {
        val fragment = bytes.copyOfRange(offset, offset + HEX)
        if (fragment.any { it.toInt().toChar() !in "0123456789abcdefABCDEF" }) throw TrmFailure.InvalidFile()
        return String(fragment, StandardCharsets.US_ASCII)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte() }
}
