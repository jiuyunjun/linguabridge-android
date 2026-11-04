package dev.linguabridge.adapter

import dev.linguabridge.domain.UiSnapshot

/** Deliberately fail closed until version-labelled physical-device fixtures support rules. */
class LineUiAdapter {
    val compatibility = "UNVERIFIED"
    fun parse(@Suppress("UNUSED_PARAMETER") snapshot: UiSnapshot): ParseResult =
        ParseResult.Unverified("缺少真机脱敏 Fixture，未启用单聊和气泡自动识别")
}

sealed interface ParseResult {
    data class Unverified(val reason: String) : ParseResult
}
