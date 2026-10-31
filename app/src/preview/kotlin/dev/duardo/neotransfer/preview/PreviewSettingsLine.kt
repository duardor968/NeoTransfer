package dev.duardo.neotransfer.preview

import androidx.compose.runtime.Composable
import dev.duardo.neotransfer.preview.PreviewData.ago
import dev.duardo.neotransfer.preview.PreviewData.cup
import dev.duardo.neotransfer.ui.model.Appearance
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankLineUi
import dev.duardo.neotransfer.ui.model.BankSettingsUi
import dev.duardo.neotransfer.ui.model.BankSummaryUi
import dev.duardo.neotransfer.ui.model.CardAssociationUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ClassicSettingsUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldShape
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LineAccountUi
import dev.duardo.neotransfer.ui.model.LineRequestUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.RemovalUi
import dev.duardo.neotransfer.ui.model.SettingsUi
import dev.duardo.neotransfer.ui.model.TELEBANCA_PREFIX
import dev.duardo.neotransfer.ui.model.WalletCardUi
import dev.duardo.neotransfer.ui.settings.BankSettingsScreen
import dev.duardo.neotransfer.ui.settings.CardAssociationScreen
import dev.duardo.neotransfer.ui.settings.ClassicSettingsScreen
import dev.duardo.neotransfer.ui.settings.LineAccountsScreen
import dev.duardo.neotransfer.ui.settings.LineRequestScreen
import dev.duardo.neotransfer.ui.settings.LineService
import dev.duardo.neotransfer.ui.settings.NewKeyScreen
import dev.duardo.neotransfer.ui.settings.SettingsScreen
import java.math.BigDecimal

