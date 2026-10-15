package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant

/** Requests traced in ENZONA 1.11.0; server acceptance remains a separate device check. */
internal class EnzonaSessionApi(
    private val http: EnzonaHttp,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens =
        token(exchange.tokenRequest, null)

    suspend fun refresh(previous: EnzonaTokens): EnzonaTokens = token(
        EnzonaAuthContract.refreshTokenRequest(requireNotNull(previous.refreshToken) { "Vuelve a conectar ENZONA" }), previous,
    )

    private suspend fun token(form: EnzonaAuthContract.FormRequest, previous: EnzonaTokens?): EnzonaTokens {
        val request = Request.Builder().url(form.endpoint.toString())
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Credentials", "true")
            .header("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS")
            .header("Access-Control-Allow-Headers", "X-Requested-With, Origin, Content-Type, X-Auth-Token")
            .post(form.body.toRequestBody(form.contentType.toMediaType())).build()
        val response = http.execute(request)
        if (response.status != 200) throw EnzonaApiException(response.status, oauthFailure(response.body))
        return EnzonaTokens.fromResponse(response.body, nowEpochSeconds(), previous)
    }

    suspend fun account(tokens: EnzonaTokens): JSONObject {
        val url = "https://api.enzona.net/account/v1.0.0/account".toHttpUrl().newBuilder()
            .addQueryParameter("username", tokens.accountSubject()).build()
        val response = http.execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (response.status != 200) throw EnzonaApiException(response.status)
        return try {
            val body = JSONObject(response.body)
            require(!body.has("fault") && !body.has("error"))
            requireNotNull(body.optJSONObject("account")).also { require(it.length() > 0) }
        } catch (_: Exception) {
            // Android's JSON diagnostics may quote private response contents.
            throw IllegalArgumentException("La respuesta de la cuenta no es válida")
        }
    }

    suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) {
        // The traced client wraps Dart Map.toString(), not a nested JSON device object.
        val deviceText = fields.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${it.value}" }
        val body = JSONObject().put("data", deviceText).toString()
        val request = enzonaApiRequest("https://api.enzona.net/account/v1.0.0/account/authentication", tokens)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val response = http.execute(request)
        if (response.status != 200) throw EnzonaApiException(response.status)
    }

    private fun oauthFailure(body: String): EnzonaOAuthFailure = try {
        val value = JSONObject(body).opt("error") as? String
        EnzonaOAuthFailure.entries.firstOrNull { it.name.lowercase(java.util.Locale.ROOT) == value }
            ?: EnzonaOAuthFailure.UNKNOWN
    } catch (_: Exception) { EnzonaOAuthFailure.UNKNOWN }
}

internal fun enzonaApiRequest(url: String, tokens: EnzonaTokens): Request.Builder = Request.Builder().url(url)
    .header("Authorization", "Bearer ${tokens.accessToken}")
    .header("accept", "application/json")
    .header("Version", "1.9.11.241103")
