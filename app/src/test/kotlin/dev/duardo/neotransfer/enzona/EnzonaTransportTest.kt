package dev.duardo.neotransfer.enzona

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EnzonaTransportTest {
    private fun withServer(block: (MockWebServer, EnzonaTransport) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory())
            server.start(InetAddress.getLoopbackAddress(), 0)
            val client = enzonaHttpClient().newBuilder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .callTimeout(3, TimeUnit.SECONDS).build()
            EnzonaTransport(client, setOf(server.url("/"))).use { transport -> block(server, transport) }
        }
    }

    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/synthetic-payment"))
        .header("Authorization", "Bearer synthetic-test-only")
        .post("{\"amount\":\"1.00\"}".toRequestBody("application/json".toMediaType())).build()

    @Test fun successfulRequestIsSentOnceOverTls() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().body("{\"reference\":\"SYNTHETIC\"}").build())
        transport.newCall(request(server)).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("{\"reference\":\"SYNTHETIC\"}", response.body.string())
        }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun serverRetryInstructionsDoNotRepeatMoneyRequests() {
        for (code in listOf(401, 408, 421, 503)) withServer { server, transport ->
            server.enqueue(MockResponse.Builder().code(code).addHeader("Retry-After", "0").build())
            server.enqueue(MockResponse.Builder().body("must not be requested").build())
            transport.newCall(request(server)).execute().use { assertEquals(code, it.code) }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS), "HTTP $code caused a second request")
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lostResponseDoesNotCauseASecondSend() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().body("must not be requested").build())
        assertFailsWith<IOException> { transport.newCall(request(server)).execute().close() }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectCannotForwardCredentialsOrRepeatTheRequest() = withServer { server, transport ->
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "/other-path").build())
        server.enqueue(MockResponse.Builder().body("must not be requested").build())
        transport.newCall(request(server)).execute().use { assertEquals(307, it.code) }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
    }

    @Test fun originValidationRejectsLookalikesCleartextAndUserInfoBeforeConnecting() {
        EnzonaTransport().use { transport ->
            for (url in listOf("http://api.enzona.net/", "https://api.enzona.net.evil.example/",
                "https://evil.example/", "https://api.enzona.net:8443/", "https://user@api.enzona.net/",
                "https://api.enzona.net/#secret")) {
                assertFailsWith<IllegalArgumentException> { transport.newCall(Request.Builder().url(url).build()) }
            }
        }
    }

    @Test fun oneShotBodyRejectsReuseEvenOutsideHttpRecovery() {
        val body = SingleSendBody("synthetic".toRequestBody())
        val first = Buffer()
        body.writeTo(first)
        assertEquals("synthetic", first.readUtf8())
        assertFailsWith<IOException> { body.writeTo(Buffer()) }
    }
}
