package dev.duardo.neotransfer.enzona

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.LocalDate

internal class EnzonaFetched<T>(val value: T, val json: String, val observedAt: Long) {
    override fun toString(): String = "EnzonaFetched(redacted)"
}

internal class EnzonaReadApi(private val http: EnzonaHttp, private val now: () -> Long = { Instant.now().toEpochMilli() }) {
    suspend fun cards(tokens: EnzonaTokens): EnzonaFetched<List<EnzonaRemoteCard>> = fetch(
        path("coremz", "1.0.2", "card", "username", identifier(tokens.accountSubject())), tokens, EnzonaReadJson::cards,
    )

    suspend fun balance(tokens: EnzonaTokens, cardUuid: String): EnzonaFetched<EnzonaCardBalance> = fetch(
        path("coremz", "1.0.2", "api", "balance", "lastOperations", identifier(cardUuid)), tokens, EnzonaReadJson::balance,
    )

    suspend fun transactions(
        tokens: EnzonaTokens,
        from: LocalDate,
        through: LocalDate,
        offset: Int = 0,
        limit: Int = 100_000,
    ): EnzonaFetched<EnzonaTransactionPage> {
        require(!from.isAfter(through) && offset >= 0 && limit > 0)
        val url = path("operations", "v1.0.0", "operations", "account").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .addQueryParameter("start_date_filter", from.toString())
            .addQueryParameter("end_date_filter", through.toString()).build()
        return fetch(url, tokens, EnzonaReadJson::transactions)
    }

    /** A provider pagination link may never forward a bearer token to another origin or API. */
    suspend fun transactionPage(tokens: EnzonaTokens, next: String): EnzonaFetched<EnzonaTransactionPage> {
        val base = path("operations", "v1.0.0", "operations", "account")
        val url = requireNotNull(base.resolve(next)) { "La página de actividad no es válida" }
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.encodedPath == base.encodedPath) {
            "La página de actividad no es válida"
        }
        return fetch(url, tokens, EnzonaReadJson::transactions)
    }

    private suspend fun <T> fetch(url: HttpUrl, tokens: EnzonaTokens, decode: (String) -> T): EnzonaFetched<T> {
        val response = http.execute(enzonaApiRequest(url.toString(), tokens).get().build())
        if (response.status != 200) throw EnzonaApiException(response.status)
        return EnzonaFetched(decode(response.body), response.body, now())
    }

    private fun path(vararg segments: String): HttpUrl = "https://api.enzona.net/".toHttpUrl().newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun identifier(value: String): String {
        require(value.isNotBlank() && value !in setOf(".", "..") && value.none(Char::isISOControl)) { "Identificador no válido" }
        return value
    }
}
