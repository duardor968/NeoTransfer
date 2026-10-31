package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.NotExecutedReason
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.ledger.OperationStatus
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.identify
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.designsystem.format.DecimalSeparator
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.ui.model.Bank as BankName
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Notification wording. The lock screen only ever sees the generic [Notice.publicTitle]. */
internal object Notices {
    /** "Juan te envió 300 CUP": the sender by phone, named only when exactly one contact has it. */
    fun credit(movement: Movement, contacts: List<Contact>): Notice {
        val phone = movement.counterparty.orEmpty()
        val sender = contacts.identify(phone = phone)?.name ?: formatPhone(Phones.normalize(phone) ?: phone)
        return Notice(movement.id, NoticeKind.CREDIT, "$sender te envió ${amount(movement.amount)}", null,
            "Transferencia recibida", Routes.movement(movement.id))
    }

    /**
     * Title: what the operation was; text: "Confirmada", "Rechazada: <motivo>", "No se ejecutó: <motivo>", or the answer
     * in the bank's or MiTransfer's own words, without a new key it may carry.
     */
    fun result(status: OperationStatus, contacts: List<Contact>): Notice {
        val (text, public) = when (val state = status.state) {
            is OperationState.Confirmed -> "Confirmada" to "Operación confirmada"
            is OperationState.Rejected -> "Rechazada: ${state.reason}" to "Operación rechazada"
            is OperationState.NotExecuted -> "No se ejecutó: ${notExecuted(state)}" to "Operación no ejecutada"
            is OperationState.Answered -> answer(status.operation.request, state.text) to "Operación respondida"
            else -> error("Solo se notifican resultados")
        }
        val id = status.operation.id
        return Notice(id, NoticeKind.RESULT, describe(status.operation.request, contacts), text, public, Routes.operation(id))
    }

    /**
     * An answer as the app shows it ([unsigned]). A key change's comes masked already ([AppController.maskKeys]); any
     * request that sets a new key gets its digits of that length masked here too.
     */
    private fun answer(request: UssdRequest, text: String): String {
        val length = request.agency?.pinLength?.takeIf { request.newKeyLength != null }
        return unsigned(if (length == null) text else AppController.maskKeys(text, setOf(length)))
    }

    /**
     * The words of MiTransfer or a bank as the screens and notifications show them: without the sender's signature
     * («Monedero Mi Transfer:», formerly «Bolsa»; «Banco Popular de Ahorro:» and the like, as the core reads them).
     */
    fun unsigned(text: String): String = Signature.replace(text, "").trim()

    private val Signature =
        Regex("""^\s*(?:(?:Monedero|Bolsa) Mi ?Transfer|Banco:? (?:Popular de Ahorros?|BPA|Bandec|Metropolitano))\b\s*:?\s*""", RegexOption.IGNORE_CASE)

    /** Why an operation never reached the bank, in plain words. */
    fun notExecuted(state: OperationState.NotExecuted): String = when (state.reason) {
        NotExecutedReason.BUSY -> "el banco estaba ocupado"
        NotExecutedReason.SEND_FAILED -> state.detail?.replaceFirstChar(Char::lowercase) ?: "no se pudo enviar"
        // In the bank's words: "Formato Incorrecto, Repita su solicitud" says more than any summary.
        NotExecutedReason.REFUSED -> state.detail?.let(::unsigned)?.takeIf(String::isNotEmpty)?.replaceFirstChar(Char::lowercase)
            ?: "la red no aceptó la solicitud"
        NotExecutedReason.AUTHENTICATION -> "no se pudo autenticar con el banco"
        NotExecutedReason.WRONG_PIN -> "clave incorrecta"
    }

    fun describe(request: UssdRequest, contacts: List<Contact>): String = when (request) {
        is Transfer -> "Transferencia de ${amount(request.amount)} a ${contacts.identify(card = request.destination)?.name ?: request.destination}"
        is QrPay -> "Pago de ${amount(request.amount)}" + (request.payment.title?.let { " a $it" } ?: "")
        is ElectricityBill -> "Pago de electricidad"
        is PhoneBill -> "Pago de teléfono fijo" + (request.amount?.let { " de ${amount(it)}" } ?: "")
        is WaterBill -> "Pago de agua"
        is NautaPayment -> when (request.service) {
            NautaService.RECHARGE -> "Recarga Nauta"
            NautaService.HOME_RECHARGE -> "Recarga Nauta Hogar"
            NautaService.HOME_DEBT -> "Pago de Nauta Hogar"
        } + " de ${amount(request.amount)}"
        is MobileRecharge -> "Recarga de ${amount(request.amount)} a ${contacts.identify(phone = request.phone)?.name ?: formatPhone(request.phone)}"
        is CubacelTransfer -> "Saldo de ${request.amount} CUP a ${contacts.identify(phone = request.phone)?.name ?: formatPhone(request.phone)}"
        is BalanceQuery -> "Consulta de saldo en ${name(request.bank)}"
        is HistoryQuery -> "Últimas operaciones en ${name(request.bank)}"
        is ClassicTransfer -> "Transferencia de ${amount(request.amount)} a ${contacts.identify(card = request.destination)?.name ?: request.destination}"
        is ClassicQrPay -> "Pago de ${amount(request.amount)}" + (request.payment.title?.let { " a $it" } ?: "")
        is ClassicBalanceQuery -> "Saldo de la Clásica"
        is ClassicHistoryQuery -> "Últimas operaciones de la Clásica"
        is ClassicAssociation -> "Asociación de la Clásica"
        is ClassicRemoval -> "Quitar la asociación de la Clásica"
        is MiTransferRegistration -> "Registro en MiTransfer"
        is BankRegistration -> "Registro en ${name(request.bank)}"
        is AccountsQuery -> "Cuentas en ${name(request.bank)}"
        is CardAssociation -> "Asociación de tarjeta en ${name(request.bank)}"
        is PinChange -> "Cambio de clave en ${name(request.bank)}"
        is RegistrationRemoval -> "Eliminar el registro en ${name(request.bank)}"
        else -> "Operación"
    }

    /** A bank as the screens name it: BPA, BANDEC, Metropolitano. */
    private fun name(bank: Bank) = BankName.valueOf(bank.name).shortName

    /** `300 CUP`, `10 000 CUP`, `300.50 CUP`: the app's grouping, a space. */
    fun amount(money: Money): String {
        val symbols = DecimalFormatSymbols(Locale.ROOT).apply { decimalSeparator = DecimalSeparator; groupingSeparator = NoBreak }
        val pattern = if (money.amount.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        return DecimalFormat(pattern, symbols).format(money.amount) + " " + money.currency.name
    }
}
