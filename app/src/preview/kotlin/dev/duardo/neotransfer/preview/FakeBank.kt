package dev.duardo.neotransfer.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.CubacelQuery
import dev.duardo.neotransfer.core.protocol.CubacelQueryKind
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.InboxSms
import dev.duardo.neotransfer.engine.NeoTransferApplication
import dev.duardo.neotransfer.engine.PhoneLine
import dev.duardo.neotransfer.engine.SmsInbox
import dev.duardo.neotransfer.engine.UssdModem
import dev.duardo.neotransfer.engine.UssdReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64

/** The preview build runs on a simulated bank: the real SIM and SMS inbox are never touched. */
class PreviewApplication : NeoTransferApplication() {
    lateinit var line: FakeBank
        private set

    override fun phoneLine(): PhoneLine = FakeBank(this).also { line = it }
}

/**
 * A pretend bank behind the preview build's SIM. Every bank USSD is acknowledged ("siendo procesada") and its SMS
 * answer is written into this line's own inbox a few seconds later, with invented references. The amount's cents
 * pick the outcome: `.13` rejected, `.44` "solicitud en proceso", `.99` no answer at all; anything else goes through.
 */
class FakeBank(private val context: Context) : PhoneLine, UssdModem, SmsInbox {
    override val modem: UssdModem get() = this
    override val inbox: SmsInbox get() = this

    private val file = File(context.noBackupFilesDir, "fake-inbox.tsv")
    private val rows = load()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val balances = mutableMapOf<Bank, BigDecimal>()
    private lateinit var controller: AppController

    override fun attach(controller: AppController) {
        this.controller = controller
    }

    override fun read(fromId: Long): List<InboxSms> = synchronized(rows) { rows.filter { it.id >= fromId } }

    override suspend fun request(command: UssdCommand): UssdReply {
        delay(900)
        // The operation being sent right now: its own request, or the one an automatic `40` unlocks.
        val request = controller.operations.value.firstOrNull { it.state == OperationState.Sending }?.operation?.request
        if (request is CubacelQuery) return UssdReply.Text(cubacel(request.kind))
        if (request is CubacelTransfer) return UssdReply.Text("Usted ha transferido ${request.amount} CUP al número ${request.phone}.")
        if (request !is BankRequest) return UssdReply.Failure("La red no aceptó la solicitud")
        val bank = request.bank
        val body = if (command.service == 40) authenticated(bank) else answer(request)
        if (body != null) scope.launch {
            delay(2_500)
            deliver(body)
        }
        return UssdReply.Text(ACK)
    }

    override fun dial(command: UssdCommand) {
        Toast.makeText(context, "Menú de paquetes (simulado)", Toast.LENGTH_SHORT).show()
    }

    /** Writes [body] as a PAGOxMOVIL SMS received now. */
    fun deliver(body: String) {
        val now = System.currentTimeMillis()
        synchronized(rows) {
            rows += InboxSms((rows.maxOfOrNull { it.id } ?: 0) + 1, now, now - 1_000, body)
            file.writeText(rows.joinToString("\n") { "${it.id}\t${it.date}\t${it.dateSent}\t${Base64.getEncoder().encodeToString(it.body.toByteArray())}" })
        }
        controller.onInboxChanged()
    }

    private fun load(): MutableList<InboxSms> = if (!file.isFile) mutableListOf() else file.readLines().filter(String::isNotBlank).map { line ->
        val (id, date, sent, body) = line.split('\t')
        InboxSms(id.toLong(), date.toLong(), sent.toLong(), String(Base64.getDecoder().decode(body)))
    }.toMutableList()

    private fun answer(request: BankRequest): String? {
        val amount = amountOf(request)
        when (amount?.amount?.remainder(BigDecimal.ONE)?.movePointRight(2)?.toInt()) {
            13 -> return rejection(request)
            44 -> return BUSY
            99 -> return null
        }
        val bank = request.bank
        val name = bankName(bank)
        val ref = reference(bank)
        val paid = amount?.amount ?: BigDecimal("312.40")
        val left = debit(bank, paid)
        return when (request) {
            is BalanceQuery -> balance(bank, request.source)
            is HistoryQuery -> "$name Ultimas operaciones. \n Fecha;Servicio;Operacion;Monto;Moneda;NoTransaccion \n ${today()};Banca Movil Transferencia Ref: $ref;Cr;1.00;CUP; | \n"
            is Transfer -> "$name:  La Transferencia fue completada. \n Fecha: ${today()} \n Beneficiario: ${mask(request.destination)} \n " +
                "Ordenante: ${(request.source as? Source.Card)?.number?.let(::mask) ?: "CUP"} \n Monto: ${request.amount.plain()} CUP \n Nro. Transaccion: $ref \n Saldo restante: CR $left CUP "
            is QrPay -> "$name: Pago completado. \n Fecha: ${today()} \n Entidad: ${request.payment.title ?: "Comercio"}\n Id Compra: ${request.payment.transactionId} \n " +
                "Importe: ${request.amount.plain()} CUP \n Importe pagado: ${request.amount.plain()} CUP \n No. Transaccion: $ref \n Saldo disponible: CR $left CUP"
            is ElectricityBill -> "$name:  El pago de la factura de electricidad fue completado. Nro. Transaccion: $ref Nro. Factura Pagada: ${request.invoice} Importe Pagado: $paid CUP"
            is PhoneBill -> "$name:  El pago de la factura telefonica fue completado. Nro. Transaccion: $ref Nro. Factura Pagada: ${request.invoice} Monto Pagado: $paid CUP"
            is WaterBill -> "$name:  El pago de la factura del agua fue completado. Nro. Transaccion: $ref Cuenta: ${request.account} Importe Pagado: $paid CUP"
            is NautaPayment -> when (request.service) {
                NautaService.RECHARGE -> "$name:  La cuenta :${request.account.address} ha sido recargada con $paid CUP. Id Transaccion: $ref. Saldo Restante: DB $left CUP. "
                else -> "$name:  La cuenta Nauta Hogar: ${request.account.address} ha sido pagada con $paid CUP. Id Transaccion: $ref. Saldo Restante: DB $left CUP. "
            }
            is MobileRecharge -> "$name:  La recarga se realizo con exito. Saldo a acreditar: $paid CUP. Monto Pagado: $paid CUP. Telefono: ${request.phone}. Id transaccion: $ref. Saldo Restante: DB $left."
            else -> null
        }
    }

