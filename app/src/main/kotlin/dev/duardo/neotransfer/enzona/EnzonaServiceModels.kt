package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Only service codes connected to request constructors in ENZONA code20003. */
enum class EnzonaInvoiceService(val code: String, val responseType: String?) {
    ELECTRICITY("2222", null), FIXED_PHONE("4441", "tb"), NAUTA("4443", "nauta"), NAUTA_HOME("4445", "adsl_nauta"),
}

data class EnzonaElectricityBill(
    val clientId: String?, val owner: String?, val folio: String?, val route: String?,
    val period: String?, val amount: BigDecimal?, val totalAmount: BigDecimal?,
    val charged: Boolean?, val original: JsonObject,
) { override fun toString() = "EnzonaElectricityBill(redacted)" }

data class EnzonaEtecsaAccount(
    val serviceType: String?, val serviceName: String?, val number: String?, val isValid: String?,
    val toPay: BigDecimal?, val currentDebt: BigDecimal?, val unpaidDebt: BigDecimal?,
    val currentUnpaidDebt: BigDecimal?, val minimumAmount: BigDecimal?, val maximumAmount: BigDecimal?,
    val original: JsonObject,
) {
    val service: EnzonaInvoiceService? get() = EnzonaInvoiceService.entries.firstOrNull {
        it.responseType != null && it.responseType == serviceType
    }
    override fun toString() = "EnzonaEtecsaAccount(redacted)"
}

data class EnzonaEtecsaQuery(
    val resultMessage: String?, val success: String?, val name: String?, val accounts: List<EnzonaEtecsaAccount>,
) { override fun toString() = "EnzonaEtecsaQuery(redacted)" }

data class EnzonaInvoiceLookup(
    val service: EnzonaInvoiceService,
    val bills: List<EnzonaElectricityBill> = emptyList(),
    val etecsa: EnzonaEtecsaQuery? = null,
) { override fun toString() = "EnzonaInvoiceLookup(service=$service, privateFields=redacted)" }

data class EnzonaServiceReference(
    val serviceUuid: String?, val uuid: String?, val clientId: String?, val owner: String?,
    val fundingSourceUuid: String?, val automatic: String?, val metadata: JsonObject?, val original: JsonObject,
) { override fun toString() = "EnzonaServiceReference(redacted)" }

/** A terminal UI status is evidence of the provider response, not an independently reconciled debit. */
enum class EnzonaServicePaymentState { ACCEPTED, PENDING, FAILED, UNKNOWN }

data class EnzonaServiceFault(val code: String?, val type: String?, val message: String?, val description: String?) {
    override fun toString() = "EnzonaServiceFault(redacted)"
}

class EnzonaServiceException(val httpStatus: Int, val fault: EnzonaServiceFault?) :
    IOException("ENZONA no pudo consultar el servicio")

/** No raw payment response retained: providers can echo authorization fields. */
data class EnzonaServicePaymentResult(
    val state: EnzonaServicePaymentState, val httpStatus: Int, val statusCode: String?, val status: String?,
    val uuid: String?, val invoiceNumber: String?, val passwordIntent: Boolean, val fault: EnzonaServiceFault?,
) { override fun toString() = "EnzonaServicePaymentResult(state=$state, privateFields=redacted)" }

/** Transient, already encrypted authorization. Never persist or log json. */
class EnzonaServicePaymentRequest internal constructor(val service: EnzonaInvoiceService, val json: String) {
    val path: String = "/invoice/v1.0.0/invoice/payment"
    override fun toString() = "EnzonaServicePaymentRequest(redacted)"
}

/** Encoders do not encrypt, submit, renew sessions or retry. */
object EnzonaServiceRequests {
    // setPaymentForm 0x854830 -> PayElectricBillUseCaseParams.toJson 0x70a328.
    fun electricityPayment(bill: EnzonaElectricityBill, fundingSourceUuid: String, encryptedPassword: String): EnzonaServicePaymentRequest =
        EnzonaServicePaymentRequest(EnzonaInvoiceService.ELECTRICITY, buildJsonObject {
            put("service_code", 2222)
            put("funding_source_uuid", serviceIdentifier(fundingSourceUuid))
            put("client_id", serviceIdentifier(bill.clientId))
            put("owner", serviceIdentifier(bill.owner))
            put("metadata", electricityMetadata(bill).toString())
            put("period", serviceIdentifier(bill.period))
            put("amount", serviceAmount(bill.amount))
            put("payment_password", serviceIdentifier(encryptedPassword))
        }.toString())

    // Payment form 0x86e1b0 takes service_name (field_23), not number (field_2b).
    fun fixedPhonePayment(account: EnzonaEtecsaAccount, fundingSourceUuid: String, encryptedPassword: String): EnzonaServicePaymentRequest {
        require(account.service == EnzonaInvoiceService.FIXED_PHONE) { "La cuenta no es de telefonía fija" }
        return etecsaPayment(account, account.toPay, fundingSourceUuid, encryptedPassword)
    }

