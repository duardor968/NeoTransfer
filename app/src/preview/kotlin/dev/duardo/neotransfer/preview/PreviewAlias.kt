package dev.duardo.neotransfer.preview

import androidx.compose.runtime.Composable
import dev.duardo.neotransfer.preview.PreviewData.aliasWallet
import dev.duardo.neotransfer.preview.PreviewData.bandec
import dev.duardo.neotransfer.preview.PreviewData.cup
import dev.duardo.neotransfer.preview.PreviewData.nomina
import dev.duardo.neotransfer.ui.activity.ActivityScreen
import dev.duardo.neotransfer.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.ui.home.HomeScreen
import dev.duardo.neotransfer.ui.model.ActivityContent
import dev.duardo.neotransfer.ui.model.ActivityFilter
import dev.duardo.neotransfer.ui.model.ActivityUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.CardFormUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.model.HomeUi
import dev.duardo.neotransfer.ui.model.TransferAmountUi
import dev.duardo.neotransfer.ui.model.TransferReviewUi
import dev.duardo.neotransfer.ui.onboarding.CardSetupScreen
import dev.duardo.neotransfer.ui.shell.Tab
import dev.duardo.neotransfer.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.ui.transfer.TransferReviewScreen

/** Cards with an alias, wherever a card is picked or recognized, by id. */
internal val aliasScreens: Map<String, @Composable () -> Unit> = linkedMapOf(
    "home-alias" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(aliasWallet, 0, emptyList(), PreviewData.recent), noop()) } },
    "home-alias-bandec" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(aliasWallet, 2, emptyList(), PreviewData.recent), noop()) } },
    "home-card-alias" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(aliasWallet, 0, emptyList(), PreviewData.recent, card = nomina), noop()) } },
    "home-card-alias-none" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(aliasWallet, 2, emptyList(), PreviewData.recent, card = bandec), noop()) } },
    "home-card-alias-edit" to {
        Tabbed(Tab.Home) { HomeScreen(HomeUi(aliasWallet, 0, emptyList(), PreviewData.recent, card = nomina, alias = "Nómina"), noop()) }
    },
    "onboarding-card-form-alias" to {
        CardSetupScreen(CardSetupUi(Bank.BPA, emptyList(), CardFormUi("9212120123456783", "Laura Méndez Soto", "0329", "CUP", canSave = true, alias = "Nómina")), noop())
    },
    "onboarding-cards-alias" to { CardSetupScreen(CardSetupUi(Bank.BPA, listOf(nomina, PreviewData.ahorro), null), noop()) },
    "transfer-amount-alias" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "300", "CUP", aliasWallet, 0, false, true), noop()) },
    "transfer-review-alias" to { TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), nomina, saveContact = null), noop()) },
    "activity-alias" to {
        Tabbed(Tab.Activity) {
            ActivityScreen(ActivityUi("", ActivityFilter.All, aliasWallet.map { it.card }, nomina.id, ActivityContent.Items(PreviewData.history)), noop())
        }
    },
    "activity-alias-long" to {
        val long = nomina.copy(alias = "Gastos de la casa ok")
        Tabbed(Tab.Activity) {
            ActivityScreen(ActivityUi("", ActivityFilter.All, listOf(long, bandec), long.id, ActivityContent.Items(PreviewData.history)), noop())
        }
    },
    "activity-check-alias" to {
        Tabbed(Tab.Activity) {
            ActivityScreen(
                ActivityUi("", ActivityFilter.All, aliasWallet.map { it.card }, null, ActivityContent.Items(PreviewData.history), pickCard = true), noop(),
            )
        }
    },
    "operation-confirmed-alias" to { OperationDetailScreen(PreviewData.confirmedToMaria.copy(card = nomina), noop()) },
)
