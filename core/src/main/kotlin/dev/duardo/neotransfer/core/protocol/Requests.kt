package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.qr.QrPayment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

/** The account a bank operation debits or queries. */
@Serializable
sealed interface Source {
    /** The bank's default account of that currency (`<moneda>*0000`). */
    @Serializable @SerialName("default")
    data class Default(val currency: Currency = Currency.CUP) : Source

    /** An explicit 16-digit card (`0*<tarjeta>`). */
    @Serializable @SerialName("card")
    data class Card(val number: String) : Source {
        init { require(Cards.isNumber(number)) { "La tarjeta debe tener 16 dígitos" } }
    }

    companion object {
        /** With one card per bank the default account is enough; with several, the card goes explicitly. */
        fun forCard(card: OwnCard, cards: List<OwnCard>): Source =
            if (cards.count { it.bank == card.bank } <= 1) Default(card.currency) else Card(card.number)
    }
}

/** Secret the integration must supply when building the commands; requests never store it. */
enum class SecretKind { BANK_PIN, CUBACEL_PIN }

/**
 * Something the app sends by USSD. Requests are plain data the integration persists with the
 * operation; [commands] turns one into wire strings at send time.
 */
@Serializable
sealed interface UssdRequest {
    val secret: SecretKind? get() = null

    /**
     * Wire strings for this request, in order. More than one only for an oversized payment
     * (Transfermóvil partial mode): send each after the previous one is acknowledged with
     * [BankResponse.PROCESSING]. [seed] is for tests; production leaves it random.
     */
    fun commands(secret: CharArray? = null, at: Instant = Instant.now(), seed: Int? = null): List<UssdCommand>

    /** Identity of a money movement (origin, destination, amount) for the duplicate check; null for queries. */
    val duplicateKey: String? get() = null
}

/** Bank requests (`*444*…`). All but [Authenticate], [Disconnect] and [QrPay] need a valid session. */
@Serializable
sealed interface BankRequest : UssdRequest {
    val bank: Bank
    val needsSession: Boolean get() = true
}

@Serializable @SerialName("authenticate")
data class Authenticate(override val bank: Bank) : BankRequest {
    override val secret get() = SecretKind.BANK_PIN
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(encoded(40, listOf(bank.code, pin(bank, secret)), seed))
}

@Serializable @SerialName("disconnect")
data class Disconnect(override val bank: Bank) : BankRequest {
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(plain(70))
}

/** Balance `46`. BANDEC has no origin: it always answers for the card the session is on. */
@Serializable @SerialName("balance")
data class BalanceQuery(override val bank: Bank, val source: Source = Source.Default()) : BankRequest {
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(
        if (bank == Bank.BANDEC) plain(46) else encoded(46, listOf(cardCurrency(bank, source), origin(source)), seed),
    )
}

/** Last operations `48`. Metropolitano asks which kind first: `1` is all of them (GhhAuUBVrI). */
@Serializable @SerialName("history")
data class HistoryQuery(override val bank: Bank, val source: Source = Source.Default()) : BankRequest {
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(encoded(48, listOf(if (bank == Bank.BANMET) "1" else "0",
        if (bank == Bank.BANDEC) (if (source is Source.Default) "1" else "0") else cardCurrency(bank, source), origin(source)), seed))
}

/** Transfer `45` to a 16-digit card or account. [notifyPhone] receives the beneficiary's SMS. */
@Serializable @SerialName("transfer")
data class Transfer(
    override val bank: Bank, val source: Source, val destination: String, val amount: Money, val notifyPhone: String? = null,
) : BankRequest {
    init {
        require(Cards.isNumber(destination)) { "La tarjeta destino debe tener 16 dígitos" }
        require(amount.amount.signum() > 0) { "El importe debe ser mayor que cero" }
        require(notifyPhone == null || Phones.normalize(notifyPhone) == notifyPhone) { "El móvil debe tener 8 dígitos" }
    }
    override val duplicateKey get() = key("45", source, destination, amount)
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(encoded(45, listOf(
        destination, amount.plain(),
        if (bank == Bank.BANDEC) "0" else amount.currency.wireCode,
        cardCurrency(bank, source), origin(source),
        notifyPhone ?: "0000",
        "0", // do not reveal the sender's phone
        "0000", // no message
        "0", // keep "Saldo restante" in the receipt (1 = omit it)
    ), seed))
}