    // 0x8670ac: entered amount, even when the lookup contains debt. No debt-payment endpoint demonstrated.
    fun nautaTopUp(account: EnzonaEtecsaAccount, amount: BigDecimal, fundingSourceUuid: String, encryptedPassword: String): EnzonaServicePaymentRequest {
        require(account.service in setOf(EnzonaInvoiceService.NAUTA, EnzonaInvoiceService.NAUTA_HOME)) { "La cuenta no es Nauta" }
        return etecsaPayment(account, amount, fundingSourceUuid, encryptedPassword)
    }

    private fun etecsaPayment(account: EnzonaEtecsaAccount, amount: BigDecimal?, card: String, encrypted: String): EnzonaServicePaymentRequest {
        require(account.isValid == "true") { "La consulta no confirma una cuenta válida" }
        val service = requireNotNull(account.service)
        return EnzonaServicePaymentRequest(service, buildJsonObject {
            put("service_code", service.code)
            put("funding_source_uuid", serviceIdentifier(card))
            put("client_id", serviceIdentifier(account.serviceName))
            put("owner", serviceIdentifier(account.serviceName))
            put("amount", serviceAmount(amount))
            put("payment_password", serviceIdentifier(encrypted))
        }.toString())
    }

    // Remote registration, distinct from a local nickname. Automatic payments are not enabled.
    fun electricityReference(bill: EnzonaElectricityBill, fundingSourceUuid: String = ""): JsonObject = buildJsonObject {
        put("service_code", "2222")
        put("funding_source_uuid", fundingSourceUuid.also { require(it.none(Char::isISOControl)) })
        put("client_id", serviceIdentifier(bill.clientId))
        put("owner", serviceIdentifier(bill.owner))
        put("automatic", "0")
        put("metadata", electricityMetadata(bill).toString())
    }

    // addIdClient 0x78073c; these six metadata keys are built at 0x780838..0x780904.
    fun etecsaReference(account: EnzonaEtecsaAccount, fundingSourceUuid: String, owner: String? = null): JsonObject {
        val service = requireNotNull(account.service) { "Servicio sin contrato de alta" }
        require(account.isValid == "true") { "La consulta no confirma una cuenta válida" }
        val metadata = buildJsonObject {
            for (key in listOf("store_name", "store_code", "contract_eid", "account_state_eid", "address", "service_type")) {
                put(key, requireNotNull(account.original.serviceString(key)) { "Faltan datos de la referencia" })
            }
        }
        return buildJsonObject {
            put("service_code", service.code)
            put("funding_source_uuid", serviceIdentifier(fundingSourceUuid))
            put("client_id", serviceIdentifier(account.serviceName).trim())
            put("owner", serviceIdentifier(owner ?: account.original.serviceString("partner_name")).trim())
            put("automatic", "0")
            put("metadata", metadata.toString())
        }
    }

    private fun electricityMetadata(bill: EnzonaElectricityBill): JsonObject = buildJsonObject {
        put("folio", serviceIdentifier(bill.folio))
        put("route", serviceIdentifier(bill.route))
    }
}

/** Explicit read envelopes and payment states from the AOT; no generic wrapper fallback. */
object EnzonaServiceJson {
    fun electricityBills(body: String): List<EnzonaElectricityBill> = serviceEnvelope(body).serviceRows("client")
        .flatMap { it.serviceRows("invoice") }.map { row ->
            EnzonaElectricityBill(row.serviceString("client_id"), row.serviceString("owner"), row.serviceString("folio"),
                row.serviceString("route"), row.serviceText("period"), row.serviceDecimal("amount"), row.serviceDecimal("total_amount"),
                row.serviceText("charged")?.let { value -> when (value) { "true" -> true; "false" -> false; else -> null } }, row)
        }

    fun etecsaAccounts(body: String): EnzonaEtecsaQuery {
        val root = serviceEnvelope(body)
        val data = root["datalist"] as? JsonObject ?: throw IllegalArgumentException("Falta datalist")
        return EnzonaEtecsaQuery(root.serviceString("resultmsg"), root.serviceString("success"), root.serviceString("name"),
            data.serviceRows("data").map { row ->
                EnzonaEtecsaAccount(row.serviceString("service_type"), row.serviceString("service_name"), row.serviceString("number"),
                    row.serviceString("is_valid"), row.serviceDecimal("to_pay"), row.serviceDecimal("current_debt"),
                    row.serviceDecimal("unpaid_debt"), row.serviceDecimal("current_unpaid_debt"),
                    row.serviceDecimal("minimum_amount"), row.serviceDecimal("maximum_amount"), row)
            })
    }

