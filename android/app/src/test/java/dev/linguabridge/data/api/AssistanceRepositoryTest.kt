package dev.linguabridge.data.api

import dev.linguabridge.domain.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AssistanceRepositoryTest {
    private val token = "synthetic-device-token-used-only-in-tests"
    private fun request(operation: AssistOperation = AssistOperation.TRANSLATE) = AssistRequest(requestId = "test-request",
        operation = operation, targetLanguage = "ja", pageType = "email", sourcePackage = "mail.synthetic",
        targetBlockId = if (operation == AssistOperation.TRANSLATE_PAGE) "" else "b1",
        userIntent = if (operation == AssistOperation.REPLY) "Politely decline" else "",
        tone = if (operation == AssistOperation.REPLY) "polite" else "",
        blocks = listOf(ContextBlock("b1", "0/1", NodeBounds(0, 0, 50, 50), "Synthetic mail")))
    private val valid = """{"requestId":"test-request","text":"翻訳","notes":"","needsClarification":false,"questions":[],"translations":[],"model":"fake-model","demo":false}"""
    private val validPage = """{"requestId":"test-request","text":"","notes":"","needsClarification":false,"questions":[],"translations":[{"id":"b1","text":"合成邮件"}],"model":"fake-model","demo":false}"""

    @Test fun onlyHttpsOrExplicitDebugLoopbackIsAllowed() {
        ProxySettings("https://proxy.example", token).validate(false)
        ProxySettings("http://127.0.0.1:8787", token).validate(true)
        for (url in listOf("http://192.168.1.2:8787", "http://proxy.example", "https://api.openai.com", "https://user:secret@proxy.example", "https://proxy.example/v1", "https://proxy.example?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) { ProxySettings(url, token).validate(true) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProxySettings("http://127.0.0.1", token).validate(false) }
        assertThrows(IllegalArgumentException::class.java) { ProxySettings("https://proxy.example", "sk-" + "a".repeat(40)).validate(false) }
    }
    @Test fun bothOperationsUseAuthenticatedProxyAndMatchResponseRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            for (operation in AssistOperation.entries) {
                server.enqueue(MockResponse().setBody(if (operation == AssistOperation.TRANSLATE_PAGE) validPage else valid))
                val result = AssistanceRepository(true).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request(operation))
                assertTrue((result as? AssistOutcome.Error)?.message ?: "expected success", result is AssistOutcome.Success)
                val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/v1/assist", sent.path)
                assertEquals("Bearer $token", sent.getHeader("Authorization"))
                val json = org.json.JSONObject(sent.body.readUtf8())
                assertEquals(operation.name.lowercase(), json.getString("operation"))
                assertFalse(json.has("apiKey"))
                assertEquals(operation == AssistOperation.REPLY, json.optString("tone") == "polite")
                if (operation == AssistOperation.TRANSLATE_PAGE)
                    assertEquals("合成邮件", (result as AssistOutcome.Success).result.translations.single().text)
            }
        }
    }
    @Test fun statusCodesAndMalformedResponsesAreRecoverable() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            for (code in listOf(401, 429, 500, 504)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("PRIVATE_UPSTREAM_BODY"))
                val result = AssistanceRepository(true).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request())
                assertTrue(result is AssistOutcome.Error)
                assertFalse((result as AssistOutcome.Error).message.contains("PRIVATE_UPSTREAM_BODY"))
            }
            for (body in listOf("not JSON", valid.replace("test-request", "stale-request"), valid.replace("false,\"questions\":[]", "true,\"questions\":[]"))) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(AssistanceRepository(true).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request()) is AssistOutcome.Error)
            }
        }
    }
    @Test fun pageTranslationsMustReferenceSentBlocks() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token)
            val cases = listOf(
                AssistOperation.TRANSLATE_PAGE to validPage.replace("\"id\":\"b1\"", "\"id\":\"zz\""),
                AssistOperation.TRANSLATE_PAGE to valid,
                AssistOperation.TRANSLATE to validPage.replace("\"text\":\"\"", "\"text\":\"ok\""),
            )
            for ((operation, body) in cases) {
                server.enqueue(MockResponse().setBody(body))
                assertTrue(body, AssistanceRepository(true).generate(settings, request(operation)) is AssistOutcome.Error)
            }
        }
    }
    @Test fun timeoutAndCancellationDoNotRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
            server.enqueue(MockResponse().setBody(valid).setBodyDelay(900, TimeUnit.MILLISECONDS))
            val result = AssistanceRepository(true, client).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request())
            assertTrue(result is AssistOutcome.Error)
            assertTrue((result as AssistOutcome.Error).message, result.message.contains("超时"))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody(valid).setHeadersDelay(600, TimeUnit.MILLISECONDS))
            val pending = launch { AssistanceRepository(true).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request()) }
            delay(50); pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(server.requestCount <= 2)
        }
    }
    @Test fun invalidInputNeverMakesARequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val result = AssistanceRepository(true).generate(ProxySettings(server.url("/").newBuilder().host("127.0.0.1").build().toString(), token), request().copy(blocks = emptyList()))
            assertTrue(result is AssistOutcome.Error)
            assertEquals(0, server.requestCount)
        }
    }
}
