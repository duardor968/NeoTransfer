package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.enzona.EnzonaRemoteCard
import dev.duardo.neotransfer.enzona.EnzonaWalletState
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant

internal enum class ProductKind { CARD, ACCOUNT, WALLET }
internal data class WalletProductUi(
    val id: String, val registrationId: String?, val identity: ProviderIdentity,
    val kind: ProductKind, val name: String, val number: String?, val currency: Currency?,
    val source: SourceSelector, val available: Money? = null, val balanceAt: Instant? = null,
    val card: CardRecord? = null, val account: AccountRecord? = null,
    val enzonaCardId: String? = null, val rawCurrency: String? = null,
    val last4: String? = null, val rawAvailable: BigDecimal? = null,
    val displayHolderName: String? = null, val displayExpiry: String? = null,
)

internal fun ProviderIdentity.title(): String = when (profile) {
    ProfileId.CLASSIC -> "Clásica"
    ProfileId.CLASSIC_BUSINESS -> "Clásica empresarial"
    ProfileId.AGENT -> "${provider.title()} · Agente"
    ProfileId.PERSONAL -> provider.title()
}

internal fun ProviderId.title(): String = when (this) {
    ProviderId.MITRANSFER -> "MiTransfer"
    ProviderId.CUBACEL -> "Cubacel"
    else -> name
}

internal fun RegistrationRecord.identity(): ProviderIdentity? = runCatching {
    ProviderIdentity(ProviderId.valueOf(providerId), ProfileId.valueOf(profileId))
}.getOrNull()

internal fun ProviderIdentity.accessIdentity(): ProviderIdentity =
    if (provider == ProviderId.MITRANSFER) ProviderIdentity(ProviderId.MITRANSFER) else this

internal fun authenticationProviders(): List<ProviderIdentity> = ProviderId.entries
    .filter { it.bank != null }.map { ProviderIdentity(it) }.filter(OperationCatalog::supports)

internal fun registrationLabel(registration: RegistrationRecord, state: AppUiState): String {
    val name = registration.label.ifBlank { registration.identity()?.title().orEmpty() }
    val line = registrationLineLabel(registration, state)
    return listOfNotNull(name, line).joinToString(" · ")
}

internal fun registrationLineLabel(registration: RegistrationRecord, state: AppUiState): String? =
    registration.linePhone ?: state.sims.firstOrNull { it.id == registration.subscriptionId }?.label
        ?: registration.previousLinePhone?.let { "Línea anterior: $it" }

internal fun productCurrencies(identity: ProviderIdentity): List<Currency> =
    if (identity in authenticationProviders()) CurrencyContract.BANK.active else emptyList()

internal fun hasAccess(state: AppUiState, identity: ProviderIdentity): Boolean =
    state.wallet.registrations.any { it.identity() == identity.accessIdentity() && it.id in state.configuredRegistrationIds } ||
        (state.wallet.registrations.none { it.identity() == identity.accessIdentity() } && identity.bank?.let { it in state.configuredBanks } == true)

internal fun hasProductAccess(state: AppUiState, product: WalletProductUi): Boolean =
    if (product.enzonaCardId != null) state.enzonaWallet.owner != null &&
        state.enzonaWallet.cards.any { it.fundingSourceUuid == product.enzonaCardId }
    else product.registrationId?.let { id -> id in state.configuredRegistrationIds &&
        state.wallet.registrations.singleOrNull { it.id == id }?.identity() == product.identity.accessIdentity()
    } ?: hasAccess(state, product.identity)

internal fun initialCompatibleProduct(state: AppUiState, products: List<WalletProductUi>): WalletProductUi? =
    if (state.enzonaWallet.selectedCardId != null) null
    else if (state.selectedProductId != null) products.firstOrNull { it.id == state.selectedProductId && it.enzonaCardId == null }
    else products.firstOrNull { it.enzonaCardId == null && it.identity.bank == state.bank }

