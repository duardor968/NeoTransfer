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
    /** The card whose sheet is open. */
    val card: CardUi? = null,
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
/**
 * [checking]: last operations asked of the bank, answer pending. [pickCard]: which card to ask about.
 */
data class ActivityUi(
    val query: String,
    val filter: ActivityFilter,
    val cards: List<CardUi>,
    val cardFilter: String?,
    val content: ActivityContent,
    val checking: Boolean = false,
    val pickCard: Boolean = false,
)

// ---- Contacts -----------------------------------------------------------------------------------------------

@Immutable
sealed interface ContactsContent {
    /** No search: [people] with a movement or a saved card, newest movement first. */
    data class People(val people: List<ContactUi>) : ContactsContent
    /** A search: matches among the people first, then everyone else ([others]). */
    data class Results(val people: List<ContactUi>, val others: List<ContactUi>) : ContactsContent
    data object NoResults : ContactsContent
}

/** [everyone]: how many contacts there are in all; [addressBook] false while the phone's address book cannot be read. */
@Immutable
data class ContactsUi(val query: String, val content: ContactsContent, val everyone: Int, val addressBook: Boolean)

/** Everyone: the phone's address book and the contacts kept only in the app, by name. */
@Immutable
data class AllContactsUi(val query: String, val contacts: List<ContactUi>)

/** A card number as typed, with what it tells so far ([bank], [currency]) and what is wrong with it. */
@Immutable
data class CardFieldUi(val number: String, val label: String, val bank: Bank? = null, val currency: String? = null, val error: String? = null)

@Immutable
data class EditablePhoneUi(val id: String, val number: String, val error: String? = null)

/** [id] null when creating, with the one [card] it starts with; a saved contact's cards are managed on its page. */
@Immutable
data class ContactEditUi(
    val id: String?,
    val name: String,
    val card: CardFieldUi?,
    val phones: List<EditablePhoneUi>,
    val canSave: Boolean,
)

/** One card of [contactName]: a new one, or [editing] a saved one. */
@Immutable
data class CardEditUi(val contactName: String, val editing: Boolean, val card: CardFieldUi, val canSave: Boolean)

// ---- Services -----------------------------------------------------------------------------------------------

/** Services by the utility they pay, in this order: electricity, water, landline, internet, mobile. */
enum class ServiceKind(val label: String, val referenceLabel: String) {
    Electricity("Electricidad", "Código de cliente"),
    Water("Agua", "Cuenta"),
    Landline("Teléfono fijo", "ID de factura"),
    NautaWifi("Nauta", "Cuenta Nauta"),
    NautaHome("Nauta Hogar", "Cuenta Nauta Hogar"),
    MobileTopUp("Recarga desde el banco", "Móvil"),
    Cubacel("Saldo Cubacel", "Móvil"),
}

@Immutable
data class SavedReferenceUi(val id: String, val kind: ServiceKind, val name: String, val value: String, val lastPaid: Money? = null)

@Immutable
data class ServicesUi(val references: List<SavedReferenceUi>)

enum class FieldInput { Number, Text, Phone, Secret }

/** How a field shows its digits as they are typed, so a value it cannot take is never typed. */
@Immutable
sealed interface FieldShape {
    /** A card number, grouped as printed. */
    data object Card : FieldShape

    /** Digits the value always starts with, shown and never typed: the field's value goes without them. */
    data class Prefix(val digits: String) : FieldShape

    /** A card's expiry as printed, `MM/AA`. */
    data object Expiry : FieldShape
}

@Immutable
data class FieldUi(
    val id: String,
    val label: String,
    val value: String,
    val input: FieldInput,
    val error: String? = null,
    /** How many digits a [FieldInput.Secret] code has: one box each. */
    val length: Int = 0,
    val suffix: String? = null,
    /** The camera can read it (a mobile number). */
    val scannable: Boolean = false,
    val shape: FieldShape? = null,
)

/**
 * Payment of a service. [amount] null when the bank charges the whole bill as issued: there is no prior query, the
 * receipt brings what was paid.
 * [saved] when paying a saved reference, which [edit] names or takes out of Guardados. [choices] splits one service in
 * its variants (water by region, Nauta Hogar recharge or debt).
 */
/** A saved reference being edited: the name it shows in Guardados, and [removing] once its removal is asked. */
@Immutable
data class SavedEditUi(val name: String, val removing: Boolean = false)

