package dev.duardo.neotransfer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.preview.PreviewData.bandec
import dev.duardo.neotransfer.preview.PreviewData.bpa
import dev.duardo.neotransfer.preview.PreviewData.cup
import dev.duardo.neotransfer.preview.PreviewData.wallet
import dev.duardo.neotransfer.ui.activity.ActivityScreen
import dev.duardo.neotransfer.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.ui.home.HomeScreen
import dev.duardo.neotransfer.ui.lock.LockScreen
import dev.duardo.neotransfer.ui.model.ActivityContent
import dev.duardo.neotransfer.ui.model.ActivityFilter
import dev.duardo.neotransfer.ui.model.ActivityUi
import dev.duardo.neotransfer.ui.model.BackupUi
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankChoiceUi
import dev.duardo.neotransfer.ui.model.BankKeyUi
import dev.duardo.neotransfer.ui.model.BankSettingsUi
import dev.duardo.neotransfer.ui.model.BankSummaryUi
import dev.duardo.neotransfer.ui.model.CardFormUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.model.CubacelPlansUi
import dev.duardo.neotransfer.ui.model.CubacelQuery
import dev.duardo.neotransfer.ui.model.CubacelResultUi
import dev.duardo.neotransfer.ui.model.CubacelUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.EditableCardUi
import dev.duardo.neotransfer.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.HomeUi
import dev.duardo.neotransfer.ui.model.LockUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.PayQrUi
import dev.duardo.neotransfer.ui.model.PayReviewUi
import dev.duardo.neotransfer.ui.model.PermissionKind
import dev.duardo.neotransfer.ui.model.PermissionsUi
import dev.duardo.neotransfer.ui.model.PlanGroupUi
import dev.duardo.neotransfer.ui.model.PlanUi
import dev.duardo.neotransfer.ui.model.RechargeTargetUi
import dev.duardo.neotransfer.ui.model.ReceiveUi
import dev.duardo.neotransfer.ui.model.RecipientPickUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.ServiceFormUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.ServicesUi
import dev.duardo.neotransfer.ui.model.SettingsUi
import dev.duardo.neotransfer.ui.model.TransferAmountUi
import dev.duardo.neotransfer.ui.model.TransferResultUi
import dev.duardo.neotransfer.ui.model.TransferReviewUi
import dev.duardo.neotransfer.ui.model.TransfermovilImportUi
import dev.duardo.neotransfer.ui.model.WalletCardUi
import dev.duardo.neotransfer.ui.onboarding.BankChoiceScreen
import dev.duardo.neotransfer.ui.onboarding.BankKeyScreen
import dev.duardo.neotransfer.ui.onboarding.CardSetupScreen
import dev.duardo.neotransfer.ui.onboarding.PermissionsScreen
import dev.duardo.neotransfer.ui.pay.PayQrScreen
import dev.duardo.neotransfer.ui.receive.QrCode
import dev.duardo.neotransfer.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.ui.services.CubacelPlansScreen
import dev.duardo.neotransfer.ui.services.CubacelScreen
import dev.duardo.neotransfer.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.ui.services.ServicesScreen
import dev.duardo.neotransfer.ui.settings.AboutScreen
import dev.duardo.neotransfer.ui.settings.BackupScreen
import dev.duardo.neotransfer.ui.settings.BankSettingsScreen
import dev.duardo.neotransfer.ui.settings.SettingsScreen
import dev.duardo.neotransfer.ui.settings.TransfermovilImportScreen
import dev.duardo.neotransfer.ui.shell.MainShell
import dev.duardo.neotransfer.ui.shell.Tab
import dev.duardo.neotransfer.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.ui.transfer.TransferResultScreen
import dev.duardo.neotransfer.ui.transfer.TransferReviewScreen
import dev.duardo.neotransfer.ui.model.Appearance as Theme

