package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankChoiceUi
import dev.duardo.neotransfer.ui.model.BankKeyUi
import dev.duardo.neotransfer.ui.model.CardFormUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.onboarding.BankChoiceActions
import dev.duardo.neotransfer.ui.onboarding.BankChoiceScreen
import dev.duardo.neotransfer.ui.onboarding.BankKeyActions
import dev.duardo.neotransfer.ui.onboarding.BankKeyScreen
import dev.duardo.neotransfer.ui.onboarding.CardCurrencies
import dev.duardo.neotransfer.ui.onboarding.CardSetupActions
import dev.duardo.neotransfer.ui.onboarding.CardSetupScreen

/**
 * Setting up a bank: its key first (when none is saved), then its cards. On the first start ([setup]) this is the
 * root and finishing opens Home; from Home or Settings it goes back where it came from.
 */
fun setUpBank(app: AppModel, bank: Bank, setup: Boolean, flowStart: Destination?) {
    if (bank.core() in app.book.value.domain.savedKeys) {
        app.navigator.push(CardSetupDestination(app, bank, setup, flowStart))
    } else {
        app.navigator.push(BankKeyDestination(app, bank) { key -> app.navigator.finish(key, CardSetupDestination(app, bank, setup, flowStart ?: key)) })
    }
}

class BankChoiceDestination(private val app: AppModel, private val setup: Boolean) : Destination(), BankChoiceActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankChoiceScreen(BankChoiceUi(book.cards.mapTo(mutableSetOf()) { it.bank }), this, canGoBack = !setup)
    }

    override fun choose(bank: Bank) = setUpBank(app, bank, setup, if (setup) null else this)
    override fun back() { app.navigator.remove(this) }
}

/** The bank key typed on the app's keypad; the fingerprint seals it in the Keystore. */
class BankKeyDestination(
    private val app: AppModel,
    private val bank: Bank,
    rejected: Boolean = false,
    private val onSaved: (BankKeyDestination) -> Unit,
) : Destination(), BankKeyActions {
    private val length = bank.core().pinLength
    private val digits = CharArray(length)
    private var entered by mutableIntStateOf(0)
    private var error by mutableStateOf(if (rejected) "Clave incorrecta" else null)
    private var checking by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankKeyScreen(BankKeyUi(bank, length, entered, error, checking), this)
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

    private fun save() {
        checking = true
        app.launch {
            try {
                val cipher = app.controller.keyCipher()
                when (val result = app.host?.biometric("Guardar clave de ${bank.shortName}", cipher = cipher)) {
                    is BiometricResult.Passed -> {
                        app.controller.saveKey(requireNotNull(result.cipher), bank.core(), digits.copyOf())
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

/** The cards held in one bank: the real card fills in as the number is typed or scanned. */
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

    private var form by mutableStateOf(if (app.book.value.cards.none { it.bank == bank }) Form() else null)

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
        val expiryError = if (form.expiry.length >= 2 && form.expiry.take(2).toInt() !in 1..12) "Mes no válido" else null
        val canSave = form.number.length == 16 && numberError == null && expiryError == null && (form.expiry.isEmpty() || form.expiry.length == 4)
        return CardFormUi(form.number, form.holder, form.expiry, form.currency, numberError, expiryError, canSave)
    }

    override fun number(value: String) { form = form?.copy(number = value.filter(Char::isDigit).take(16)) }
    override fun holder(value: String) { form = form?.copy(holder = value.take(26)) }
    override fun expiry(value: String) { form = form?.copy(expiry = value.filter(Char::isDigit).take(4)) }
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

    override fun finish() {
        if (setup) app.navigator.reset(Main(app)) else app.navigator.remove(flowStart ?: this)
    }

    override fun back() {
        if (form != null && app.book.value.cards.any { it.bank == bank }) form = null else app.navigator.remove(this)
    }
}
