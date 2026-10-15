package dev.duardo.neotransfer.ui.model

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

// ---- Access -------------------------------------------------------------------------------------------------

@Immutable
sealed interface LockUi {
    /** The system biometric prompt is on top; only brand and lock state show behind it. */
    data object Prompting : LockUi
    /** The prompt was dismissed: one action to show it again, no loop. */
    data object Cancelled : LockUi
    data class LockedOut(val seconds: Int) : LockUi
}

enum class PermissionKind { Sms, Phone, Contacts, Camera, Notifications }

/** First launch: system dialogs ask for everything at once; [denied] lists what is still missing afterwards. */
@Immutable
data class PermissionsUi(val denied: List<PermissionKind>)

@Immutable
data class BankChoiceUi(val configured: Set<Bank>)

@Immutable
data class BankKeyUi(val bank: Bank, val length: Int, val entered: Int, val error: String? = null, val checking: Boolean = false)

@Immutable
data class CardFormUi(
    val number: String,
    val holder: String,
    val expiry: String,
    val currency: String,
    val numberError: String? = null,
    val expiryError: String? = null,
    val canSave: Boolean = false,
)

/** Cards of a bank being set up. [form] is shown while adding one; null shows the list. */
@Immutable
data class CardSetupUi(val bank: Bank, val cards: List<CardUi>, val form: CardFormUi?)

// ---- Home ---------------------------------------------------------------------------------------------------

/** [recent] null while history is still being rebuilt. */
@Immutable
data class HomeUi(
    val cards: List<WalletCardUi>,
    val selected: Int,
    val inProgress: List<OperationUi>,
    val recent: List<OperationUi>?,
    val recharge: List<RechargeTargetUi>? = null,
)

/** Saved destination for the Recharge shortcut. */
@Immutable
data class RechargeTargetUi(val id: String, val kind: ServiceKind, val name: String, val value: String)

// ---- Activity -----------------------------------------------------------------------------------------------

enum class ActivityFilter(val label: String) { All("Todo"), In("Entradas"), Out("Salidas") }

@Immutable
sealed interface ActivityContent {
    data object Loading : ActivityContent
    /** Reading bank messages is not allowed: history cannot be built. */
    data object NoAccess : ActivityContent
    data object Empty : ActivityContent
    data object NoResults : ActivityContent
    data class Items(val operations: List<OperationUi>) : ActivityContent
}

@Immutable
data class ActivityUi(
    val query: String,
    val filter: ActivityFilter,
    val cards: List<CardUi>,
    val cardFilter: String?,
    val content: ActivityContent,
)

// ---- Contacts -----------------------------------------------------------------------------------------------

@Immutable
sealed interface ContactsContent {
    data object Empty : ContactsContent
    data object NoResults : ContactsContent
    data class Items(val contacts: List<ContactUi>) : ContactsContent
}

@Immutable
data class ContactsUi(val query: String, val content: ContactsContent, val importing: Boolean = false)

@Immutable
data class EditableCardUi(val id: String, val number: String, val label: String, val bankName: String? = null, val error: String? = null)

@Immutable
data class EditablePhoneUi(val id: String, val number: String, val error: String? = null)

/** [id] null when creating. */
@Immutable
data class ContactEditUi(
    val id: String?,
    val name: String,
    val cards: List<EditableCardUi>,
    val phones: List<EditablePhoneUi>,
    val canSave: Boolean,
)

// ---- Services -----------------------------------------------------------------------------------------------

enum class Utility(val label: String) {
    Electricity("Electricidad"),
    Water("Agua"),
    Landline("Teléfono fijo"),
    Internet("Internet"),
    Mobile("Móvil"),
}

enum class ServiceKind(val utility: Utility, val label: String, val referenceLabel: String) {
    Electricity(Utility.Electricity, "Electricidad", "Código de cliente"),
    Water(Utility.Water, "Agua", "Cuenta"),
    Landline(Utility.Landline, "Teléfono fijo", "ID de factura"),
    NautaHome(Utility.Internet, "Nauta Hogar", "Usuario"),
    NautaWifi(Utility.Internet, "Nauta Wi‑Fi", "Usuario"),
    MobileTopUp(Utility.Mobile, "Recarga desde el banco", "Móvil"),
    Cubacel(Utility.Mobile, "Saldo Cubacel", "Móvil"),
}

@Immutable
data class SavedReferenceUi(val id: String, val kind: ServiceKind, val name: String, val value: String, val lastPaid: Money? = null)

@Immutable
data class ServicesUi(val references: List<SavedReferenceUi>)

enum class FieldInput { Number, Text, Phone, Secret }

@Immutable
data class FieldUi(
    val id: String,
    val label: String,
    val value: String,
    val input: FieldInput,
    val error: String? = null,
    val suffix: String? = null,
)