/** Every screen and state, by id. Ids are what `--es screen` takes and how captures are named. */
object PreviewCatalog {
    private val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        // Access ----------------------------------------------------------------------------------------------
        "lock" to { LockScreen(LockUi.Prompting, noop()) },
        "lock-cancelled" to { LockScreen(LockUi.Cancelled, noop()) },
        "onboarding-permissions" to { PermissionsScreen(PermissionsUi(emptyList()), noop()) },
        "onboarding-permissions-denied" to { PermissionsScreen(PermissionsUi(listOf(PermissionKind.Sms, PermissionKind.Camera)), noop()) },
        "onboarding-bank" to { BankChoiceScreen(BankChoiceUi(emptySet()), noop()) },
        "onboarding-key" to { BankKeyScreen(BankKeyUi(Bank.BANDEC, length = 5, entered = 3), noop()) },
        "onboarding-key-error" to { BankKeyScreen(BankKeyUi(Bank.BPA, length = 4, entered = 4, error = "Clave incorrecta"), noop()) },
        "onboarding-card-form" to {
            CardSetupScreen(CardSetupUi(Bank.BPA, emptyList(), CardFormUi("92120601234567", "Laura Méndez Soto", "0329", "CUP")), noop())
        },
        "onboarding-card-form-bandec" to {
            CardSetupScreen(
                CardSetupUi(Bank.BANDEC, emptyList(), CardFormUi("9205129987654325", "Laura Méndez Soto", "1130", "CUP", canSave = true)),
                noop(),
            )
        },
        "onboarding-cards" to { CardSetupScreen(CardSetupUi(Bank.BPA, listOf(bpa, PreviewData.bpaUsd), null), noop()) },