private fun BalanceRecord.validSentAt(): Long? = sentAt?.takeIf { it > 0 && it <= at && it <= System.currentTimeMillis() }
private fun BalanceRecord.displayAt(): Long = validSentAt() ?: at

internal fun latestRegistrationBalance(rows: List<BalanceRecord>): BalanceRecord? {
    if (rows.size <= 1) return rows.singleOrNull()
    val account = rows.first().account ?: return null
    val currency = rows.first().currency
    if (rows.any { it.account != account || it.currency != currency }) return null
    val dated = rows.mapNotNull { row -> row.validSentAt()?.let { row to it } }
    if (dated.size == rows.size) {
        val newest = dated.maxOf { it.second }
        val latest = dated.filter { it.second == newest }.map { it.first }
        if (latest.map { it.available to it.ledger }.distinct().size != 1) return null
        return latest.maxByOrNull { it.at }
    }
    // Reception time cannot be compared with an SMSC date. Exact duplicate values are safe to show.
    if (rows.map { it.available to it.ledger }.distinct().size != 1) return null
    return dated.maxByOrNull { it.second }?.first ?: rows.maxByOrNull { it.at }
}

internal fun walletProducts(state: AppUiState, includeHistorical: Boolean = false, includeEnzona: Boolean = true): List<WalletProductUi> {
    val wallet = state.wallet
    val products = buildList {
        wallet.cards.forEach { card ->
            val registration = wallet.registrations.singleOrNull { it.id == card.registrationId } ?: return@forEach
            val identity = registration.productIdentity(card.profileId) ?: return@forEach
            val account = wallet.accounts.singleOrNull { it.id == card.accountId }
            val currency = (card.currency ?: account?.currency)?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
            val balance = wallet.balances.filter { row ->
                row.bankCode == registration.bankCode && row.subscriptionId == registration.subscriptionId &&
                    (row.registrationId == null || row.registrationId == registration.id) &&
                    (row.cardId == card.id || (row.cardId == null && row.accountId != null && row.accountId == card.accountId) ||
                        (row.cardId == null && row.accountId == null && row.account?.let { number ->
                            val matchingCards = wallet.cards.filter { it.registrationId == registration.id &&
                                (it.currency == null || it.currency == row.currency) && matchesAccount(number, it.number) }
                            matchingCards.singleOrNull()?.id == card.id || (account != null && number == account.number &&
                                wallet.cards.count { it.accountId == account.id } == 1)
                        } == true)) &&
                    (currency == null || row.currency == currency.name)
            }.maxByOrNull { it.at }
            add(WalletProductUi(card.id, registration.id, identity, ProductKind.CARD, card.label,
                card.number, currency, SourceSelector.Explicit(card.number),
                balance?.let { Money(it.available.toBigDecimal(), Currency.valueOf(it.currency)) },
                balance?.let { Instant.ofEpochMilli(it.displayAt()) }, card = card))
        }
        wallet.accounts.filter { account -> wallet.cards.none { it.accountId == account.id } }.forEach { account ->
            val registration = wallet.registrations.singleOrNull { it.id == account.registrationId } ?: return@forEach
            val identity = registration.productIdentity(account.profileId) ?: return@forEach
            val currency = account.currency?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
            val balance = wallet.balances.filter { row ->
                row.bankCode == registration.bankCode && row.subscriptionId == registration.subscriptionId &&
                    (row.registrationId == null || row.registrationId == registration.id) &&
                    (row.accountId == account.id || (row.accountId == null && row.cardId == null && row.account?.let { number ->
                        wallet.accounts.filter { it.registrationId == registration.id &&
                            (it.currency == null || it.currency == row.currency) && (number == it.number || matchesAccount(number, it.number)) }.singleOrNull()?.id == account.id
                    } == true)) &&
                    (currency == null || row.currency == currency.name)
            }.maxByOrNull { it.at }
            add(WalletProductUi(account.id, registration.id, identity,
                if (identity == ProviderIdentity(ProviderId.MITRANSFER)) ProductKind.WALLET else ProductKind.ACCOUNT,
                account.label, account.number.takeIf { it.isNotBlank() }, currency,
                if (account.number.isBlank()) SourceSelector.Default else SourceSelector.Explicit(account.number),
                balance?.let { Money(it.available.toBigDecimal(), Currency.valueOf(it.currency)) },
                balance?.let { Instant.ofEpochMilli(it.displayAt()) }, account = account))
        }
        wallet.registrations.forEach { registration ->
            if (none { it.registrationId == registration.id }) {
                val identity = registration.identity() ?: return@forEach
                val balance = latestRegistrationBalance(wallet.balances.filter { row -> row.cardId == null && row.accountId == null &&
                    row.bankCode == registration.bankCode && row.subscriptionId == registration.subscriptionId &&
                    (row.registrationId == registration.id || (row.registrationId == null && wallet.registrations.count {
                            it.bankCode == row.bankCode && it.subscriptionId == row.subscriptionId
                        } == 1)) })
                val currency = balance?.currency?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
                add(WalletProductUi("registration:${registration.id}", registration.id, identity,
                    if (identity.provider == ProviderId.MITRANSFER && identity.profile == ProfileId.PERSONAL) ProductKind.WALLET else ProductKind.ACCOUNT,
                    registration.label.ifBlank { "Cuenta predeterminada" }, balance?.account, currency, SourceSelector.Default,
                    balance?.let { Money(it.available.toBigDecimal(), Currency.valueOf(it.currency)) }, balance?.let { Instant.ofEpochMilli(it.displayAt()) }))
            }
        }
        // A bank's default selector is represented as an account, never as an invented card.
        state.configuredBanks.forEach { bank ->
            if (none { it.identity.bank == bank }) {
                val known = if (bank == state.bank) state.accounts.singleOrNull() else null
                add(WalletProductUi("default:${bank.name}", null, ProviderIdentity.forBank(bank), ProductKind.ACCOUNT,
                    "Cuenta predeterminada", known?.account, known?.available?.currency, SourceSelector.Default,
                    known?.available, if (known == null) null else state.balanceAt))
            }
        }
    }
    val combined = if (includeEnzona) mergeEnzonaProducts(products, state.enzonaWallet) else products
    return combined.filter { it.enzonaCardId != null || OperationCatalog.supports(it.identity) ||
        (includeHistorical && (it.card != null || it.account != null)) }
}