/** QR / online payment `30`/`31`. Always carries the banking key; it does not need a session. */
@Serializable @SerialName("qr")
data class QrPay(
    override val bank: Bank, val source: Source, val payment: QrPayment, val amount: Money,
    val description: String = "", val notifyPhone: String? = null,
) : BankRequest {
    init {
        require(amount.amount.signum() > 0 && amount.currency == payment.amount.currency) { "Importe no válido" }
        require(payment.editableAmount || amount == payment.amount) { "No se puede modificar el importe de este QR" }
        require(payment.description.isEmpty() || description == payment.description) { "No se puede modificar la descripción" }
        require(notifyPhone == null || Phones.normalize(notifyPhone) == notifyPhone) { "El móvil debe tener 8 dígitos" }
    }
    override val secret get() = SecretKind.BANK_PIN
    override val needsSession get() = false
    override val duplicateKey get() = key("${payment.service}:${payment.provider}", source, payment.transactionId, amount)

    override fun commands(secret: CharArray?, at: Instant, seed: Int?): List<UssdCommand> {
        val service = payment.service
        val special = service == 30 && payment.auxiliary.contains("11200301")
        val auxData = payment.qrId != null || notifyPhone != null
        val codec = ParameterCodec()
        val prefix = seed ?: java.security.SecureRandom().nextInt(100)
        fun parameters(id: String) = buildList {
            addAll(listOf(bank.code, pin(bank, secret), id, amount.plain(), amount.currency.wireCode, cardCurrency(bank, source),
                payment.provider, if (special) "0" else payment.auxiliary))
            if (service == 31) add(description.ifEmpty { "0000" })
            add(origin(source))
            if (auxData) {
                add(notifyPhone ?: if (special) "0000" else "0")
                if (service == 31) add(payment.qrId ?: "0000")
            }
        }
        var payload = codec.encode(parameters(payment.transactionId), prefix)
        // r7Rinp9ooL: Transfermóvil measures `tel:*444*<payload>*<version>%23` and, from 130 on,
        // keeps the id's last 15 characters (8 for a static payment without phone and QR id).
        if (!special && payload.length + 20 >= 130) {
            val keep = if (service == 31 && !auxData) 8 else 15
            payload = codec.encode(parameters(payment.transactionId.takeLast(keep)), prefix)
        }
        val wire = "*444*$service*$payload*$CLIENT_VERSION#"
        if (wire.length <= USSD_MAX_LENGTH) return listOf(UssdCommand(service, wire, UssdTransport.REQUEST))
        val time = at.atOffset(ZoneOffset.UTC)
        return partialEncodedCommands(service, bank.code, payload, "${time.minute}${time.second.toString().padStart(2, '0')}").also { packets ->
            require(packets.all { it.valueForTransport().length <= USSD_MAX_LENGTH }) { "La descripción es demasiado larga para enviarla" }
        }
    }
}

/** Electricity `41`; the bank pays the whole invoice (amount `0`). Metropolitano takes 13-digit invoices only (PliSaYhOg0). */
@Serializable @SerialName("electricity")
data class ElectricityBill(override val bank: Bank, val source: Source, val invoice: String) : BankRequest {
    init {
        require(invoice.matches(Regex("[0-9]{11}|[0-9]{13}"))) { "La factura eléctrica tiene 11 o 13 dígitos" }
        require(bank != Bank.BANMET || invoice.length == 13) { "Con Metropolitano, la factura eléctrica tiene 13 dígitos" }
    }
    override val duplicateKey get() = key("41", source, invoice, null)
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(encoded(41, billParameters(bank, source, listOf(invoice, "0")), seed))
}

/**
 * Landline `42`. BPA needs the amount; in BANDEC and Metropolitano a null amount pays the total. Metropolitano's
 * invoices start with `01` (pnl2Kga8j7).
 */
@Serializable @SerialName("phone")
data class PhoneBill(override val bank: Bank, val source: Source, val invoice: String, val amount: Money? = null) : BankRequest {
    init {
        require(invoice.matches(Regex("[0-9]{14,15}"))) { "La factura telefónica tiene 14 o 15 dígitos" }
        require(bank != Bank.BANMET || invoice.startsWith("01")) { "Con Metropolitano, la factura telefónica empieza por 01" }
        require(bank != Bank.BPA || amount != null) { "Indica el importe" }
        require(amount == null || amount.amount.signum() > 0) { "El importe debe ser mayor que cero" }
    }
    override val duplicateKey get() = key("42", source, invoice, amount)
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) =
        listOf(encoded(42, billParameters(bank, source, listOf(invoice, amount?.plain() ?: "0")), seed))
}

enum class WaterRegion(val code: String, val service: Int) { HAVANA("1", 19), OTHER("2", 51), VARADERO("3", 21) }

/** Water `19`/`51`/`21`, total debt. Metropolitano sends what BPA does; BANDEC's `51` has one field less (T4RQ5wDJDt). */
@Serializable @SerialName("water")
data class WaterBill(override val bank: Bank, val source: Source, val region: WaterRegion, val account: String) : BankRequest {
    init { require(account.isNotEmpty() && account.all { it in '0'..'9' }) { "Cuenta de agua no válida" } }
    override val duplicateKey get() = key("${region.service}", source, account, null)
    override fun commands(secret: CharArray?, at: Instant, seed: Int?): List<UssdCommand> {
        val type = if (region == WaterRegion.OTHER) "0" else "1"
        val parameters = when {
            bank != Bank.BANDEC -> listOf(region.code, account, "0.0", type, cardCurrency(bank, source), origin(source))
            region == WaterRegion.OTHER -> listOf(region.code, account, "0", type, origin(source))
            else -> listOf(region.code, account, "0", type, "0", origin(source))
        }
        return listOf(encoded(region.service, parameters, seed))
    }
}