        // Home ------------------------------------------------------------------------------------------------
        "home" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, PreviewData.inProgress, PreviewData.recent), noop()) } },
        "home-bandec" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop()) } },
        "home-refreshing" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(wallet[0].copy(refreshing = true), wallet[1]), 0, emptyList(), PreviewData.recent), noop()) }
        },
        "home-new" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(WalletCardUi(bpa, BalanceUi.Unknown)), 0, emptyList(), null), noop()) } },
        "home-recharge" to {
            Tabbed(Tab.Home) {
                HomeScreen(
                    HomeUi(
                        wallet, 0, emptyList(), PreviewData.recent,
                        recharge = listOf(
                            RechargeTargetUi("r1", ServiceKind.MobileTopUp, "Mi móvil", "5312 4578"),
                            RechargeTargetUi("r2", ServiceKind.MobileTopUp, "Abuela Carmen", "5441 7720"),
                            RechargeTargetUi("r3", ServiceKind.NautaWifi, "Nauta", "laura.mendez@nauta.com.cu"),
                        ),
                    ),
                    noop(),
                )
            }
        },

        // Activity --------------------------------------------------------------------------------------------
        "activity" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Items(PreviewData.history)), noop()) } },
        "activity-search" to {
            Tabbed(Tab.Activity) {
                ActivityScreen(
                    ActivityUi("juan", ActivityFilter.Out, listOf(bpa, bandec), bpa.id, ActivityContent.Items(listOf(PreviewData.sentToJuan))),
                    noop(),
                )
            }
        },
        "activity-loading" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Loading), noop()) } },
        "activity-empty" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Empty), noop()) } },
        "activity-no-access" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.NoAccess), noop()) } },
        "operation-confirmed" to { OperationDetailScreen(PreviewData.confirmedToMaria, noop()) },
        "operation-sent" to { OperationDetailScreen(PreviewData.sentToJuan, noop()) },
        "operation-rejected" to { OperationDetailScreen(PreviewData.rejectedToYunior, noop()) },
        "operation-no-response" to { OperationDetailScreen(PreviewData.noResponseNauta, noop()) },
        "operation-not-executed" to { OperationDetailScreen(PreviewData.notExecuted, noop()) },
        "operation-incoming" to { OperationDetailScreen(PreviewData.incomingUnknown, noop(), canSaveContact = true) },
        "operation-qr" to { OperationDetailScreen(PreviewData.qrCafe, noop()) },

        // Contacts --------------------------------------------------------------------------------------------
        "contacts" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.Items(PreviewData.contacts)), noop()) } },
        "contacts-empty" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.Empty), noop()) } },
        "contact" to { ContactDetailScreen(PreviewData.juan, noop()) },
        "contact-delete" to { ContactDetailScreen(PreviewData.juan, noop(), confirmingDelete = true) },
        "contact-edit" to {
            ContactEditScreen(
                ContactEditUi(
                    PreviewData.juan.id, "Juan Carlos Pérez",
                    listOf(
                        EditableCardUi("jc-1", "9205990144718265", "Salario", "BANDEC"),
                        EditableCardUi("jc-3", "92123331880421", "", error = null),
                    ),
                    listOf(EditablePhoneUi("p1", "53124578"), EditablePhoneUi("p2", "7830221", error = "Número incompleto")),
                    canSave = false,
                ),
                noop(),
            )
        },

        // Services --------------------------------------------------------------------------------------------
        "services" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(PreviewData.references), noop()) } },
        "service-electricity" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "Electricidad",
                    PreviewData.references.filter { it.kind == ServiceKind.Electricity }, "ref-casa",
                    listOf(FieldUi("client", "Código de cliente", "01234567891", FieldInput.Number)),
                    amount = null, currency = "CUP", due = cup("412.35"),
                    cards = wallet, source = 0, canContinue = true, action = "Pagar 412.35 CUP",
                ),
                noop(),
            )
        },
        "service-nauta" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.NautaWifi, "Nauta Wi‑Fi",
                    PreviewData.references.filter { it.kind == ServiceKind.NautaWifi }, null,
                    listOf(FieldUi("user", "Usuario", "yaima.garcia", FieldInput.Text, suffix = "@nauta.com.cu")),
                    amount = "300", currency = "CUP", cards = wallet, source = 1, saveReference = true,
                    canContinue = true, action = "Recargar 300.00 CUP",
                ),
                noop(),
            )
        },
        "service-cubacel-transfer" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Cubacel, "Transferir saldo", emptyList(), null,
                    listOf(
                        FieldUi("to", "Móvil", "54417720", FieldInput.Phone),
                        FieldUi("key", "Clave de transferencia", "1234", FieldInput.Secret),
                    ),
                    amount = "50", currency = "CUP", canContinue = true, action = "Transferir 50.00 CUP",
                ),
                noop(),
            )
        },
        "cubacel" to {
            CubacelScreen(
                CubacelUi(
                    "5312 4578", cup("125.40"), java.time.LocalDate.of(2026, 12, 3),
                    listOf(
                        CubacelResultUi(CubacelQuery.Data, "4.2 GB", "Vence el 9 de noviembre"),
                        CubacelResultUi(CubacelQuery.Voice, "38 min"),
                        CubacelResultUi(CubacelQuery.Sms, null, loading = true),
                        CubacelResultUi(CubacelQuery.Bonus, null),
                    ),
                ),
                noop(),
            )
        },
        "cubacel-plans" to {
            CubacelPlansScreen(
                CubacelPlansUi(
                    cup("125.40"),
                    listOf(
                        PlanGroupUi(
                            "Datos",
                            listOf(
                                PlanUi("d1", "1 GB", "Todas las redes · 30 días", cup("100.00")),
                                PlanUi("d2", "4 GB", "Todas las redes · 30 días", cup("240.00")),
                                PlanUi("d3", "10 GB", "Todas las redes · 30 días", cup("480.00")),
                            ),
                        ),
                        PlanGroupUi(
                            "Combinados",
                            listOf(
                                PlanUi("c1", "3 GB + 100 min + 50 SMS", "30 días", cup("360.00")),
                                PlanUi("c2", "6 GB + 200 min + 100 SMS", "30 días", cup("600.00")),
                            ),
                        ),
                    ),
                    selected = "d2",
                ),
                noop(),
            )
        },

        // Transfer --------------------------------------------------------------------------------------------
        "transfer-recipient" to {
            RecipientScreen(
                RecipientPickUi(
                    "", null, null,
                    recent = listOf(
                        PreviewData.juanRecipient,
                        RecipientUi(PreviewData.maria.cards.first(), PreviewData.maria.name, PreviewData.maria.id),
                        RecipientUi(ForeignCardUi("x", "9212333188042154", "BPA")),
                        RecipientUi(PreviewData.daniela.cards.first(), PreviewData.daniela.name, PreviewData.daniela.id),
                    ),
                    contacts = PreviewData.contacts.filter { it.cards.isNotEmpty() },
                ),
                noop(),
            )
        },
        "transfer-recipient-typed" to {
            RecipientScreen(
                RecipientPickUi("9205 8800 1234 5673", ForeignCardUi("t", "9205880012345673", "BANDEC"), null, emptyList(), emptyList()),
                noop(),
            )
        },
        "transfer-recipient-cards" to {
            RecipientScreen(
                RecipientPickUi("", null, null, emptyList(), PreviewData.contacts.filter { it.cards.isNotEmpty() }, chooseCardOf = PreviewData.juan),
                noop(),
            )
        },
        "transfer-amount" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "300", "CUP", wallet, 0, false, true), noop()) },
        "transfer-amount-over" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "4500.5", "CUP", wallet, 1, true, true), noop()) },
        "transfer-review" to { TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, saveContact = null), noop()) },
        "transfer-review-new" to {
            TransferReviewScreen(TransferReviewUi(RecipientUi(ForeignCardUi("t", "9205880012345673", "BANDEC")), cup("1250.00"), bpa, saveContact = true), noop())
        },
        "transfer-duplicate" to {
            TransferReviewScreen(
                TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, null, duplicate = DuplicateUi(cup("300.00"), "Juan Carlos Pérez", PreviewData.ago(1))),
                noop(),
            )
        },
        "transfer-sent" to { TransferResultScreen(TransferResultUi(PreviewData.sentToJuan), noop()) },
        "transfer-confirmed" to {
            TransferResultScreen(TransferResultUi(PreviewData.sentToJuan.copy(status = OperationStatus.Confirmed, transactionId = "TRF8826104190")), noop())
        },
        "transfer-rejected" to {
            TransferResultScreen(TransferResultUi(PreviewData.sentToJuan.copy(status = OperationStatus.Rejected, reason = "Saldo insuficiente")), noop())
        },

        // Pay and receive -------------------------------------------------------------------------------------
        "pay-scanning" to { PayQrScreen(PayQrUi.Scanning, noop(), { FakeCamera() }) },
        "pay-review" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Cafetería La Esquina", "480.00", false, "CLE-00417", "ID de pago", false, "CUP", wallet, 0, canPay = true)),
                noop(), { FakeCamera() },
            )
        },
        "pay-review-open-amount" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Mercado La Plaza", "", true, "", "Descripción", true, "CUP", wallet, 1, canPay = false)),
                noop(), { FakeCamera() },
            )
        },
        "pay-unsupported" to { PayQrScreen(PayQrUi.Unsupported("Este código no es de pago"), noop(), { FakeCamera() }) },
        "receive" to { ReceiveScreen(ReceiveUi(listOf(bpa, bandec), 0, PreviewData.receivePayload), noop()) },
        "receive-amount" to { ReceiveScreen(ReceiveUi(listOf(bpa, bandec), 1, PreviewData.receivePayload, Money(java.math.BigDecimal("300"), "CUP")), noop()) },

        // Settings --------------------------------------------------------------------------------------------
        "settings" to {
            SettingsScreen(
                SettingsUi(
                    listOf(BankSummaryUi(Bank.BPA, listOf(bpa, PreviewData.bpaUsd), true), BankSummaryUi(Bank.BANDEC, listOf(bandec), true)),
                    Theme.System, notifications = true, lastBackup = PreviewData.at(2, 21, 10), version = "1.0.0",
                ),
                noop(),
            )
        },
        "settings-bank" to { BankSettingsScreen(BankSettingsUi(Bank.BPA, listOf(wallet[0], WalletCardUi(PreviewData.bpaUsd, BalanceUi.Unknown)), true), noop()) },
        "settings-bank-remove" to { BankSettingsScreen(BankSettingsUi(Bank.BANDEC, listOf(wallet[1]), true), noop(), confirmingRemoval = true) },
        "settings-backup-export" to { BackupScreen(BackupUi.Export("contraseña-larga", "contraseña-larg", "Las contraseñas no coinciden", canExport = false), noop()) },
        "settings-backup-import" to { BackupScreen(BackupUi.Import("neotransfer-2026-10-13.ntb", "", null), noop()) },
        "settings-transfermovil" to { TransfermovilImportScreen(TransfermovilImportUi.Pick, noop()) },
        "settings-transfermovil-preview" to { TransfermovilImportScreen(TransfermovilImportUi.Preview("transfermovil_2026-10-01.trm", 14, 19, 6), noop()) },
        "settings-about" to { AboutScreen("1.0.0", noop()) },
    )

    val ids: List<String> get() = screens.keys.toList()

    @Composable
    fun content(id: String) {
        screens.getValue(id)()
    }
}

private fun activity(content: ActivityContent) = ActivityUi("", ActivityFilter.All, listOf(bpa, bandec), null, content)

@Composable
private fun Tabbed(tab: Tab, content: @Composable () -> Unit) {
    MainShell(tab, {}, content = content)
}

/** Stand-in for the camera feed: a dim surface with a merchant QR on it. */
@Composable
private fun FakeCamera() {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3530), Color(0xFF1C1A18), Color(0xFF0E0D0C))))) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(236.dp)
                .graphicsLayer { rotationZ = -3f }
                .background(Color(0xFFF2F0EC), RoundedCornerShape(12.dp))
                .padding(22.dp),
        ) { QrCode(PreviewData.merchantPayload, Modifier.fillMaxSize(), Color(0xFF1A1A1A)) }
    }
}
