package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryKnownAccountTest {
    private val mask = "0000XXXXXXXX0001"
    private val pan = "0000111122220001"
    private val otherPan = "0000999988880001"
    private val at = Instant.parse("2026-09-24T12:00:00Z")
    private val registration = RegistrationRecord("reg", "owner", "BANDEC", "PERSONAL", "02", 7, null, "BANDEC")

    private fun state(wallet: WalletSnapshot, legacySim: Int? = 7, receivedAccount: String = mask) = AppUiState(
        unlocked = true, hasCredentials = true, bank = Bank.BANDEC, subscription = 7,
        sims = emptyList(), configuredBanks = setOf(Bank.BANDEC), busy = false,
        accounts = emptyList(), balanceAt = null,
        history = listOf(HistoryEntry(BankSmsRecord(1, "fixture", at, legacySim),
            BankMessage.TransferReceived(receivedAccount, "50123456", Money(BigDecimal("10.00"), Currency.CUP), "REF1"))),
        pending = null, confirmed = null, recipients = emptyList(), notice = null,
        permissions = false, cameraPermission = false, contact = null, wallet = wallet)

    private fun wallet() = WalletSnapshot(
        registrations = listOf(registration), cards = listOf(CardRecord("card", "reg", pan, "Propia")),
        receipts = listOf(ReceiptRecord("receipt", "event", "02", 7, "RECEIVED", "REF2", "10.00", "CUP", "50123456", mask)),
        movements = listOf(MovementRecord("movement", "receipt", "RECEIVED", "10.00", "CUP", at.toEpochMilli(), true)),
        histories = listOf(HistoryEntryRecord("history", "02", 7, "2026-09-24", "Abono", true, "10.00", "CUP", "REF3", account = mask)))

    @Test fun ownAccountIsExpandedAcrossLegacyPersistedAndRecoveredViews() {
        val rows = appFinancialHistory(state(wallet()))
        assertEquals(3, rows.size)
        assertEquals(listOf(pan, pan, pan), rows.map { it.movement.account })
        assertEquals(mask, state(wallet()).history.single().message.let { (it as BankMessage.TransferReceived).account })
    }

    @Test fun ambiguousOwnPanBankOrSimKeepsTheOriginalMask() {
        val anotherPan = CardRecord("other", "reg", otherPan, "Otra propia")
        val ambiguousPan = wallet().copy(cards = wallet().cards + anotherPan)
        assertEquals(listOf(mask, mask, mask), appFinancialHistory(state(ambiguousPan)).map { it.movement.account })

        val otherBank = RegistrationRecord("other-bank", "owner", "BPA", "PERSONAL", "01", 7, null, "BPA")
        val ambiguousBank = wallet().copy(registrations = wallet().registrations + otherBank,
            cards = wallet().cards + CardRecord("other-bank-card", "other-bank", otherPan, "Otra propia"))
        val bySource = appFinancialHistory(state(ambiguousBank))
        assertEquals(mask, bySource.single { it.entry != null }.movement.account)
        assertEquals(pan, bySource.single { it.storedId == "movement" }.movement.account)

        val noSim = appFinancialHistory(state(wallet(), legacySim = null))
        assertEquals(mask, noSim.single { it.entry != null }.movement.account)
        val wrongSim = wallet().copy(registrations = listOf(registration.copy(subscriptionId = 8)))
        assertEquals(listOf(mask, mask, mask), appFinancialHistory(state(wrongSim)).map { it.movement.account })
    }

    @Test fun banklessReceivedTransferBelongsToTheOnlySavedOwnCard() {
        val uiState = state(wallet(), receivedAccount = pan)
        val rows = appFinancialHistory(uiState)
        val card = walletProducts(uiState).single { it.id == "card" }

        assertEquals(3, rows.size) // Home uses this global list, including the unattributed ledger credit.
        val received = rows.filter { it.movement.kind == MovementKind.RECEIVED }
        assertEquals(2, received.size)
        assertEquals(listOf(Bank.BANDEC, Bank.BANDEC), received.map { it.movement.bank })
        assertEquals(listOf("card", "card"), received.map { it.cardId })
        assertEquals(listOf(true, true), received.map { movementBelongsToProduct(it, card) })
    }

    @Test fun ambiguousOrUnknownOwnCardStaysGlobalWithoutSourceAttribution() {
        val duplicate = RegistrationRecord("other-reg", "owner", "BPA", "PERSONAL", "01", 7, null, "BPA")
        val ambiguousWallet = wallet().copy(registrations = listOf(registration, duplicate),
            cards = listOf(CardRecord("card", "reg", pan, "Propia"), CardRecord("other", "other-reg", pan, "Otra")),
            receipts = emptyList(), movements = emptyList(), histories = emptyList())
        val uiState = state(ambiguousWallet, receivedAccount = pan)
        val row = appFinancialHistory(uiState).single()

        assertEquals(MovementKind.RECEIVED, row.movement.kind)
        assertEquals(null, row.movement.bank)
        assertEquals(null, row.cardId)
        assertEquals(listOf(false, false), walletProducts(uiState).map { movementBelongsToProduct(row, it) })

        val unknown = appFinancialHistory(state(WalletSnapshot(), receivedAccount = pan)).single()
        assertEquals(MovementKind.RECEIVED, unknown.movement.kind)
        assertEquals(null, unknown.movement.bank)
    }
}