/** Nauta services: Wi‑Fi account recharge `59`, Nauta Hogar recharge `84`, Nauta Hogar debt `86`. */
enum class NautaService(val service: Int, val suffix: String) { RECHARGE(59, "1"), HOME_RECHARGE(84, "2"), HOME_DEBT(86, "3") }

/** `user@nauta.com.cu` (international, type 1) or `user@nauta.co.cu` (national, type 2). */
@Serializable
data class NautaAccount(val user: String, val international: Boolean = true) {
    init { require(user.matches(Regex("[A-Za-z0-9_.-]{1,20}"))) { "Usuario Nauta sin dominio, de hasta 20 caracteres" } }
    val address: String get() = "$user@${if (international) "nauta.com.cu" else "nauta.co.cu"}"

    companion object {
        fun parse(value: String): NautaAccount {
            val text = value.trim().lowercase()
            return when {
                text.endsWith("@nauta.com.cu") -> NautaAccount(text.substringBefore('@'), true)
                text.endsWith("@nauta.co.cu") -> NautaAccount(text.substringBefore('@'), false)
                '@' in text -> throw IllegalArgumentException("Dominio Nauta no válido")
                else -> NautaAccount(text, true)
            }
        }
    }
}

@Serializable @SerialName("nauta")
data class NautaPayment(
    override val bank: Bank, val source: Source, val service: NautaService, val account: NautaAccount, val amount: Money,
    val notifyPhone: String? = null,
) : BankRequest {
    init {
        require(amount.amount >= java.math.BigDecimal.ONE) { "El importe mínimo es 1 CUP" }
        require(notifyPhone == null || Phones.normalize(notifyPhone) == notifyPhone) { "El móvil debe tener 8 dígitos" }
    }
    override val duplicateKey get() = key("${service.service}", source, account.address, amount)
    // PqTzgaEYF6: the amount travels as String.valueOf(float), e.g. "100.0".
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(encoded(service.service, listOf(
        account.user, if (account.international) "1" else "2", amount.amount.toFloat().toString(), cardCurrency(bank, source),
        notifyPhone ?: "0000000", origin(source), service.suffix), seed))
}

/** Cubacel top-up from the bank `54`. */
@Serializable @SerialName("mobile")
data class MobileRecharge(override val bank: Bank, val source: Source, val phone: String, val amount: Money) : BankRequest {
    init {
        require(phone.matches(Regex("[0-9]{8}|[0-9]{10}"))) { "Móvil no válido" }
        require(amount.amount >= java.math.BigDecimal.ONE) { "El importe mínimo es 1 CUP" }
    }
    override val duplicateKey get() = key("54", source, Phones.normalize(phone) ?: phone, amount)
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) =
        listOf(encoded(54, listOf(phone, amount.plain(), cardCurrency(bank, source), origin(source)), seed))
}

private fun encoded(service: Int, parameters: List<String>, seed: Int?): UssdCommand {
    val codec = ParameterCodec()
    val payload = if (seed == null) codec.encode(parameters) else codec.encode(parameters, seed)
    return UssdCommand(service, "*444*$service*$payload*$CLIENT_VERSION#", UssdTransport.REQUEST)
}

private fun plain(service: Int) = UssdCommand(service, "*444*$service#", UssdTransport.REQUEST)

private fun origin(source: Source) = when (source) { is Source.Default -> "0000"; is Source.Card -> source.number }

/** `monedaTarjeta`: always 0 in BANDEC and for an explicit card; the default account's currency otherwise. */
private fun cardCurrency(bank: Bank, source: Source) = when (source) {
    is Source.Card -> "0"
    is Source.Default -> if (bank == Bank.BANDEC) "0" else source.currency.wireCode
}

/** 41/42: BPA and Metropolitano always send currency and origin; BANDEC only adds an explicit origin. */
private fun billParameters(bank: Bank, source: Source, values: List<String>) = when (bank) {
    Bank.BPA, Bank.BANMET -> values + listOf(cardCurrency(bank, source), origin(source))
    Bank.BANDEC -> if (source is Source.Card) values + source.number else values
}

private fun pin(bank: Bank, secret: CharArray?): String {
    require(secret != null && secret.size == bank.pinLength && secret.all { it in '0'..'9' }) { "Clave bancaria no válida" }
    return String(secret)
}

private fun key(kind: String, source: Source, target: String, amount: Money?) =
    "$kind|${origin(source)}|$target|${amount?.amount?.stripTrailingZeros()?.toPlainString()}"
