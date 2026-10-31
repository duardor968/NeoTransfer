package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ParameterCodec
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.engine.Submission
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankChoiceUi
import dev.duardo.neotransfer.ui.model.BankKeyUi
import dev.duardo.neotransfer.ui.model.BankRegistrationUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.CardFormUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.model.ClassicSetupUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.RegistrationField
import dev.duardo.neotransfer.ui.model.TELEBANCA_PREFIX
import dev.duardo.neotransfer.ui.onboarding.BankChoiceActions
import dev.duardo.neotransfer.ui.onboarding.BankChoiceScreen
import dev.duardo.neotransfer.ui.onboarding.BankKeyActions
import dev.duardo.neotransfer.ui.onboarding.BankKeyScreen
import dev.duardo.neotransfer.ui.onboarding.BankRegistrationActions
import dev.duardo.neotransfer.ui.onboarding.BankRegistrationScreen
import dev.duardo.neotransfer.ui.onboarding.CardCurrencies
import dev.duardo.neotransfer.ui.onboarding.CardSetupActions
import dev.duardo.neotransfer.ui.onboarding.CardSetupScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicAssociateActions
import dev.duardo.neotransfer.ui.onboarding.ClassicAssociateScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicNumberActions
import dev.duardo.neotransfer.ui.onboarding.ClassicNumberScreen
import dev.duardo.neotransfer.ui.onboarding.ClassicRegisterActions
import dev.duardo.neotransfer.ui.onboarding.ClassicRegisterScreen
import java.text.Normalizer

/**
 * Setting up a bank: its key first (when none is saved), then its cards; without a key, registering the line brings
 * it. The Clásica goes the same way with MiTransfer's PIN and the card's number, or registering the line in MiTransfer
 * and associating the card. On the first start ([setup]) this is the root and finishing opens Home; from Home it goes
 * back to [flowStart], from Settings to where it came from.
 */
fun setUpBank(app: AppModel, bank: Bank, setup: Boolean, flowStart: Destination?) {
    val next = { if (bank == Bank.CLASSIC) ClassicNumberDestination(app, setup, flowStart) else CardSetupDestination(app, bank, setup, flowStart) }
    if (bank.agency() in app.book.value.domain.savedKeys) {
        app.navigator.push(next())
        return
    }
    val register = { key: BankKeyDestination ->
        app.navigator.push(
            if (bank == Bank.CLASSIC) ClassicRegisterDestination(app) { app.navigator.finish(key, ClassicAssociateDestination(app, setup, flowStart)) }
            else BankRegistrationDestination(app, bank) { app.navigator.finish(key, next()) },
        )
    }
    app.navigator.push(BankKeyDestination(app, bank, onRegister = register) { key -> app.navigator.finish(key, next()) })
}

/** The end of a setup: Home on the first start, otherwise back to where it began ([flowStart], or before [last]). */
private fun finishSetup(app: AppModel, setup: Boolean, flowStart: Destination?, last: Destination) {
    if (setup) app.navigator.reset(Main(app)) else app.navigator.remove(flowStart ?: last)
}

class BankChoiceDestination(private val app: AppModel, private val setup: Boolean) : Destination(), BankChoiceActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankChoiceScreen(BankChoiceUi(book.cards.mapTo(mutableSetOf()) { it.bank }), this, canGoBack = !setup)
    }

    override fun choose(bank: Bank) = setUpBank(app, bank, setup, if (setup) null else this)
    override fun back() { app.navigator.remove(this) }
}

/**
 * The bank key, or MiTransfer's PIN, typed on the app's keypad; the fingerprint seals it in the Keystore. While setting
 * up, [onRegister] leads whoever has no key to registering the line.
 */
