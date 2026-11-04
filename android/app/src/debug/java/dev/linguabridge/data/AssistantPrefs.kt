package dev.linguabridge.data

import android.content.Context
import android.content.SharedPreferences
import dev.linguabridge.domain.REPLY_TONES

/** Non-secret floating-assistant preferences shared by the settings screen and the service. */
class AssistantPrefs(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("assistant_prefs", Context.MODE_PRIVATE)

    var translateLanguage: String
        get() = preferences.getString(KEY_TRANSLATE, "zh").takeIf { it in TRANSLATE_LANGUAGES } ?: "zh"
        set(value) { require(value in TRANSLATE_LANGUAGES); preferences.edit().putString(KEY_TRANSLATE, value).apply() }

    var replyLanguage: String
        get() = preferences.getString(KEY_REPLY, "auto").takeIf { it in REPLY_LANGUAGES } ?: "auto"
        set(value) { require(value in REPLY_LANGUAGES); preferences.edit().putString(KEY_REPLY, value).apply() }

    var tone: String
        get() = preferences.getString(KEY_TONE, "casual").takeIf { it != null && it in REPLY_TONES && it.isNotEmpty() } ?: "casual"
        set(value) { require(value in REPLY_TONES && value.isNotEmpty()); preferences.edit().putString(KEY_TONE, value).apply() }

    var bubbleEnabled: Boolean
        get() = preferences.getBoolean(KEY_BUBBLE, true)
        set(value) { preferences.edit().putBoolean(KEY_BUBBLE, value).apply() }

    var hiddenPackages: Set<String>
        get() = preferences.getStringSet(KEY_HIDDEN, emptySet())?.toSet().orEmpty()
        set(value) { preferences.edit().putStringSet(KEY_HIDDEN, value.toSet()).apply() }

    /** Bubble vertical position as a fraction of screen height. */
    var bubbleY: Float
        get() = preferences.getFloat(KEY_BUBBLE_Y, 0.55f).coerceIn(0.05f, 0.9f)
        set(value) { preferences.edit().putFloat(KEY_BUBBLE_Y, value.coerceIn(0.05f, 0.9f)).apply() }

    /** Screen edge the bubble docks to: "left" or "right". */
    var bubbleSide: String
        get() = if (preferences.getString(KEY_BUBBLE_SIDE, "right") == "left") "left" else "right"
        set(value) { preferences.edit().putString(KEY_BUBBLE_SIDE, if (value == "left") "left" else "right").apply() }

    /** Model chosen in Settings for debug direct mode; blank = openai.properties default. */
    var openAiModel: String
        get() = preferences.getString(KEY_MODEL, "").orEmpty()
        set(value) { require(value.length <= 200); preferences.edit().putString(KEY_MODEL, value.trim()).apply() }

    /** What a bubble tap opens: "last" (default), "translate" or "reply". */
    var openWith: String
        get() = preferences.getString(KEY_OPEN_WITH, "last").takeIf { it in OPEN_WITH.keys } ?: "last"
        set(value) { require(value in OPEN_WITH.keys); preferences.edit().putString(KEY_OPEN_WITH, value).apply() }

    var lastTab: String
        get() = preferences.getString(KEY_LAST_TAB, "translate").takeIf { it == "translate" || it == "reply" } ?: "translate"
        set(value) { require(value == "translate" || value == "reply"); preferences.edit().putString(KEY_LAST_TAB, value).apply() }

    /** Resolves [openWith] to the tab a bubble tap should show. */
    val initialTab: String get() = if (openWith == "last") lastTab else openWith

    fun clear() { check(preferences.edit().clear().commit()) }

    companion object {
        const val KEY_TRANSLATE = "translateLanguage"
        const val KEY_REPLY = "replyLanguage"
        const val KEY_TONE = "tone"
        const val KEY_BUBBLE = "bubbleEnabled"
        const val KEY_HIDDEN = "hiddenPackages"
        const val KEY_BUBBLE_Y = "bubbleY"
        const val KEY_MODEL = "openAiModel"
        const val KEY_BUBBLE_SIDE = "bubbleSide"
        const val KEY_OPEN_WITH = "openWith"
        const val KEY_LAST_TAB = "lastTab"
        val OPEN_WITH = linkedMapOf("last" to "上次使用", "translate" to "翻译", "reply" to "回复")
        val TRANSLATE_LANGUAGES = listOf("zh", "ja", "en")
        val REPLY_LANGUAGES = listOf("auto", "ja", "en", "zh")
        val LANGUAGE_LABELS = mapOf("auto" to "跟随对方", "zh" to "中文", "ja" to "日语", "en" to "英语")
        val TONE_LABELS = linkedMapOf("formal" to "礼貌", "casual" to "平常", "snarky" to "阴阳", "rude" to "粗鲁")
    }
}