/**
 * Payment of a service. [amount] null when the bank charges the bill as issued; [due] is the bill when known.
 * [saveReference] null when paying a saved reference. [choices] splits one service in its variants (water by
 * region, Nauta Hogar recharge or debt).
 */
@Immutable
data class ServiceFormUi(
    val kind: ServiceKind,
    val title: String,
    val references: List<SavedReferenceUi>,
    val selectedReference: String?,
    val fields: List<FieldUi>,
    val amount: String?,
    val currency: String,
    val due: Money? = null,
    val cards: List<WalletCardUi> = emptyList(),
    val source: Int = 0,
    val saveReference: Boolean? = null,
    val canContinue: Boolean = false,
    val action: String,
    val choices: List<String> = emptyList(),
    val choice: Int = 0,
)

enum class CubacelQuery(val label: String) { Balance("Saldo"), Data("Datos"), Voice("Minutos"), Sms("SMS"), Bonus("Bonos") }

@Immutable
data class CubacelResultUi(val query: CubacelQuery, val value: String?, val detail: String? = null, val loading: Boolean = false)

@Immutable
data class CubacelUi(val line: String, val balance: Money?, val expires: LocalDate?, val results: List<CubacelResultUi>)

@Immutable
data class PlanUi(val id: String, val name: String, val detail: String, val price: Money)

@Immutable
data class PlanGroupUi(val name: String, val plans: List<PlanUi>)

@Immutable
data class CubacelPlansUi(val balance: Money?, val groups: List<PlanGroupUi>, val selected: String?)

// ---- Transfer, pay, receive ---------------------------------------------------------------------------------

/** [typed] is a valid card number typed or scanned that matches no contact. */
@Immutable
data class RecipientPickUi(
    val query: String,
    val typed: ForeignCardUi?,
    val typedError: String?,
    val recent: List<RecipientUi>,
    val contacts: List<ContactUi>,
    val chooseCardOf: ContactUi? = null,
)

@Immutable
data class TransferAmountUi(
    val recipient: RecipientUi,
    val input: String,
    val currency: String,
    val sources: List<WalletCardUi>,
    val source: Int,
    val overBalance: Boolean,
    val canContinue: Boolean,
)

/** [saveContact] null when the recipient is already a contact. */
@Immutable
data class TransferReviewUi(
    val recipient: RecipientUi,
    val amount: Money,
    val source: CardUi,
    val saveContact: Boolean?,
    val sending: Boolean = false,
    val duplicate: DuplicateUi? = null,
)

/** Same origin, destination and amount still pending: ask before sending again. */
@Immutable
data class DuplicateUi(val amount: Money, val recipient: String, val sentAt: Instant)

@Immutable
data class TransferResultUi(val operation: OperationUi)

@Immutable
data class PayReviewUi(
    val merchant: String,
    val amountInput: String,
    val amountEditable: Boolean,
    val reference: String,
    val referenceLabel: String,
    /** A description the payer may write; otherwise the code's own id or description, read-only. */
    val referenceEditable: Boolean,
    val currency: String,
    val sources: List<WalletCardUi>,
    val source: Int,
    val canPay: Boolean,
    val paying: Boolean = false,
)

@Immutable
sealed interface PayQrUi {
    data object Scanning : PayQrUi
    data class Review(val review: PayReviewUi) : PayQrUi
    data class Unsupported(val message: String) : PayQrUi
}

@Immutable
data class ReceiveUi(val cards: List<CardUi>, val selected: Int, val payload: String, val amount: Money? = null)

// ---- Settings -----------------------------------------------------------------------------------------------

@Immutable
data class BankSummaryUi(val bank: Bank, val cards: List<CardUi>, val keySaved: Boolean)

@Immutable
data class SettingsUi(
    val banks: List<BankSummaryUi>,
    val appearance: Appearance,
    val notifications: Boolean,
    val lastBackup: Instant?,
    val version: String,
)


@Immutable
data class BankSettingsUi(val bank: Bank, val cards: List<WalletCardUi>, val keySaved: Boolean)

@Immutable
sealed interface BackupUi {
    data class Export(val password: String, val confirm: String, val error: String?, val canExport: Boolean, val busy: Boolean = false) : BackupUi
    data class Import(val fileName: String?, val password: String, val error: String?, val busy: Boolean = false) : BackupUi
}

@Immutable
sealed interface TransfermovilImportUi {
    data object Pick : TransfermovilImportUi
    data class Preview(val fileName: String, val contacts: Int, val cards: Int, val references: Int, val busy: Boolean = false) : TransfermovilImportUi
    data class Done(val contacts: Int, val cards: Int, val references: Int) : TransfermovilImportUi
    data class Failed(val message: String) : TransfermovilImportUi
}
