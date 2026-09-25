package dev.duardo.neotransfer.core

/** Never log the wire value: authentication payloads encode the banking PIN. */
class UssdCommand internal constructor(val service: Int, private val value: String,
    internal val partialPayload: String? = null, internal val partialAgency: String? = null) {
    fun valueForTransport(): String = value
    override fun toString(): String = "UssdCommand(service=$service)"
}

class BankCommands(private val codec: ParameterCodec = ParameterCodec()) {
    fun qrPayment(bank: Bank, pin: CharArray, qr: QrPayment, amount: Money,
                  description: String, sourceAccount: String = "0000", phone: String? = null,
                  sequence: String, seed: Int? = null): List<UssdCommand> {
        require(bank != Bank.BFI) { "BFI no está disponible" }
        require(pin.size == bank.pinLength && pin.all { it in '0'..'9' }) { "Clave bancaria no válida" }
        validateAccount(sourceAccount)
        qr.validateDate(java.time.LocalDate.now())
        require(amount.amount.signum() > 0 && amount.currency == qr.amount.currency) { "Importe no válido" }
        require(qr.editableAmount || amount.sameValue(qr.amount)) { "No se puede modificar el importe de este QR" }
        require(qr.description.isEmpty() || description == qr.description) { "No se puede modificar la descripción" }
        require(phone == null || phone.matches(Regex("[0-9]{8}"))) { "Móvil no válido" }
        val special = qr.service == 30 && qr.auxiliary.contains("11200301")
        val parameters = mutableListOf(bank.code, String(pin), qr.transactionId, amount.amount.toPlainString(),
            amount.currency.code(), if (bank == Bank.BANDEC || sourceAccount != "0000") "0" else amount.currency.code(),
            qr.provider, if (special) "0" else qr.auxiliary)
        if (qr.service == 31) parameters += description.ifEmpty { "0000" }
        parameters += sourceAccount
        if (qr.qrId != null || phone != null) {
            parameters += phone ?: if (special) "0000" else "0"
            if (qr.service == 31) parameters += qr.qrId ?: "0000"
        }
        val payload = if (seed == null) codec.encode(parameters) else codec.encode(parameters, seed)
        val direct = "*444*${qr.service}*$payload*1260416#"
        return if (direct.length <= 130) listOf(UssdCommand(qr.service, direct))
            else partialCommands(qr.service, bank, payload, sequence).also { parts ->
                require(parts.all { it.valueForTransport().length <= 130 }) { "El QR es demasiado largo para enviarlo sin alterar sus datos" }
            }
    }

    fun recharge(bank: Bank, mobile: String, amount: Money, sourceAccount: String = "0000"): UssdCommand {
        require(bank == Bank.BPA || bank == Bank.BANDEC) { "Selecciona el contrato de recarga de este banco" }
        require(mobile.matches(Regex("[0-9]{8}|[0-9]{10}"))) { "Móvil no válido" }
        require(amount.currency == Currency.CUP && amount.amount >= java.math.BigDecimal.ONE) { "Importe no válido" }
        validateAccount(sourceAccount)
        val values = mutableListOf(mobile, amount.amount.toPlainString(), "1")
        if (bank == Bank.BPA || sourceAccount != "0000") values += sourceAccount
        return encoded(54, values, null)
    }

    fun bill(bank: Bank, electricity: Boolean, invoice: String, amount: Money, sourceAccount: String = "0000"): UssdCommand {
        require(bank == Bank.BPA || bank == Bank.BANDEC) { "Selecciona el contrato de facturación de este banco" }
        require(invoice.matches(if (electricity) Regex("[0-9]{11}|[0-9]{13}") else Regex("[0-9]{14,15}"))) { "Identificador de factura no válido" }
        require(amount.currency == Currency.CUP) { "Moneda no válida" }
        validateAccount(sourceAccount)
        val values = mutableListOf(invoice, if (electricity) "0" else amount.amount.toPlainString())
        if (bank == Bank.BPA) values += "1"
        if (bank == Bank.BPA || sourceAccount != "0000") values += sourceAccount
        return encoded(if (electricity) 41 else 42, values, null)
    }

    fun authenticate(bank: Bank, pin: CharArray, seed: Int? = null): UssdCommand {
        require(bank != Bank.BFI) { "BFI no está disponible" }
        require(pin.size == bank.pinLength && pin.all { it in '0'..'9' }) { "Clave bancaria no válida" }
        return encoded(40, listOf(bank.code, String(pin)), seed)
    }

    fun defaultBalance(): UssdCommand = UssdCommand(46, "*444*46#")
    fun disconnect(): UssdCommand = UssdCommand(70, "*444*70#")

    fun bpaBalance(currency: Currency, sourceAccount: String = "0000", seed: Int? = null): UssdCommand {
        validateAccount(sourceAccount)
        return encoded(46, listOf(if (sourceAccount == "0000") currency.code() else "0", sourceAccount), seed)
    }

