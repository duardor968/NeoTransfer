package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.CardRecord
import dev.duardo.neotransfer.enzona.*
import dev.duardo.neotransfer.enzona.data.EnzonaCardLink
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import kotlin.test.*

class EnzonaWalletMappingTest {
    private val pan = "1234567890123456"
    private val bpa = ProviderIdentity.forBank(Bank.BPA)
    private val bandec = ProviderIdentity.forBank(Bank.BANDEC)
    private val owner = "owner-a"

    private fun local(id: String = "local", identity: ProviderIdentity = bandec, number: String = pan) =
        WalletProductUi(id, "registration", identity, ProductKind.CARD, "Mi tarjeta", number, Currency.CUP,
            SourceSelector.Explicit(number))

    private fun localWithMetadata() = local().copy(card = CardRecord("local", "registration", pan, "Mi tarjeta",
        holderName = "TITULAR LOCAL", expiry = "07/29"))

    private fun card(id: String = "remote", bank: String? = "BANDEC", suffix: String? = "3456", currency: String? = "CUP") =
        EnzonaRemoteCard(fundingSourceUuid = id, fundingSourceId = null, last4 = suffix, cardholder = null,
            expiryDate = null, createdAt = null, updatedAt = null, status = null, currency = currency,
            primarySource = null, bankName = bank, bankCode = "01", verified = null, deleted = null,
            bankCertificate = null, bin = 920495)

    private fun state(wallet: EnzonaWalletState, localId: String? = null) = AppUiState(
        unlocked = true, hasCredentials = false, bank = Bank.BPA, subscription = -1, sims = emptyList(),
        configuredBanks = emptySet(), busy = false, accounts = emptyList(), balanceAt = null, history = emptyList(),
        pending = null, confirmed = null, recipients = emptyList(), notice = null, permissions = false,
        cameraPermission = false, contact = null, selectedProductId = localId, enzonaWallet = wallet)

