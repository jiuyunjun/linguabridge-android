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
    private val translationItem = Regex("""\{\s*"id"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")

    fun extract(buffer: String): PartialResult {
        val text = Regex(""""text"\s*:\s*"""").find(buffer)?.let { readString(buffer, it.range.last + 1) }.orEmpty()
        val start = buffer.indexOf("\"translations\"")
        val translations = if (start < 0) emptyList() else translationItem.findAll(buffer, start).map {
            BlockTranslation(readString(it.groupValues[1] + "\"", 0), readString(it.groupValues[2] + "\"", 0))
        }.toList()
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
