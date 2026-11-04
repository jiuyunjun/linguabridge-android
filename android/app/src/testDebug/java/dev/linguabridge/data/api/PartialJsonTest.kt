package dev.linguabridge.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class PartialJsonTest {
    @Test fun partialReplyTextDecodesEscapesAndStopsAtEnd() {
        assertEquals("", PartialJson.extract("""{"te""").text)
        assertEquals("承知", PartialJson.extract("""{"text":"承知""").text)
        assertEquals("a\"b\nc", PartialJson.extract("""{"text":"a\"b\nc""").text)
        assertEquals("ab", PartialJson.extract("""{"text":"ab\""").text) // dangling escape held back
        assertEquals("é", PartialJson.extract("""{"text":"\u00e9""").text)
        assertEquals("x", PartialJson.extract("""{"text":"\u00""".replace("{\"text\":\"", "{\"text\":\"x")).text)
        assertEquals("done", PartialJson.extract("""{"text":"done","notes":"n"""").text)
    }

    @Test fun onlyCompletedTranslationItemsAreReturned() {
        val partial = """{"text":"","notes":"","needsClarification":false,"questions":[],"translations":[{"id":"b1","text":"一"},{"id":"b2","text":"二\"引"},{"id":"b3","text":"未完"""
        val result = PartialJson.extract(partial)
        assertEquals("", result.text)
        assertEquals(listOf("b1" to "一", "b2" to "二\"引"), result.translations.map { it.id to it.text })
    }
}
