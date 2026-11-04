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
        get() = preferences.getString(KEY_TONE, "polite").takeIf { it != null && it in REPLY_TONES && it.isNotEmpty() } ?: "polite"
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

    fun clear() { check(preferences.edit().clear().commit()) }

    companion object {
        const val KEY_TRANSLATE = "translateLanguage"
        const val KEY_REPLY = "replyLanguage"
        const val KEY_TONE = "tone"
        const val KEY_BUBBLE = "bubbleEnabled"
        const val KEY_HIDDEN = "hiddenPackages"
        const val KEY_BUBBLE_Y = "bubbleY"
        val TRANSLATE_LANGUAGES = listOf("zh", "ja", "en")
        val REPLY_LANGUAGES = listOf("auto", "ja", "en", "zh")
        val LANGUAGE_LABELS = mapOf("auto" to "跟随对方", "zh" to "中文", "ja" to "日语", "en" to "英语")
        val TONE_LABELS = linkedMapOf("polite" to "礼貌", "casual" to "随意", "formal" to "正式")
    }
}
