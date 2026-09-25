package dev.duardo.neotransfer.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A one-way copy of v0.2 metadata. The source preferences and vault are never modified. */
internal class LegacyWalletMigration(private val repository: RoomWalletRepository) {
    private val dao get() = repository.dao

    fun migrate(values: Map<String, *>): MigrationResult = repository.write {
        if (dao.metadata(MARKER) != null) return@write MigrationResult(true, 0, emptyList())
        val issues = mutableListOf<String>()
        var imported = 0
        val known = values.filterKeys { it in setOf("bank", "subscription", "pending", "uncertain", "receipts", "recipients", "refresh") ||
            it.matches(Regex("balance_[A-Z0-9]+_-?[0-9]+")) }
        fun issue(key: String) { issues += "$key: no se pudo interpretar; original conservado" }
        fun parse(key: String, block: () -> Unit) {
            try { block(); imported++ }
            catch (_: JSONException) { issue(key) }
            catch (_: IllegalArgumentException) { issue(key) }
        }
        val selectedBank = (known["bank"] as? String)?.let(::legacyBankCode) ?: "02"
        val selectedSubscription = (known["subscription"] as? Number)?.toInt() ?: -1
        val selectedRegistration = if (known.isEmpty()) null else ensureRegistration(selectedBank, selectedSubscription)
        dao.put(SettingsRow(value = WalletSettings(selectedBank, selectedSubscription, selectedRegistration)))
        if (known.containsKey("bank")) imported++
        if (known.containsKey("subscription")) imported++

        (known["pending"] as? String)?.let { raw -> parse("pending") {
            val json = JSONObject(raw)
            val operation = operation(json, "pending")
            validateOperation(operation)
            require(dao.operation(operation.id) == null)
            dao.put(OperationRow(operation))
        } }

        (known["uncertain"] as? String)?.let { raw -> parse("uncertain") {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) parse("uncertain[$index]") {
                val operation = operation(array.getJSONObject(index), "uncertain")
                validateOperation(operation)
                require(dao.operation(operation.id) == null)
                dao.put(OperationRow(operation))
            }
        } }

        (known["receipts"] as? Set<*>)?.filterIsInstance<String>()?.sorted()?.forEach { raw -> parse("receipts") {
            val split = raw.indexOf(':')
            require(split > 0 && split < raw.lastIndex)
            repository.claimReference(UsedReference(legacyBankCode(raw.substring(0, split)), raw.substring(split + 1)))
        } }

