package dev.linguabridge.accessibility

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.linguabridge.R
import dev.linguabridge.data.AssistantPrefs
import kotlin.math.abs

/** One translated line shown in the panel. */
data class TranslatedLine(val original: String, val translation: String)

/**
 * Accessibility-overlay bubble and panel (TYPE_ACCESSIBILITY_OVERLAY; no SYSTEM_ALERT_WINDOW).
 * Visual language follows the LinguaBridge extension's dark glass panel. Pure view layer: it
 * never reads other apps' windows.
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
        /** The translate tab became visible and has nothing yet: translation is requested lazily. */
        fun onTranslateNeeded()
        fun onScreenshotTranslate()
    }

    enum class Tab { TRANSLATE, REPLY }

    private val windows = context.getSystemService(WindowManager::class.java)
    private val metrics get() = context.resources.displayMetrics

    // ---------- bubble: small brand icon that docks half-hidden at the edge when idle ----------

    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dockAnimator: ValueAnimator? = null
    private val bubbleSize get() = dp(40)
    private val dockRunnable = Runnable { dock(animated = true) }

    val panelOpen get() = panel != null
    val bubbleShown get() = bubble != null

    fun showBubble() {
        if (bubble != null) return
        val size = bubbleSize
        val view = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "LinguaBridge：翻译或回复"
            elevation = dp(3).toFloat()
        }
        val params = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            y = (metrics.heightPixels * prefs.bubbleY).toInt()
        }
        bubbleParams = params
        params.x = dockedX()
        view.alpha = IDLE_ALPHA
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val longPress = Runnable { if (!moved) listener.onBubbleHide() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockAnimator?.cancel(); v.removeCallbacks(dockRunnable)
                    v.alpha = 1f
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false
                    v.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceIn(-size / 2, metrics.widthPixels - size / 2)
                        params.y = (startY + dy).toInt().coerceIn(0, metrics.heightPixels - size)
                        windows.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        prefs.bubbleSide = if (event.rawX < metrics.widthPixels / 2f) "left" else "right"
                        prefs.bubbleY = params.y.toFloat() / metrics.heightPixels
                        dock(animated = true)
                    } else {
                        if (event.eventTime - event.downTime < 600) listener.onBubbleTap()
                        scheduleDock()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); scheduleDock() }
            }
            true
        }
        windows.addView(view, params)
        bubble = view
    }

    /** Docked: half the icon tucked behind the screen edge, semi-transparent. */
    private fun dockedX() = if (prefs.bubbleSide == "left") -bubbleSize / 2 else metrics.widthPixels - bubbleSize / 2

    private fun scheduleDock() { bubble?.let { it.removeCallbacks(dockRunnable); it.postDelayed(dockRunnable, 1800) } }

    private fun dock(animated: Boolean) {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val target = dockedX()
        dockAnimator?.cancel()
        if (!animated) { params.x = target; windows.updateViewLayout(view, params); view.alpha = IDLE_ALPHA; return }
        dockAnimator = ValueAnimator.ofInt(params.x, target).setDuration(220).apply {
            addUpdateListener {
                if (bubble === view) { params.x = it.animatedValue as Int; runCatching { windows.updateViewLayout(view, params) } }
            }
            start()
        }
        view.animate().alpha(IDLE_ALPHA).setDuration(220).start()
    }

    fun hideBubble() {
        dockAnimator?.cancel()
        bubble?.let { it.removeCallbacks(dockRunnable); runCatching { windows.removeView(it) } }
        bubble = null; bubbleParams = null
    }

    fun setBubbleBusy(busy: Boolean) {
        bubble?.let { it.alpha = if (busy) 0.35f else IDLE_ALPHA }
    }

    // ---------- panel ----------

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelDetached = false
    private var tab = Tab.TRANSLATE
    private var translateTab: TextView? = null
    private var replyTab: TextView? = null
    private var status: TextView? = null
    private var content: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var sheetView: View? = null
    private var footer: LinearLayout? = null
    private var intentInput: EditText? = null
    private var translatedText = ""
    private var replyText = ""
    private var replyNotes = ""
    private var translateView: (() -> Unit)? = null
    private var replyView: (() -> Unit)? = null
    private var contextBlocks = 0

    /** Context replaced while the panel is open (screenshot translation): the reply tab re-renders with it. */
    fun setContextBlocks(blocks: Int) {
        contextBlocks = blocks
        replyView = null
    }

    /** Opens on [initial]; nothing is uploaded until a tab asks for it. */
    fun openPanel(initial: Tab, blocks: Int) {
        if (panel != null) return
        tab = initial
        contextBlocks = blocks
        translatedText = ""; replyText = ""; replyNotes = ""
        val scrim = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closePanel(); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(SCRIM)
            setOnClickListener { closePanel() }
        }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = glassBackground()
            isClickable = true // swallow taps so they don't reach the scrim
            clipToOutline = true
            elevation = dp(12).toFloat()
        }

        // Header: brand + tabs + close, like the extension's .cnjp-panel-head
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8)); background = fill(HEAD)
        }
        header.addView(label("LinguaBridge", 14f, TEXT).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        translateTab = button("翻译") { selectTab(Tab.TRANSLATE) }
        replyTab = button("回复") { selectTab(Tab.REPLY) }
        header.addView(translateTab, smallParams())
        header.addView(replyTab, smallParams())
        header.addView(button("关闭") { closePanel() }, smallParams())
        sheet.addView(header)
        sheet.addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        status = label("", 12f, MUTED).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = fill(STATUS_BG) }
        sheet.addView(status)

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), 0) }
        val scroll = ScrollView(context).apply {
            addView(content)
            // A manual scroll stops auto-follow for the rest of this stream.
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_MOVE) followStream = false; false }
        }
        scrollView = scroll
        val maxHeight = (metrics.heightPixels * 0.55f).toInt()
        sheet.addView(object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(scroll) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        sheet.addView(footer)

        scrim.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = true).apply {
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        }
        windows.addView(scrim, params)
        panel = scrim; panelParams = params; panelDetached = false; sheetView = sheet
        renderTabs()
        // Slide up from the bottom edge, scrim fades in.
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        sheet.translationY = metrics.heightPixels * 0.4f
        sheet.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Temporarily removes the panel window (e.g. for a clean screenshot) without losing its state. */
    fun detachPanel() {
        val view = panel ?: return
        if (panelDetached) return
        intentInput?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        runCatching { windows.removeView(view) }
        panelDetached = true
    }

    fun reattachPanel() {
        val view = panel ?: return
        if (!panelDetached) return
        runCatching { windows.addView(view, panelParams) }
        panelDetached = false
    }

    fun closePanel() {
        val view = panel ?: return
        intentInput?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        val sheet = sheetView
        if (!panelDetached) {
            // Slide down, then remove the window. State is reset immediately so a new panel can open.
            view.isEnabled = false
            view.animate().alpha(0f).setDuration(160).start()
            (sheet ?: view).animate().translationY(metrics.heightPixels * 0.3f).setDuration(160)
                .setInterpolator(AccelerateInterpolator()).withEndAction { runCatching { windows.removeView(view) } }.start()
        }
        panel = null; panelParams = null; panelDetached = false; sheetView = null; scrollView = null
        content = null; footer = null; status = null; intentInput = null; streamTextView = null; lineViews.clear()
        translatedText = ""; replyText = ""; replyNotes = ""; translateView = null; replyView = null
        listener.onPanelClosed()
    }

    private fun selectTab(selected: Tab) {
        tab = selected
        prefs.lastTab = if (selected == Tab.REPLY) "reply" else "translate"
        renderTabs()
    }

    private fun renderTabs() {
        styleButton(translateTab, tab == Tab.TRANSLATE)
        styleButton(replyTab, tab == Tab.REPLY)
        if (tab == Tab.TRANSLATE) {
            val view = translateView
            if (view != null) view() else { showStatus("正在翻译当前屏幕…", ACCENT); clear(); listener.onTranslateNeeded() }
        } else {
            (replyView ?: { renderReplyForm() })()
            if (replyText.isEmpty()) focusIntent()
        }
    }

    private fun focusIntent() {
        val input = intentInput ?: return
        input.post {
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------- translate tab ----------

    /** Translation TextViews of the cards on screen, in order (updated in place while streaming). */
    private val lineViews = mutableListOf<TextView>()

    fun showWorking(message: String) {
        lineViews.clear(); followStream = true
        translateView = { showStatus(message, ACCENT); clear() }
        if (tab == Tab.TRANSLATE) translateView!!()
    }

    private fun addLineCard(line: TranslatedLine, animate: Boolean = false): TextView {
        val translation = label(line.translation, 15f, TEXT).apply { setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) }
        val card = block().apply { addView(label(line.original, 13f, MUTED)); addView(translation) }
        content?.addView(card, blockParams())
        if (animate) appear(card)
        return translation
    }

    /** Static render used when the tab is (re)shown; registers the cards so streaming can continue in place. */
    private fun renderLines(lines: List<TranslatedLine>) {
        lineViews.clear(); content?.removeAllViews()
        lines.forEach { lineViews += addLineCard(it) }
    }

    /** New cards fade in with an empty translation that types out; existing cards keep typing toward the new text. */
    private fun streamLines(lines: List<TranslatedLine>) {
        while (lineViews.size > lines.size) {
            val stale = lineViews.removeAt(lineViews.lastIndex)
            (stale.parent as? View)?.let { card -> content?.removeView(card) }
        }
        lines.forEachIndexed { i, line ->
            val view = lineViews.getOrNull(i) ?: addLineCard(line.copy(translation = ""), animate = true).also { lineViews += it }
            typewriter(view).update(line.translation)
        }
        followScroll()
    }

    /** Streaming: translations so far (the last one may still be growing). */
    fun showTranslationProgress(lines: List<TranslatedLine>) {
        val text = "翻译中… ${lines.size} 段"
        translateView = { showStatus(text, ACCENT); renderLines(lines); footerButtons(button("关闭") { closePanel() }) }
        if (tab != Tab.TRANSLATE) return
        showStatus(text, ACCENT)
        if (lineViews.isEmpty()) { clear(); footerButtons(button("关闭") { closePanel() }) }
        streamLines(lines)
    }

    fun showTranslations(lines: List<TranslatedLine>, demo: Boolean, fromScreenshot: Boolean = false) {
        translatedText = lines.joinToString("\n") { it.translation }
        val done = {
            showStatus(when {
                demo -> "固定模拟结果 · 未调用 OpenAI"
                fromScreenshot -> "截图翻译完成 · ${lines.size} 段"
                else -> "翻译完成 · ${lines.size} 段"
            }, if (demo) WARN else OK)
            footerButtons(
                button("截图翻译") { listener.onScreenshotTranslate() },
                button("复制") { listener.onCopy(translatedText) },
                button("回复", primary = true) { selectTab(Tab.REPLY) },
            )
        }
        translateView = { renderLines(lines); done() }
        if (tab != Tab.TRANSLATE) return
        if (lineViews.isEmpty()) clear() // nothing streamed (proxy mode): cards still animate in
        streamLines(lines)
        done()
    }

    /** [screenshot] offers screenshot translation, e.g. when the app exposes no text. */
    fun showError(message: String, retry: Boolean, screenshot: Boolean = false) {
        val view = {
            lineViews.clear()
            showStatus(message, ERROR); clear()
            val buttons = mutableListOf(button("关闭") { closePanel() })
            if (screenshot) buttons += button("截图翻译", primary = !retry) { listener.onScreenshotTranslate() }
            if (retry) buttons += button("重试", primary = true) { listener.onRetry() }
            footerButtons(*buttons.toTypedArray())
        }
        if (tab == Tab.TRANSLATE) { translateView = view; view() } else { replyView = { renderReplyForm(); showStatus(message, ERROR) }; replyView!!() }
    }

    // ---------- reply tab ----------

    private fun renderReplyForm(animateDraft: Boolean = false) {
        streamTextView = null
        val language = AssistantPrefs.LANGUAGE_LABELS[prefs.replyLanguage]
        showStatus(if (contextBlocks > 0) "已读取屏幕 $contextBlocks 段作为上下文 · → $language" else "没有屏幕上下文 · → $language")
        clear()
        val input = (intentInput ?: EditText(context).apply {
            hint = "你想说什么（中文就行）"
            setHintTextColor(MUTED); setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(BLOCK, dp(12), LINE); setPadding(dp(10), dp(8), dp(10), dp(8))
            minLines = 2; maxLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }).also { intentInput = it }
        (input.parent as? ViewGroup)?.removeView(input)
        content?.addView(input, blockParams())
        val tones = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tones.addView(label("语气", 11f, MUTED), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        AssistantPrefs.TONE_LABELS.forEach { (id, name) ->
            tones.addView(button(name) { prefs.tone = id; renderReplyForm() }.also { styleButton(it, prefs.tone == id) }, smallParams())
        }
        content?.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tones) }, blockParams())
        if (replyText.isNotEmpty()) {
            val body = addDraftCard(if (animateDraft) "" else replyText, animateDraft)
            if (animateDraft) typewriter(body).update(replyText)
            addNotes(body, animateDraft)
        }
        replyFooter()
    }

    private fun replyFooter() {
        val buttons = mutableListOf<TextView>()
        if (replyText.isNotEmpty()) {
            buttons += button("重写") { generate() }
            buttons += button("复制") { listener.onCopy(replyText) }
            buttons += button("填入输入框", primary = true) { listener.onFill(replyText) }
        } else buttons += button("生成回复", primary = true) { generate() }
        footerButtons(*buttons.toTypedArray())
    }

    private fun generate() {
        val text = intentInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { showStatus("先写一句你想表达的意思", WARN); return }
        listener.onGenerateReply(text, prefs.tone)
    }

    fun showReplyWorking() {
        replyText = ""; replyNotes = ""; followStream = true
        replyView = { renderReplyForm(); showStatus("正在生成回复…", ACCENT) }
        if (tab == Tab.REPLY) replyView!!()
    }

    private var streamTextView: TextView? = null

    private fun addDraftCard(text: String, animate: Boolean): TextView {
        val body = label(text, 16f, TEXT).apply { setPadding(0, dp(4), 0, 0); setTextIsSelectable(true) }
        val card = block().apply { addView(label("回复草稿", 11f, MUTED)); addView(body) }
        content?.addView(card, blockParams())
        if (animate) appear(card)
        return body
    }

    private fun addNotes(body: TextView, animate: Boolean) {
        if (replyNotes.isBlank()) return
        val notes = label(replyNotes, 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) }
        (body.parent as? LinearLayout)?.addView(notes)
        if (animate) appear(notes)
    }

    /** Streaming: the draft so far. The draft card fades in once, then its text types out in place. */
    fun showReplyProgress(text: String) {
        replyView = { renderReplyForm(); showStatus("生成中…", ACCENT); streamTextView = addDraftCard(text, animate = false) }
        if (tab != Tab.REPLY) return
        val current = streamTextView?.takeIf { it.isAttachedToWindow }
            ?: run { showStatus("生成中…", ACCENT); addDraftCard("", animate = true).also { streamTextView = it } }
        typewriter(current).update(text)
    }

    fun showReply(text: String, notes: String, questions: List<String>, demo: Boolean) {
        replyText = text
        replyNotes = (listOf(notes) + questions.map { "• $it" }).filter { it.isNotBlank() }.joinToString("\n")
        val status = {
            when {
                demo -> showStatus("固定模拟结果 · 未调用 OpenAI", WARN)
                questions.isNotEmpty() -> showStatus("需要你补充信息，草稿仅供参考", WARN)
                else -> showStatus("已生成 · 不会自动发送", OK)
            }
        }
        replyView = { renderReplyForm(); status() }
        if (tab != Tab.REPLY) return
        val live = streamTextView?.takeIf { it.isAttachedToWindow }
        if (live != null) {
            // Finish the streamed card in place: no rebuild, no jump.
            typewriter(live).update(text)
            addNotes(live, animate = true)
            replyFooter()
        } else renderReplyForm(animateDraft = true)
        status()
    }

    fun showStatus(message: String, color: Int = MUTED) { status?.text = message; status?.setTextColor(color) }

    // ---------- styling (extension tokens: .cnjp-panel / .cnjp-btn / .cnjp-block / .cnjp-label) ----------

    private fun clear() { content?.removeAllViews(); footer?.removeAllViews() }

    private fun footerButtons(vararg buttons: TextView) {
        footer?.removeAllViews()
        buttons.forEach { footer?.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) }) }
    }

    private fun smallParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) }

    /**
     * No FLAG_SECURE (user choice): on many ROMs any secure window on screen makes system
     * screenshots fail, so the bubble and panel stay screenshot-able.
     */
    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    /** .cnjp-btn: 9dp radius, hairline border, soft fill; primary = accent outline. */
    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setOnClickListener { onClick() }
        styleButton(this, primary)
    }

    private fun styleButton(view: TextView?, selected: Boolean) {
        view ?: return
        view.background = rounded(if (selected) ACCENT_FILL else SOFT, dp(9), if (selected) ACCENT_LINE else LINE)
        view.setTextColor(if (selected) ACCENT else TEXT)
        view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** .cnjp-block: 12dp radius, hairline border, very soft fill. */
    private fun block() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(BLOCK, dp(12), LINE); setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun blockParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }

    private fun label(text: String, sp: Float, color: Int) = TextView(context).apply {
        this.text = text; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setLineSpacing(0f, 1.15f)
    }

    private fun fill(color: Int) = GradientDrawable().apply { setColor(color) }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); stroke?.let { setStroke(dp(1), it) }
    }

    /** Extension panel background: dark navy glass with cyan/violet corner glows and a hairline border. */
    private fun glassBackground(): Drawable {
        val radius = dp(16).toFloat()
        val reach = metrics.widthPixels * 0.9f
        fun glow(color: Int, x: Float) = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; colors = intArrayOf(color, 0x00000000)
            setGradientCenter(x, 0f); gradientRadius = reach; cornerRadius = radius
        }
        val base = GradientDrawable().apply { setColor(PANEL_BG); cornerRadius = radius }
        val border = GradientDrawable().apply { setColor(0); cornerRadius = radius; setStroke(dp(1), LINE) }
        return LayerDrawable(arrayOf(base, glow(GLOW_CYAN, 0f), glow(GLOW_VIOLET, 1f), border))
    }

    private fun dp(value: Int) = (value * metrics.density).toInt()

    fun destroy() { closePanel(); hideBubble() }

    // ---------- motion ----------

    /** New blocks fade in and rise a little instead of popping in. */
    private fun appear(view: View) {
        view.alpha = 0f; view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    /**
     * Reveals streamed text a few characters per frame instead of jumping by network chunks.
     * The step grows with the backlog, so it never falls far behind the model.
     */
    private inner class Typewriter(private val view: TextView) : Runnable {
        private var target = ""
        private var shown = 0
        private var running = false

        /** The view already shows [text] (e.g. a static re-render): continue from there. */
        fun startFrom(text: String) { target = text; shown = text.length }

        fun update(text: String) {
            // Keep what is already on screen if the new text extends it; otherwise restart at the common prefix.
            if (!text.startsWith(target.substring(0, shown))) shown = text.commonPrefixWith(target.substring(0, shown)).length
            target = text
            if (!running && shown < target.length) { running = true; view.postOnAnimation(this) }
        }

        override fun run() {
            val backlog = target.length - shown
            if (backlog <= 0 || !view.isAttachedToWindow) { running = false; return }
            shown += maxOf(1, backlog / 8).coerceAtMost(backlog)
            if (shown < target.length && Character.isLowSurrogate(target[shown])) shown++ // never split an emoji
            view.text = target.substring(0, shown)
            followScroll()
            view.postOnAnimation(this)
        }
    }

    private fun typewriter(view: TextView): Typewriter =
        (view.getTag(R.id.lb_typewriter) as? Typewriter) ?: Typewriter(view).also { it.startFrom(view.text.toString()); view.setTag(R.id.lb_typewriter, it) }

    /** While streaming, keep the newest text in view until the user scrolls by hand. */
    private var followStream = true
    private fun followScroll() {
        if (!followStream) return
        val scroll = scrollView ?: return
        val inner = content ?: return
        scroll.post { scroll.scrollTo(0, maxOf(0, inner.height - scroll.height)) }
    }

    private companion object {
        // Tokens from translator-ext styles.css (dark): --cnjp-bg rgba(8,18,36,.9), --cnjp-line rgba(255,255,255,.16) …
        const val PANEL_BG = 0xE6081224.toInt()
        const val GLOW_CYAN = 0x385ED0FF
        const val GLOW_VIOLET = 0x2E7A8FFF
        const val HEAD = 0x14FFFFFF
        const val STATUS_BG = 0x0BFFFFFF
        const val SOFT = 0x12FFFFFF
        const val BLOCK = 0x0EFFFFFF
        const val LINE = 0x29FFFFFF
        const val TEXT = 0xFFEEF4FF.toInt()
        const val MUTED = 0xB8EEF4FF.toInt()
        const val ACCENT = 0xFF5ED0FF.toInt()
        const val ACCENT_FILL = 0x245ED0FF
        const val ACCENT_LINE = 0xB35ED0FF.toInt()
        const val OK = 0xFF67E3A1.toInt()
        const val WARN = 0xFFFFD36B.toInt()
        const val ERROR = 0xFFFF8B8B.toInt()
        const val SCRIM = 0x33000000
        const val IDLE_ALPHA = 0.55f
    }
}
