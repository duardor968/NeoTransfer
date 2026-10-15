package dev.duardo.neotransfer.enzona

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

internal class EnzonaHttp(private val transport: EnzonaTransport = EnzonaTransport()) : AutoCloseable {
    suspend fun execute(request: Request): EnzonaHttpReply = suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use {
                        // Read on OkHttp's worker, not the presentation thread; retain no HTTP cache.
                        val source = it.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("La respuesta supera el tamaño admitido")
                        EnzonaHttpReply(it.code, source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(reply))
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    override fun close() = transport.close()

    private companion object { const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024 }
}

internal class EnzonaHttpReply(val status: Int, val body: String) {
    override fun toString(): String = "EnzonaHttpReply(status=$status, body=redacted)"
}

internal enum class EnzonaOAuthFailure {
    INVALID_GRANT, INVALID_CLIENT, INVALID_REQUEST, INVALID_SCOPE, UNAUTHORIZED_CLIENT,
    UNSUPPORTED_GRANT_TYPE, ACCESS_DENIED, TEMPORARILY_UNAVAILABLE, SERVER_ERROR, UNKNOWN,
}

internal class EnzonaApiException(val httpStatus: Int, val oauthFailure: EnzonaOAuthFailure? = null) :
    IOException("ENZONA no pudo completar la solicitud")
