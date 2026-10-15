package dev.duardo.neotransfer.enzona.data

import android.content.Context
import dev.duardo.neotransfer.enzona.EnzonaInvoiceService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Synthetic fixtures only. Root invokes this on its existing instrumentation worker. */
object EnzonaDataChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, body: (RoomEnzonaRepository, String) -> Unit) {
            val databaseName = "enzona_data_test_${UUID.randomUUID()}.db"
            val repository = RoomEnzonaRepository.openNamed(context, databaseName)
            var bodyFailure: Throwable? = null
            try { body(repository, databaseName); passed++; onPassed(name) }
            catch (failure: Throwable) { bodyFailure = failure; throw failure }
            finally {
                val cleanupFailure = runCatching {
                    repository.close()
                    if (context.getDatabasePath(databaseName).exists()) check(context.deleteDatabase(databaseName))
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    val original = bodyFailure ?: throw cleanupFailure
                    original.addSuppressed(cleanupFailure)
                }
            }
        }

        case("ENZONA owners isolate the same remote card UUID, cache, aliases and journal") { r, _ ->
            r.cacheRead(OWNER, EnzonaReadKind.BALANCE, CARD, """{"balance":10.0000,"operation":[]}""", 100)
            r.cacheRead(OTHER_OWNER, EnzonaReadKind.BALANCE, CARD, """{"balance":20.0000,"operation":[]}""", 100)
            r.cacheRead(OWNER, EnzonaReadKind.BALANCE, CARD, """{"balance":1,"operation":[]}""", 99)
            r.putCardLink(EnzonaCardLink(OWNER, CARD, "0000000000000001", "Propia A"))
            r.putCardLink(EnzonaCardLink(OTHER_OWNER, CARD, "0000000000000002", "Propia B"))
            val first = fixture()
            val second = fixture(OTHER_OWNER)
            r.prepareIntent(first)
            r.prepareIntent(second)
            check(r.readCache(OWNER, EnzonaReadKind.BALANCE, CARD)?.json == """{"balance":10.0000,"operation":[]}""")
            check(r.readCache(OTHER_OWNER, EnzonaReadKind.BALANCE, CARD)?.json == """{"balance":20.0000,"operation":[]}""")
            check(r.cachedReads(OWNER, EnzonaReadKind.BALANCE).single().ownerSubject == OWNER)
            check(r.cardLinks(OWNER).single().pan == "0000000000000001")
            check(r.cardLinks(OTHER_OWNER).single().alias == "Propia B")
            check(r.journal(OWNER).single().intent == first)
            check(r.journal(OTHER_OWNER).single().intent == second)
            check(r.intent(OTHER_OWNER, first.id) == null)
            check(!r.tryBeginSubmission(OTHER_OWNER, first.id))
            check(!r.markTimedOut(OTHER_OWNER, first.id))
            check(!r.recordResult(OTHER_OWNER, first.id, EnzonaIntentState.CONFIRMED, "remote-fixture"))
            check(runCatching { r.journal("") }.isFailure)
            check(runCatching { r.readCache(" ", EnzonaReadKind.BALANCE, CARD) }.isFailure)
            check(runCatching { r.exportLocal("") }.isFailure)
        }

        case("ENZONA immutable intents reject UUID conflicts without amount or time deduplication") { r, _ ->
            val original = fixture().copy(
                type = EnzonaIntentType.ACCOUNT_TRANSFER,
                destination = EnzonaDestination("recipient-account-fixture", "recipient-username-fixture"),
            )
            r.prepareIntent(original)
            check(r.prepareIntent(original).intent == original)
            val conflicts = listOf(
                original.copy(ownerSubject = OTHER_OWNER),
                original.copy(cardUuid = "other-card-fixture"),
                original.copy(type = EnzonaIntentType.QR_MERCHANT_PAYMENT, destination = EnzonaDestination("vendor-fixture")),
                original.copy(destination = original.destination.copy(value = "other-recipient-fixture")),
                original.copy(destination = original.destination.copy(username = "other-username-fixture")),
                original.copy(amount = "10.00"),
                original.copy(currency = "USD"),
                original.copy(description = "Otro concepto"),
                original.copy(phone = "50000002"),
                original.copy(preparedAt = original.preparedAt + 1),
            )
            conflicts.forEach { check(runCatching { r.prepareIntent(it) }.isFailure) }
            check(r.intent(OWNER, original.id)?.intent == original)
            r.prepareIntent(original.copy(id = UUID.randomUUID().toString()))
            check(r.journal(OWNER).size == 2)
            check(!r.recordResult(OWNER, original.id, EnzonaIntentState.CONFIRMED, "remote-fixture"))
            check(r.tryBeginSubmission(OWNER, original.id))
            check(r.recordResult(OWNER, original.id, EnzonaIntentState.REJECTED))
            check(r.prepareIntent(original).state == EnzonaIntentState.REJECTED)
            check(!r.tryBeginSubmission(OWNER, original.id))
        }

        case("ENZONA concurrent connections grant exactly one dispatch and bind late results") { r, name ->
            val intent = fixture()
            r.prepareIntent(intent)
            RoomEnzonaRepository.openNamed(context, name).use { second ->
                val ready = CountDownLatch(8)
                val start = CountDownLatch(1)
                val workers = Executors.newFixedThreadPool(8)
                try {
                    val results = (0 until 8).map { index -> workers.submit<Boolean> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        (if (index % 2 == 0) r else second).tryBeginSubmission(OWNER, intent.id)
                    } }
                    check(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    check(results.count { it.get(20, TimeUnit.SECONDS) } == 1)
                } finally {
                    start.countDown()
                    workers.shutdownNow()
                    check(workers.awaitTermination(20, TimeUnit.SECONDS))
                }
            }
            check(r.markTimedOut(OWNER, intent.id))
            check(!r.markTimedOut(OWNER, intent.id))
            check(!r.tryBeginSubmission(OWNER, intent.id))
            check(r.recordResult(OWNER, intent.id, EnzonaIntentState.CONFIRMED, "observed-remote-fixture"))
            check(r.recordResult(OWNER, intent.id, EnzonaIntentState.CONFIRMED))
            check(!r.recordResult(OWNER, intent.id, EnzonaIntentState.CONFIRMED, "different-remote-fixture"))
            check(!r.recordResult(OWNER, intent.id, EnzonaIntentState.REJECTED))
            check(!r.markTimedOut(OWNER, intent.id))
            check(r.intent(OWNER, intent.id)?.let {
                it.state == EnzonaIntentState.CONFIRMED && it.remoteId == "observed-remote-fixture" && it.intent == intent
            } == true)
        }

        case("ENZONA reopened UNKNOWN and interrupted SUBMITTING never grant another dispatch") { r, name ->
            val unknown = fixture()
            val interrupted = fixture()
            r.prepareIntent(unknown)
            r.prepareIntent(interrupted)
            check(r.tryBeginSubmission(OWNER, unknown.id))
            check(r.markTimedOut(OWNER, unknown.id))
            check(r.tryBeginSubmission(OWNER, interrupted.id))
            r.close()
            RoomEnzonaRepository.openNamed(context, name).use { reopened ->
                check(reopened.intent(OWNER, unknown.id)?.state == EnzonaIntentState.UNKNOWN)
                check(reopened.intent(OWNER, interrupted.id)?.state == EnzonaIntentState.SUBMITTING)
                check(!reopened.tryBeginSubmission(OWNER, unknown.id))
                check(!reopened.tryBeginSubmission(OWNER, interrupted.id))
                check(reopened.recordResult(OWNER, unknown.id, EnzonaIntentState.REJECTED))
                check(!reopened.tryBeginSubmission(OWNER, unknown.id))
                check(reopened.intent(OWNER, unknown.id)?.intent?.amount == "00010.0000")
            }
        }

        case("ENZONA local restore preserves links and journal, flags every pending intent and is atomic") { r, _ ->
            r.putCardLink(EnzonaCardLink(OWNER, CARD, "0000000000000001", "Propia sintética"))
            r.cacheRead(OWNER, EnzonaReadKind.CARDS, OWNER, """{"card":[]}""", 10)
            val prepared = fixture()
            val submitting = fixture()
            val unknown = fixture()
            val confirmed = fixture()
            val rejected = fixture()
            listOf(prepared, submitting, unknown, confirmed, rejected).forEach { r.prepareIntent(it) }
            listOf(submitting, unknown, confirmed, rejected).forEach { check(r.tryBeginSubmission(OWNER, it.id)) }
            check(r.markTimedOut(OWNER, unknown.id))
            check(r.recordResult(OWNER, confirmed.id, EnzonaIntentState.CONFIRMED, "restored-remote-fixture"))
            check(r.recordResult(OWNER, rejected.id, EnzonaIntentState.REJECTED))
            val exported = r.exportLocal(OWNER)
            RoomEnzonaRepository.openInMemory(context).use { target ->
                target.restoreLocal(OWNER, exported)
                target.restoreLocal(OWNER, exported)
                check(target.cardLinks(OWNER) == exported.cardLinks)
                check(target.journal(OWNER) == exported.journal.map { it.copy(restored = true) })
                check(target.cachedReads(OWNER, EnzonaReadKind.CARDS).isEmpty())
                check(target.journal(OTHER_OWNER).isEmpty())
                listOf(prepared, submitting, unknown).forEach {
                    check(target.intent(OWNER, it.id)?.restored == true)
                    check(!target.tryBeginSubmission(OWNER, it.id))
                    check(target.prepareIntent(it).restored)
                }
                check(target.recordResult(OWNER, unknown.id, EnzonaIntentState.CONFIRMED, "late-restored-fixture"))
                target.restoreLocal(OWNER, exported) // Older backup cannot downgrade a resolved result.
                check(target.intent(OWNER, unknown.id)?.state == EnzonaIntentState.CONFIRMED)
                val before = target.exportLocal(OWNER)
                val changedAlias = exported.cardLinks.single().copy(alias = "No debe persistir")
                val invalid = exported.copy(cardLinks = listOf(changedAlias), journal = listOf(
                    EnzonaJournalEntry(fixture()),
                    EnzonaJournalEntry(prepared.copy(amount = "99.00")),
                ))
                check(runCatching { target.restoreLocal(OWNER, invalid) }.isFailure)
                check(target.exportLocal(OWNER) == before)
                check(runCatching { target.restoreLocal(OTHER_OWNER, exported) }.isFailure)
                check(runCatching { target.restoreLocal(OWNER, exported.copy(version = 2)) }.isFailure)
                check(target.exportLocal(OWNER) == before)
            }
            check(r.exportLocal(OWNER) == exported)
            val unrelated = fixture()
            r.prepareIntent(unrelated)
            r.restoreLocal(OWNER, exported)
            check(r.intent(OWNER, prepared.id)?.restored == true)
            check(!r.tryBeginSubmission(OWNER, prepared.id))
            check(r.intent(OWNER, unrelated.id)?.restored == false)
            check(r.tryBeginSubmission(OWNER, unrelated.id))
        }

        case("ENZONA read cache recursively redacts authorization while keeping exact amounts and references") { r, _ ->
            val secretKeys = listOf("access_token", "refresh_token", "id_token", "payment_password", "fingerprint",
                "fingerpring", "client_secret", "authorization", "code_verifier", "password", "pin")
            val nestedSecrets = secretKeys.joinToString(",") { "\"$it\":\"synthetic-secret-marker\"" }
            val json = """{"transaction":[{"transaction_uuid":"transaction-fixture","amount":9007199254740993.000100,"total_amount":1.2300e+10,"currency":"1","source":false,"recipient":false,"code":"REF-CODE","codigo":"REF-01","status":1111,"nested":[{$nestedSecrets,"Authorization":"synthetic-secret-marker","reference":"KEEP-REF","amount":"00010.0000","active":false,"optional":null}]}],"pagination":{}}"""
            r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "page-fixture", json, 100)
            val stored = checkNotNull(r.readCache(OWNER, EnzonaReadKind.HISTORY, "page-fixture"))
            check(!stored.json.contains("synthetic-secret-marker"))
            val envelope = Json.parseToJsonElement(stored.json) as JsonObject
            val root = (envelope["transaction"] as JsonArray).single() as JsonObject
            check((root["amount"] as JsonPrimitive).content == "9007199254740993.000100")
            check((root["total_amount"] as JsonPrimitive).content == "1.2300e+10")
            check((root["currency"] as JsonPrimitive).let { it.isString && it.content == "1" })
            check(root["source"].toString() == "false" && root["recipient"].toString() == "false")
            check((root["code"] as JsonPrimitive).content == "REF-CODE")
            check((root["codigo"] as JsonPrimitive).content == "REF-01")
            check((root["status"] as JsonPrimitive).content == "1111")
            val nested = (root["nested"] as JsonArray).single() as JsonObject
            check(secretKeys.none { it in nested } && "Authorization" !in nested)
            check((nested["reference"] as JsonPrimitive).content == "KEEP-REF")
            check((nested["amount"] as JsonPrimitive).let { it.isString && it.content == "00010.0000" })
            check(nested["active"].toString() == "false" && nested["optional"].toString() == "null")
            r.cacheRead(OWNER, EnzonaReadKind.ACCOUNT, OWNER, """{"account":{"name":"Persona sintética","enabled":false}}""", 100)
            check(r.readCache(OWNER, EnzonaReadKind.ACCOUNT, OWNER) != null)
            val failure = runCatching {
                r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "page-fixture", """{"access_token":"synthetic-secret-marker",}""", 200)
            }.exceptionOrNull()
            check(failure is IllegalArgumentException && failure.cause == null)
            check(!failure.message.orEmpty().contains("synthetic-secret-marker"))
            check(r.readCache(OWNER, EnzonaReadKind.HISTORY, "page-fixture") == stored)
            check(runCatching { r.cacheRead(OWNER, EnzonaReadKind.ACCOUNT, OWNER, "42", 200) }.isFailure)
        }

        case("ENZONA rejects wrong-kind and error envelopes without replacing a good cache") { r, _ ->
            val reads = mapOf(
                EnzonaReadKind.ACCOUNT to """{"account":{"uuid":"account-fixture","enabled":false}}""",
                EnzonaReadKind.CARDS to """{"card":[{"funding_source_uuid":"card-fixture","currency":"literal-currency"}]}""",
                EnzonaReadKind.BALANCE to """{"balance":false,"balance_available":"00010.0000","operation":[],"currency":"1"}""",
                EnzonaReadKind.HISTORY to """{"transaction":[{"transaction_uuid":"history-fixture","source":false,"recipient":false,"currency":"1"}],"pagination":{"total":1,"next":false}}""",
            )
            val malformed = mapOf(
                EnzonaReadKind.ACCOUNT to listOf("""{"account":false}""", """{"account":{}}""",
                    """{"account":{"error":"synthetic-error"}}""", """{"account":{"access_token":"synthetic-secret"}}"""),
                EnzonaReadKind.CARDS to listOf("""{"card":{}}""", """{"card":[{}]}"""),
                EnzonaReadKind.BALANCE to listOf("""{"balance":10}""", """{"balance":"bad-amount","operation":[]}"""),
                EnzonaReadKind.HISTORY to listOf("""{"transaction":[],"pagination":[]}""", """{"transaction":[{}],"pagination":{}}"""),
            )
            reads.forEach { (kind, json) -> r.cacheRead(OWNER, kind, "same-resource", json, 100) }
            reads.forEach { (kind, json) ->
                val before = checkNotNull(r.readCache(OWNER, kind, "same-resource"))
                check(before.json == json && before.observedAt == 100L)
                val errors = listOf("fault", "error", "errors", "error_description").map { key ->
                    JsonObject((Json.parseToJsonElement(json) as JsonObject) + (key to JsonPrimitive("synthetic-error"))).toString()
                }
                val invalid = reads.filterKeys { it != kind }.values + errors + malformed.getValue(kind) + listOf(
                    "{}", "[]", """{"access_token":"synthetic-secret","refresh_token":"synthetic-secret"}""",
                )
                invalid.forEach { body ->
                    val failure = runCatching { r.cacheRead(OWNER, kind, "same-resource", body, 200) }.exceptionOrNull()
                    check(failure is IllegalArgumentException && failure.cause == null)
                    check(r.readCache(OWNER, kind, "same-resource") == before)
                    check(runCatching { r.cacheRead(OWNER, kind, "uncached-resource", body, 200) }.isFailure)
                    check(r.readCache(OWNER, kind, "uncached-resource") == null)
                }
                r.cacheRead(OWNER, kind, "same-resource", json, 300)
                check(r.readCache(OWNER, kind, "same-resource") == before.copy(observedAt = 300))
            }
        }
        case("ENZONA invoice fields are immutable and the same journal grants only one dispatch") { r, _ ->
            val invoice = fixture().copy(type = EnzonaIntentType.INVOICE_PAYMENT,
                destination = EnzonaDestination("client-id-fixture"), invoiceService = EnzonaInvoiceService.ELECTRICITY.name,
                invoiceOwner = "Titular sintético", invoiceFolio = "000-folio", invoiceRoute = "000-route", invoicePeriod = "2026-10")
            r.prepareIntent(invoice)
            val changes = listOf(invoice.copy(invoiceOwner = "Otro titular"), invoice.copy(invoiceFolio = "other-folio"),
                invoice.copy(invoiceRoute = "other-route"), invoice.copy(invoicePeriod = "other-period"),
                invoice.copy(destination = EnzonaDestination("other-client")),
                invoice.copy(invoiceService = EnzonaInvoiceService.FIXED_PHONE.name, invoiceFolio = null, invoiceRoute = null, invoicePeriod = null))
            changes.forEach { check(runCatching { r.prepareIntent(it) }.isFailure) }
            check(r.intent(OWNER, invoice.id)?.intent == invoice)
            val count = r.journal(OWNER).size
            listOf(invoice.copy(invoiceOwner = null), invoice.copy(invoiceService = null), invoice.copy(invoicePeriod = null),
                invoice.copy(type = EnzonaIntentType.CARD_TRANSFER)).forEach { invalid ->
                check(runCatching { r.prepareIntent(invalid.copy(id = UUID.randomUUID().toString())) }.isFailure)
            }
            check(r.journal(OWNER).size == count)
            check(r.tryBeginSubmission(OWNER, invoice.id))
            check(!r.tryBeginSubmission(OWNER, invoice.id))
            check(r.markTimedOut(OWNER, invoice.id))
            check(!r.tryBeginSubmission(OWNER, invoice.id))
            check(r.recordResult(OWNER, invoice.id, EnzonaIntentState.CONFIRMED, "observed-invoice-result"))
            check(!r.tryBeginSubmission(OWNER, invoice.id))
            check(r.prepareIntent(invoice).state == EnzonaIntentState.CONFIRMED)
        }
        case("ENZONA retained history survives window changes and merges only exact owner and UUID") { r, _ ->
            fun row(id: String, status: String = "pending") =
                """{"transaction_uuid":"$id","amount":10.0000,"currency":"1","transaction_created_at":"same-source-date","transaction_status":"$status","access_token":"synthetic-secret","metadata":{"payment_password":"synthetic-secret","codigo":"KEEP"}}"""
            fun page(vararg rows: String) = """{"transaction":[${rows.joinToString(",")}],"pagination":{}}"""
            val local = fixture()
            r.prepareIntent(local)
            check(r.tryBeginSubmission(OWNER, local.id))
            check(r.markTimedOut(OWNER, local.id))
            r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "activity", page(row("TX-A"), row("tx-a")), 100)
            r.cacheRead(OTHER_OWNER, EnzonaReadKind.HISTORY, "activity", page(row("TX-A", "other-owner")), 100)
            r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "activity", page(row("tx-a", "confirmed"), row(local.id, "confirmed")), 200)
            r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "activity", page(row("tx-a", "older")), 99)
            check(r.transactions(OWNER).size == 3)
            check(r.transactions(OWNER).single { it.transactionUuid == "tx-a" }.transactionStatus == "confirmed")
            check(r.transactions(OWNER).single { it.transactionUuid == "TX-A" }.transactionStatus == "pending")
            check(r.transactions(OTHER_OWNER).single().transactionStatus == "other-owner")
            check(r.transactions(OWNER).all { !it.original.toString().contains("synthetic-secret") })
            check(r.exportLocal(OWNER).transactions.all { it.json.contains("10.0000") && it.json.contains("KEEP") })
            val before = r.exportLocal(OWNER)
            r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "activity", page(), 300)
            check(r.exportLocal(OWNER) == before)
            check(r.transactions(OWNER).size == 3)
            check(runCatching { r.cacheRead(OWNER, EnzonaReadKind.HISTORY, "activity", page(row("new-id"), "{}"), 400) }.isFailure)
            check(r.exportLocal(OWNER) == before)
            check(r.intent(OWNER, local.id)?.state == EnzonaIntentState.UNKNOWN)
            check(!r.tryBeginSubmission(OWNER, local.id))
            check(runCatching { r.transactions("") }.isFailure)
        }
        return passed
    }

    private const val OWNER = "owner-fixture-a"
    private const val OTHER_OWNER = "owner-fixture-b"
    private const val CARD = "remote-card-fixture"

    private fun fixture(owner: String = OWNER) = EnzonaIntent(
        id = UUID.randomUUID().toString(), ownerSubject = owner, cardUuid = CARD,
        type = EnzonaIntentType.CARD_TRANSFER, destination = EnzonaDestination("0000000000000002"),
        amount = "00010.0000", currency = "CUP", description = "Concepto sintético", phone = "50000001", preparedAt = 1000,
    )
}
