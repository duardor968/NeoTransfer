package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.data.*
import java.security.MessageDigest
import java.util.Locale

data class TrmWalletMapping(
    val snapshot: WalletSnapshot,
    val importedByKind: Map<TrmRecordKind, Int>,
    val omittedByKind: Map<TrmRecordKind, Int>,
    val issues: List<String>,
)

/** Converts only fields with a known wallet meaning. No legacy credential is made operational. */
object TrmWalletMapper {
    fun map(preview: TrmPreview): TrmWalletMapping {
        val issues = mutableListOf<String>()
        val omitted = linkedMapOf<TrmRecordKind, Int>()
        val imported = linkedMapOf<TrmRecordKind, Int>()
        val seen = mutableSetOf<String>()
        val contacts = linkedMapOf<String, ContactDraft>()
        val registrations = linkedMapOf<String, RegistrationRecord>()
        val accounts = mutableListOf<AccountRecord>()
        val services = mutableListOf<SavedServiceRecord>()
        fun id(table: String, value: String) = stableId(preview.sourcePhone ?: "unknown", table, value)

        fun omit(kind: TrmRecordKind, reason: String) {
            omitted[kind] = (omitted[kind] ?: 0) + 1
            if (reason !in issues) issues += reason
        }
        fun import(kind: TrmRecordKind) { imported[kind] = (imported[kind] ?: 0) + 1 }
        fun unique(record: TrmRecord): Boolean {
            val key = "${record.legacyTable}:${record.fields["id"] ?: canonical(record.fields)}"
            if (seen.add(key)) return true
            omit(record.kind, "El archivo repite identificadores de ${record.legacyTable}")
            return false
        }

        preview.records(TrmRecordKind.CONTACT).forEach { record ->
            if (!unique(record)) return@forEach
            val oldId = record.fields["id"]?.takeIf(String::isNotBlank)
            val name = record.fields["name"]?.trim()?.takeIf(String::isNotBlank)
            if (oldId == null || name == null) {
                omit(record.kind, "Hay contactos sin identificador o nombre")
                return@forEach
            }
            val contact = ContactDraft(id("Client", oldId), name)
            record.fields["phone"]?.trim()?.takeIf(String::isNotBlank)?.let { contact.phones += ContactPhone(it) }
            contacts[oldId] = contact
            import(record.kind)
        }

        preview.records(TrmRecordKind.MOBILE).forEach { record ->
            if (!unique(record)) return@forEach
            val contact = contacts[record.clientId()]
            val number = record.fields["number"]?.trim()?.takeIf(String::isNotBlank)
            if (contact == null || number == null) {
                omit(record.kind, "Hay móviles sin contacto o número válido")
            } else {
                contact.phones += ContactPhone(number)
                import(record.kind)
            }
        }
        preview.records(TrmRecordKind.LANDLINE).forEach { record ->
            if (!unique(record)) return@forEach
            val contact = contacts[record.clientId()]
            val number = record.fields["number"]?.trim()?.takeIf(String::isNotBlank)
            if (contact == null || number == null) {
                omit(record.kind, "Hay teléfonos fijos sin contacto o número válido")
            } else {
                contact.phones += ContactPhone(number, "Fijo")
                import(record.kind)
            }
        }
        preview.records(TrmRecordKind.RECIPIENT_ACCOUNT).forEach { record ->
            if (!unique(record)) return@forEach
            val contact = contacts[record.clientId()]
            val account = record.fields["cuenta"]?.trim()?.takeIf(String::isNotBlank)
            if (contact == null || account == null) {
                omit(record.kind, "Hay cuentas destinatarias sin contacto o número")
            } else {
                contact.cards += ContactCard(account, record.fields["descripcion"]?.trim().orEmpty().ifEmpty { "Cuenta" })
                import(record.kind)
            }
        }

        preview.records(TrmRecordKind.OWN_ACCOUNT).forEach { record ->
            if (!unique(record)) return@forEach
            val bank = bank(record.fields["agencia"])
            val number = record.fields["cuenta"]?.trim()?.takeIf(String::isNotBlank)
            if (bank == null || number == null) {
                omit(record.kind, "Hay cuentas propias sin banco reconocido o número")
                return@forEach
            }
            val registrationId = id("registration", bank.first)
            registrations.getOrPut(registrationId) {
                RegistrationRecord(registrationId, id("owner", "wallet"), bank.second, "PERSONAL", bank.first,
                    null, null, bank.second, credentialAlias = null, enabled = false)
            }
            val accountId = id("MCBank", record.fields["id"] ?: canonical(record.fields))
            accounts += AccountRecord(accountId, registrationId, number,
                record.fields["alias"]?.trim().orEmpty().ifEmpty { "Cuenta importada" },
                record.fields["tipo_moneda"]?.takeUnless { it == "NONE" || it == "OTHER" })
            import(record.kind)
        }

        fun service(kind: TrmRecordKind, table: String, key: String, type: String, label: String,
                    record: TrmRecord, currency: String? = null) {
            if (!unique(record)) return
            val identifier = record.fields[key]?.trim()?.takeIf(String::isNotBlank)
            if (identifier == null) {
                omit(kind, "Hay datos de $table sin identificador")
                return
            }
            if (kind in setOf(TrmRecordKind.PREPAID_CARD, TrmRecordKind.RECHARGE_CODE) &&
                !identifier.matches(Regex("[0-9]{12}"))) {
                omit(kind, "Hay códigos de $table con formato no válido")
                return
            }
            services += SavedServiceRecord(
                id(table, record.fields["id"] ?: canonical(record.fields)), type,
                record.fields["descripcion"]?.trim().orEmpty().ifEmpty { label }, identifier,
                currency = currency,
            )
            import(kind)
        }
        preview.records(TrmRecordKind.PREPAID_CARD).forEach {
            service(it.kind, "TarjetaPropia", "serie", "LEGACY_PREPAID_CARD", "Tarjeta propia", it,
                it.fields["tipo_moneda"]?.takeUnless { value -> value == "NONE" || value == "OTHER" })
        }
        preview.records(TrmRecordKind.NAUTA).forEach { service(it.kind, "Nauta", "cuenta", "NAUTA", "Nauta", it) }
        preview.records(TrmRecordKind.BILL).forEach {
            service(it.kind, "Factura", "factura", "LEGACY_BILL", "Factura", it)
        }
        preview.records(TrmRecordKind.PUBLIC_SERVICE).forEach {
            service(it.kind, "ServicioPublico", "valor", "LEGACY_PUBLIC_SERVICE", "Servicio público", it)
        }
        preview.records(TrmRecordKind.RECHARGE_CODE).forEach {
            service(it.kind, "Pin", "pin", "LEGACY_RECHARGE_CODE", "Código de recarga", it)
        }
        listOf(TrmRecordKind.BANK_MESSAGE, TrmRecordKind.RECEIPT).forEach { kind ->
            val count = preview.records(kind).size
            if (count > 0) {
                omitted[kind] = count
                issues += "${kind.name}: conserva la vista previa; no hay correspondencia segura con el historial bancario"
            }
        }

        val contactRecords = contacts.values.map { draft ->
            ContactRecord(draft.id, draft.name,
                draft.phones.distinctBy { it.number.filter(Char::isDigit) },
                draft.cards.distinctBy(ContactCard::number))
        }
        val snapshot = WalletSnapshot(
            identities = if (registrations.isEmpty()) emptyList() else listOf(IdentityRecord(id("owner", "wallet"), "Importado de Transfermóvil")),
            registrations = registrations.values.toList(), accounts = accounts,
            contacts = contactRecords, services = services,
        )
        return TrmWalletMapping(snapshot, imported, omitted, issues)
    }

    private data class ContactDraft(
        val id: String, val name: String,
        val phones: MutableList<ContactPhone> = mutableListOf(),
        val cards: MutableList<ContactCard> = mutableListOf(),
    )

    private fun TrmRecord.clientId(): String? = fields["id_client"] ?: fields["idClient"]

    private fun bank(value: String?): Pair<String, String>? = when (value) {
        "BPA" -> "01" to "BPA"
        "BANDEC" -> "02" to "BANDEC"
        "Metropolitano" -> "03" to "BANMET"
        "BFI" -> "05" to "BFI"
        else -> null
    }

    private fun canonical(fields: Map<String, String?>): String = fields.toSortedMap()
        .entries.joinToString("|") { (key, value) -> "$key=${value.orEmpty()}" }

    private fun stableId(source: String, table: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$source:$table:$value".toByteArray(Charsets.UTF_8))
        return "trm-${table.lowercase(Locale.ROOT)}-" + digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