/** Ajustes › banco and Ajustes › Clásica: the line services and the Clásica's settings, by id. */
internal val settingsLineScreens: Map<String, @Composable () -> Unit> = linkedMapOf(
    // Ajustes › banco ----------------------------------------------------------------------------------------------
    "settings-bank" to { BankSettingsScreen(LineData.bpaSettings, noop(), line = LineData.line(Bank.BPA)) },
    "settings-bank-remove-card" to {
        BankSettingsScreen(LineData.bpaSettings, noop(), line = LineData.line(Bank.BPA), removingCard = LineData.bpaSettings.cards.first().card.id)
    },
    "settings-bank-remove" to {
        BankSettingsScreen(BankSettingsUi(Bank.BANDEC, listOf(PreviewData.wallet[1]), true), noop(), confirmingRemoval = true, line = LineData.line(Bank.BANDEC))
    },
    "settings-line-bank-states" to {
        BankSettingsScreen(
            LineData.bpaSettings, noop(),
            line = LineData.line(
                Bank.BPA,
                keyChange = LineData.request(LineStateUi.Sent),
                accounts = LineData.request(LineStateUi.Sent),
                association = LineData.request(LineStateUi.Rejected("Esta tarjeta ya está asociada a un celular.")),
                removal = LineData.request(LineStateUi.NotExecuted("El banco estaba ocupado")),
            ),
        )
    },
    "settings-line-bank-key-to-save" to {
        BankSettingsScreen(
            LineData.bpaSettings, noop(),
            line = LineData.line(
                Bank.BPA,
                keyChange = LineData.request(LineStateUi.Answered(LineData.bankText, ago(3))),
                association = LineData.request(LineStateUi.NoResponse, minutesAgo = 40),
                keyToSave = KeyToSaveUi(Bank.BPA, inHand = true),
            ),
        )
    },
    "settings-line-unregister" to {
        BankSettingsScreen(LineData.bpaSettings, noop(), line = LineData.line(Bank.BPA), unregistering = RemovalUi(Bank.BPA, emptyList(), canSend = true))
    },
    "settings-line-unregister-banmet" to {
        BankSettingsScreen(
            BankSettingsUi(Bank.BANMET, listOf(WalletCardUi(PreviewData.banmet, BalanceUi.Known(cup("8020.00"), ago(20)))), true), noop(),
            line = LineData.line(Bank.BANMET),
            unregistering = RemovalUi(
                Bank.BANMET,
                listOf(
                    FieldUi("identity", "Carné de identidad", "85031204567", FieldInput.Number),
                    FieldUi("telebanca", "Tarjeta Telebanca", "312", FieldInput.Number, shape = FieldShape.Prefix(TELEBANCA_PREFIX)),
                ),
                canSend = false,
            ),
        )
    },

    // Cambiar clave (69) -------------------------------------------------------------------------------------------
    "settings-line-key" to { NewKeyScreen(NewKeyUi(Bank.BPA, length = 4, repeat = false, entered = 2), noop()) },
    "settings-line-key-repeat" to { NewKeyScreen(NewKeyUi(Bank.BANDEC, length = 5, repeat = true, entered = 3), noop()) },
    "settings-line-key-mismatch" to { NewKeyScreen(NewKeyUi(Bank.BPA, length = 4, repeat = false, entered = 0, error = "Las claves no coinciden"), noop()) },
    "settings-line-key-sent" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.Sent) },
    "settings-line-key-confirmed" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.Confirmed(ago(1)), KeyToSaveUi(Bank.BPA, inHand = true)) },
    "settings-line-key-saved" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.Confirmed(ago(1))) },
    "settings-line-key-answered" to {
        LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.Answered(LineData.bankText, ago(1)), KeyToSaveUi(Bank.BPA, inHand = true))
    },
    "settings-line-key-rejected" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.Rejected("La clave nueva no es válida.")) },
    "settings-line-key-not-executed" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.NotExecuted("El banco estaba ocupado")) },
    "settings-line-key-no-response" to { LineData.Status(Bank.BPA, LineService.KeyChange, LineStateUi.NoResponse) },

    // Cuentas en el banco (58) -------------------------------------------------------------------------------------
    "settings-line-accounts" to {
        LineAccountsScreen(LineData.line(Bank.BPA, accounts = LineData.request(LineStateUi.Confirmed(ago(178))), accountList = LineData.accounts), noop())
    },
    "settings-line-accounts-sending" to { LineAccountsScreen(LineData.line(Bank.BPA, accounts = LineData.request(LineStateUi.Sent)), noop()) },
    "settings-line-accounts-not-executed" to {
        LineAccountsScreen(
            LineData.line(Bank.BPA, accounts = LineData.request(LineStateUi.NotExecuted("El banco estaba ocupado")), accountList = LineData.accounts),
            noop(),
        )
    },

    // Asociar una tarjeta (60) --------------------------------------------------------------------------
    "settings-line-association" to {
        CardAssociationScreen(CardAssociationUi(Bank.BPA, CardFieldUi("9212129900000037", "", Bank.BPA, "CUP"), null, "", canSend = true), noop())
    },
    "settings-line-association-banmet" to {
        CardAssociationScreen(CardAssociationUi(Bank.BANMET, CardFieldUi("9205950012345674", "", Bank.BANMET, "CUP"), "B7", "4", canSend = false), noop())
    },
    "settings-line-association-confirmed" to {
        LineData.Status(Bank.BPA, LineService.Association, LineStateUi.Confirmed(ago(1)), card = "9212129900000037")
    },
    "settings-line-association-rejected" to {
        LineData.Status(Bank.BPA, LineService.Association, LineStateUi.Rejected("esta tarjeta ya está asociada a un celular"))
    },

    // Eliminar el registro (68) ------------------------------------------------------------------------------------
    "settings-line-removal-sent" to { LineData.Status(Bank.BANMET, LineService.Removal, LineStateUi.Sent) },
    "settings-line-removal-confirmed" to { LineData.Status(Bank.BPA, LineService.Removal, LineStateUi.Confirmed(ago(1))) },
    "settings-line-removal-rejected" to { LineData.Status(Bank.BPA, LineService.Removal, LineStateUi.Rejected("La línea no está registrada en el banco.")) },

    // Ajustes › Clásica --------------------------------------------------------------------------------------------
    "settings-classic" to { ClassicSettingsScreen(LineData.classicSettings, noop()) },
    "settings-classic-states" to {
        ClassicSettingsScreen(
            LineData.classicSettings.copy(removal = LineData.request(LineStateUi.Sent), keyToSave = KeyToSaveUi(Bank.CLASSIC, inHand = true)),
            noop(),
        )
    },
    "settings-classic-unassociate" to {
        ClassicSettingsScreen(
            LineData.classicSettings, noop(),
            unassociating = RemovalUi(Bank.CLASSIC, listOf(FieldUi("identity", "Carné de identidad", "58072304511", FieldInput.Number)), canSend = true),
        )
    },
    "settings-classic-remove" to { ClassicSettingsScreen(LineData.classicSettings, noop(), confirmingRemoval = true, removesPin = true) },
    "settings-classic-answered" to {
        LineData.Status(Bank.CLASSIC, LineService.Removal, LineStateUi.Answered("La tarjeta 9760XXXXXXXX7812 fue desasociada de su cuenta.", ago(1)))
    },

    // Últimas operaciones de la Clásica (48) -----------------------------------------------------------------------
    "classic-history-sent" to { LineData.Status(Bank.CLASSIC, LineService.History, LineStateUi.Sent) },
    "classic-history" to { LineData.Status(Bank.CLASSIC, LineService.History, LineStateUi.Answered(PreviewData.classicHistoryText, ago(1))) },
    "classic-history-rejected" to { LineData.Status(Bank.CLASSIC, LineService.History, LineStateUi.Rejected("Error en la operacion solicitada.")) },
    "classic-history-not-executed" to { LineData.Status(Bank.CLASSIC, LineService.History, LineStateUi.NotExecuted("el servicio estaba ocupado")) },

    "settings-classic-home" to {
        SettingsScreen(
            SettingsUi(
                listOf(
                    BankSummaryUi(Bank.BPA, listOf(PreviewData.bpa, PreviewData.bpaUsd), true),
                    BankSummaryUi(Bank.BANDEC, listOf(PreviewData.bandec), true),
                    BankSummaryUi(Bank.BANMET, emptyList(), false),
                    BankSummaryUi(Bank.CLASSIC, listOf(LineData.classic), true),
                ),
                Appearance.System, notifications = true, lastBackup = PreviewData.at(2, 21, 10), version = appVersion(),
            ),
            noop(),
        )
    },
)

