package dev.duardo.neotransfer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.app.registrationFields
import dev.duardo.neotransfer.preview.PreviewData.bandec
import dev.duardo.neotransfer.preview.PreviewData.bpa
import dev.duardo.neotransfer.preview.PreviewData.cup
import dev.duardo.neotransfer.preview.PreviewData.wallet
import dev.duardo.neotransfer.ui.activity.ActivityScreen
import dev.duardo.neotransfer.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.ui.contacts.AllContactsScreen
import dev.duardo.neotransfer.ui.contacts.CardEditScreen
import dev.duardo.neotransfer.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.ui.home.HomeScreen
import dev.duardo.neotransfer.ui.lock.LockScreen
import dev.duardo.neotransfer.ui.model.ActivityContent
import dev.duardo.neotransfer.ui.model.BANK_CARD_ONLY
import dev.duardo.neotransfer.ui.model.ActivityFilter
import dev.duardo.neotransfer.ui.model.ActivityUi
import dev.duardo.neotransfer.ui.model.AllContactsUi
import dev.duardo.neotransfer.ui.model.Appearance as Theme
import dev.duardo.neotransfer.ui.model.BackupUi
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankChoiceUi
import dev.duardo.neotransfer.ui.model.BankKeyUi
import dev.duardo.neotransfer.ui.model.BankRegistrationUi
import dev.duardo.neotransfer.ui.model.BankSummaryUi
import dev.duardo.neotransfer.ui.model.CardEditUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.CardFormUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.model.ClassicSetupUi
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.model.CubacelQuery
import dev.duardo.neotransfer.ui.model.CubacelResultUi
import dev.duardo.neotransfer.ui.model.CubacelUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.HomeUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LineRequestUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.LockUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.PayQrUi
import dev.duardo.neotransfer.ui.model.PayReviewUi
import dev.duardo.neotransfer.ui.model.PermissionKind
import dev.duardo.neotransfer.ui.model.PermissionsUi
import dev.duardo.neotransfer.ui.model.ReceiveUi
import dev.duardo.neotransfer.ui.model.PhonePickUi
import dev.duardo.neotransfer.ui.model.PhoneTargetUi
import dev.duardo.neotransfer.ui.model.RechargeTargetUi
import dev.duardo.neotransfer.ui.model.RecipientPickUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.RegistrationField
import dev.duardo.neotransfer.ui.model.SavedEditUi
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
import dev.duardo.neotransfer.ui.onboarding.BankRegistrationScreen
import dev.duardo.neotransfer.ui.onboarding.CardSetupScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicAssociateScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicNumberScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicRegisterScreen
import dev.duardo.neotransfer.ui.onboarding.PermissionsScreen
import dev.duardo.neotransfer.ui.pay.PayQrScreen
import dev.duardo.neotransfer.ui.receive.QrCode
import dev.duardo.neotransfer.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.ui.services.CubacelScreen
import dev.duardo.neotransfer.ui.services.PhonePickScreen
import dev.duardo.neotransfer.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.ui.services.ServicesScreen
import dev.duardo.neotransfer.ui.settings.AboutScreen
import dev.duardo.neotransfer.ui.settings.BackupScreen
import dev.duardo.neotransfer.ui.settings.SettingsScreen
import dev.duardo.neotransfer.ui.settings.TransfermovilImportScreen
import dev.duardo.neotransfer.ui.shell.MainShell
import dev.duardo.neotransfer.ui.shell.Tab
import dev.duardo.neotransfer.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.ui.transfer.TransferResultScreen
import dev.duardo.neotransfer.ui.transfer.TransferReviewScreen
import kotlinx.coroutines.delay