    @Test fun remoteCardsUseOnlyExplicitBankNamesAndNeverExpandLastFourDigits() {
        val saved = local()
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(), card("other", "Banco Popular de Ahorro")))
        val products = mergeEnzonaProducts(listOf(saved), wallet)
        assertEquals(listOf("local", "enzona:remote", "enzona:other"), products.map { it.id })
        val remote = products[1]
        assertNull(remote.number)
        assertEquals("Terminación 3456", remote.numberLabel())
        assertEquals(bandec, remote.identity)
        assertEquals(bpa, products[2].identity)
        assertEquals(bandec, enzonaBankIdentity("Banco de Crédito y Comercio"))
        assertEquals(bandec, enzonaBankIdentity("  banco de credito y comercio  "))
        assertEquals(SourceSelector.Default, remote.source)
    }

    @Test fun explicitFullPanLinkReplacesExactlyOneLocalProductAndKeepsItsIdentity() {
        val saved = local()
        val other = local("other", bpa, "5555666677773456")
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(bank = null)),
            links = listOf(EnzonaCardLink(owner, "remote", pan, "Principal")))
        val products = mergeEnzonaProducts(listOf(saved, other), wallet)
        assertEquals(listOf("other", "enzona:remote"), products.map { it.id })
        val remote = products.last()
        assertEquals(pan, remote.number)
        assertEquals(readableAccount(pan), remote.numberLabel())
        assertEquals("Principal", remote.name)
        assertEquals(saved.identity, remote.identity)
        assertNull(remote.registrationId)
        assertNull(remote.card)
        assertNull(remote.account)
        assertEquals(SourceSelector.Default, remote.source)
    }

    @Test fun ambiguousFullPanAndSharedLastFourNeverRemoveLocalProducts() {
        val locals = listOf(local(), local("duplicate", bpa))
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()),
            links = listOf(EnzonaCardLink(owner, "remote", pan)))
        assertEquals(listOf("local", "duplicate", "enzona:remote"), mergeEnzonaProducts(locals, wallet).map { it.id })
        assertEquals(3, mergeEnzonaProducts(locals, wallet.copy(links = emptyList())).size)
    }

    @Test fun explicitRemoteBankContradictingTheLinkedLocalBankRejectsTheAssociation() {
        val saved = local(identity = bandec)
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(bank = "Banco Popular de Ahorro")),
            links = listOf(EnzonaCardLink(owner, "remote", pan, "Asociación incompatible")))
        val products = mergeEnzonaProducts(listOf(saved), wallet)
        assertEquals(listOf("local", "enzona:remote"), products.map { it.id })
        assertEquals(bpa, products.last().identity)
        assertNull(products.last().number)
        assertEquals("BPA", products.last().name)
        assertEquals(pan, products.first().number)
    }

    @Test fun unknownBankCodesAndBinsDoNotAssignAnIdentity() {
        assertNull(enzonaBankIdentity("01"))
        assertNull(enzonaBankIdentity("920495"))
        assertNull(enzonaBankIdentity("Banco desconocido BPA"))
        val saved = local()
        val unknown = EnzonaWalletState(owner = owner, cards = listOf(card(bank = null)))
        // Such cards remain available for explicit association in BanksScreen.
        assertEquals(listOf(saved), mergeEnzonaProducts(listOf(saved), unknown))
        assertEquals(1, unknown.cards.size)
    }

    @Test fun ownerSwitchAndCardUuidPreventReusingAnotherLink() {
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()), links = listOf(
            EnzonaCardLink("owner-b", "remote", pan, "Ajena"), EnzonaCardLink(owner, "other", pan, "Otra")))
        val remote = mergeEnzonaProducts(emptyList(), wallet).single()
        assertNull(remote.number)
        assertEquals("BANDEC", remote.name)
        assertTrue(mergeEnzonaProducts(emptyList(), wallet.copy(owner = null)).isEmpty())
        val saved = local()
        assertEquals(listOf(saved), mergeEnzonaProducts(listOf(saved), wallet.copy(owner = null)))
    }

    @Test fun aChangedSuffixDoesNotUseAStaleFullPanLink() {
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(suffix = "9999")),
            links = listOf(EnzonaCardLink(owner, "remote", pan)))
        val products = mergeEnzonaProducts(listOf(local()), wallet)
        assertEquals(2, products.size)
        assertNull(products.last().number)
        assertEquals("Terminación 9999", products.last().numberLabel())
    }

    @Test fun remoteAmountsAndCurrencyStayLiteralIncludingUnsupportedValues() {
        val amount = BigDecimal("-3.125")
        val balance = EnzonaCardBalance(null, amount, "1", null, null, null, null, emptyList(), JsonObject(emptyMap()))
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()),
            balances = mapOf("remote" to EnzonaObservedBalance(balance, 1000)))
        val remote = mergeEnzonaProducts(emptyList(), wallet).single()
        assertNull(remote.currency)
        assertNull(remote.available)
        assertEquals("1", remote.rawCurrency)
        assertEquals(amount, remote.rawAvailable)
        assertEquals("-3.125 1", remote.balanceLabel())
        assertEquals(1000L, remote.balanceAt?.toEpochMilli())
    }

    @Test fun remoteSelectionWinsAndAStaleSelectionNeverFallsBackToBankOperations() {
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()), selectedCardId = "remote")
        val products = mergeEnzonaProducts(listOf(local()), wallet)
        val ui = state(wallet, "local")
        val selected = requireNotNull(selectedProduct(ui, products))
        assertEquals("enzona:remote", selected.id)
        assertTrue(hasProductAccess(ui, selected)) // No SMS permission, registration, bank PIN or configured bank.
        assertNull(initialCompatibleProduct(ui, products))
        assertNull(selectedProduct(ui.copy(enzonaWallet = wallet.copy(selectedCardId = "missing")), products))
        assertEquals("local", selectedProduct(ui.copy(enzonaWallet = wallet.copy(selectedCardId = null)), products)?.id)
        assertEquals("enzona:remote", selectedProduct(state(wallet.copy(selectedCardId = null)), products)?.id)
    }

    @Test fun literalCucIsNeverAnOperatingOriginAndItsCachedDataRemainsIntact() {
        val saved = local()
        for ((cardCurrency, balanceCurrency) in listOf("CUC" to "CUP", "cuc" to null, "CUP" to "CuC")) {
            val cached = EnzonaObservedBalance(EnzonaCardBalance(null, BigDecimal("12.00"), balanceCurrency,
                null, null, null, null, emptyList(), JsonObject(emptyMap())), 1000)
            val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(currency = cardCurrency)),
                links = listOf(EnzonaCardLink(owner, "remote", pan)), balances = mapOf("remote" to cached), selectedCardId = "remote")
            val products = mergeEnzonaProducts(listOf(saved), wallet)
            assertEquals(listOf(saved), products)
            assertNull(selectedProduct(state(wallet, "local"), products))
            assertEquals(cardCurrency, wallet.cards.single().currency)
            assertSame(cached, wallet.balances["remote"])
            assertEquals(pan, wallet.links.single().pan)
        }
        for (literal in listOf("CUP", "cup", "2", "CUC ")) {
            val wallet = EnzonaWalletState(owner = owner, cards = listOf(card(currency = literal)))
            val product = mergeEnzonaProducts(emptyList(), wallet).single()
            assertEquals(literal, product.rawCurrency)
            assertEquals(if (literal == "CUP") Currency.CUP else null, product.currency)
        }
    }

    @Test fun remoteHolderAndExplicitExpiryRenderWithoutInventingACardOrPan() {
        val source = card().copy(cardholder = "Titular Remoto", expiryDate = "12/30")
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(source))
        val product = mergeEnzonaProducts(emptyList(), wallet).single()
        assertEquals("Titular Remoto", product.cardHolderLabel())
        assertEquals("12/30", product.cardExpiryLabel())
        assertNull(product.number)
        assertNull(product.card)
        assertNull(product.registrationId)
        assertEquals("Terminación 3456", product.numberLabel())
        assertEquals(bandec, product.identity)
        assertEquals(Currency.CUP, product.currency)
        assertSame(source, wallet.cards.single())
    }

    @Test fun linkedLocalMetadataIsUsedOnlyForMissingRemoteValues() {
        val saved = localWithMetadata()
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()),
            links = listOf(EnzonaCardLink(owner, "remote", pan)))
        for (missing in listOf<String?>(null, "", "  ")) {
            val product = mergeEnzonaProducts(listOf(saved), wallet.copy(cards = listOf(
                card().copy(cardholder = missing, expiryDate = missing)))).single()
            assertEquals("TITULAR LOCAL", product.cardHolderLabel())
            assertEquals("07/29", product.cardExpiryLabel())
            assertEquals(pan, product.number)
            assertNull(product.card)
            assertNull(product.registrationId)
        }
        val remote = card().copy(cardholder = "Titular Remoto", expiryDate = "12/30")
        val product = mergeEnzonaProducts(listOf(saved), wallet.copy(cards = listOf(remote))).single()
        assertEquals("Titular Remoto", product.cardHolderLabel())
        assertEquals("12/30", product.cardExpiryLabel())
        assertEquals("TITULAR LOCAL", saved.card?.holderName)
        assertEquals("07/29", saved.card?.expiry)
    }

    @Test fun unprovenRemoteExpiryRemainsLiteralAndDoesNotBorrowALocalDate() {
        val wallet = EnzonaWalletState(owner = owner, links = listOf(EnzonaCardLink(owner, "remote", pan)))
        for (literal in listOf("1230", "2030-12-31", "13/30", "00/30", "12/2030", " 12/30 ")) {
            val source = card().copy(expiryDate = literal)
            val product = mergeEnzonaProducts(listOf(localWithMetadata()), wallet.copy(cards = listOf(source))).single()
            assertEquals(literal, product.displayExpiry)
            assertNull(product.cardExpiryLabel())
            assertEquals(literal, source.expiryDate)
        }
    }

    @Test fun unlinkedAmbiguousAndContradictoryCardsNeverBorrowLocalMetadata() {
        val saved = localWithMetadata()
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()))
        val withoutLink = mergeEnzonaProducts(listOf(saved), wallet).last()
        assertNull(withoutLink.cardHolderLabel())
        assertNull(withoutLink.cardExpiryLabel())
        val linked = wallet.copy(links = listOf(EnzonaCardLink(owner, "remote", pan)))
        val ambiguous = mergeEnzonaProducts(listOf(saved, saved.copy(id = "duplicate")), linked).last()
        assertNull(ambiguous.cardHolderLabel())
        assertNull(ambiguous.cardExpiryLabel())
        val contradictory = mergeEnzonaProducts(listOf(saved), linked.copy(cards = listOf(card(bank = "BPA")))).last()
        assertNull(contradictory.cardHolderLabel())
        assertNull(contradictory.cardExpiryLabel())
    }

    @Test fun validatedLocalMetadataKeepsItsExistingAppearance() {
        val saved = localWithMetadata()
        assertEquals("TITULAR LOCAL", saved.cardHolderLabel())
        assertEquals("07/29", saved.cardExpiryLabel())
        val invalid = saved.copy(card = requireNotNull(saved.card).copy(holderName = "\n", expiry = "99/29"))
        val wallet = EnzonaWalletState(owner = owner, cards = listOf(card()), links = listOf(EnzonaCardLink(owner, "remote", pan)))
        val product = mergeEnzonaProducts(listOf(invalid), wallet).single()
        assertNull(product.cardHolderLabel())
        assertNull(product.cardExpiryLabel())
    }
}