/** A mobile, named after its saved reference or the contact that has it. */
@Immutable
data class PhoneTargetUi(val phone: String, val name: String? = null)

/**
 * Who a recharge or a balance transfer goes to. [typed] is a complete mobile typed or scanned that no listed contact
 * has; [contacts] holds one row per mobile.
 */
@Immutable
data class PhonePickUi(
    val title: String,
    val query: String,
    val typed: String?,
    val typedError: String?,
    val recent: List<PhoneTargetUi>,
    val contacts: List<PhoneTargetUi>,
)

@Immutable
data class ServiceFormUi(
    val kind: ServiceKind,
    val title: String,
    val references: List<SavedReferenceUi>,
    val selectedReference: String?,
    val fields: List<FieldUi>,
    val amount: String?,
    val currency: String,
    val cards: List<WalletCardUi> = emptyList(),
    val source: Int = 0,
    val saved: Boolean = false,
    val edit: SavedEditUi? = null,
    val canContinue: Boolean = false,
    val action: String,
    val choices: List<String> = emptyList(),
    val choice: Int = 0,
)

enum class CubacelQuery(val label: String) { Balance("Saldo"), Data("Datos"), Voice("Minutos"), Sms("SMS"), Bonus("Bonos") }

@Immutable
data class CubacelResultUi(val query: CubacelQuery, val value: String?, val detail: String? = null, val loading: Boolean = false)

@Immutable
data class CubacelUi(
    /** The line's number when the phone knows it. */
    val line: String?,
    val balance: Money?,
    val expires: LocalDate?,
    val results: List<CubacelResultUi>,
    val balanceLoading: Boolean = false,
)

// ---- Transfer, pay, receive ---------------------------------------------------------------------------------

/**
 * [typed] is a valid card number typed or scanned that matches no contact. [contacts]: people with a card, and while
 * searching also those without one, whose card is asked for when chosen.
 */
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
    /** [addCard]: the code is fine, a card in its currency is missing. */
    data class Unsupported(val message: String, val addCard: Boolean = false) : PayQrUi
}

/** Why a Clásica cannot pay the special QR (`aux` with `11200301`): it does not say which card pays, so a bank's does. */
const val BANK_CARD_ONLY = "Este tipo de pago solo se hace con una tarjeta de banco"

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

// ---- The line with each bank and the Clásica ----------------------------------------------------------------

/** The last request of a line service and where it stands. [id] is its operation: open it, or retry it when not executed. */
@Immutable
data class LineRequestUi(val id: String, val sentAt: Instant, val state: LineStateUi)

@Immutable
sealed interface LineStateUi {
    /** Waiting its turn, being sent, or acknowledged and waiting for the SMS. */
    data object Sent : LineStateUi
    data class Confirmed(val at: Instant) : LineStateUi
    /** The bank or MiTransfer refused it; [reason] is ready to show. */
    data class Rejected(val reason: String) : LineStateUi
    /** It never reached them; [reason] is ready to show. */
    data class NotExecuted(val reason: String) : LineStateUi
    /** Fifteen minutes without an SMS; a late one still closes it. */
    data object NoResponse : LineStateUi
    /** MiTransfer answered in words the app does not know: [text] as it came, [at] when. */
    data class Answered(val text: String, val at: Instant) : LineStateUi
}

/**
 * A key the bank or MiTransfer ([Bank.CLASSIC]) took that the app has not saved: a registration's or a confirmed
 * change's. Nothing that needs that key goes out until it is saved. [inHand]: the fingerprint alone saves it; otherwise
 * the person types it.
 */
@Immutable
data class KeyToSaveUi(val bank: Bank, val inHand: Boolean)

/** A new key typed twice: a key change (`69`), or the PIN chosen at MiTransfer's registration. [repeat]: the second time. */
@Immutable
data class NewKeyUi(val bank: Bank, val length: Int, val repeat: Boolean, val entered: Int, val error: String? = null, val sending: Boolean = false)

/**
 * One account the bank listed (`58`). [account]: its card, whole or masked, when the bank gave it; [label] the bank's
 * name for it («Ahorro»). [card]: the wallet's card it is. [addable]: its whole number, when it is not in the app yet.
 */
@Immutable
data class LineAccountUi(val account: String?, val label: String?, val balance: Money, val card: CardUi?, val addable: String?)

