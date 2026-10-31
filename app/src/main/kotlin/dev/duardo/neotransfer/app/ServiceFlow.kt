package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Money as CoreMoney
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceKind as CoreKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.CubacelQuery as CubacelRequest
import dev.duardo.neotransfer.core.protocol.CubacelQueryKind
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaAccount
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.protocol.WaterRegion
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.CubacelQuery
import dev.duardo.neotransfer.ui.model.CubacelResultUi
import dev.duardo.neotransfer.ui.model.CubacelUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.PhonePickUi
import dev.duardo.neotransfer.ui.model.PhoneTargetUi
import dev.duardo.neotransfer.ui.model.SavedEditUi
import dev.duardo.neotransfer.ui.model.ServiceFormUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.services.CubacelActions
import dev.duardo.neotransfer.ui.services.CubacelScreen
import dev.duardo.neotransfer.ui.services.PhonePickActions
import dev.duardo.neotransfer.ui.services.PhonePickScreen
import dev.duardo.neotransfer.ui.services.ServiceFormActions
import dev.duardo.neotransfer.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.ui.transfer.DuplicateSheet
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One form for every service: saved references on top, what the bank asks for, the card and the amount.
 * Transferring Cubacel balance uses the same form with the line's transfer key instead of a card.
 */
class ServiceDestination(
    private val app: AppModel,
    private val kind: ServiceKind,
    reference: ServiceReference? = null,
    retry: CubacelTransfer? = null,
    phone: String? = null,
    /** Where the flow began; the result replaces everything from here up. */
    private val origin: Destination? = null,
) : Destination(), ServiceFormActions {
    private var choice by mutableIntStateOf(0)
    private var selected by mutableStateOf(reference?.let(::referenceId))
    private val values = mutableStateMapOf<String, String>()
    private var amount by mutableStateOf(retry?.amount?.toString().orEmpty())
    private var source by mutableStateOf(app.homeCard)
    private var edit by mutableStateOf<SavedEditUi?>(null)
    private var sending by mutableStateOf(false)
    private var duplicate by mutableStateOf<DuplicateUi?>(null)

    init {
        reference?.let(::fill)
        retry?.let { values[PHONE] = it.phone }
        phone?.let { number ->
            values[PHONE] = number
            if (kind == ServiceKind.MobileTopUp) selected = app.book.value.domain.services
                .firstOrNull { it.kind == CoreKind.MOBILE && sameAccount(it.value, number) }?.let(::referenceId)
        }
    }

    private val fields: List<Pair<String, String>>
        get() = when (kind) {
            ServiceKind.Electricity -> listOf(CLIENT to "Código de cliente")
            ServiceKind.Landline -> listOf(INVOICE to "ID de factura")
            ServiceKind.Water -> listOf(ACCOUNT to "Cuenta")
            ServiceKind.NautaHome, ServiceKind.NautaWifi -> listOf(USER to kind.referenceLabel)
            ServiceKind.MobileTopUp -> listOf(PHONE to "Móvil")
            ServiceKind.Cubacel -> listOf(PHONE to "Móvil", KEY to "Clave de transferencia")
        }

    private val choices: List<String>
        get() = when (kind) {
            ServiceKind.Water -> listOf("La Habana", "Varadero", "Provincias")
            ServiceKind.NautaHome -> listOf("Recargar", "Pagar deuda")
            else -> emptyList()
        }

    /** Whether the person types the amount; electricity and water charge the bill as issued. */
    private val hasAmount get() = kind != ServiceKind.Electricity && kind != ServiceKind.Water

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val references = book.references.filter { it.kind == kind && (kind != ServiceKind.Water || book.reference(it.id)?.kind == waterKind()) }
        val cards = if (kind == ServiceKind.Cubacel) emptyList() else book.wallet.filter { it.card.currency == "CUP" }
        val value = parsedAmount()
        val request = runCatching { request(book) }.getOrNull()
        ServiceFormScreen(
            ServiceFormUi(
                kind = kind,
                title = when (kind) {
                    ServiceKind.MobileTopUp -> "Recargar móvil"
                    ServiceKind.Cubacel -> "Transferir saldo"
                    else -> kind.label
                },
                // A mobile is chosen before the form ([PhonePickDestination]).
                references = if (kind == ServiceKind.Cubacel || kind == ServiceKind.MobileTopUp) emptyList() else references,
                selectedReference = selected,
                fields = fields.map { (id, label) -> fieldUi(id, label) },
                amount = if (hasAmount) amount else null,
                currency = "CUP",
                cards = cards,
                source = cards.indexOfFirst { it.card.id == source }.coerceAtLeast(0),
                saved = selected != null,
                edit = edit,
                canContinue = request != null && !sending,
                action = action(value),
                choices = choices,
                choice = choice,
            ),
            this,
        )
        duplicate?.let { DuplicateSheet(it, ::confirmDuplicate) { duplicate = null } }
    }

    private fun fieldUi(id: String, label: String): FieldUi {
        val value = values[id].orEmpty()
        return when (id) {
            PHONE -> FieldUi(id, label, value, FieldInput.Phone,
                error = Phones.mobileProblem(value), scannable = true)
            KEY -> FieldUi(id, label, value, FieldInput.Secret, length = 4)
            USER -> FieldUi(id, label, value, FieldInput.Text, suffix = if ('@' in value) null else "@nauta.com.cu")
            else -> FieldUi(id, label, value, FieldInput.Number)
        }
    }

    private fun action(value: BigDecimal?): String {
        val money = value?.let { " " + formatMoney(it, "CUP") }.orEmpty()
        return when (kind) {
            ServiceKind.Electricity, ServiceKind.Water -> "Pagar factura"
            ServiceKind.Landline -> "Pagar$money"
            ServiceKind.NautaHome -> if (choice == 1) "Pagar$money" else "Recargar$money"
            ServiceKind.NautaWifi, ServiceKind.MobileTopUp -> "Recargar$money"
            ServiceKind.Cubacel -> "Transferir$money"
        }
    }

    private fun parsedAmount(): BigDecimal? = amount.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }

    private fun waterKind() = when (choice) {
        0 -> CoreKind.WATER_HAVANA
        1 -> CoreKind.WATER_VARADERO
        else -> CoreKind.WATER_OTHER
    }

    /** The request exactly as the form reads now; throws when something is missing or wrong. */
    private fun request(book: Book): UssdRequest {
        val money = { CoreMoney(requireNotNull(parsedAmount()) { "Indica el importe" }) }
        if (kind == ServiceKind.Cubacel) {
            val whole = requireNotNull(parsedAmount()?.takeIf { it.scale() <= 0 || it.stripTrailingZeros().scale() <= 0 }) { "Importe entero" }
            require(values[KEY].orEmpty().length == 4) { "La clave tiene 4 dígitos" }
            return CubacelTransfer(mobile(), whole.toInt())
        }
        val cards = book.wallet.filter { it.card.currency == "CUP" }
        val chosen = cards.firstOrNull { it.card.id == source } ?: cards.firstOrNull() ?: error("Sin tarjeta")
        val card = requireNotNull(book.card(chosen.card.id)).card.card
        val bank = card.bank
        val origin = app.controller.sourceFor(card.number)
        return when (kind) {
            ServiceKind.Electricity -> ElectricityBill(bank, origin, values[CLIENT].orEmpty())
            ServiceKind.Landline -> PhoneBill(bank, origin, values[INVOICE].orEmpty(), money())
            ServiceKind.Water -> WaterBill(bank, origin, listOf(WaterRegion.HAVANA, WaterRegion.VARADERO, WaterRegion.OTHER)[choice], values[ACCOUNT].orEmpty())
            ServiceKind.NautaHome -> NautaPayment(bank, origin, if (choice == 1) NautaService.HOME_DEBT else NautaService.HOME_RECHARGE, nauta(), money())
            ServiceKind.NautaWifi -> NautaPayment(bank, origin, NautaService.RECHARGE, nauta(), money())
            ServiceKind.MobileTopUp -> MobileRecharge(bank, origin, mobile(), money())
            ServiceKind.Cubacel -> error("Cubacel")
        }
    }

    private fun nauta() = NautaAccount.parse(values[USER].orEmpty())
    private fun mobile() = requireNotNull(values[PHONE]?.takeIf { it.length == 8 && (it.startsWith("5") || it.startsWith("6")) }) { "Móvil no válido" }

    private fun fill(reference: ServiceReference) {
        when (reference.kind) {
            CoreKind.ELECTRICITY -> values[CLIENT] = reference.value
            CoreKind.PHONE -> values[INVOICE] = reference.value
            CoreKind.WATER_HAVANA, CoreKind.WATER_VARADERO, CoreKind.WATER_OTHER -> {
                values[ACCOUNT] = reference.value
                choice = listOf(CoreKind.WATER_HAVANA, CoreKind.WATER_VARADERO, CoreKind.WATER_OTHER).indexOf(reference.kind)
            }
            CoreKind.NAUTA, CoreKind.NAUTA_HOME -> values[USER] = reference.value.removeSuffix("@nauta.com.cu")
            CoreKind.MOBILE -> values[PHONE] = Phones.normalize(reference.value) ?: reference.value
        }
    }

    override fun back() { app.navigator.remove(this) }

    override fun choice(index: Int) {
        choice = index
        if (kind == ServiceKind.Water) selected = null
    }

    override fun reference(id: String?) {
        selected = id
        if (id == null) fields.forEach { (field, _) -> values.remove(field) } else app.book.value.reference(id)?.let(::fill)
    }

    override fun field(id: String, value: String) {
        values[id] = when (id) {
            PHONE -> Phones.input(value)
            KEY -> value.filter(Char::isDigit).take(4)
            USER -> value.trim().take(40)
            CLIENT -> value.filter(Char::isDigit).take(13)
            INVOICE -> value.filter(Char::isDigit).take(15)
            else -> value.filter(Char::isDigit).take(20)
        }
        selected = null
    }

    override fun scan(fieldId: String) = app.navigator.push(ScanDestination(app, ScanMode.NUMBERS) { result ->
        if (result is ScanResult.PhoneNumber) field(fieldId, result.digits)
    })

    override fun amount(value: String) { amount = value.filter { it.isDigit() || it == '.' || it == ',' }.take(12) }
    override fun source(index: Int) { source = app.book.value.wallet.filter { it.card.currency == "CUP" }.getOrNull(index)?.card?.id }
    override fun editSaved() {
        val reference = selected?.let(app.book.value::reference) ?: return
        edit = SavedEditUi(reference.label.orEmpty())
    }
    override fun savedName(value: String) { edit = edit?.copy(name = value.take(40)) }
    override fun closeSaved() { edit = null }
    override fun saveSaved() {
        val reference = selected?.let(app.book.value::reference) ?: return
        val name = edit?.name ?: return
        edit = null
        app.launch { runCatching { app.controller.saveService(reference.copy(label = name)) }.onFailure { app.message(it.message ?: "No se pudo guardar") } }
    }
    override fun removeSaved() { edit = edit?.copy(removing = true) }
    override fun cancelRemoveSaved() { edit = null }
    override fun confirmRemoveSaved() {
        val reference = selected?.let(app.book.value::reference) ?: return
        edit = null
        app.launch {
            app.controller.deleteService(reference)
            app.navigator.remove(this@ServiceDestination)
        }
    }

    override fun submit() {
        val request = runCatching { request(app.book.value) }.getOrElse { app.message(it.message ?: "Revisa los datos"); return }
        sending = true
        app.launch {
            val subtitle = when (request) {
                is CubacelTransfer -> "A ${formatPhone(request.phone)}"
                is MobileRecharge -> formatPhone(request.phone)
                is NautaPayment -> request.account.address
                else -> fields.firstOrNull()?.first?.let(values::get)
            }
            if (app.authorize(action(parsedAmount()), subtitle)) send(request, confirmed = false)
            sending = false
        }
    }

    private fun confirmDuplicate() {
        val request = runCatching { request(app.book.value) }.getOrNull() ?: return
        duplicate = null
        sending = true
        app.launch {
            send(request, confirmed = true)
            sending = false
        }
    }

    private suspend fun send(request: UssdRequest, confirmed: Boolean) {
        val key = values[KEY]?.toCharArray()
        try {
            duplicate = app.submit(request, origin ?: this, confirmed, secret = if (request is CubacelTransfer) key else null)
        } finally {
            key?.fill('\u0000')
        }
    }

    private companion object {
        const val CLIENT = "client"
        const val INVOICE = "invoice"
        const val ACCOUNT = "account"
        const val USER = "user"
        const val PHONE = "phone"
        const val KEY = "key"
    }
}

