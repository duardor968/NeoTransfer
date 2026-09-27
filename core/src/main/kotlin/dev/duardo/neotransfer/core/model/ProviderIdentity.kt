package dev.duardo.neotransfer.core

enum class ProviderId(val bank: Bank?) {
    BPA(Bank.BPA), BANDEC(Bank.BANDEC), BANMET(Bank.BANMET), BFI(Bank.BFI),
    MITRANSFER(null), CUBACEL(null),
}

enum class ProfileId { PERSONAL, CLASSIC, CLASSIC_BUSINESS, AGENT }

/** Product identity. A registration adds the user's line, SIM and protected credential reference. */
data class ProviderIdentity(val provider: ProviderId, val profile: ProfileId = ProfileId.PERSONAL) {
    val bank: Bank? get() = provider.bank
    val id: String get() = "${provider.name}:${profile.name}"

    companion object {
        fun forBank(bank: Bank): ProviderIdentity = ProviderIdentity(ProviderId.entries.single { it.bank == bank })
    }
}

/** The default account is a network selector, never a stored account numbered 0000. */
sealed interface SourceSelector {
    val wireValue: String

    data object Default : SourceSelector {
        override val wireValue = "0000"
    }

    data class Explicit(val account: String) : SourceSelector {
        init { require(account.isNotEmpty() && account.all { it in '0'..'9' }) { "Cuenta no válida" } }
        override val wireValue: String get() = account
        override fun toString(): String = "SourceSelector.Explicit"
    }

    companion object {
        fun fromLegacy(account: String): SourceSelector = if (account == "0000") Default else Explicit(account)
    }
}

/** Wire currency codes belong to a contract, not to Currency or the user's saved account. */
enum class CurrencyContract(private val currencies: List<Currency>) {
    BANK(listOf(Currency.CUP, Currency.CUC, Currency.USD)),
    BFI(listOf(Currency.USD, Currency.EUR, Currency.GBP, Currency.CAD, Currency.CHF, Currency.MXN,
        Currency.SEK, Currency.DKK, Currency.NOK, Currency.JPY, Currency.CUP));

    fun code(currency: Currency): String {
        val index = currencies.indexOf(currency)
        require(index >= 0) { "Moneda no admitida por esta operación" }
        return (index + 1).toString()
    }

    val supported: List<Currency> get() = currencies
    /** Currencies offered for new operations; [supported] retains historical wire codes. */
    val active: List<Currency> get() = currencies.filterNot { it == Currency.CUC }
}