    private fun rejection(request: BankRequest): String = when (request) {
        is Transfer -> "Fallo la transferencia. ${bankName(request.bank)}: SALDO INSUFICIENTE"
        is QrPay -> "Fallo el pago. ${bankName(request.bank)}: SALDO INSUFICIENTE"
        is MobileRecharge -> "Fallo la recarga del movil. ${bankName(request.bank)}: SALDO INSUFICIENTE"
        is NautaPayment -> "Fallo la recarga de la cuenta nauta. ${bankName(request.bank)}: SALDO INSUFICIENTE"
        else -> "Fallo la operacion. vuelva a intentarlo, si persiste el problema, contacte con ETECSA."
    }

    private fun authenticated(bank: Bank): String = when (bank) {
        Bank.BPA -> "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
        Bank.BANDEC -> "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta ${firstCard(bank)?.let(::mask).orEmpty()}, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
        Bank.BANMET -> "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Metropolitano, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
    }

    private fun balance(bank: Bank, source: Source): String {
        val value = balances.getOrPut(bank) { BigDecimal("25000.00") }.setScale(2)
        return when (bank) {
            Bank.BPA -> "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Saldo Disponible: CR $value CUP"
            Bank.BANMET -> "Banco Metropolitano:  La consulta de saldo fue completada. \n Saldo Contable: CR $value CUP \n Saldo Disponible: CR $value CUP"
            Bank.BANDEC -> {
                val card = (source as? Source.Card)?.number ?: firstCard(bank) ?: "9205000000000000"
                "Banco Bandec La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n${mask(card)}; CR $value ; CR $value ;CUP | \n"
            }
        }
    }

    private fun cubacel(kind: CubacelQueryKind): String = when (kind) {
        CubacelQueryKind.BALANCE -> "Saldo: 125.40 CUP. Linea activa hasta 03-12-2026."
        CubacelQueryKind.DATA -> "Usted dispone de 4.2 GB validos hasta el 09-11-2026."
        CubacelQueryKind.VOICE -> "Usted dispone de 38 min validos hasta el 09-11-2026."
        CubacelQueryKind.SMS -> "Usted dispone de 120 SMS validos hasta el 09-11-2026."
        CubacelQueryKind.BONUS -> "Usted no tiene bonos activos."
    }

    private fun debit(bank: Bank, amount: BigDecimal): BigDecimal {
        val next = (balances.getOrPut(bank) { BigDecimal("25000.00") } - amount).max(BigDecimal.ZERO)
        balances[bank] = next
        return next.setScale(2)
    }

    private fun amountOf(request: BankRequest): Money? = when (request) {
        is Transfer -> request.amount
        is QrPay -> request.amount
        is PhoneBill -> request.amount
        is NautaPayment -> request.amount
        is MobileRecharge -> request.amount
        else -> null
    }

    private fun firstCard(bank: Bank): String? = controller.cards.value.firstOrNull { it.card.card.bank == bank }?.card?.card?.number

    private fun bankName(bank: Bank) = when (bank) {
        Bank.BPA -> "Banco Popular de Ahorro"
        Bank.BANDEC -> "Banco Bandec"
        Bank.BANMET -> "Banco Metropolitano"
    }
    private fun mask(card: String) = card.take(4) + "XXXXXXXX" + card.takeLast(4)
    private fun today(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("d/M/yyyy"))
    private fun reference(bank: Bank) = when (bank) { Bank.BPA -> "BR"; Bank.BANDEC -> "KW"; Bank.BANMET -> "MM" } +
        (System.currentTimeMillis() % 100_000_000).toString().padStart(8, '0')

    private companion object {
        const val ACK = "Su solicitud esta siendo procesada, espere un SMS"
        const val BUSY = "Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. "
    }
}

/**
 * `adb shell am broadcast -n <paquete>/dev.duardo.neotransfer.preview.FakeSms --es credit <tarjeta> --es amount 300 --es from 5350000009`
 * delivers a credit to the simulated inbox; `--es body "…"` delivers any text.
 */
class FakeSms : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val line = (context.applicationContext as PreviewApplication).line
        val body = intent.getStringExtra("body") ?: intent.getStringExtra("credit")?.let { card ->
            val amount = intent.getStringExtra("amount") ?: "300.00"
            val from = intent.getStringExtra("from") ?: "5350000009"
            "El titular del telefono $from le ha realizado una transferencia a la cuenta $card de $amount CUP. " +
                "Nro. Transaccion IN${System.currentTimeMillis() % 100_000_000}. Fecha: ${LocalDate.now().format(DateTimeFormatter.ofPattern("d/M/yyyy"))}."
        } ?: return
        line.deliver(body)
    }
}