/** Which mobile a recharge ([ServiceKind.MobileTopUp]) or a Cubacel balance transfer goes to; the form follows. */
class PhonePickDestination(private val app: AppModel, private val kind: ServiceKind) : Destination(), PhonePickActions {
    private var query by mutableStateOf("")

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val digits = query.filter(Char::isDigit)
        val numeric = digits.isNotEmpty() && query.all(Char::isDigit)
        val phone = if (numeric) Phones.normalize(digits) else null
        val contacts = book.contactPhones.filter { target ->
            query.isBlank() || target.name?.contains(query.trim(), ignoreCase = true) == true || (numeric && digits.length >= 3 && digits in target.phone)
        }
        val typed = phone?.takeIf { it[0] in "56" && contacts.none { target -> target.phone == it } }
        PhonePickScreen(
            PhonePickUi(
                title = if (kind == ServiceKind.Cubacel) "Transferir saldo" else "Recargar móvil",
                query = query,
                typed = typed,
                typedError = if (phone != null && phone[0] !in "56") "No es un móvil cubano" else null,
                recent = if (query.isBlank()) book.recentPhones else emptyList(),
                contacts = contacts,
            ),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }
    /** A number keeps digits only, +53 included; the field groups them as printed. */
    override fun query(value: String) {
        query = if (value.isNotEmpty() && value.all { it.isDigit() || it in " -+()" }) Phones.input(value) else value
    }