/** Every screen and state, by id. Ids are what `--es screen` takes and how captures are named. */
object PreviewCatalog {
    private val screens: Map<String, @Composable () -> Unit> = linkedMapOf<String, @Composable () -> Unit>(
        // Access ----------------------------------------------------------------------------------------------
        "lock" to { LockScreen(LockUi.Prompting, noop()) },
        "lock-cancelled" to { LockScreen(LockUi.Cancelled, noop()) },
        "onboarding-permissions" to { PermissionsScreen(PermissionsUi(emptyList()), noop()) },
        "onboarding-permissions-denied" to { PermissionsScreen(PermissionsUi(listOf(PermissionKind.Sms, PermissionKind.Camera)), noop()) },
        "onboarding-bank" to { BankChoiceScreen(BankChoiceUi(emptySet()), noop()) },
        "onboarding-bank-added" to { BankChoiceScreen(BankChoiceUi(setOf(Bank.BPA)), noop(), canGoBack = true) },
        "onboarding-key" to { BankKeyScreen(BankKeyUi(Bank.BANDEC, length = 5, entered = 3), noop()) },
        "onboarding-key-error" to { BankKeyScreen(BankKeyUi(Bank.BPA, length = 4, entered = 4, error = "Clave incorrecta"), noop()) },
        "onboarding-card-form" to {
            CardSetupScreen(CardSetupUi(Bank.BPA, emptyList(), CardFormUi("92121201234567", "Laura Méndez Soto", "0329", "CUP")), noop())
        },
        "onboarding-card-form-bandec" to {
            CardSetupScreen(
                CardSetupUi(Bank.BANDEC, emptyList(), CardFormUi("9205069987654323", "Laura Méndez Soto", "1130", "CUP", canSave = true)),
                noop(),
            )
        },
        "onboarding-cards" to { CardSetupScreen(CardSetupUi(Bank.BPA, listOf(bpa, PreviewData.bpaUsd), null), noop()) },
        "onboarding-key-register" to { BankKeyScreen(BankKeyUi(Bank.BPA, length = 4, entered = 0), noop(), canRegister = true) },
        "onboarding-register" to {
            BankRegistrationScreen(
                BankRegistrationUi(
                    Bank.BPA,
                    registration(
                        Bank.BPA, RegistrationField.Card to "9212120123456783", RegistrationField.Name to "Laura",
                        RegistrationField.FirstSurname to "Mendez", RegistrationField.SecondSurname to "Soto", RegistrationField.Identity to "85031204567",
                    ),
                    canSend = true,
                ),
                noop(),
            )
        },
        "onboarding-register-bandec" to {
            BankRegistrationScreen(
                BankRegistrationUi(
                    Bank.BANDEC,
                    registration(Bank.BANDEC, RegistrationField.Card to "9205069987654323", RegistrationField.FullName to "Laura Mendez Soto", RegistrationField.Expiry to "1230"),
                    canSend = true,
                ),
                noop(),
            )
        },
        "onboarding-register-waiting" to { BankRegistrationScreen(registered(LineStateUi.Sent), noop()) },
        "onboarding-register-key" to {
            BankRegistrationScreen(registered(LineStateUi.Confirmed(PreviewData.ago(0))).copy(keyToSave = KeyToSaveUi(Bank.BPA, inHand = true)), noop())
        },
        "onboarding-register-done" to { BankRegistrationScreen(registered(LineStateUi.Confirmed(PreviewData.ago(0))), noop(), saved = true) },
        "onboarding-register-rejected" to {
            BankRegistrationScreen(registered(LineStateUi.Rejected("El teléfono ya tiene una cuenta asociada.")), noop())
        },
        "onboarding-classic-pin" to { BankKeyScreen(BankKeyUi(Bank.CLASSIC, length = 4, entered = 3), noop(), canRegister = true) },
        "onboarding-classic-number" to {
            ClassicNumberScreen(ClassicSetupUi.Number(CardFieldUi("97600303123456", "", Bank.CLASSIC, "USD"), canSave = false), noop())
        },
        "onboarding-classic-number-error" to {
            ClassicNumberScreen(ClassicSetupUi.Number(CardFieldUi("9212120", "", Bank.BPA, "CUP", "La Clásica empieza por 9760 03"), canSave = false), noop())
        },
        "onboarding-classic-register" to {
            ClassicRegisterScreen(
                ClassicSetupUi.Register(FieldUi("identity", "Carné de identidad", "58072304511", FieldInput.Number), canSend = false),
                NewKeyUi(Bank.CLASSIC, length = 4, repeat = true, entered = 2),
                noop(),
            )
        },
        "onboarding-classic-register-mismatch" to {
            ClassicRegisterScreen(
                ClassicSetupUi.Register(FieldUi("identity", "Carné de identidad", "58072304511", FieldInput.Number), canSend = false),
                NewKeyUi(Bank.CLASSIC, length = 4, repeat = false, entered = 0, error = "Los PIN no coinciden"),
                noop(),
            )
        },
        "onboarding-classic-register-done" to {
            ClassicRegisterScreen(
                ClassicSetupUi.Register(
                    FieldUi("identity", "Carné de identidad", "58072304511", FieldInput.Number), canSend = false,
                    request = LineRequestUi("reg", PreviewData.ago(2), LineStateUi.Confirmed(PreviewData.ago(0))),
                ),
                NewKeyUi(Bank.CLASSIC, length = 4, repeat = false, entered = 0),
                noop(),
                saved = true,
            )
        },
        "onboarding-classic-associate" to { ClassicAssociateScreen(associate(), noop()) },
        "onboarding-classic-associate-answer" to {
            ClassicAssociateScreen(
                associate(LineRequestUi("asc", PreviewData.ago(3), LineStateUi.Answered("La tarjeta 9760XXXXXXXX5679 fue asociada a su cuenta.", PreviewData.ago(0)))),
                noop(),
            )
        },

        // Home ------------------------------------------------------------------------------------------------
        "home" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, PreviewData.inProgress, PreviewData.recent), noop()) } },
        "home-bandec" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop()) } },
        "home-banmet" to {
            Tabbed(Tab.Home) {
                HomeScreen(HomeUi(listOf(WalletCardUi(PreviewData.banmet, BalanceUi.Known(cup("8020.00"), PreviewData.ago(20)))), 0, emptyList(), PreviewData.recent), noop())
            }
        },
        "home-refreshing" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(wallet[0].copy(refreshing = true), wallet[1]), 0, emptyList(), PreviewData.recent), noop()) }
        },
        "home-new" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(WalletCardUi(bpa, BalanceUi.Unknown)), 0, emptyList(), null), noop()) } },
        "home-classic" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet + PreviewData.classicWallet, 2, emptyList(), PreviewData.classicRecent), noop()) }
        },
        "home-classic-new" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(WalletCardUi(PreviewData.classic, BalanceUi.Unknown)), 0, emptyList(), emptyList()), noop()) }
        },
        "home-card" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent, card = wallet[1].card), noop()) } },
        "home-recharge" to {
            Tabbed(Tab.Home) {
                HomeScreen(
                    HomeUi(
                        wallet, 0, emptyList(), PreviewData.recent,
                        recharge = listOf(
                            RechargeTargetUi("r1", ServiceKind.MobileTopUp, "Mi móvil", formatPhone("53124578")),
                            RechargeTargetUi("r2", ServiceKind.MobileTopUp, "Abuela Carmen", formatPhone("54417720")),
                            RechargeTargetUi("r3", ServiceKind.NautaWifi, "Nauta", "laura.mendez@nauta.com.cu"),
                        ),
                    ),
                    noop(),
                )
            }
        },

        // Activity --------------------------------------------------------------------------------------------
        "activity" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Items(PreviewData.history)), noop()) } },
        "activity-check" to {
            Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Items(PreviewData.history)).copy(pickCard = true), noop()) }
        },
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
        "contacts" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(PreviewData.people), 214, addressBook = true), noop()) } },
        "contacts-empty" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(emptyList()), 214, addressBook = true), noop()) } },
        "contacts-search" to {
            Tabbed(Tab.Contacts) {
                ContactsScreen(ContactsUi("mar", ContactsContent.Results(listOf(PreviewData.maria), listOf(PreviewData.mario)), 214, addressBook = true), noop())
            }
        },
        "contacts-no-access" to {
            Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.People(listOf(PreviewData.juan, PreviewData.carmen)), 2, addressBook = false), noop()) }
        },
        "contacts-all" to { AllContactsScreen(AllContactsUi("", PreviewData.everyone), noop()) },
        "contact" to { ContactDetailScreen(PreviewData.juan, noop()) },
        "contact-agenda" to { ContactDetailScreen(PreviewData.mario, noop()) },
        "contact-agenda-cards" to { ContactDetailScreen(PreviewData.yunior, noop(), movements = PreviewData.withYunior) },
        "contact-delete" to { ContactDetailScreen(PreviewData.juan, noop(), confirmingDelete = true) },
        "contact-edit" to {
            ContactEditScreen(
                ContactEditUi(
                    PreviewData.juan.id, "Juan Carlos Pérez", null,
                    listOf(EditablePhoneUi("p1", "53124578"), EditablePhoneUi("p2", "6312455", error = "Número incompleto")),
                    canSave = false,
                ),
                noop(),
            )
        },
        "contact-new" to {
            ContactEditScreen(
                ContactEditUi(null, "Rosa Díaz", CardFieldUi("9204069954120382", "", Bank.BANDEC, "CUP"), listOf(EditablePhoneUi("p1", "")), canSave = true),
                noop(),
            )
        },
        "card-new" to { CardEditScreen(CardEditUi(PreviewData.mario.name, false, CardFieldUi("9225129", "", Bank.BPA, "USD"), canSave = false), noop()) },
        "card-new-empty" to { CardEditScreen(CardEditUi(PreviewData.mario.name, false, CardFieldUi("", ""), canSave = false), noop()) },
        "card-new-unknown" to { CardEditScreen(CardEditUi(PreviewData.mario.name, false, CardFieldUi("9204", "", currency = "CUP"), canSave = false), noop()) },
        "card-edit" to {
            CardEditScreen(CardEditUi(PreviewData.juan.name, true, CardFieldUi("9204069901447185", "Salario", Bank.BANDEC, "CUP"), canSave = true), noop())
        },
        "card-error" to {
            CardEditScreen(CardEditUi(PreviewData.juan.name, false, CardFieldUi("9205959803124473", "", Bank.BANMET, "CUP", "Algún dígito está mal"), canSave = false), noop())
        },

        // Services --------------------------------------------------------------------------------------------
        "services" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(PreviewData.references), noop()) } },
        "services-new" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(emptyList()), noop()) } },
        "service-electricity" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "Electricidad",
                    PreviewData.references.filter { it.kind == ServiceKind.Electricity }, "ref-casa",
                    listOf(FieldUi("client", "Código de cliente", "01234567891", FieldInput.Number)),
                    amount = null, currency = "CUP",
                    cards = wallet, source = 0, saved = true, canContinue = true, action = "Pagar factura",
                ),
                noop(),
            )
        },
        "service-saved-edit" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "Electricidad",
                    PreviewData.references.filter { it.kind == ServiceKind.Electricity }, "ref-casa",
                    listOf(FieldUi("client", "Código de cliente", "01234567891", FieldInput.Number)),
                    amount = null, currency = "CUP", cards = wallet, source = 0, saved = true, edit = SavedEditUi("Casa"),
                    canContinue = true, action = "Pagar factura",
                ),
                noop(),
            )
        },
        "service-saved-remove" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "Electricidad",
                    PreviewData.references.filter { it.kind == ServiceKind.Electricity }, "ref-casa",
                    listOf(FieldUi("client", "Código de cliente", "01234567891", FieldInput.Number)),
                    amount = null, currency = "CUP", cards = wallet, source = 0, saved = true, edit = SavedEditUi("Casa", removing = true),
                    canContinue = true, action = "Pagar factura",
                ),
                noop(),
            )
        },
        "service-nauta" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.NautaWifi, "Nauta",
                    PreviewData.references.filter { it.kind == ServiceKind.NautaWifi }, null,
                    listOf(FieldUi("user", "Cuenta Nauta", "yaima.garcia", FieldInput.Text, suffix = "@nauta.com.cu")),
                    amount = "300", currency = "CUP", cards = wallet, source = 1,
                    canContinue = true, action = "Recargar 300.00 CUP",
                ),
                noop(),
            )
        },
        "phone-pick" to {
            PhonePickScreen(
                PhonePickUi(
                    "Recargar móvil", "", null, null,
                    recent = listOf(PhoneTargetUi("53124578", "Mi móvil"), PhoneTargetUi("54417720", "Abuela Carmen"), PhoneTargetUi("56208813")),
                    contacts = PreviewData.everyone.flatMap { c -> c.phones.map { PhoneTargetUi(it, c.name) } },
                ),
                noop(),
            )
        },
        "phone-pick-search" to {
            PhonePickScreen(PhonePickUi("Recargar móvil", "mar", null, null, emptyList(), listOf(PhoneTargetUi("52981134", "María Fernanda Ruiz"), PhoneTargetUi("52339054", "Mario Almeida"))), noop())
        },
        "phone-pick-typed" to {
            PhonePickScreen(PhonePickUi("Transferir saldo", "56208813", "56208813", null, emptyList(), emptyList()), noop())
        },
        "phone-pick-wrong" to {
            PhonePickScreen(PhonePickUi("Recargar móvil", "78324410", null, "No es un móvil cubano", emptyList(), emptyList()), noop())
        },
        "phone-pick-empty" to {
            PhonePickScreen(PhonePickUi("Recargar móvil", "", null, null, emptyList(), emptyList()), noop())
        },
        "service-cubacel-transfer" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Cubacel, "Transferir saldo", emptyList(), null,
                    listOf(
                        FieldUi("phone", "Móvil", "54417720", FieldInput.Phone, scannable = true),
                        FieldUi("key", "Clave de transferencia", "1234", FieldInput.Secret, length = 4),
                    ),
                    amount = "50", currency = "CUP", canContinue = true, action = "Transferir 50.00 CUP",
                ),
                noop(),
            )
        },
        "cubacel" to {
            CubacelScreen(
                CubacelUi(
                    formatPhone("53124578"), cup("125.40"), java.time.LocalDate.of(2026, 12, 3),
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

        // Transfer --------------------------------------------------------------------------------------------
        "transfer-recipient" to {
            RecipientScreen(
                RecipientPickUi(
                    "", null, null,
                    recent = listOf(
                        PreviewData.juanRecipient,
                        RecipientUi(PreviewData.maria.cards.first(), PreviewData.maria.name, PreviewData.maria.id),
                        RecipientUi(ForeignCardUi("x", "9212129931880423", Bank.BPA, "CUP")),
                        RecipientUi(PreviewData.daniela.cards.first(), PreviewData.daniela.name, PreviewData.daniela.id),
                    ),
                    contacts = PreviewData.contacts.filter { it.cards.isNotEmpty() },
                ),
                noop(),
            )
        },
        "transfer-recipient-empty" to {
            RecipientScreen(
                RecipientPickUi("", null, null, recent = emptyList(), contacts = emptyList()),
                noop(),
            )
        },
        "transfer-recipient-search" to {
            RecipientScreen(RecipientPickUi("mar", null, null, emptyList(), listOf(PreviewData.maria, PreviewData.mario)), noop())
        },
        "transfer-recipient-typed" to {
            RecipientScreen(
                RecipientPickUi("9204 0699 8801 2340", ForeignCardUi("t", "9204069988012340", Bank.BANDEC, "CUP"), null, emptyList(), emptyList()),
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
            TransferReviewScreen(TransferReviewUi(RecipientUi(ForeignCardUi("t", "9204069988012340", Bank.BANDEC, "CUP")), cup("1250.00"), bpa, saveContact = true), noop())
        },
        "transfer-duplicate" to {
            TransferReviewScreen(
                TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, null, duplicate = DuplicateUi(cup("300.00"), "Juan Carlos Pérez", PreviewData.ago(1))),
                noop(),
            )
        },
        "transfer-classic-recipient" to {
            RecipientScreen(RecipientPickUi("", null, null, listOf(PreviewData.elenaRecipient), listOf(PreviewData.elena)), noop(), classicOnly = true)
        },
        "transfer-classic-recipient-bank" to {
            RecipientScreen(RecipientPickUi("9204069988012340", null, "La Clásica solo transfiere a otra Clásica", emptyList(), emptyList()), noop(), classicOnly = true)
        },
        "transfer-classic-amount" to {
            TransferAmountScreen(TransferAmountUi(PreviewData.elenaRecipient, "25", "USD", listOf(PreviewData.classicWallet), 0, false, true), noop())
        },
        "transfer-classic-review" to {
            TransferReviewScreen(TransferReviewUi(PreviewData.elenaRecipient, PreviewData.usd("25.00"), PreviewData.classic, saveContact = null), noop())
        },
        "transfer-sent" to { TransferResultScreen(TransferResultUi(PreviewData.sentToJuan), noop()) },
        "transfer-confirmed" to {
            TransferResultScreen(TransferResultUi(PreviewData.sentToJuan.copy(status = OperationStatus.Confirmed, transactionId = "TRF8826104190")), noop())
        },
        "transfer-live" to { LiveResult() },
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
        "pay-review-classic" to {
            PayQrScreen(
                PayQrUi.Review(
                    PayReviewUi(
                        "Servicentro La Rotonda", "18.00", false, "SLR-20931", "ID de pago", false, "USD",
                        listOf(WalletCardUi(PreviewData.bpaUsd, BalanceUi.Unknown), PreviewData.classicWallet), 1, canPay = true,
                    ),
                ),
                noop(), { FakeCamera() },
            )
        },
        "pay-unsupported-classic" to {
            PayQrScreen(PayQrUi.Unsupported(BANK_CARD_ONLY, addCard = true), noop(), { FakeCamera() })
        },
        "receive-classic" to { ReceiveScreen(ReceiveUi(listOf(bpa, PreviewData.classic), 1, PreviewData.receivePayload), noop()) },
        "receive" to { ReceiveScreen(ReceiveUi(listOf(bpa, bandec), 0, PreviewData.receivePayload), noop()) },
        "receive-amount" to { ReceiveScreen(ReceiveUi(listOf(bpa, bandec), 1, PreviewData.receivePayload, Money(java.math.BigDecimal("300"), "CUP")), noop()) },

        // Settings --------------------------------------------------------------------------------------------
        "settings" to {
            SettingsScreen(
                SettingsUi(
                    listOf(BankSummaryUi(Bank.BPA, listOf(bpa, PreviewData.bpaUsd), true), BankSummaryUi(Bank.BANDEC, listOf(bandec), true)),
                    Theme.System, notifications = true, lastBackup = PreviewData.at(2, 21, 10), version = appVersion(),
                ),
                noop(),
            )
        },
        "settings-backup-export" to { BackupScreen(BackupUi.Export("contraseña-larga", "contraseña-larg", "Las contraseñas no coinciden", canExport = false), noop()) },
        "settings-backup-import" to { BackupScreen(BackupUi.Import("neotransfer-2026-10-13.ntb", "", null), noop()) },
        "settings-transfermovil" to { TransfermovilImportScreen(TransfermovilImportUi.Pick, noop()) },
        "settings-transfermovil-preview" to { TransfermovilImportScreen(TransfermovilImportUi.Preview("transfermovil_2026-10-01.trm", 14, 19, 6), noop()) },
        "settings-about" to { AboutScreen(appVersion(), noop()) },
    ) + settingsLineScreens

    val ids: List<String> get() = screens.keys.toList()

    @Composable
    fun content(id: String) {
        screens.getValue(id)()
    }
}

private fun activity(content: ActivityContent) = ActivityUi("", ActivityFilter.All, listOf(bpa, bandec), null, content)

/** The registration form of [bank], in its order, with [values] typed. */
private fun registration(bank: Bank, vararg values: Pair<RegistrationField, String>): List<FieldUi> = bank.registrationFields().map { field ->
    val numeric = field == RegistrationField.Card || field == RegistrationField.Telebanca || field == RegistrationField.Identity || field == RegistrationField.Expiry
    FieldUi(field.name, field.label, values.toMap()[field].orEmpty(), if (numeric) FieldInput.Number else FieldInput.Text)
}

/** BPA's registration, sent and now in [state]. */
private fun registered(state: LineStateUi) = BankRegistrationUi(
    Bank.BPA,
    registration(Bank.BPA, RegistrationField.Card to "9212120123456783", RegistrationField.Name to "Laura"),
    canSend = false,
    request = LineRequestUi("reg", PreviewData.ago(1), state),
)

/** Associating the Clásica, filled in from a receipt; [request] once sent. */
private fun associate(request: LineRequestUi? = null) = ClassicSetupUi.Associate(
    CardFieldUi(PreviewData.classic.number, "", Bank.CLASSIC, "USD"),
    FieldUi("authorization", "Autorización", "482913", FieldInput.Text),
    FieldUi("terminal", "Terminal", "40217385", FieldInput.Text),
    canSend = true,
    request = request,
)

@Composable
private fun Tabbed(tab: Tab, content: @Composable () -> Unit) {
    MainShell(tab, {}, content = content)
}

/** Sent, then confirmed 2.5 s later as the bank's SMS would do it: to watch the mark change on a device. */
@Composable
private fun LiveResult() {
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2_500)
        confirmed = true
    }
    val sent = PreviewData.sentToJuan
    TransferResultScreen(TransferResultUi(if (confirmed) sent.copy(status = OperationStatus.Confirmed, transactionId = "TRF8826104190") else sent), noop())
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
