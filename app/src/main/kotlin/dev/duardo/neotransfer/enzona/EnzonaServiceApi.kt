package dev.duardo.neotransfer.enzona

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** All URLs are fixed-origin, AOT-traced requests. Callers own authorization and payment journaling. */
internal class EnzonaServiceApi(
    private val execute: suspend (Request) -> EnzonaHttpReply,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) {
    constructor(http: EnzonaHttp) : this(http::execute)

    // getElectricMonthBill 0x70f8bc; omitted client_id selects saved clients (0x84c5d0).
    suspend fun electricityBills(tokens: EnzonaTokens, clientId: String? = null): EnzonaFetched<List<EnzonaElectricityBill>> =
        fetch(tokens, search("2222", clientId), EnzonaServiceJson::electricityBills)

    /** Pass the full account identifier, including the selected Nauta navigation suffix. */
    suspend fun etecsaAccounts(tokens: EnzonaTokens, service: EnzonaInvoiceService, clientId: String): EnzonaFetched<EnzonaEtecsaQuery> {
        require(service != EnzonaInvoiceService.ELECTRICITY)
        return fetch(tokens, search(service.code, clientId), EnzonaServiceJson::etecsaAccounts)
    }

    suspend fun savedEtecsaAccounts(tokens: EnzonaTokens): EnzonaFetched<EnzonaEtecsaQuery> =
        fetch(tokens, search("444", null), EnzonaServiceJson::etecsaAccounts)

    // Both ETECSA and electricity list remote references; this never creates a local favorite.
    suspend fun references(tokens: EnzonaTokens, service: EnzonaInvoiceService): EnzonaFetched<List<EnzonaServiceReference>> =
        fetch(tokens, path("client-id", "search").newBuilder()
            .addQueryParameter("service_code", if (service == EnzonaInvoiceService.ELECTRICITY) "2222" else "444").build(),
            EnzonaServiceJson::references)

    suspend fun saveElectricityReference(tokens: EnzonaTokens, bill: EnzonaElectricityBill, fundingSourceUuid: String = ""): EnzonaFetched<EnzonaServiceReference> =
        save(tokens, EnzonaInvoiceService.ELECTRICITY, EnzonaServiceRequests.electricityReference(bill, fundingSourceUuid))

    suspend fun saveEtecsaReference(tokens: EnzonaTokens, account: EnzonaEtecsaAccount, fundingSourceUuid: String, owner: String? = null): EnzonaFetched<EnzonaServiceReference> =
        save(tokens, requireNotNull(account.service), EnzonaServiceRequests.etecsaReference(account, fundingSourceUuid, owner))

    /** Exactly one send. Uncertain outcomes and transport exceptions belong to the coordinator. */
    suspend fun submit(tokens: EnzonaTokens, request: EnzonaServicePaymentRequest): EnzonaServicePaymentResult {
        val reply = execute(enzonaApiRequest(path("invoice", "payment").toString(), tokens)
            .post(request.json.toRequestBody(JSON)).build())
        return EnzonaServiceJson.payment(request.service, reply.status, reply.body)
    }

    private suspend fun save(tokens: EnzonaTokens, service: EnzonaInvoiceService, body: JsonObject): EnzonaFetched<EnzonaServiceReference> {
        val reply = execute(enzonaApiRequest(path("client-id").toString(), tokens).post(body.toString().toRequestBody(JSON)).build())
        checkReply(reply)
        return EnzonaFetched(EnzonaServiceJson.savedReference(service, reply.body), reply.body, now())
    }

    private suspend fun <T> fetch(tokens: EnzonaTokens, url: HttpUrl, decode: (String) -> T): EnzonaFetched<T> {
        val reply = execute(enzonaApiRequest(url.toString(), tokens).get().build())
        checkReply(reply)
        return EnzonaFetched(decode(reply.body), reply.body, now())
    }

    private fun checkReply(reply: EnzonaHttpReply) {
        val fault = EnzonaServiceJson.fault(reply.body)
        if (reply.status != 200 || fault != null) throw EnzonaServiceException(reply.status, fault)
    }

    private fun search(code: String, clientId: String?): HttpUrl = path("invoice", "search").newBuilder().apply {
        addQueryParameter("service_code", code)
        clientId?.let {
            require(it.isNotBlank() && it.none(Char::isISOControl)) { "Identificador no válido" }
            addQueryParameter("client_id", it)
        }
    }.build()

    private fun path(vararg parts: String): HttpUrl = "https://api.enzona.net/invoice/v1.0.0/".toHttpUrl().newBuilder().apply {
        parts.forEach(::addPathSegment)
    }.build()

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
