package dev.linguabridge.data.api

import dev.linguabridge.domain.BlockTranslation

/** What can be shown while the structured JSON is still streaming. Display-only; never validated. */
data class PartialResult(val text: String, val translations: List<BlockTranslation>)

/**
 * Extracts display progress from an incomplete JSON object that follows the schema key order
 * (text, notes, needsClarification, questions, translations). The final result is always
 * re-validated with [parseAssistResult] once the stream completes.
 */
object PartialJson {
    /** A complete JSON string literal; group 1 is its raw (still escaped) body. */
    private const val STR = """"((?:[^"\\]|\\.)*)""""
    private val translationItem = Regex("""\{\s*"id"\s*:\s*$STR\s*,\s*"text"\s*:\s*$STR\s*\}""")
    private val translationOpen = Regex("""\{\s*"id"\s*:\s*$STR\s*,\s*"text"\s*:\s*"""")
    private val imageItem = Regex("""\{\s*"original"\s*:\s*$STR\s*,\s*"translation"\s*:\s*$STR\s*\}""")
    private val imageOpen = Regex("""\{\s*"original"\s*:\s*$STR\s*,\s*"translation"\s*:\s*"""")

    private fun decode(raw: String) = readString(raw + "\"", 0)

    /**
     * Completed items plus the item still being written (its second string is partial), so the
     * UI can type it out live. The open item is only returned once its first string is complete.
     */
    private fun pairs(buffer: String, from: Int, complete: Regex, open: Regex): List<Pair<String, String>> {
        val done = complete.findAll(buffer, from).toList()
        val result = done.map { decode(it.groupValues[1]) to decode(it.groupValues[2]) }.toMutableList()
        val tail = done.lastOrNull()?.range?.last?.plus(1) ?: from
        open.find(buffer, tail)?.let { m ->
            val partial = readString(buffer, m.range.last + 1)
            if (partial.isNotEmpty()) result += decode(m.groupValues[1]) to partial
        }
        return result
    }

    /** {original, translation} items of a streaming screenshot translation (last one may be partial). */
    fun items(buffer: String): List<Pair<String, String>> = pairs(buffer, 0, imageItem, imageOpen)

    fun extract(buffer: String): PartialResult {
        val text = Regex(""""text"\s*:\s*"""").find(buffer)?.let { readString(buffer, it.range.last + 1) }.orEmpty()
        val start = buffer.indexOf("\"translations\"")
        val translations = if (start < 0) emptyList()
            else pairs(buffer, start, translationItem, translationOpen).map { (id, t) -> BlockTranslation(id, t) }
        return PartialResult(text, translations)
    }

    /** Decodes a JSON string body from [from] up to the closing quote or the end of input. */
    fun readString(source: String, from: Int): String {
        val out = StringBuilder()
        var i = from
        while (i < source.length) {
            val c = source[i]
            when {
                c == '"' -> return out.toString()
                c != '\\' -> { out.append(c); i++ }
                i + 1 >= source.length -> return out.toString() // dangling escape: wait for more
                else -> {
                    when (val e = source[i + 1]) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 6 > source.length) return out.toString()
                            val code = source.substring(i + 2, i + 6).toIntOrNull(16) ?: return out.toString()
                            out.append(code.toChar()); i += 4
                        }
                        else -> out.append(e) // \" \\ \/
                    }
                    i += 2
                }
            }
        }
        return out.toString()
    }
}