class BankKeyDestination(
    private val app: AppModel,
    private val bank: Bank,
    rejected: Boolean = false,
    private val onRegister: ((BankKeyDestination) -> Unit)? = null,
    private val onSaved: (BankKeyDestination) -> Unit,
) : Destination(), BankKeyActions {
    private val length = bank.agency().pinLength
    private val digits = CharArray(length)
    private var entered by mutableIntStateOf(0)
    private var error by mutableStateOf(if (rejected) "Clave incorrecta" else null)
    private var checking by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankKeyScreen(BankKeyUi(bank, length, entered, error, checking), this, canRegister = onRegister != null)
    }

    override fun key(key: KeypadKey) {
        if (checking) return
        when (key) {
            KeypadKey.Backspace -> if (entered > 0) entered--
            is KeypadKey.Digit -> if (entered < length) {
                error = null
                digits[entered] = '0' + key.value
                entered++
                if (entered == length) save()
            }
            KeypadKey.Decimal -> Unit
        }
    }

    override fun back() {
        clear()
        app.navigator.remove(this)
    }

    override fun register() {
        if (checking) return
        clear()
        onRegister?.invoke(this)
    }

    private fun save() {
        checking = true
        app.launch {
            try {
                val cipher = app.controller.keyCipher()
                when (val result = app.host?.biometric(bank.saveKeyTitle, cipher = cipher)) {
                    is BiometricResult.Passed -> {
                        app.controller.saveKey(requireNotNull(result.cipher), bank.agency(), digits.copyOf())
                        clear()
                        onSaved(this)
                    }
                    BiometricResult.Unavailable -> fail("Configura una huella en el teléfono para guardar la clave")
                    BiometricResult.LockedOut -> fail("Demasiados intentos con la huella")
                    is BiometricResult.Failed -> fail(result.message)
                    BiometricResult.Cancelled, null -> clear()
                }
            } catch (e: Exception) {
                fail(e.message ?: "No se pudo guardar la clave")
            } finally {
                checking = false
            }
        }
    }

    private fun fail(message: String) {
        clear()
        error = message
    }

    private fun clear() {
        digits.fill('\u0000')
        entered = 0
    }
}

/**
 * The cards held in one bank: the real card fills in as the number is typed or scanned. The first one comes filled in
 * with what the line was registered with ([registered]), so the person only confirms it.
 */
class CardSetupDestination(
    private val app: AppModel,
    private val bank: Bank,
    private val setup: Boolean,
    private val flowStart: Destination?,
) : Destination(), CardSetupActions {
    private class Form(val number: String = "", val holder: String = "", val expiry: String = "", val currency: String = CardCurrencies.first()) {
        fun copy(number: String = this.number, holder: String = this.holder, expiry: String = this.expiry, currency: String = this.currency) =
            Form(number, holder, expiry, currency)
    }

    private var form by mutableStateOf(if (app.book.value.cards.none { it.bank == bank }) registered() ?: Form() else null)

    /**
     * The card of this bank's confirmed registration (`49`), as its form had it ([values]): its number, its holder and,
     * from BANDEC, its expiry. Metropolitano registers a Telebanca card, not one of its cards: only the holder comes.
     */
    private fun registered(): Form? {
        val sent = app.book.value.domain.operations.firstNotNullOfOrNull { status ->
            (status.operation.request as? BankRegistration)?.takeIf { it.bank == bank.core() && status.state is OperationState.Confirmed }
        } ?: return null
        val typed = sent.values()
        val number = typed[RegistrationField.Card]?.takeIf { app.book.value.cards.none { card -> card.number == it } }.orEmpty()
        return Form(
            number,
            listOfNotNull(sent.name, sent.firstSurname, sent.secondSurname).joinToString(" ").take(HOLDER_LENGTH),
            typed[RegistrationField.Expiry].orEmpty(),
            Cards.issuer(number).currency?.name ?: CardCurrencies.first(),
        )
    }

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val cards = book.cards.filter { it.bank == bank }
        CardSetupScreen(CardSetupUi(bank, cards, form?.let { formUi(book, it) }), this)
    }

    private fun formUi(book: Book, form: Form): CardFormUi {
        val numberError = when {
            form.number.length < 16 -> null
            !luhn(form.number) -> "Revisa el número"
            book.cards.any { it.number == form.number } -> "Ya está en la cartera"
            else -> null
        }
        val canSave = form.number.length == 16 && numberError == null && (form.expiry.isEmpty() || form.expiry.length == 4)
        return CardFormUi(form.number, form.holder, form.expiry, form.currency, numberError, canSave)
    }

    override fun number(value: String) { form = form?.copy(number = value.filter(Char::isDigit).take(16)) }
    override fun holder(value: String) { form = form?.copy(holder = value.take(HOLDER_LENGTH)) }
    /** A digit that would make the month impossible is not taken ([possibleExpiry]). */
    override fun expiry(value: String) {
        val digits = value.filter(Char::isDigit).take(4)
        if (possibleExpiry(digits)) form = form?.copy(expiry = digits)
    }
    override fun currency(value: String) { form = form?.copy(currency = value) }

    override fun scan() = app.navigator.push(ScanDestination(app, ScanMode.NUMBERS) { result ->
        if (result is ScanResult.CardNumber) number(result.digits)
    })

    override fun save() {
        val form = form ?: return
        if (!formUi(app.book.value, form).canSave) return
        val expiry = form.expiry.takeIf { it.length == 4 }?.let { it.take(2) + "/" + it.drop(2) }
        app.launch {
            try {
                app.controller.saveCard(WalletCard(OwnCard(form.number, bank.core(), Currency.valueOf(form.currency)), form.holder.trim(), expiry))
                app.homeCard = form.number
                this.form = null
            } catch (e: IllegalArgumentException) {
                app.message(e.message ?: "No se pudo guardar la tarjeta")
            }
        }
    }

    override fun addAnother() { form = Form() }
    override fun remove(cardId: String) { app.launch { app.controller.deleteCard(cardId) } }
    override fun finish() = finishSetup(app, setup, flowStart, this)

    override fun back() {
        if (form != null && app.book.value.cards.any { it.bank == bank }) form = null else app.navigator.remove(this)
    }
}