        (known["recipients"] as? String)?.let { raw -> parse("recipients") {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) parse("recipients[$index]") {
                val json = array.getJSONObject(index)
                val card = json.getString("card")
                val name = json.getString("name")
                require(card.isNotBlank() && name.isNotBlank())
                val id = "legacy-contact-${digest(card).take(24)}"
                dao.put(ContactRow(id, name, false))
                // A duplicated legacy card represents the last saved recipient, as in v0.2.
                dao.deletePhones(id); dao.deleteContactCards(id)
                dao.put(ContactCardRow(id, 0, card, "", null))
                json.nullableString("phone")?.let { dao.put(PhoneRow(id, 0, it, "")) }
            }
        } }

        (known["refresh"] as? String)?.let { raw -> parse("refresh") {
            val json = JSONObject(raw)
            dao.put(RefreshRow(value = RefreshRequest(legacyBankCode(json.getString("bank")),
                json.getInt("subscription"), json.getLong("due"))))
        } }

        known.filterKeys { it.startsWith("balance_") }.forEach { (key, value) -> parse(key) {
            require(value is String)
            val suffix = key.removePrefix("balance_")
            val bank = legacyBankCode(suffix.substringBeforeLast('_'))
            val subscription = suffix.substringAfterLast('_').toInt()
            val registration = ensureRegistration(bank, subscription)
            val json = JSONObject(value)
            val at = json.getLong("at")
            val array = json.getJSONArray("accounts")
            for (index in 0 until array.length()) parse("$key[$index]") {
                val row = array.getJSONObject(index)
                val available = row.getString("available")
                val ledger = row.nullableString("ledger")
                checkAmount(available); ledger?.let(::checkAmount)
                checkCurrency(row.getString("currency"))
                dao.put(BalanceRow(BalanceRecord("legacy-balance-$bank-$subscription-$index", bank, subscription,
                    at, row.nullableString("account"), available, row.getString("currency"),
                    ledger, row.nullableString("label"), registration)))
            }
        } }

        known.forEach { (key, value) ->
            val problem = issues.filter { it.startsWith("$key:") || it.startsWith("$key[") }.joinToString("; ").ifEmpty { null }
            dao.put(LegacyArchiveRow(key, safeArchive(value), problem))
        }
        // A failed archive or marker write rolls back every imported table.
        dao.put(MetadataRow(MARKER, "1"))
        MigrationResult(false, imported, issues)
    }

    private fun operation(json: JSONObject, state: String): OperationRecord {
        val bank = legacyBankCode(json.getString("bank"))
        val subscription = json.getInt("subscription")
        val started = json.getLong("started")
        val id = json.nullableString("id") ?: "legacy-operation-${digest("$state:${json}").take(24)}"
        val kind = json.nullableString("kind") ?: "TRANSFER"
        // v0.2 did not persist QR details, descriptions, or uncertain source selection.
        return OperationRecord(id, kind, bank, subscription, json.getString("destination"),
            json.getString("amount"), json.getString("currency"), started,
            registrationId = ensureRegistration(bank, subscription), source = json.nullableString("source") ?: "0000",
            phone = json.nullableString("phone"), status = if (state == "pending") OperationStatus.AWAITING_CONFIRMATION else OperationStatus.UNCERTAIN,
            refreshTaken = json.optBoolean("refresh", false), restored = true, reviewRequired = true,
            legacyState = state, providerId = legacyProvider(bank),
        )
    }

    private fun ensureRegistration(bank: String, subscription: Int): String {
        val identityId = "legacy-owner"
        if (dao.identities().none { it.value.id == identityId }) dao.put(IdentityRow(IdentityRecord(identityId, "Perfil importado")))
        val id = "legacy-registration-$bank-$subscription"
        if (dao.registrations().none { it.value.id == id }) dao.put(RegistrationRow(RegistrationRecord(
            id, identityId, legacyProvider(bank), "PERSONAL", bank, subscription.takeIf { it >= 0 }, null,
            legacyProvider(bank), credentialAlias = null,
        )))
        return id
    }

    companion object { internal const val MARKER = "migration.bank_state.v0.2" }
}

internal fun legacyBankCode(value: String): String = when (value) {
    "BPA" -> "01"
    "BANDEC" -> "02"
    "BANMET" -> "03"
    "BFI" -> "05"
    else -> value
}
internal fun legacyProvider(bank: String): String = when (bank) {
    "01" -> "BPA"
    "02" -> "BANDEC"
    "03" -> "BANMET"
    "04" -> "MITRANSFER"
    "05" -> "BFI"
    else -> bank
}

/** Keep unknown non-secret legacy fields for recovery without copying credentials. */
internal fun safeArchive(value: Any?): String {
    fun sanitized(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().filterNot(::sensitiveKey).forEach { key -> put(key, sanitized(value.get(key))) }
        }
        is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(sanitized(value.get(index))) }
        is Set<*> -> JSONArray(value.sortedBy { it.toString() }.map { sanitized(it) })
        is String -> value.takeUnless(::containsSecret) ?: "[credential content withheld]"
        else -> value
    }
    if (value is String) {
        val structured: Any? = try {
            when { value.trimStart().startsWith('{') -> JSONObject(value)
                value.trimStart().startsWith('[') -> JSONArray(value)
                else -> value }
        } catch (_: JSONException) {
            return if (containsSecret(value)) "[credential content withheld]" else value
        }
        return sanitized(structured).toString()
    }
    return sanitized(value).toString()
}