    fun transfer(request: TransferRequest, seed: Int? = null): UssdCommand {
        require(request.bank != Bank.BFI) { "BFI no está disponible" }
        CurrencyContract.BANK.code(request.amount.currency)
        val amountCurrency = if (request.bank == Bank.BANDEC) "0" else request.amount.currency.code()
        val accountCurrency = if (request.bank == Bank.BANDEC || request.sourceAccount != "0000") "0"
            else request.amount.currency.code()
        return encoded(45, listOf(
            request.destination,
            request.amount.amount.toPlainString(),
            amountCurrency,
            accountCurrency,
            request.sourceAccount,
            request.notificationPhone ?: "0000",
            "0", // Do not disclose the sender's phone by default.
            "0000", // No optional message.
            "0", // Do not enable the provider's optional balance-sharing flag.
        ), seed)
    }

    /** BPA/BANMET select a currency or card; BANDEC's balance action uses the active account. */
    fun balance(bank: Bank, currency: Currency? = null, source: SourceSelector = SourceSelector.Default,
                seed: Int? = null): UssdCommand {
        require(bank != Bank.BFI) { "BFI no está disponible" }
        validateBankSource(source)
        if (bank == Bank.BANDEC) {
            require(source == SourceSelector.Default && currency == null) { "El saldo BANDEC consulta la cuenta actual" }
            return defaultBalance()
        }
        return encoded(46, listOf(accountCurrency(currency, source), source.wireValue), seed)
    }

    fun recentOperations(bank: Bank, currency: Currency? = null, source: SourceSelector = SourceSelector.Default,
                         filter: BankOperationFilter? = null, seed: Int? = null): UssdCommand {
        require(bank != Bank.BFI) { "BFI no está disponible" }
        validateBankSource(source)
        val parameters = when (bank) {
            Bank.BPA -> {
                require(filter == null) { "BPA consulta todas las operaciones" }
                listOf("0", accountCurrency(currency, source), source.wireValue)
            }
            Bank.BANDEC -> {
                require(currency == null && filter == null) { "BANDEC usa la cuenta actual o una cuenta explícita" }
                listOf("0", if (source == SourceSelector.Default) "1" else "0", source.wireValue)
            }
            Bank.BANMET -> listOf(requireNotNull(filter) { "Selecciona el tipo de operación" }.code,
                accountCurrency(currency, source), source.wireValue)
            Bank.BFI -> throw IllegalArgumentException("BFI no está disponible")
        }
        return encoded(48, parameters, seed)
    }

    fun allAccounts(bank: Bank): UssdCommand {
        require(bank == Bank.BANDEC || bank == Bank.BANMET) { "Consulta de todas las cuentas no disponible en este banco" }
        return UssdCommand(58, "*444*58#")
    }

    /** This simple 60 contract is distinct from other banks' coordinate challenge flows. */
    fun associateAccount(bank: Bank, account: String, seed: Int? = null): UssdCommand {
        require(bank == Bank.BPA || bank == Bank.BANDEC) { "Este banco requiere otro contrato de asociación" }
        validateBankSource(SourceSelector.Explicit(account))
        return encoded(60, listOf(account), seed)
    }

    fun recentPayments(bank: Bank, filter: BankPaymentFilter, source: SourceSelector = SourceSelector.Default,
                       seed: Int? = null): UssdCommand {
        require(bank == Bank.BANDEC) { "Este contrato de últimos pagos corresponde a BANDEC" }
        validateBankSource(source)
        return encoded(63, listOf(filter.code) + if (source is SourceSelector.Explicit) listOf(source.account) else emptyList(), seed)
    }

    fun paymentStatus(bank: Bank, reference: String, source: SourceSelector = SourceSelector.Default,
                      seed: Int? = null): UssdCommand {
        require(bank == Bank.BANDEC) { "Este contrato de estado de pago corresponde a BANDEC" }
        validateBankSource(source)
        require(reference.isNotBlank() && reference.none { it == '*' || it == '#' || it.isISOControl() }) { "Referencia no válida" }
        return encoded(89, listOf(reference, source.wireValue), seed)
    }

    private fun validateBankSource(source: SourceSelector) {
        if (source is SourceSelector.Explicit) require(source.account.matches(Regex("[0-9]{16}"))) { "La cuenta debe tener 16 dígitos" }
    }

    private fun accountCurrency(currency: Currency?, source: SourceSelector): String =
        if (source is SourceSelector.Explicit) "0" else CurrencyContract.BANK.code(requireNotNull(currency) { "Selecciona la moneda" })

    private fun encoded(service: Int, parameters: List<String>, seed: Int?): UssdCommand {
        val payload = if (seed == null) codec.encode(parameters) else codec.encode(parameters, seed)
        // sendUssdRequest expects the final # directly, unlike a tel: URI.
        return UssdCommand(service, "*444*$service*$payload*1260416#")
    }

    private fun validateAccount(value: String) {
        require(value == "0000" || value.matches(Regex("[0-9]{16}"))) { "Cuenta de origen no válida" }
    }

    private fun Currency.code(): String = CurrencyContract.BANK.code(this)
}
