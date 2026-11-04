package dev.linguabridge.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake HTTP only; the "image" is a few synthetic bytes. */
class ScreenshotTranslationTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    private val itemsJson = """{"notes":"","items":[{"original":"明日15時でいい？","translation":"明天15点可以吗？"},{"original":"OK","translation":"好的"}]}"""

    private fun sse(json: String) = (json.chunked(9).map { JSONObject().put("type", "response.output_text.delta").put("delta", it) } +
        JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("output", JSONArray()
            .put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", json)))))))
        .joinToString("") { "data: $it\n\n" }

    @Test fun imageIsUntrustedInputAndRulesStayInInstructions() = MockWebServer().use { server ->
        server.start()
        val client = OpenAIDirectClient("sk-test-not-real", "vision-model", server.url("/v1/"))
        server.enqueue(MockResponse().setBody(sse(itemsJson)))
        val progress = mutableListOf<Int>()
        val result = runBlocking { client.translateScreenshot(jpeg, "zh", onPartial = { progress += it.size }) }
        val lines = (result as OpenAIDirectClient.ImageOutcome.Success).lines
        assertEquals(listOf("明天15点可以吗？", "好的"), lines.map { it.translation })
        assertEquals(2, progress.last())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(false, body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getString("instructions").contains("untrusted"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getString("image_url").startsWith("data:image/jpeg;base64,/9g"))
        assertTrue(body.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
    }

    @Test fun invalidImageResultsAndInputsAreRejected() = MockWebServer().use { server ->
        server.start()
        val client = OpenAIDirectClient("sk-test-not-real", "vision-model", server.url("/v1/"))
        for (bad in listOf("""{"notes":"","items":[{"original":"a","translation":" "}]}""",
                """{"notes":"","items":[{"original":"a","translation":"b","run":"x"}]}""",
                """{"items":[]}""")) {
            server.enqueue(MockResponse().setBody(sse(bad)))
            assertTrue(bad, runBlocking { client.translateScreenshot(jpeg, "zh", onPartial = {}) } is OpenAIDirectClient.ImageOutcome.Error)
        }
        val before = server.requestCount
        assertTrue(runBlocking { client.translateScreenshot(ByteArray(0), "zh") } is OpenAIDirectClient.ImageOutcome.Error)
        assertTrue(runBlocking { client.translateScreenshot(jpeg, "auto") } is OpenAIDirectClient.ImageOutcome.Error)
        assertEquals(before, server.requestCount)
    }

    @Test fun partialItemsOnlyReturnsCompletedPairs() {
        assertEquals(listOf("a\"x" to "b"), PartialJson.items("""{"notes":"","items":[{"original":"a\"x","translation":"b"},{"original":"c","transl"""))
    }
}