    fun references(body: String): List<EnzonaServiceReference> = serviceEnvelope(body).serviceRows("client_id").map(::reference)

    fun savedReference(service: EnzonaInvoiceService, body: String): EnzonaServiceReference {
        val root = serviceEnvelope(body)
        return reference(if (service == EnzonaInvoiceService.ELECTRICITY) {
            root["client_id"] as? JsonObject ?: throw IllegalArgumentException("Falta client_id del alta")
        } else root)
    }

    private fun reference(row: JsonObject): EnzonaServiceReference {
        val metadata = when (val value = row["metadata"]) {
            null, JsonNull -> null
            is JsonObject -> value
            is JsonPrimitive -> if (value.isString) serviceObject(value.content) else null
            else -> null
        }
        require(row["metadata"] == null || row["metadata"] == JsonNull || metadata != null) { "Metadata de referencia no válida" }
        return EnzonaServiceReference(row.serviceString("service_uuid"), row.serviceString("uuid"), row.serviceText("client_id"),
            row.serviceString("owner"), row.serviceString("funding_source_uuid"), row.serviceText("automatic"), metadata, row)
    }

    fun payment(service: EnzonaInvoiceService, httpStatus: Int, body: String): EnzonaServicePaymentResult {
        val root = serviceObject(body)
        val fault = fault(body)
        val invoice = root?.get("charged_invoice") as? JsonObject
        // AOT defaults absent/null to an empty map (0x700a14, 0x869b00), not a password error.
        val intents = listOf(root?.get("getPaymentPasswordIntent"), invoice?.get("getPaymentPasswordIntent"))
        val intent = intents.any { it is JsonObject && it.isNotEmpty() }
        val malformedIntent = intents.any { it != null && it != JsonNull && it !is JsonObject }
        val code = invoice?.serviceText("status_code")
        val status = invoice?.serviceText("status")
        val error = root == null || listOf("fault", "error", "errors", "error_description").any { it in root }
        val state = when {
            httpStatus != 200 || error || intent || malformedIntent || invoice == null -> EnzonaServicePaymentState.UNKNOWN
            service == EnzonaInvoiceService.ELECTRICITY -> {
                val integer = (invoice["status_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (integer == 1111 && status !in setOf("Pendiente", "Fallida")) EnzonaServicePaymentState.ACCEPTED else EnzonaServicePaymentState.UNKNOWN
            }
            status == "Aceptada" -> EnzonaServicePaymentState.ACCEPTED
            status == "Pendiente" -> EnzonaServicePaymentState.PENDING
            status == "Fallida" -> EnzonaServicePaymentState.FAILED
            else -> EnzonaServicePaymentState.UNKNOWN
        }
        return EnzonaServicePaymentResult(state, httpStatus, code, status, invoice?.serviceString("uuid"),
            invoice?.serviceText("invoice_number"), intent, fault)
    }

    fun fault(body: String): EnzonaServiceFault? = (serviceObject(body)?.get("fault") as? JsonObject)?.let { row ->
        EnzonaServiceFault(row.serviceText("code") ?: row.serviceText("cod"), row.serviceString("type"),
            row.serviceString("message"), row.serviceString("description"))
    }
}

private fun serviceObject(body: String): JsonObject? = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: IllegalArgumentException) { null }
private fun serviceEnvelope(body: String): JsonObject {
    val root = serviceObject(body) ?: throw IllegalArgumentException("Respuesta de servicio no válida")
    require(listOf("fault", "error", "errors", "error_description").none { it in root }) { "La respuesta contiene un error de servicio" }
    return root
}
private fun JsonObject.serviceRows(key: String): List<JsonObject> {
    val rows = this[key] as? JsonArray ?: throw IllegalArgumentException("Lista de servicio ausente o no válida")
    return rows.map { it as? JsonObject ?: throw IllegalArgumentException("Elemento de servicio no válido") }
}
private fun JsonObject.serviceString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.serviceText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
private fun JsonObject.serviceDecimal(key: String): BigDecimal? {
    val value: JsonElement = this[key]?.takeIf { it != JsonNull } ?: return null
    val text = (value as? JsonPrimitive)?.content ?: throw IllegalArgumentException("Importe de servicio no válido")
    return text.toBigDecimalOrNull() ?: throw IllegalArgumentException("Importe de servicio no válido")
}
private fun serviceIdentifier(value: String?): String {
    require(!value.isNullOrBlank() && value.none(Char::isISOControl)) { "Falta un dato del servicio" }
    return value
}
private fun serviceAmount(value: BigDecimal?): String {
    require(value != null && value.signum() > 0) { "El importe debe ser mayor que cero" }
    return value.toPlainString()
}
