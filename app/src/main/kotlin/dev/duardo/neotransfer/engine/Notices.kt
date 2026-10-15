package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.ledger.OperationStatus
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.identify
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Notification wording. The lock screen only ever sees the generic [Notice.publicTitle]. */
internal object Notices {
    /** "Juan te envió 300 CUP": the sender by phone, named only when exactly one contact has it. */
    fun credit(movement: Movement, contacts: List<Contact>): Notice {
        val phone = movement.counterparty.orEmpty()
        val sender = contacts.identify(phone = phone)?.name ?: Phones.normalize(phone) ?: phone
        return Notice(movement.id, NoticeKind.CREDIT, "$sender te envió ${amount(movement.amount)}", null,
            "Transferencia recibida", Routes.movement(movement.id))
    }

    /** Title: what the operation was; text: "Confirmada" or "Rechazada: <motivo>". */
    fun result(status: OperationStatus, contacts: List<Contact>): Notice {
        val (text, public) = when (val state = status.state) {
            is OperationState.Confirmed -> "Confirmada" to "Operación confirmada"
            is OperationState.Rejected -> "Rechazada: ${state.reason}" to "Operación rechazada"
            else -> error("Solo se notifican resultados")
        }
        val id = status.operation.id
        return Notice(id, NoticeKind.RESULT, describe(status.operation.request, contacts), text, public, Routes.operation(id))
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
        is MobileRecharge -> "Recarga de ${amount(request.amount)} a ${contacts.identify(phone = request.phone)?.name ?: request.phone}"
        is BalanceQuery -> "Consulta de saldo"
        is HistoryQuery -> "Últimas operaciones"
        else -> "Operación"
    }

    /** `300 CUP`, `10,000 CUP`, `300.50 CUP` (es-CU). */
    fun amount(money: Money): String {
        val symbols = DecimalFormatSymbols(Locale.forLanguageTag("es-CU"))
        val pattern = if (money.amount.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        return DecimalFormat(pattern, symbols).format(money.amount) + " " + money.currency.name
    }
}