    override fun scan() = app.navigator.push(ScanDestination(app, ScanMode.NUMBERS) { result ->
        if (result is ScanResult.PhoneNumber) Phones.normalize(result.digits)?.let { choose(it) } ?: run { query = result.digits }
    })

    override fun pickTyped() { Phones.normalize(query)?.let(::choose) }
    override fun pick(target: PhoneTargetUi) = choose(target.phone)

    private fun choose(phone: String) = app.navigator.push(ServiceDestination(app, kind, phone = phone, origin = this))
}

/**
 * The line's own balance. Each query is a free USSD answered on screen; packages open the operator's menu;
 * transferring balance is a form.
 */
class CubacelDestination(private val app: AppModel) : Destination(), CubacelActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        val latest = book.domain.operations.mapNotNull { status -> (status.operation.request as? CubacelRequest)?.let { it.kind to status.state } }
            .distinctBy { it.first }.toMap()
        val balance = (latest[CubacelQueryKind.BALANCE] as? OperationState.Answered)?.text
        CubacelScreen(
            CubacelUi(
                line = null,
                balance = balance?.let(::cup),
                expires = balance?.let(::date),
                balanceLoading = latest[CubacelQueryKind.BALANCE]?.inFlight == true,
                results = CubacelQuery.entries.filter { it != CubacelQuery.Balance }.map { query ->
                    val state = latest[query.request()]
                    val answer = (state as? OperationState.Answered)?.text
                    when {
                        state?.inFlight == true -> CubacelResultUi(query, null, loading = true)
                        answer == null -> CubacelResultUi(query, null)
                        else -> figure(query, answer)?.let { CubacelResultUi(query, it, date(answer)?.let { d -> "Vence el ${d.dayOfMonth} de ${month(d)}" }) }
                            ?: CubacelResultUi(query, null, answer.trim())
                    }
                },
            ),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }
    override fun query(kind: CubacelQuery) {
        app.launch { runCatching { app.controller.submit(CubacelRequest(kind.request())) }.onFailure { app.message(it.message ?: "No se pudo consultar") } }
    }
    override fun plans() { app.controller.cubacelPackages()?.let(app::message) }
    override fun transferBalance() = app.navigator.push(PhonePickDestination(app, ServiceKind.Cubacel))

    private fun CubacelQuery.request() = when (this) {
        CubacelQuery.Balance -> CubacelQueryKind.BALANCE
        CubacelQuery.Data -> CubacelQueryKind.DATA
        CubacelQuery.Voice -> CubacelQueryKind.VOICE
        CubacelQuery.Sms -> CubacelQueryKind.SMS
        CubacelQuery.Bonus -> CubacelQueryKind.BONUS
    }

    /** The figure an answer is about ("4.2 GB", "38 min", "120 SMS"); null when it says something else. */
    private fun figure(query: CubacelQuery, text: String): String? = when (query) {
        CubacelQuery.Data -> Regex("([0-9]+(?:[.,][0-9]+)? ?(?:GB|MB|KB))", RegexOption.IGNORE_CASE).find(text)?.value
        CubacelQuery.Voice -> Regex("([0-9]+:[0-9]{2}(?::[0-9]{2})?|[0-9]+ ?min)", RegexOption.IGNORE_CASE).find(text)?.value
        CubacelQuery.Sms -> Regex("([0-9]+) ?SMS", RegexOption.IGNORE_CASE).find(text)?.value
        else -> null
    }

    private fun cup(text: String): Money? = Regex("([0-9]+(?:\\.[0-9]{1,2})?) ?CUP", RegexOption.IGNORE_CASE).find(text)
        ?.groupValues?.get(1)?.toBigDecimalOrNull()?.let { Money(it, "CUP") }

    private fun date(text: String): LocalDate? = Regex("([0-9]{1,2})[-/]([0-9]{1,2})[-/]([0-9]{2,4})").find(text)?.destructured?.let { (d, m, y) ->
        runCatching { LocalDate.of(y.toInt().let { if (it < 100) 2000 + it else it }, m.toInt(), d.toInt()) }.getOrNull()
    }

    private fun month(date: LocalDate) = date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es"))
}