/**
 * «Tu línea en X», in Ajustes › banco: the last request of each service, null while never asked. [keyChange] `69`, whose
 * new key has [keyLength] digits; [accounts] `58`, with the rows of its answer in [accountList]; [association] `60`;
 * [removal] `68`, apart and in red. Metropolitano ([coordinates]) asks the value of a coordinates-card cell for the
 * association, and the identity card and Telebanca card for the removal.
 */
@Immutable
data class BankLineUi(
    val bank: Bank,
    val keyLength: Int,
    val keyChange: LineRequestUi?,
    val accounts: LineRequestUi?,
    val accountList: List<LineAccountUi>,
    val association: LineRequestUi?,
    val removal: LineRequestUi?,
    val coordinates: Boolean,
    val keyToSave: KeyToSaveUi?,
)

/**
 * Associating a card with the line (`60`). Metropolitano asks the value of [cell] (letter A–J and number 1–10) of its
 * coordinates card, [valueLength] characters, typed for this send only.
 */
@Immutable
data class CardAssociationUi(
    val bank: Bank,
    val card: CardFieldUi,
    val cell: String?,
    val value: String,
    val canSend: Boolean,
    val request: LineRequestUi? = null,
    val valueLength: Int = 2,
)

/**
 * Removing a registration, `68`, destructive: the line's with a bank, or the Clásica's association with MiTransfer.
 * [fields] what it asks: nothing for BPA and BANDEC; the identity card and Telebanca card for Metropolitano; the
 * identity card for the Clásica.
 */
@Immutable
data class RemovalUi(val bank: Bank, val fields: List<FieldUi>, val canSend: Boolean, val request: LineRequestUi? = null)

/** Ajustes › Clásica: the card, «PIN guardado» and removing its association with MiTransfer ([removal]). */
@Immutable
data class ClassicSettingsUi(val card: WalletCardUi, val pinSaved: Boolean, val removal: LineRequestUi?, val keyToSave: KeyToSaveUi?)

/** What a bank's registration (`49`) asks, in its order ([dev.duardo.neotransfer.app.registrationFields]). */
enum class RegistrationField(val label: String, val shape: FieldShape? = null) {
    Card("Número de tarjeta", FieldShape.Card),
    /** Metropolitano's Telebanca card: 10 digits that start with 95; only the other 8 are typed. */
    Telebanca("Tarjeta Telebanca", FieldShape.Prefix(TELEBANCA_PREFIX)),
    Name("Nombre"),
    /** BANDEC: name and surnames in one field. */
    FullName("Nombre y apellidos"),
    FirstSurname("Primer apellido"),
    SecondSurname("Segundo apellido"),
    Identity("Carné de identidad"),
    /** BANDEC: the card's expiry as printed, `MMAA`; the bank takes it as `AAMM` ([dev.duardo.neotransfer.app.registration]). */
    Expiry("Vencimiento", FieldShape.Expiry),
}

/** What every Telebanca card number starts with. */
const val TELEBANCA_PREFIX = "95"

/**
 * Registering the line with [bank] (`49`), from its setup. [fields] in [RegistrationField] order, ids by their name.
 * The bank answers with the key: [keyToSave] once it came; the card PIN of the same SMS is never read.
 */
@Immutable
data class BankRegistrationUi(
    val bank: Bank,
    val fields: List<FieldUi>,
    val canSend: Boolean,
    val request: LineRequestUi? = null,
    val keyToSave: KeyToSaveUi? = null,
)

/**
 * Setting up the Clásica. With the line already in MiTransfer: its PIN ([BankKeyUi] of [Bank.CLASSIC]) and the
 * card's number. Without: register the line (`49`: identity card and a new PIN, [NewKeyUi]) and associate the card
 * (`111`: its number, and the authorization and terminal numbers of a POS receipt).
 */
@Immutable
sealed interface ClassicSetupUi {
    /** The card's number, `976003…`, for a line already registered. */
    data class Number(val number: CardFieldUi, val canSave: Boolean) : ClassicSetupUi

    /** MiTransfer's registration: [request] once sent, [keyToSave] once confirmed (the PIN chosen). */
    data class Register(val identity: FieldUi, val canSend: Boolean, val request: LineRequestUi? = null, val keyToSave: KeyToSaveUi? = null) : ClassicSetupUi

    /** The association (`111`): [authorization] 6 characters, [terminal] 8, both from a POS receipt of the card. */
    data class Associate(
        val number: CardFieldUi,
        val authorization: FieldUi,
        val terminal: FieldUi,
        val canSend: Boolean,
        val request: LineRequestUi? = null,
    ) : ClassicSetupUi
}
