package dev.linguabridge.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.linguabridge.data.AssistantPrefs
import kotlin.math.abs

/** One translated line shown in the panel. */
data class TranslatedLine(val original: String, val translation: String)

/**
 * Accessibility-overlay bubble and bottom panel (TYPE_ACCESSIBILITY_OVERLAY; no
 * SYSTEM_ALERT_WINDOW permission). Pure view layer: it never reads other apps' windows.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class FloatingAssistant(private val context: Context, private val prefs: AssistantPrefs, private val listener: Listener) {
    interface Listener {
        fun onBubbleTap()
        fun onBubbleHide()
        fun onGenerateReply(intent: String, tone: String)
        fun onCopy(text: String)
        fun onFill(text: String)
        fun onRetry()
        fun onPanelClosed()
    }

    private enum class Tab { TRANSLATE, REPLY }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var tab = Tab.TRANSLATE
    private var translateTab: TextView? = null
    private var replyTab: TextView? = null
    private var status: TextView? = null
    private var content: LinearLayout? = null
    private var footer: LinearLayout? = null
    private var intentInput: EditText? = null
    private var translatedText = ""
    private var replyText = ""
    private var translateView: (() -> Unit)? = null
    private var replyView: (() -> Unit)? = null

    val panelOpen get() = panel != null
    val bubbleShown get() = bubble != null

    // ---------- bubble ----------

    fun showBubble() {
        if (bubble != null) return
        val size = dp(52)
        val view = TextView(context).apply {
            text = "译"; gravity = Gravity.CENTER; setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BG); setStroke(dp(2), TEXT) }
            elevation = dp(6).toFloat()
            contentDescription = "上下文助手：翻译或回复"
        }
        val metrics = context.resources.displayMetrics
        val params = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8); y = (metrics.heightPixels * prefs.bubbleY).toInt()
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f; var startY = 0; var moved = false
        val longPress = Runnable { if (!moved) listener.onBubbleHide() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; startY = params.y; moved = false; v.postDelayed(longPress, 600) }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - downY
                    if (abs(delta) > slop) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) {
                        params.y = (startY + delta).toInt().coerceIn(0, metrics.heightPixels - size)
                        windows.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) prefs.bubbleY = params.y.toFloat() / metrics.heightPixels
                    else if (event.eventTime - event.downTime < 600) listener.onBubbleTap()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }
        windows.addView(view, params)
        bubble = view; bubbleParams = params
    }

    fun hideBubble() {
        bubble?.let { runCatching { windows.removeView(it) } }
        bubble = null; bubbleParams = null
    }

    fun setBubbleBusy(busy: Boolean) { bubble?.text = if (busy) "…" else "译" }

    // ---------- panel ----------

    fun openPanel() {
        if (panel != null) return
        tab = Tab.TRANSLATE
        translatedText = ""; replyText = ""
        val scrim = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closePanel(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closePanel() }
        }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG); val r = dp(22).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            isClickable = true // swallow taps so they don't reach the scrim
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        sheet.addView(View(context).apply {
            background = rounded(LINE_STRONG, dp(2))
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(CARD, dp(12), LINE); setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        translateTab = tabButton("翻译") { selectTab(Tab.TRANSLATE) }
        replyTab = tabButton("回复") { selectTab(Tab.REPLY) }
        tabs.addView(translateTab, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(replyTab, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(tabs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            contentDescription = "关闭面板"; setOnClickListener { closePanel() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheet.addView(header)

        status = label("", 13f, MUTED).apply { setPadding(0, dp(10), 0, dp(10)) }
        sheet.addView(status)

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(content); isFillViewport = false }
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        sheet.addView(object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        sheet.addView(footer)

        scrim.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = true).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        windows.addView(scrim, params)
        panel = scrim
        renderTabs()
    }

    fun closePanel() {
        val view = panel ?: return
        intentInput?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        runCatching { windows.removeView(view) }
        panel = null; content = null; footer = null; status = null; intentInput = null
        translatedText = ""; replyText = ""; translateView = null; replyView = null
        listener.onPanelClosed()
    }

    private fun selectTab(selected: Tab) { tab = selected; renderTabs() }

    private fun renderTabs() {
        styleTab(translateTab, tab == Tab.TRANSLATE)
        styleTab(replyTab, tab == Tab.REPLY)
        if (tab == Tab.TRANSLATE) (translateView ?: { showStatus("正在读取当前屏幕…"); clear() })() else (replyView ?: ::renderReplyForm)()
    }

    // ---------- translate tab ----------

    fun showWorking(message: String) {
        translateView = { showStatus(message); clear() }
        if (tab == Tab.TRANSLATE) translateView!!()
    }

    fun showTranslations(lines: List<TranslatedLine>, demo: Boolean) {
        translatedText = lines.joinToString("\n") { it.translation }
        translateView = {
            showStatus(if (demo) "固定模拟结果 · 未调用 OpenAI" else "已翻译当前屏幕 · ${lines.size} 段", if (demo) WARN else OK)
            clear()
            lines.forEach { line ->
                content?.addView(card().apply {
                    addView(label(line.original, 13f, MUTED))
                    addView(label(line.translation, 15f, TEXT).apply { setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) })
                }, cardParams())
            }
            footerButtons(secondary("复制译文") { listener.onCopy(translatedText) }, primary("回复对方") { selectTab(Tab.REPLY) })
        }
        if (tab == Tab.TRANSLATE) translateView!!()
    }

    fun showError(message: String, retry: Boolean) {
        val view = {
            showStatus(message, ERROR); clear()
            if (retry) footerButtons(secondary("关闭") { closePanel() }, primary("重试") { listener.onRetry() })
            else footerButtons(secondary("关闭") { closePanel() })
        }
        if (tab == Tab.TRANSLATE) { translateView = view; view() } else { replyView = { renderReplyForm(); showStatus(message, ERROR) }; replyView!!() }
    }

    // ---------- reply tab ----------

    private fun renderReplyForm() {
        val language = AssistantPrefs.LANGUAGE_LABELS[prefs.replyLanguage]
        showStatus("写下你想说的话（中文就行），会结合屏幕上的对话生成 · → $language")
        clear()
        val input = (intentInput ?: EditText(context).apply {
            hint = "例如：可以，资料收到后我会先看一遍"
            setHintTextColor(MUTED); setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(CARD, dp(12), ACCENT); setPadding(dp(12), dp(10), dp(12), dp(10))
            minLines = 2; maxLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }).also { intentInput = it }
        (input.parent as? ViewGroup)?.removeView(input)
        content?.addView(input, cardParams())
        val tones = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        AssistantPrefs.TONE_LABELS.forEach { (id, name) ->
            tones.addView(chip(name, prefs.tone == id) { prefs.tone = id; renderReplyForm() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { rightMargin = dp(8) })
        }
        content?.addView(tones, cardParams())
        if (replyText.isNotEmpty()) addReplyCard(replyText, replyNotes)
        footerButtons(
            secondary(if (replyText.isEmpty()) "关闭" else "复制") { if (replyText.isEmpty()) closePanel() else listener.onCopy(replyText) },
            primary(if (replyText.isEmpty()) "生成回复" else "填入输入框") {
                if (replyText.isEmpty()) generate() else listener.onFill(replyText)
            },
        )
        if (replyText.isNotEmpty()) footer?.addView(secondary("重写") { generate() }, 0, buttonParams(0.8f))
    }

    private var replyNotes = ""

    private fun generate() {
        val text = intentInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { showStatus("先写一句你想表达的意思", WARN); return }
        listener.onGenerateReply(text, prefs.tone)
    }

    fun showReplyWorking() {
        replyView = { renderReplyForm(); showStatus("正在生成回复…") }
        if (tab == Tab.REPLY) replyView!!()
    }

    fun showReply(text: String, notes: String, questions: List<String>, demo: Boolean) {
        replyText = text
        replyNotes = (listOf(notes) + questions.map { "• $it" }).filter { it.isNotBlank() }.joinToString("\n")
        replyView = {
            renderReplyForm()
            if (demo) showStatus("固定模拟结果 · 未调用 OpenAI", WARN)
            else if (questions.isNotEmpty()) showStatus("需要你补充信息，草稿仅供参考", WARN)
            else showStatus("已生成 · 不会自动发送", OK)
        }
        if (tab == Tab.REPLY) replyView!!()
    }

    private fun addReplyCard(text: String, notes: String) {
        content?.addView(card(ACCENT_DIM).apply {
            addView(label("回复草稿", 12f, ACCENT_SOFT))
            addView(label(text, 16f, TEXT).apply { setPadding(0, dp(6), 0, 0); setTextIsSelectable(true) })
            if (notes.isNotBlank()) addView(label(notes, 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }, cardParams())
    }

    fun showStatus(message: String, color: Int = MUTED) { status?.text = message; status?.setTextColor(color) }

    // ---------- helpers ----------

    private fun clear() { content?.removeAllViews(); footer?.removeAllViews() }

    private fun footerButtons(vararg buttons: TextView) {
        footer?.removeAllViews()
        buttons.forEachIndexed { index, button -> footer?.addView(button, buttonParams(if (index == buttons.lastIndex && buttons.size > 1) 1.4f else 1f)) }
    }

    private fun buttonParams(weight: Float) = LinearLayout.LayoutParams(0, dp(48), weight).apply { marginStart = dp(5); marginEnd = dp(5) }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT,
    )

    private fun tabButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setOnClickListener { onClick() }
    }

    private fun styleTab(view: TextView?, selected: Boolean) {
        view ?: return
        view.background = if (selected) rounded(ACCENT, dp(9)) else null
        view.setTextColor(if (selected) ON_ACCENT else MUTED)
        view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (selected) ACCENT else TEXT)
        background = rounded(if (selected) ACCENT_DIM else 0, dp(18), if (selected) ACCENT else LINE)
        setOnClickListener { onClick() }
    }

    private fun primary(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; setTextColor(ON_ACCENT); typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); background = rounded(ACCENT, dp(12)); setOnClickListener { onClick() }
    }

    private fun secondary(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); background = rounded(0, dp(12), LINE); setOnClickListener { onClick() }
    }

    private fun card(fill: Int = CARD) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(fill, dp(14), LINE); setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }

    private fun label(text: String, sp: Float, color: Int) = TextView(context).apply {
        this.text = text; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setLineSpacing(0f, 1.15f)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun destroy() { closePanel(); hideBubble() }

    private companion object {
        // Palette shared with the translator extension's dark theme.
        const val BG = 0xFF0F1B33.toInt()
        const val CARD = 0xFF172440.toInt()
        const val LINE = 0xFF2C3A57.toInt()
        const val LINE_STRONG = 0xFF3A4A6A.toInt()
        const val TEXT = 0xFFEAF2FF.toInt()
        const val MUTED = 0xFFA8B7D4.toInt()
        const val ACCENT = 0xFF5ED0FF.toInt()
        const val ACCENT_DIM = 0xFF19304D.toInt()
        const val ACCENT_SOFT = 0xFFA8D8F0.toInt()
        const val ON_ACCENT = 0xFF08233A.toInt()
        const val OK = 0xFF67E3A1.toInt()
        const val WARN = 0xFFFFD36B.toInt()
        const val ERROR = 0xFFFF8B8B.toInt()
    }
}
