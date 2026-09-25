package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import java.time.Instant

internal enum class ProductKind { CARD, ACCOUNT, WALLET }
internal data class WalletProductUi(
    val id: String, val registrationId: String?, val identity: ProviderIdentity,
    val kind: ProductKind, val name: String, val number: String?, val currency: Currency?,
    val source: SourceSelector, val available: Money? = null, val balanceAt: Instant? = null,
    val card: CardRecord? = null, val account: AccountRecord? = null,
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
    .filter { it.bank != null || it == ProviderId.MITRANSFER }.map { ProviderIdentity(it) }.filter(OperationCatalog::supports)

internal fun registrationLabel(registration: RegistrationRecord, state: AppUiState): String {
    val name = registration.label.ifBlank { registration.identity()?.title().orEmpty() }
    val line = registrationLineLabel(registration, state)
    return listOfNotNull(name, line).joinToString(" · ")
}

internal fun registrationLineLabel(registration: RegistrationRecord, state: AppUiState): String? =
    registration.linePhone ?: state.sims.firstOrNull { it.id == registration.subscriptionId }?.label
        ?: registration.previousLinePhone?.let { "Línea anterior: $it" }

internal fun productCurrencies(identity: ProviderIdentity): List<Currency> = when {
    !OperationCatalog.supports(identity) -> emptyList()
    identity.provider == ProviderId.MITRANSFER && identity.profile == ProfileId.CLASSIC -> listOf(Currency.USD)
    identity.provider == ProviderId.MITRANSFER -> listOf(Currency.CUP, Currency.USD)
    else -> CurrencyContract.BANK.supported
}

internal fun hasAccess(state: AppUiState, identity: ProviderIdentity): Boolean =
    state.wallet.registrations.any { it.identity() == identity.accessIdentity() && it.id in state.configuredRegistrationIds } ||
        (state.wallet.registrations.none { it.identity() == identity.accessIdentity() } && identity.bank?.let { it in state.configuredBanks } == true)

internal fun hasProductAccess(state: AppUiState, product: WalletProductUi): Boolean =
    product.registrationId?.let { id -> id in state.configuredRegistrationIds &&
        state.wallet.registrations.singleOrNull { it.id == id }?.identity() == product.identity.accessIdentity()
    } ?: hasAccess(state, product.identity)

internal fun initialCompatibleProduct(state: AppUiState, products: List<WalletProductUi>): WalletProductUi? =
    if (state.selectedProductId != null) products.firstOrNull { it.id == state.selectedProductId }
    else products.firstOrNull { it.identity.bank == state.bank }

internal fun walletProducts(state: AppUiState): List<WalletProductUi> {
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
                balance?.let { Instant.ofEpochMilli(it.at) }, card = card))
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
                balance?.let { Instant.ofEpochMilli(it.at) }, account = account))
        }
        wallet.registrations.forEach { registration ->
            if (none { it.registrationId == registration.id }) {
                val identity = registration.identity() ?: return@forEach
                val balance = wallet.balances.filter { row -> row.cardId == null && row.accountId == null &&
                    (row.registrationId == registration.id || (row.registrationId == null && row.bankCode == registration.bankCode &&
                        row.subscriptionId == registration.subscriptionId && wallet.registrations.count {
                            it.bankCode == row.bankCode && it.subscriptionId == row.subscriptionId
                        } == 1)) }.singleOrNull()
                val currency = balance?.currency?.let { Currency.valueOf(it) }
                add(WalletProductUi("registration:${registration.id}", registration.id, identity,
                    if (identity.provider == ProviderId.MITRANSFER && identity.profile == ProfileId.PERSONAL) ProductKind.WALLET else ProductKind.ACCOUNT,
                    registration.label.ifBlank { "Cuenta predeterminada" }, balance?.account, currency, SourceSelector.Default,
                    balance?.let { Money(it.available.toBigDecimal(), Currency.valueOf(it.currency)) }, balance?.let { Instant.ofEpochMilli(it.at) }))
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
    return products.filter { OperationCatalog.supports(it.identity) }
}

internal fun selectedProduct(state: AppUiState, products: List<WalletProductUi> = walletProducts(state)): WalletProductUi? =
    products.firstOrNull { it.id == state.selectedProductId } ?: products.firstOrNull { it.identity.bank == state.bank } ?: products.firstOrNull()

internal fun displayRecipients(state: AppUiState): List<Recipient> =
    state.wallet.contacts.flatMap { contact ->
        contact.cards.map { Recipient(contact.name, it.number, null) } +
            contact.phones.map { Recipient(contact.name, "", it.number) }
    }.ifEmpty { state.recipients }
