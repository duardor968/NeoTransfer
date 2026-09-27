package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.core.BankSmsParser
import kotlin.test.*

class BpaBalanceBindingTest {
    private val body = "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n\n Saldo Disponible: CR 42.00 CUP"
    private val message = BankSmsParser().parse("PAGOxMOVIL", body) as BankMessage.Balance
    private val event = EventRecord("event", EventSource.INBOX, "1", "PAGOxMOVIL", body, 1_015_000, 7,
        "event", sentAt = 1_010_000)
    private val query = OperationRecord("query", "bpa.balance", "01", 7, "", null, "CUP", 1_000_000,
        registrationId = "bpa", source = "0000000000000001", providerId = "BPA",
        status = OperationStatus.AWAITING_CONFIRMATION, parameters = mapOf("sourceCurrency" to "CUP"))
    private val state = WalletSnapshot(
        registrations = listOf(RegistrationRecord("bpa", "owner", "BPA", "PERSONAL", "01", 7, null, "BPA")),
        cards = listOf(CardRecord("card", "bpa", query.source, "Propia", currency = "CUP")),
        operations = listOf(query),
    )
    private fun bind(s: WalletSnapshot = state, e: EventRecord = event) = bpaBalanceBinding(s, e, message, 1_020_000)

    @Test fun flatReplyUsesExplicitJournalSourceWithoutInventingAnEchoedAccountOrLedger() {
        val binding = assertNotNull(bind())
        assertEquals("card", binding.balance.cardId)
        assertEquals("42.00", binding.balance.available)
        assertNull(binding.balance.account)
        assertNull(binding.balance.ledger)
        assertEquals(listOf("query"), binding.queries.map { it.id })
    }

    @Test fun wrongSimOldFutureUnknownAndRestoredEvidenceCannotBind() {
        listOf(event.copy(subscriptionId = 8), event.copy(sentAt = 900_000), event.copy(sentAt = null),
            event.copy(sentAt = 1_016_000), event.copy(receivedAt = 1_030_000),
            event.copy(evidenceEligible = false), event.copy(source = EventSource.LEGACY),
            event.copy(originalSource = EventSource.INBOX)).forEach { assertNull(bind(e = it)) }
    }

    @Test fun defaultRestoredForeignAndFinancialRequestsCannotAttributeASavedCard() {
        listOf(query.copy(source = "0000"), query.copy(restored = true), query.copy(providerId = "BANDEC"),
            query.copy(bankCode = "02"), query.copy(kind = "TRANSFER"), query.copy(amount = "10.00"),
            query.copy(destination = "0000000000000002"), query.copy(status = OperationStatus.PREPARED),
            query.copy(profileId = "CLASSIC")).forEach { assertNull(bind(state.copy(operations = listOf(it)))) }
    }

    @Test fun knownAccountAlsoBindsButConflictingOrMissingProductsDoNot() {
        val account = AccountRecord("account", "bpa", query.source, "Cuenta", currency = "CUP")
        assertEquals("account", bind(state.copy(cards = emptyList(), accounts = listOf(account)))?.balance?.accountId)
        assertNull(bind(state.copy(cards = emptyList())))
        assertNull(bind(state.copy(accounts = listOf(account))))
        assertNull(bind(state.copy(cards = listOf(state.cards.single().copy(currency = "USD")))))
        assertNull(bind(state.copy(registrations = listOf(state.registrations.single().copy(subscriptionId = 8)))))
        assertNull(bind(state.copy(registrations = listOf(state.registrations.single().copy(enabled = false)))))
    }

    @Test fun selectedCardNeverOverridesTheExplicitRequest() {
        val other = CardRecord("other", "bpa", "0000000000000002", "Otra", currency = "CUP")
        assertEquals("card", bind(state.copy(cards = state.cards + other,
            settings = WalletSettings(selectedRegistrationId = "bpa", selectedCardId = "other")))?.balance?.cardId)
    }

    @Test fun explicitSourceDoesNotNeedAWireCurrencyBecauseTheBankDeterminesItsUnit() {
        assertEquals("card", bind(state.copy(operations = listOf(query.copy(currency = null, parameters = emptyMap()))))?.balance?.cardId)
    }

    @Test fun inboxAliasCannotReuseAnEarlierBroadcastForANewQueryInTheSameSmsSecond() {
        val broadcast = event.copy(receivedAt = 1_010_100, source = EventSource.BROADCAST)
        val alias = event.copy(id = "inbox", receivedAt = 1_010_300)
        val laterQuery = query.copy(startedAt = 1_010_200)
        assertNull(bind(state.copy(events = listOf(broadcast, alias), operations = listOf(laterQuery)), alias))
        assertNull(bind(state.copy(events = listOf(broadcast.copy(evidenceEligible = false), alias)), alias))
    }

    @Test fun repeatedSameSourceReadsIdentifyTheCardButKeepAllPossibleQueries() {
        val second = query.copy(id = "query2", startedAt = 1_001_000, status = OperationStatus.UNCERTAIN)
        assertEquals(setOf("query", "query2"), bind(state.copy(operations = listOf(query, second)))?.queries?.map { it.id }?.toSet())
    }

    @Test fun otherUnresolvedSourcesBlockEvenWhenTheyAreNotSavedOrHaveConflictingMetadata() {
        listOf(query.copy(id = "other", source = "0000000000000002"),
            query.copy(id = "default", source = "0000"),
            query.copy(id = "other-reg", registrationId = "missing"),
            query.copy(id = "other-currency", source = "0000000000000002", parameters = mapOf("sourceCurrency" to "USD")))
            .forEach { assertNull(bind(state.copy(operations = listOf(query, it)))) }
    }
}