// ---- Registering the line -------------------------------------------------------------------------------------

/**
 * Registering the line with [bank] (`49`), for whoever has no key: the bank answers by SMS with it, and the fingerprint
 * saves it, by itself the first time it arrives with the screen open. [request]: one sent before, opened with its
 * answer (from its notification), whose key is saved when the person says. [onRegistered] goes on once it is saved.
 */
class BankRegistrationDestination(
    private val app: AppModel,
    private val bank: Bank,
    request: String? = null,
    private val onRegistered: (BankRegistrationDestination) -> Unit,
) : Destination(), BankRegistrationActions, Follows {
    private val fields = bank.registrationFields()
    private val values = mutableStateMapOf<RegistrationField, String>()
    private var requestId by mutableStateOf(request)
    private var sending by mutableStateOf(false)
    private var prompted = request != null

    override fun follows(id: String) = id == requestId

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val request = requestId?.let(book::lineRequest)
        val keyToSave = book.keyToSave(bank)
        val shown = fields.map { field -> values[field].orEmpty().let { FieldUi(field.name, field.label, field.typed(it), field.input, field.error(it)) } }
        BankRegistrationScreen(
            BankRegistrationUi(bank, shown, complete() && !sending, request, keyToSave), this,
            saved = bank.agency() in book.domain.savedKeys, sending = sending,
        )
        val arrived = request?.state is LineStateUi.Confirmed && keyToSave?.inHand == true
        LaunchedEffect(arrived) {
            if (arrived && !prompted) {
                prompted = true
                saveKey()
            }
        }
    }

    private fun complete() = fields.all { field -> values[field].orEmpty().let { field.complete(it) && field.error(it) == null } }

    override fun close() { app.navigator.remove(this) }
    /** A digit that would make the expiry's month impossible is not taken ([possibleExpiry]). */
    override fun field(id: String, value: String) {
        val field = RegistrationField.valueOf(id)
        val clean = field.clean(value)
        if (field != RegistrationField.Expiry || possibleExpiry(clean)) values[field] = clean
    }

    override fun scan() = app.navigator.push(scanCard(app) { card, _ -> card?.let { field(RegistrationField.Card.name, it) } })

    override fun send() {
        if (!complete() || sending) return
        val request = try {
            bank.registration(values)
        } catch (e: IllegalArgumentException) {
            app.message(e.message ?: "Revisa los datos")
            return
        }
        sending = true
        app.launch {
            requestId = sendLine(app, request, "Registrar la línea en ${bank.shortName}", null) ?: requestId
            sending = false
        }
    }

    override fun saveKey() = saveReceived(app, bank)
    override fun typeKey() = typeReceivedKey(app, bank)
    override fun edit() {
        // Opened from a notification, the form starts empty: it comes back with what was sent.
        if (values.isEmpty()) (requestId?.let { app.book.value.operationOf(it)?.operation?.request } as? BankRegistration)?.let { values.putAll(it.values()) }
        requestId = null
        prompted = false
    }

    override fun done() = onRegistered(this)
}

/**
 * Registering the line in MiTransfer (`49`): the identity card and a new PIN typed twice, which lives only while the
 * form is on screen and goes to the controller as typed. [request]: one sent before, opened with its answer, whose PIN
 * is saved when the person says. [onRegistered] goes on to associating the card.
 */