internal fun selectedProduct(state: AppUiState, products: List<WalletProductUi> = walletProducts(state)): WalletProductUi? =
    if (state.enzonaWallet.selectedCardId != null) products.singleOrNull { it.enzonaCardId == state.enzonaWallet.selectedCardId }
    else products.firstOrNull { it.id == state.selectedProductId } ?: products.firstOrNull { it.enzonaCardId != null }
        ?: products.firstOrNull { it.identity.bank == state.bank } ?: products.firstOrNull()

internal fun enzonaBankIdentity(name: String?): ProviderIdentity? {
    val normalized = name?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        ?.replace(Regex("\\p{M}+"), "")?.trim()?.replace(Regex("\\s+"), " ")?.uppercase()
    return when (normalized) {
        "BPA", "BANCO POPULAR DE AHORRO" -> ProviderIdentity.forBank(Bank.BPA)
        "BANDEC", "BANCO DE CREDITO Y COMERCIO" -> ProviderIdentity.forBank(Bank.BANDEC)
        else -> null
    }
}

internal fun EnzonaRemoteCard.knownPan(wallet: EnzonaWalletState): String? = wallet.links.singleOrNull {
    it.ownerSubject == wallet.owner && it.cardUuid == fundingSourceUuid
}?.pan?.takeIf { it.matches(Regex("[0-9]{16}")) && (last4.isNullOrBlank() || it.takeLast(4) == last4) }