/** Invented line answers: card numbers start with 92 or 976003, identity numbers belong to nobody. */
private object LineData {
    val bpaSettings = BankSettingsUi(Bank.BPA, listOf(PreviewData.wallet[0], WalletCardUi(PreviewData.bpaUsd, BalanceUi.Unknown)), true)

    val classic = CardUi("classic-1", Bank.CLASSIC, "9760031234567812", "USD")
    val classicSettings = ClassicSettingsUi(
        WalletCardUi(classic, BalanceUi.Text("Su saldo es 86.40 USD", ago(30))),
        pinSaved = true, removal = null, keyToSave = null,
    )

    /** A bank's answer to a key change in words the app does not know; digits of the key's length come masked, as the app masks them. */
    const val bankText = "Banco Popular de Ahorro: Su clave de acceso a Transfermovil fue cambiada. Clave: ••••"

    val accounts = listOf(
        LineAccountUi(PreviewData.bpa.number, null, cup("12480.50"), PreviewData.bpa, null),
        LineAccountUi("9225129900435519", null, Money(BigDecimal("120.00"), "USD"), null, "9225129900435519"),
        LineAccountUi("921212XXXXXX4410", "Ahorro", cup("300.00"), null, null),
    )

    fun request(state: LineStateUi, minutesAgo: Long = 2) = LineRequestUi("line", ago(minutesAgo), state)

    fun line(
        bank: Bank,
        keyChange: LineRequestUi? = null,
        accounts: LineRequestUi? = null,
        accountList: List<LineAccountUi> = emptyList(),
        association: LineRequestUi? = null,
        removal: LineRequestUi? = null,
        keyToSave: KeyToSaveUi? = null,
    ) = BankLineUi(bank, if (bank == Bank.BANDEC) 5 else 4, keyChange, accounts, accountList, association, removal, bank == Bank.BANMET, keyToSave)

    /** Where a request stands, closed toward where it was opened: the bank's or the Clásica's settings, or Activity. */
    @Composable
    fun Status(bank: Bank, service: LineService, state: LineStateUi, keyToSave: KeyToSaveUi? = null, card: String? = null) {
        val back = when {
            service == LineService.History -> "Volver a Actividad"
            bank == Bank.CLASSIC -> "Volver a la Clásica"
            else -> "Volver a ${bank.shortName}"
        }
        LineRequestScreen(bank, service, request(state), keyToSave, back, noop(), card = card)
    }
}