class ClassicRegisterDestination(
    private val app: AppModel,
    request: String? = null,
    private val onRegistered: (ClassicRegisterDestination) -> Unit,
) : Destination(), ClassicRegisterActions, Follows {
    private val length = MiTransfer.pinLength
    private val pin = CharArray(length)
    private val repetition = CharArray(length)
    private var identity by mutableStateOf("")
    private var repeat by mutableStateOf(false)
    private var entered by mutableIntStateOf(0)
    private var pinError by mutableStateOf<String?>(null)
    private var requestId by mutableStateOf(request)
    private var sending by mutableStateOf(false)
    private var prompted = request != null

    override fun follows(id: String) = id == requestId

    @Composable
    override fun Content(app: AppModel, book: Book) {
        DisposableEffect(Unit) { onDispose { clearPin() } }
        val request = requestId?.let(book::lineRequest)
        val keyToSave = book.keyToSave(Bank.CLASSIC)
        ClassicRegisterScreen(
            ClassicSetupUi.Register(FieldUi("identity", RegistrationField.Identity.label, identity, FieldInput.Number), ready() && !sending, request, keyToSave),
            NewKeyUi(Bank.CLASSIC, length, repeat, entered, pinError, sending),
            this,
            saved = MiTransfer in book.domain.savedKeys,
        )
        val arrived = request?.state is LineStateUi.Confirmed && keyToSave?.inHand == true
        LaunchedEffect(arrived) {
            if (arrived && !prompted) {
                prompted = true
                saveKey()
            }
        }
    }

    private fun ready() = identity.length == IDENTITY_LENGTH && repeat && entered == length && pinError == null

    override fun close() { app.navigator.remove(this) }
    override fun identity(value: String) { identity = value.filter(Char::isDigit).take(IDENTITY_LENGTH) }

    override fun pinKey(key: KeypadKey) {
        if (sending) return
        when (key) {
            KeypadKey.Backspace -> when {
                entered > 0 -> entered--
                // Back from an empty repetition into the new PIN, dropping its last digit.
                repeat -> {
                    repeat = false
                    entered = length - 1
                }
            }
            is KeypadKey.Digit -> if (entered < length) {
                pinError = null
                (if (repeat) repetition else pin)[entered] = '0' + key.value
                entered++
                when {
                    entered < length -> Unit
                    !repeat -> {
                        repeat = true
                        entered = 0
                    }
                    !pin.contentEquals(repetition) -> {
                        clearPin()
                        pinError = "Los PIN no coinciden"
                    }
                }
            }
            KeypadKey.Decimal -> Unit
        }
    }

    override fun send() {
        if (!ready() || sending) return
        val request = try {
            Bank.CLASSIC.registration(mapOf(RegistrationField.Identity to identity))
        } catch (e: IllegalArgumentException) {
            app.message(e.message ?: "Revisa el carné de identidad")
            return
        }
        sending = true
        app.launch {
            sendLine(app, request, "Registrar en MiTransfer", null, newKey = pin.copyOf())?.let { id ->
                requestId = id
                clearPin()
            }
            sending = false
        }
    }

    override fun saveKey() = saveReceived(app, Bank.CLASSIC)
    override fun typeKey() = typeReceivedKey(app, Bank.CLASSIC)
    override fun edit() {
        requestId = null
        prompted = false
    }

    override fun done() = onRegistered(this)

    private fun clearPin() {
        pin.fill('\u0000')
        repetition.fill('\u0000')
        repeat = false
        entered = 0
    }
}

/**
 * Associating the Clásica with the line (`111`), after registering it: the card goes into the app once MiTransfer
 * confirms, or when the person adds it after an answer in MiTransfer's own words. [request]: one sent before, opened
 * with its answer and with what it sent in the form.
 */
