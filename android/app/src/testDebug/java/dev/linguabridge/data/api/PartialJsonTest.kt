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

    @Test fun completedItemsPlusTheOneBeingWritten() {
        val partial = """{"text":"","notes":"","needsClarification":false,"questions":[],"translations":[{"id":"b1","text":"一"},{"id":"b2","text":"二\"引"},{"id":"b3","text":"未完"""
        val result = PartialJson.extract(partial)
        assertEquals("", result.text)
        assertEquals(listOf("b1" to "一", "b2" to "二\"引", "b3" to "未完"), result.translations.map { it.id to it.text })
        // An item whose id is still being written, or whose text has not started, is not shown.
        assertEquals(2, PartialJson.extract(partial.substringBefore("{\"id\":\"b3") + "{\"id\":\"b").translations.size)
        assertEquals(2, PartialJson.extract(partial.substringBefore("未完")).translations.size)
        assertEquals(listOf("o" to "部分"), PartialJson.items("""{"notes":"","items":[{"original":"o","translation":"部分"""))
    }
}
