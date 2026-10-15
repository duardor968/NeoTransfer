package dev.duardo.neotransfer.enzona

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Session renewal and financial recovery belong to the caller, never to HTTP retries. */
internal fun enzonaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

internal class EnzonaTransport(
    private val client: OkHttpClient = enzonaHttpClient(),
    private val origins: Set<HttpUrl> = setOf(
        "https://api.enzona.net/".toHttpUrl(),
        "https://identity.enzona.net/".toHttpUrl(),
    ),
) : AutoCloseable {
    init {
        require(!client.retryOnConnectionFailure && !client.followRedirects && !client.followSslRedirects)
        require(client.authenticator === Authenticator.NONE && client.proxyAuthenticator === Authenticator.NONE)
        require(origins.isNotEmpty() && origins.all { it.isHttps && it.username.isEmpty() && it.password.isEmpty() })
    }

    fun newCall(request: Request): Call {
        val url = request.url
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            origins.any { it.scheme == url.scheme && it.host == url.host && it.port == url.port }) {
            "Destino de conexión no permitido"
        }
        val body = request.body
        val protectedRequest = if (body == null || body is SingleSendBody) request else
            request.newBuilder().method(request.method, SingleSendBody(body)).build()
        return client.newCall(protectedRequest)
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Also prevents server-driven follow-ups such as HTTP 503 + Retry-After: 0. */
internal class SingleSendBody(private val delegate: RequestBody) : RequestBody() {
    private val written = AtomicBoolean(false)
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
        if (!written.compareAndSet(false, true)) throw IOException("La solicitud ya se ha enviado")
        delegate.writeTo(sink)
    }
}
