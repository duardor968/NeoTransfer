package dev.duardo.neotransfer.data

import org.json.JSONArray
import org.json.JSONObject
import dev.duardo.neotransfer.core.fuel.FuelCoupon

/** Versioned metadata inside the encrypted backup envelope. Never accepts credentials. */
object SnapshotCodec {
    fun encode(value: WalletSnapshot): ByteArray = obj(
        "format" to "neotransfer-wallet", "version" to 1,
        "identities" to value.identities.map { obj("id" to it.id, "label" to it.label, "documentNumber" to it.documentNumber) },
        "registrations" to value.registrations.map { obj("id" to it.id, "identityId" to it.identityId,
            "providerId" to it.providerId, "profileId" to it.profileId, "bankCode" to it.bankCode,
            "subscriptionId" to it.subscriptionId, "linePhone" to it.linePhone, "label" to it.label,
            "credentialAlias" to it.credentialAlias, "enabled" to it.enabled,
            "previousSubscriptionId" to it.previousSubscriptionId, "previousLinePhone" to it.previousLinePhone) },
        "accounts" to value.accounts.map { obj("id" to it.id, "registrationId" to it.registrationId, "number" to it.number,
            "label" to it.label, "currency" to it.currency, "isDefault" to it.isDefault, "favorite" to it.favorite,
            "profileId" to it.profileId) },
        "cards" to value.cards.map { obj("id" to it.id, "registrationId" to it.registrationId, "number" to it.number,
            "label" to it.label, "accountId" to it.accountId, "currency" to it.currency,
            "isDefault" to it.isDefault, "favorite" to it.favorite, "profileId" to it.profileId,
            "holderName" to it.holderName, "expiry" to it.expiry) },
        "contacts" to value.contacts.map { c -> obj("id" to c.id, "name" to c.name, "favorite" to c.favorite,
            "phones" to c.phones.map { obj("number" to it.number, "label" to it.label) },
            "cards" to c.cards.map { obj("number" to it.number, "label" to it.label, "bankCode" to it.bankCode) }) },
        "services" to value.services.map { obj("id" to it.id, "kind" to it.kind, "label" to it.label,
            "identifier" to it.identifier, "registrationId" to it.registrationId, "amount" to it.amount, "currency" to it.currency,
            "fieldKey" to it.fieldKey) },
        "operations" to value.operations.map(::operationJson),
        "events" to value.events.map { obj("id" to it.id, "source" to it.source.name, "sourceId" to it.sourceId,
            "sender" to it.sender, "body" to it.body, "receivedAt" to it.receivedAt,
            "subscriptionId" to it.subscriptionId, "canonicalEventId" to it.canonicalEventId, "bodyWithheld" to it.bodyWithheld,
            "originalSource" to it.originalSource?.name, "originalSourceId" to it.originalSourceId,
            "evidenceEligible" to it.evidenceEligible, "sentAt" to it.sentAt) },
        "receipts" to value.receipts.map { obj("id" to it.id, "eventId" to it.eventId, "bankCode" to it.bankCode,
            "subscriptionId" to it.subscriptionId, "kind" to it.kind, "reference" to it.reference,
            "amount" to it.amount, "currency" to it.currency, "party" to it.party, "account" to it.account,
            "purchaseId" to it.purchaseId, "bankDate" to it.bankDate, "nominalAmount" to it.nominalAmount,
            "amountIsNominal" to it.amountIsNominal, "operationId" to it.operationId, "referenceConflict" to it.referenceConflict) },
        "movements" to value.movements.map { obj("id" to it.id, "receiptId" to it.receiptId, "kind" to it.kind,
            "amount" to it.amount, "currency" to it.currency, "occurredAt" to it.occurredAt, "incoming" to it.incoming) },
        "balances" to value.balances.map { obj("id" to it.id, "bankCode" to it.bankCode, "subscriptionId" to it.subscriptionId,
            "at" to it.at, "account" to it.account, "available" to it.available, "currency" to it.currency,
            "ledger" to it.ledger, "label" to it.label, "registrationId" to it.registrationId,
            "cardId" to it.cardId, "accountId" to it.accountId, "sentAt" to it.sentAt) },
        "usedReferences" to value.usedReferences.map { obj("bankCode" to it.bankCode, "reference" to it.reference, "operationId" to it.operationId) },
        "settings" to value.settings.let { obj("activeBankCode" to it.activeBankCode, "subscriptionId" to it.subscriptionId,
            "selectedRegistrationId" to it.selectedRegistrationId, "selectedCardId" to it.selectedCardId, "selectedAccountId" to it.selectedAccountId) },
        "refresh" to value.refresh?.let { obj("bankCode" to it.bankCode, "subscriptionId" to it.subscriptionId, "dueAt" to it.dueAt) },
        "migrationIssues" to value.migrationIssues,
        "legacyMetadata" to value.legacyMetadata.map { obj("key" to it.key, "safeValue" to it.safeValue, "issue" to it.issue) },
        "histories" to value.histories.map { obj("id" to it.id, "bankCode" to it.bankCode, "subscriptionId" to it.subscriptionId,
            "postedOn" to it.postedOn, "service" to it.service, "incoming" to it.incoming, "amount" to it.amount,
            "currency" to it.currency, "reference" to it.reference, "transactionNumber" to it.transactionNumber,
            "account" to it.account, "registrationId" to it.registrationId, "accountId" to it.accountId, "cardId" to it.cardId,
            "queryOperationId" to it.queryOperationId, "receiptId" to it.receiptId,
            "ambiguous" to it.ambiguous, "referenceConflict" to it.referenceConflict) },
        "historyObservations" to value.historyObservations.map { obj("eventId" to it.eventId, "position" to it.position, "entryId" to it.entryId) },
        "fuelCoupons" to value.fuelCoupons.map { obj("id" to it.id, "serial" to it.serial, "currency" to it.currency,
            "paidAmount" to it.paidAmount, "balance" to it.balance, "bankCode" to it.bankCode,
            "bankReference" to it.bankReference, "tmReference" to it.tmReference, "observedAt" to it.observedAt,
            "reportedDate" to it.reportedDate, "balanceObservedAt" to it.balanceObservedAt, "sourceEventId" to it.sourceEventId,
            "subscriptionId" to it.subscriptionId, "secretRevision" to it.secretRevision, "evidenceEligible" to it.evidenceEligible,
            "ambiguous" to it.ambiguous, "label" to it.label, "archived" to it.archived) },
        "fuelObservations" to value.fuelObservations.map { obj("eventId" to it.eventId, "position" to it.position,
            "kind" to it.kind, "result" to it.result, "couponId" to it.couponId, "creditedAmount" to it.creditedAmount,
            "receiptId" to it.receiptId, "rejectedRows" to it.rejectedRows, "purchaseEvidence" to it.purchaseEvidence?.let(::fuelEvidenceJson)) },
    ).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): WalletSnapshot {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getString("format") == "neotransfer-wallet" && root.getInt("version") == 1) { "Versión de respaldo no compatible" }
        return WalletSnapshot(
            identities = root.rows("identities") { IdentityRecord(s("id"), s("label"), ns("documentNumber")) },
            registrations = root.rows("registrations") { RegistrationRecord(s("id"), s("identityId"), s("providerId"),
                s("profileId"), ns("bankCode"), ni("subscriptionId"), ns("linePhone"), s("label"), ns("credentialAlias"),
                getBoolean("enabled"), ni("previousSubscriptionId"), ns("previousLinePhone")) },
            accounts = root.rows("accounts") { AccountRecord(s("id"), s("registrationId"), s("number"), s("label"),
                ns("currency"), getBoolean("isDefault"), getBoolean("favorite"), ns("profileId")) },
            cards = root.rows("cards") { CardRecord(s("id"), s("registrationId"), s("number"), s("label"),
                ns("accountId"), ns("currency"), getBoolean("isDefault"), getBoolean("favorite"), ns("profileId"), ns("holderName"), ns("expiry")) },
            contacts = root.rows("contacts") { ContactRecord(s("id"), s("name"),
                rows("phones") { ContactPhone(s("number"), s("label")) },
                rows("cards") { ContactCard(s("number"), s("label"), ns("bankCode")) }, getBoolean("favorite")) },
            services = root.rows("services") { SavedServiceRecord(s("id"), s("kind"), s("label"), s("identifier"),
                ns("registrationId"), ns("amount"), ns("currency"), ns("fieldKey")) },
            operations = root.rows("operations") { decodeOperation(this) },
            events = root.rows("events") { EventRecord(s("id"), EventSource.valueOf(s("source")), ns("sourceId"), s("sender"),
                ns("body"), getLong("receivedAt"), ni("subscriptionId"), s("canonicalEventId"), getBoolean("bodyWithheld"),
                ns("originalSource")?.let(EventSource::valueOf), ns("originalSourceId"), getBoolean("evidenceEligible"), nl("sentAt")) },
            receipts = root.rows("receipts") { ReceiptRecord(s("id"), s("eventId"), ns("bankCode"), ni("subscriptionId"),
                s("kind"), ns("reference"), s("amount"), s("currency"), s("party"), ns("account"), ns("purchaseId"),
                ns("bankDate"), ns("nominalAmount"), getBoolean("amountIsNominal"), ns("operationId"), getBoolean("referenceConflict")) },
            movements = root.rows("movements") { MovementRecord(s("id"), s("receiptId"), s("kind"), s("amount"),
                s("currency"), getLong("occurredAt"), getBoolean("incoming")) },
            balances = root.rows("balances") { BalanceRecord(s("id"), s("bankCode"), getInt("subscriptionId"), getLong("at"),
                ns("account"), s("available"), s("currency"), ns("ledger"), ns("label"), ns("registrationId"), ns("cardId"), ns("accountId"), nl("sentAt")) },
            usedReferences = root.rows("usedReferences") { UsedReference(s("bankCode"), s("reference"), ns("operationId")) },
            settings = root.getJSONObject("settings").run { WalletSettings(s("activeBankCode"), getInt("subscriptionId"),
                ns("selectedRegistrationId"), ns("selectedCardId"), ns("selectedAccountId")) },
            refresh = root.optJSONObject("refresh")?.run { RefreshRequest(s("bankCode"), getInt("subscriptionId"), getLong("dueAt")) },
            migrationIssues = root.getJSONArray("migrationIssues").let { array -> List(array.length()) { array.getString(it) } },
            legacyMetadata = root.rows("legacyMetadata") { LegacyMetadataRecord(s("key"), s("safeValue"), ns("issue")) },
            histories = root.optionalRows("histories") { HistoryEntryRecord(s("id"), s("bankCode"), ni("subscriptionId"),
                s("postedOn"), s("service"), getBoolean("incoming"), s("amount"), s("currency"), ns("reference"),
                ns("transactionNumber"), ns("account"), ns("registrationId"), ns("accountId"), ns("cardId"),
                ns("queryOperationId"), ns("receiptId"), getBoolean("ambiguous"), getBoolean("referenceConflict")) },
            historyObservations = root.optionalRows("historyObservations") { HistoryObservationRecord(s("eventId"), getInt("position"), s("entryId")) },
            fuelCoupons = root.optionalRows("fuelCoupons") { FuelCoupon(s("id"), s("serial"), s("currency"), ns("paidAmount"),
                ns("balance"), ns("bankCode"), s("bankReference"), s("tmReference"), getLong("observedAt"), ns("reportedDate"),
                if (isNull("balanceObservedAt")) null else getLong("balanceObservedAt"), s("sourceEventId"), ni("subscriptionId"),
                ns("secretRevision"), getBoolean("evidenceEligible"), getBoolean("ambiguous"), s("label"), getBoolean("archived")) },
            fuelObservations = root.optionalRows("fuelObservations") { FuelObservation(s("eventId"), getInt("position"), s("kind"),
                s("result"), ns("couponId"), ns("creditedAmount"), ns("receiptId"), getInt("rejectedRows"), optJSONObject("purchaseEvidence")?.let(::readFuelEvidence)) },
        ).also(::validateSnapshot)
    }

    private fun operationJson(value: OperationRecord): JSONObject = value.let {
        obj("id" to it.id, "kind" to it.kind, "bankCode" to it.bankCode, "subscriptionId" to it.subscriptionId,
            "destination" to it.destination, "amount" to it.amount, "currency" to it.currency, "startedAt" to it.startedAt,
            "registrationId" to it.registrationId, "source" to it.source, "phone" to it.phone, "description" to it.description,
            "qr" to it.qr?.let { q -> obj("transactionId" to q.transactionId, "provider" to q.provider, "amount" to q.amount,
                "currency" to q.currency, "auxiliary" to q.auxiliary, "description" to q.description,
                "descriptionHint" to q.descriptionHint, "qrId" to q.qrId, "validFrom" to q.validFrom, "validThrough" to q.validThrough) },
            "status" to it.status.name, "updatedAt" to it.updatedAt, "refreshTaken" to it.refreshTaken,
            "restored" to it.restored, "reviewRequired" to it.reviewRequired, "legacyState" to it.legacyState,
            "specId" to it.specId, "providerId" to it.providerId, "profileId" to it.profileId,
            "parameters" to JSONObject(it.parameters),
            "httpEvidence" to it.httpEvidence?.let { e -> obj("requestId" to e.requestId, "specId" to e.specId,
                "httpStatus" to e.httpStatus, "responseCode" to e.responseCode, "receivedAt" to e.receivedAt, "reference" to e.reference) },
            "timeoutAt" to it.timeoutAt)
    }
    private fun decodeOperation(json: JSONObject): OperationRecord = json.run {
        OperationRecord(s("id"), s("kind"), ns("bankCode"), getInt("subscriptionId"), s("destination"), ns("amount"),
            ns("currency"), getLong("startedAt"), ns("registrationId"), s("source"), ns("phone"), s("description"),
            optJSONObject("qr")?.run { QrDetails(s("transactionId"), s("provider"), s("amount"), s("currency"),
                s("auxiliary"), s("description"), s("descriptionHint"), ns("qrId"), ns("validFrom"), ns("validThrough")) },
            OperationStatus.valueOf(s("status")), getLong("updatedAt"), getBoolean("refreshTaken"), getBoolean("restored"),
            getBoolean("reviewRequired"), ns("legacyState"), s("specId"), ns("providerId"), s("profileId"),
            getJSONObject("parameters").let { fields -> fields.keys().asSequence().associateWith { fields.getString(it) } },
            optJSONObject("httpEvidence")?.run { BulevarHttpEvidence(s("requestId"), s("specId"), getInt("httpStatus"),
                s("responseCode"), getLong("receivedAt"), ns("reference")) }, nl("timeoutAt"))
    }

    private fun obj(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, when (value) {
            null -> JSONObject.NULL
            is List<*> -> JSONArray(value)
            else -> value
        }) }
    }
    private fun JSONObject.s(key: String): String = getString(key)
    private fun JSONObject.ns(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.ni(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.nl(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun <T> JSONObject.rows(key: String, decode: JSONObject.() -> T): List<T> = getJSONArray(key).let { array ->
        List(array.length()) { decode(array.getJSONObject(it)) }
    }
    private fun <T> JSONObject.optionalRows(key: String, decode: JSONObject.() -> T): List<T> =
        if (has(key)) rows(key, decode) else emptyList()
}