class ClassicAssociateDestination(
    private val app: AppModel,
    private val setup: Boolean,
    private val flowStart: Destination?,
    request: String? = null,
) : Destination(), ClassicAssociateActions, Follows {
    private var number by mutableStateOf("")
    private var authorization by mutableStateOf("")
    private var terminal by mutableStateOf("")
    private var requestId by mutableStateOf(request)
    private var sending by mutableStateOf(false)
    private var added = false

    init {
        (request?.let(app.book.value::operationOf)?.operation?.request as? ClassicAssociation)?.let { sent ->
            number = sent.card
            authorization = sent.authorization
            terminal = sent.terminal
        }
    }

    override fun follows(id: String) = id == requestId

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val request = requestId?.let(book::lineRequest)
        val card = classicField(book, number, taken = false)
        val canSend = !sending && number.length == 16 && card.error == null &&
            authorization.length == AUTHORIZATION_LENGTH && terminal.length == TERMINAL_LENGTH
        ClassicAssociateScreen(
            ClassicSetupUi.Associate(
                card,
                FieldUi("authorization", "Autorización", authorization, FieldInput.Text),
                FieldUi("terminal", "Terminal", terminal, FieldInput.Text),
                canSend, request,
            ),
            this, sending = sending,
        )
        // Confirmed, the card goes in by itself, even if the screen is closed with ✕.
        if (request?.state is LineStateUi.Confirmed) LaunchedEffect(Unit) { app.launch { addCard() } }
    }

    override fun close() { app.navigator.remove(this) }
    override fun number(value: String) { number = value.filter(Char::isDigit).take(16) }
    override fun authorization(value: String) { authorization = receiptCode(value, AUTHORIZATION_LENGTH) }
    override fun terminal(value: String) { terminal = receiptCode(value, TERMINAL_LENGTH) }
    override fun scan() = app.navigator.push(scanCard(app) { card, _ -> card?.let { number(it) } })

    override fun send() {
        if (sending) return
        val request = try {
            ClassicAssociation(number, authorization, terminal)
        } catch (e: IllegalArgumentException) {
            app.message(e.message ?: "Revisa los datos")
            return
        }
        sending = true
        app.launch {
            if (!app.authorize("Asociar la Clásica", formatCardNumber(number))) {
                sending = false
                return@launch
            }
            // The association goes inside MiTransfer's session: the PIN is asked for first when it is not saved.
            app.query {
                try {
                    app.controller.submit(request).also { if (it is Submission.Queued) requestId = it.id }
                } finally {
                    sending = false
                }
            }
        }
    }

    override fun add() = done()
    override fun edit() { requestId = null }

    override fun done() {
        app.launch { if (addCard()) finishSetup(app, setup, flowStart, this) }
    }

    private suspend fun addCard(): Boolean {
        if (added) return true
        return try {
            app.controller.saveClassic(number)
            app.homeCard = number
            added = true
            true
        } catch (e: IllegalArgumentException) {
            app.message(e.message ?: "No se pudo añadir la Clásica")
            false
        }
    }
}

/** «Tu Clásica», for a line already registered in MiTransfer whose PIN is saved: its number goes into the app. */
class ClassicNumberDestination(
    private val app: AppModel,
    private val setup: Boolean,
    private val flowStart: Destination?,
) : Destination(), ClassicNumberActions {
    private var number by mutableStateOf("")

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val card = classicField(book, number, taken = true)
        ClassicNumberScreen(ClassicSetupUi.Number(card, number.length == 16 && card.error == null), this)
    }

    override fun back() { app.navigator.remove(this) }
    override fun number(value: String) { number = value.filter(Char::isDigit).take(16) }
    override fun scan() = app.navigator.push(scanCard(app) { card, _ -> card?.let { number(it) } })

    override fun save() {
        if (number.length != 16 || classicField(app.book.value, number, taken = true).error != null) return
        app.launch {
            try {
                app.controller.saveClassic(number)
                app.homeCard = number
                finishSetup(app, setup, flowStart, this)
            } catch (e: IllegalArgumentException) {
                app.message(e.message ?: "No se pudo añadir la Clásica")
            }
        }
    }
}

/**
 * Sends a line request after the fingerprint confirms what was reviewed ([title], [subtitle]); its operation id, or
 * null when it was not sent (cancelled, or refused with a message). [newKey] is copied and wiped by the controller.
 */
private suspend fun sendLine(app: AppModel, request: UssdRequest, title: String, subtitle: String?, newKey: CharArray? = null): String? {
    try {
        if (!app.authorize(title, subtitle)) return null
        return (app.controller.submit(request, newKey = newKey) as? Submission.Queued)?.id
    } catch (e: IllegalArgumentException) {
        app.message(e.message ?: "No se pudo enviar")
    } catch (e: IllegalStateException) {
        app.message(e.message ?: "No se pudo enviar")
    } finally {
        newKey?.fill('\u0000')
    }
    return null
}