internal fun mergeEnzonaProducts(local: List<WalletProductUi>, wallet: EnzonaWalletState): List<WalletProductUi> {
    if (wallet.owner == null) return local
    val linkedLocalIds = mutableSetOf<String>()
    val remote = wallet.cards.mapNotNull { card ->
        val observed = wallet.balances[card.fundingSourceUuid]
        if (card.currency.equals("CUC", ignoreCase = true) || observed?.value?.currency.equals("CUC", ignoreCase = true))
            return@mapNotNull null
        val pan = card.knownPan(wallet)
        val linked = local.singleOrNull { pan != null && it.kind == ProductKind.CARD && it.number == pan &&
            it.enzonaCardId == null }?.takeIf { OperationCatalog.supports(it.identity) }
        val explicitBank = enzonaBankIdentity(card.bankName)
        val conflictingBank = explicitBank != null && local.any {
            pan != null && it.kind == ProductKind.CARD && it.number == pan && it.enzonaCardId == null && it.identity != explicitBank
        }
        val compatibleLocal = linked.takeUnless { conflictingBank }
        val identity = compatibleLocal?.identity ?: explicitBank ?: return@mapNotNull null
        compatibleLocal?.let { linkedLocalIds += it.id }
        val link = wallet.links.singleOrNull { it.ownerSubject == wallet.owner && it.cardUuid == card.fundingSourceUuid }
            .takeUnless { conflictingBank }
        val currencyText = observed?.value?.currency ?: card.currency
        val currency = Currency.entries.singleOrNull { it.name == currencyText }
        val amount = observed?.value?.availableBalance
        WalletProductUi("enzona:${card.fundingSourceUuid}", null, identity, ProductKind.CARD,
            link?.alias?.takeIf(String::isNotBlank) ?: compatibleLocal?.name?.takeIf(String::isNotBlank) ?: identity.title(),
            pan.takeUnless { conflictingBank }, currency, SourceSelector.Default,
            available = if (amount != null && currency != null && amount.signum() >= 0 && amount.scale() <= 2) Money(amount, currency) else null,
            balanceAt = observed?.let { Instant.ofEpochMilli(it.observedAt) },
            enzonaCardId = card.fundingSourceUuid, rawCurrency = currencyText,
            last4 = card.last4?.takeIf { it.matches(Regex("[0-9]{4}")) }, rawAvailable = amount,
            displayHolderName = card.cardholder?.takeIf(String::isNotBlank) ?: compatibleLocal?.card?.holderName?.printableCardHolder(),
            displayExpiry = card.expiryDate?.takeIf(String::isNotBlank) ?: compatibleLocal?.card?.expiry?.printedCardExpiry())
    }
    return local.filterNot { it.id in linkedLocalIds } + remote
}

internal fun WalletProductUi.numberLabel(): String? = number?.let(::readableAccount)
    ?: last4?.let { "Terminación $it" }

private fun String.printableCardHolder(): String? = takeIf { it.isNotBlank() && it.none(Char::isISOControl) }

// No conversion of compact digits or other remote date formats without a proven contract.
private fun String.printedCardExpiry(): String? = takeIf { it.matches(Regex("(0[1-9]|1[0-2])/[0-9]{2}")) }

internal fun WalletProductUi.cardHolderLabel(): String? = (displayHolderName ?: card?.holderName)?.printableCardHolder()

internal fun WalletProductUi.cardExpiryLabel(): String? = (displayExpiry ?: card?.expiry)?.printedCardExpiry()

internal fun WalletProductUi.balanceLabel(): String = if (enzonaCardId != null) rawAvailable?.let { value ->
    listOfNotNull(value.toPlainString(), rawCurrency).joinToString(" ")
} ?: "Sin consultar" else available?.let(::formatMoney) ?: "Sin consultar"

internal fun displayRecipients(state: AppUiState): List<Recipient> =
    state.wallet.contacts.flatMap { contact ->
        contact.cards.map { Recipient(contact.name, it.number, null) } +
            contact.phones.map { Recipient(contact.name, "", it.number) }
    }.ifEmpty { state.recipients }
