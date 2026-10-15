package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.SecretKind
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.sms.SmsParser
import dev.duardo.neotransfer.core.sms.SmsRecord
import java.time.Duration
import java.time.Instant

const val ACK = "Su solicitud esta siendo procesada, espere un SMS"

/** Test double of the Android integration: persists operations, drives the modem and ingests SMS. */
class Phone(var cards: List<OwnCard>) {
    var now: Instant = Instant.parse("2026-10-15T10:00:00Z")
    val operations = mutableListOf<Operation>()
    private val messages = mutableListOf<SmsRecord>()
    val pinSavedAt = mutableMapOf<Bank, Instant>()
    /** Wire strings handed to the modem, in order. */
    val wire = mutableListOf<String>()
    /** Service numbers handed to the modem, in order. */
    val services get() = sentRequests.map { it.second }
    private val sentRequests = mutableListOf<Pair<String, Int?>>()
    private var nextSms = 1L
    private var nextOp = 1

    fun view(): LedgerView = Ledger.evaluate(operations, messages, cards, now, pinSavedAt)
    fun state(id: String): OperationState = requireNotNull(view().status(id)).state

    fun submit(request: UssdRequest): String = "op${nextOp++}".also { operations += Ledger.submit(it, request, now) }

    /** Sends everything the ledger allows now; each send is acknowledged after [ackDelay] with [ack]. */
    fun pump(ack: (Operation) -> String? = { ACK }, ackDelay: Duration = Duration.ofSeconds(2)) {
        while (true) {
            val dispatch = view().next ?: return
            if (dispatch.isNew) operations += dispatch.operation
            val sending = dispatch.operation.sending(now)
            save(sending)
            val pin = when (sending.request.secret) {
                SecretKind.BANK_PIN -> if ((sending.request as? dev.duardo.neotransfer.core.protocol.BankRequest)?.bank == Bank.BANDEC) "12345" else "1234"
                SecretKind.CUBACEL_PIN -> "1234"
                null -> null
            }
            sending.request.commands(pin?.toCharArray(), now, 0).forEach {
                wire += it.valueForTransport()
                sentRequests += sending.id to it.service
            }
            now += ackDelay
            val reply = ack(sending) ?: return // no acknowledgement: the modem stays silent
            save(sending.replied(reply, now))
        }
    }

    fun save(operation: Operation) {
        val index = operations.indexOfFirst { it.id == operation.id }
        if (index < 0) operations += operation else operations[index] = operation
    }

    /** An SMS that left the SMSC [delay] ago and arrives now. */
    fun receive(body: String, delay: Duration = Duration.ofSeconds(5)): Long {
        val id = nextSms++
        messages += SmsRecord(id, now - delay, now, SmsParser.parse(body))
        return id
    }

    fun advance(duration: Duration) { now += duration }
    fun seconds(value: Long) = advance(Duration.ofSeconds(value))
    fun minutes(value: Long) = advance(Duration.ofMinutes(value))
}

object Sms {
    fun authBpa() = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
    fun authBandec(card: String) = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta ${mask6(card)}, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
    fun wrongPin() = "Banco Bandec: Clave incorrecta Error de autenticacion, intente nuevamente. Si persiste el problema, contacte con ETECSA. "
    fun busy() = "Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. "
    fun balanceBpa(value: String) = "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Saldo Disponible: CR $value CUP"
    fun balanceBandec(card: String, available: String, ledger: String = available) =
        "Banco Bandec La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n${mask6(card)}; CR $ledger ; CR $available ;CUP | \n"
    fun sent(bank: Bank, to: String, amount: String, ref: String, balance: String? = null, from: String? = null) =
        "${if (bank == Bank.BANDEC) "Banco Bandec" else "Banco Popular de Ahorro"}:  La Transferencia fue completada. \n Fecha: 15/10/2026 \n Beneficiario: ${mask4(to)} \n " +
            "Ordenante: ${from?.let(::mask4) ?: "CUP"} \n Monto: $amount CUP \n Nro. Transaccion: $ref" + (balance?.let { " \n Saldo restante: CR $it CUP " } ?: "")
    fun copy(to: String, amount: String, ref: String) = "Se ha realizado una transferencia a la cuenta $to de $amount CUP. Nro. Transaccion $ref. Fecha: 15/10/2026."
    fun received(to: String, amount: String, ref: String) =
        "El titular del telefono 5350000009 le ha realizado una transferencia a la cuenta $to de $amount CUP. Nro. Transaccion $ref. Fecha: 15/10/2026."

    /** BANDEC last operations; rows from [row]. */
    fun history(vararg rows: String) =
        "Banco Bandec Ultimas operaciones. \n Fecha;Servicio;Operacion;Monto;Moneda;NoTransaccion \n " + rows.joinToString(" | \n ") + " | \n"
    fun row(date: String, service: String, ref: String?, credit: Boolean, amount: String) =
        "$date;$service${ref?.let { " Ref: $it" }.orEmpty()};${if (credit) "Cr" else "Db"};$amount;CUP;"

    private fun mask6(card: String) = card.take(6) + "XXXXXX" + card.takeLast(4)
    private fun mask4(card: String) = card.take(4) + "XXXXXXXX" + card.takeLast(4)
}