/** The key a registration brought, saved with the fingerprint; unlocked without the saved keys, the fingerprint unlocks the app first. */
private fun saveReceived(app: AppModel, bank: Bank) {
    if (app.book.value.domain.keysLocked) app.unlock() else app.launch { app.saveReceivedKey(bank) }
}

/** The key a registration brought, typed from its SMS (or the PIN chosen, typed again) when it is not in hand. */
private fun typeReceivedKey(app: AppModel, bank: Bank) {
    if (app.book.value.domain.keysLocked) app.unlock() else app.navigator.push(BankKeyDestination(app, bank) { app.navigator.remove(it) })
}

private const val IDENTITY_LENGTH = 11
private const val HOLDER_LENGTH = 26
private const val AUTHORIZATION_LENGTH = 6
private const val TERMINAL_LENGTH = 8
private const val CLASSIC_PREFIX = "976003"

/** A Clásica's number as typed, with what it tells and what is wrong with it; [taken] also looks in the wallet. */
private fun classicField(book: Book, number: String, taken: Boolean): CardFieldUi {
    val error = when {
        number.isNotEmpty() && !number.startsWith(CLASSIC_PREFIX) && !CLASSIC_PREFIX.startsWith(number) -> "La Clásica empieza por 9760 03"
        number.length == 16 && !luhn(number) -> "Revisa el número"
        taken && book.cards.any { it.number == number } -> "Ya está en la cartera"
        else -> null
    }
    return CardFieldUi(number, "", issuerBank(number), Cards.issuer(number).currency?.name, error)
}

/** Authorization and terminal numbers as a receipt prints them: letters and digits, capitals. */
private fun receiptCode(value: String, length: Int) = value.filter(Char::isLetterOrDigit).uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(length)

private val RegistrationField.input: FieldInput
    get() = when (this) {
        RegistrationField.Card, RegistrationField.Telebanca, RegistrationField.Identity, RegistrationField.Expiry -> FieldInput.Number
        RegistrationField.Name, RegistrationField.FullName, RegistrationField.FirstSurname, RegistrationField.SecondSurname -> FieldInput.Text
    }

/**
 * What a field keeps of what is typed: digits up to its length, names in the protocol's alphabet ([plainName]). The
 * Telebanca's `95` is never typed ([typed]): it is kept in front of the 8 digits that are.
 */
private fun RegistrationField.clean(value: String): String = when (this) {
    RegistrationField.Card -> value.filter(Char::isDigit).take(16)
    RegistrationField.Telebanca -> value.filter(Char::isDigit).take(8).let { if (it.isEmpty()) it else TELEBANCA_PREFIX + it }
    RegistrationField.Identity -> value.filter(Char::isDigit).take(IDENTITY_LENGTH)
    RegistrationField.Expiry -> value.filter(Char::isDigit).take(4)
    RegistrationField.Name, RegistrationField.FullName, RegistrationField.FirstSurname, RegistrationField.SecondSurname -> plainName(value).take(40)
}

/** What the field shows of a kept value: the Telebanca without its `95`, which the field prints itself. */
private fun RegistrationField.typed(value: String): String = if (this == RegistrationField.Telebanca) value.removePrefix(TELEBANCA_PREFIX) else value

/** An expiry as printed (`MMAA`) as far as typed, with a month that can be: its first digit 0 or 1, and the whole of it 01 to 12. */
private fun possibleExpiry(digits: String): Boolean =
    (digits.isEmpty() || digits[0] in '0'..'1') && (digits.length < 2 || digits.take(2).toInt() in 1..12)

private fun RegistrationField.complete(value: String): Boolean = when (this) {
    RegistrationField.Card -> value.length == 16
    RegistrationField.Telebanca -> value.length == 10
    RegistrationField.Identity -> value.length == IDENTITY_LENGTH
    RegistrationField.Expiry -> value.length == 4
    RegistrationField.Name, RegistrationField.FullName, RegistrationField.FirstSurname, RegistrationField.SecondSurname -> value.isNotBlank()
}

private fun RegistrationField.error(value: String): String? = when (this) {
    RegistrationField.Card -> if (value.length == 16 && !luhn(value)) "Revisa el número" else null
    else -> null
}

private val codec = ParameterCodec()

/**
 * A name in the protocol's alphabet, which has no accents and no ñ: an accented letter or an ñ goes as its plain letter,
 * anything else the alphabet lacks is left out, so what the field shows is what the bank receives.
 */
internal fun plainName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD).filter { it == ' ' || codec.accepts(it.toString()) }
