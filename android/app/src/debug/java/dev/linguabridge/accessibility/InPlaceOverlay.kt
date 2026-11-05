package dev.linguabridge.accessibility

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.linguabridge.domain.DisplayMode
import dev.linguabridge.domain.InPlaceItem
import dev.linguabridge.domain.InPlaceLayout
import dev.linguabridge.domain.NodeBounds
import dev.linguabridge.domain.Placement

/**
 * In-place translations: a full-screen, non-touchable overlay that draws each translation at the
 * original text's bounds, plus a small touchable capsule with the controls. Touches always pass
 * through the drawing layer to the app below. Pure view layer: it never reads other apps' windows.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n", "ViewConstructor")
class InPlaceOverlay(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onInPlaceClose()
        fun onInPlaceReply()
        fun onInPlaceModeChange(mode: DisplayMode)
        fun onInPlaceTranslateNew()
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private val metrics get() = context.resources.displayMetrics
    private var layer: Layer? = null
    private var capsule: LinearLayout? = null
    private var mode = DisplayMode.COVER

    /** Shown (drawing or showing the "stale" capsule). */
    val active get() = capsule != null
    /** Translations are on screen and still valid. */
    val drawing get() = layer != null

    fun show(mode: DisplayMode) {
        require(mode.inPlace)
        close()
        this.mode = mode
        val view = Layer(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windows.addView(view, params)
        layer = view
        addCapsule()
        showWorking("正在翻译当前屏幕…")
    }

    /**
     * Draws [items] at their (screen) bounds, replacing the previous ones at once: after a scroll
     * the translations jump straight to the newly read positions (design.md 15.10 A). [pending]:
     * original-text bounds still waiting for a translation, drawn as shimmering skeletons (15.10 A2).
     */
    fun update(items: List<InPlaceItem>, pending: List<NodeBounds> = emptyList()) { layer?.setItems(items, pending, mode) }

    fun showWorking(text: String) = capsuleContent(DOT_WORKING, text, close = true)

    private var demo = false
    private var newCount = 0

    /** [newCount]: text on screen that has no translation yet (scrolled into view); offered, never auto-uploaded. */
    fun showDone(count: Int, skipped: Int, demo: Boolean, newCount: Int = this.newCount) {
        this.demo = demo; this.newCount = newCount
        val text = buildString {
            append(if (mode == DisplayMode.COVER) "原位译文" else "就近标签")
            append(" · $count 段")
            if (skipped > 0) append("（$skipped 段放不下）")
            if (newCount > 0) append(" · 新内容 $newCount 段")
            if (demo) append(" · 模拟")
        }
        capsuleContent(if (demo) DOT_WARN else DOT_OK, text, close = true, controls = true, translateNew = newCount > 0)
    }

    val placedCount get() = layer?.placedCount ?: 0
    val skippedCount get() = layer?.skippedCount ?: 0

    fun close() {
        removeLayer()
        pulse?.cancel(); pulse = null
        capsule?.let { runCatching { windows.removeView(it) } }
        capsule = null; capsuleState = null; capsuleText = null; newCount = 0
    }

    private fun removeLayer() {
        layer?.let { runCatching { windows.removeView(it) } }
        layer = null
    }

    // ---------- capsule ----------

    private fun addCapsule() {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44); setPadding(dp(12), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(22).toFloat(); setStroke(dp(1), LINE) }
            elevation = dp(6).toFloat()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(44) }
        windows.addView(view, params)
        capsule = view
    }

    /** Last capsule state: re-anchoring while scrolling re-sends it often, and rebuilding would break a held "原文". */
    private var capsuleState: List<Any>? = null
    private var capsuleText: TextView? = null
    /** Breathing status dot while translating. */
    private var pulse: ObjectAnimator? = null

    private fun capsuleContent(dot: Int, text: String, close: Boolean, controls: Boolean = false, translateNew: Boolean = false) {
        val view = capsule ?: return
        val state = listOf(dot, close, controls, translateNew, mode)
        // Only the text changed (e.g. streaming progress): update it in place so the pulse keeps running.
        if (state == capsuleState) { capsuleText?.let { if (it.text != text) it.text = text }; return }
        capsuleState = state
        layer?.visibility = View.VISIBLE
        pulse?.cancel(); pulse = null
        view.removeAllViews()
        val dotView = View(context).apply { background = GradientDrawable().apply { setColor(dot); shape = GradientDrawable.OVAL } }
        view.addView(dotView, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        if (dot == DOT_WORKING) pulse = ObjectAnimator.ofFloat(dotView, View.ALPHA, 1f, 0.3f).apply {
            duration = 400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        capsuleText = TextView(context).apply {
            this.text = text; setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; maxWidth = (metrics.widthPixels * 0.45f).toInt()
        }
        view.addView(capsuleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        if (controls) {
            view.addView(pill("原文").apply {
                contentDescription = "按住查看原文"
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> layer?.visibility = View.INVISIBLE
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> layer?.visibility = View.VISIBLE
                    }
                    true
                }
            })
            val other = if (mode == DisplayMode.COVER) DisplayMode.LABEL else DisplayMode.COVER
            view.addView(pill(if (other == DisplayMode.COVER) "覆盖" else "标签") {
                mode = other; layer?.setMode(other); listener.onInPlaceModeChange(other)
                showDone(placedCount, skippedCount, demo, newCount)
            })
            view.addView(pill("回复", primary = true) { listener.onInPlaceReply() })
        }
        if (translateNew) view.addView(pill("翻译新增") { listener.onInPlaceTranslateNew() })
        if (close) view.addView(pill("✕") { listener.onInPlaceClose() }.apply { contentDescription = "关闭原位译文" })
    }

    private fun pill(text: String, primary: Boolean = false, onClick: (() -> Unit)? = null) = TextView(context).apply {
        this.text = text; gravity = Gravity.CENTER; minWidth = dp(40); minHeight = dp(36); setPadding(dp(10), 0, dp(10), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (primary) PRIMARY_TEXT else TEXT)
        if (primary) typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(if (primary) ACCENT else PILL); cornerRadius = dp(18).toFloat() }
        onClick?.let { action -> setOnClickListener { action() } }
    }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(2) } }

    private fun dp(value: Int) = (value * metrics.density).toInt()

    // ---------- drawing layer ----------

    /** Draws placements from [InPlaceLayout]; bounds are screen coordinates, shifted by this window's own screen offset. */
    private inner class Layer(context: Context) : View(context) {
        private var items: List<InPlaceItem> = emptyList()
        private var pending: List<NodeBounds> = emptyList()
        private var mode = DisplayMode.COVER
        private var plan: List<Placement> = emptyList()
        var placedCount = 0; private set
        var skippedCount = 0; private set
        private var dirty = true
        private val location = IntArray(2)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOX }
        private val rect = RectF()
        private val layouts = HashMap<Pair<String, Float>, StaticLayout>()

        fun setItems(items: List<InPlaceItem>, pending: List<NodeBounds>, mode: DisplayMode) {
            this.items = items; this.pending = pending; this.mode = mode; changed()
        }
        fun setMode(mode: DisplayMode) { this.mode = mode; changed() }

        /** Re-plans at once when laid out, so the capsule's counts are current. */
        private fun changed() { dirty = true; if (width > 0 && height > 0 && isAttachedToWindow) replan(); invalidate() }

        private fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

        private fun staticLayout(text: String, width: Int, sp: Float, maxLines: Int = Int.MAX_VALUE): StaticLayout {
            val paint = TextPaint(textPaint).apply { textSize = px(sp) }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.1f).setIncludePad(false)
                .setMaxLines(maxLines).setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END).build()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { dirty = true }

        private fun replan() {
            getLocationOnScreen(location)
            val (ox, oy) = location[0] to location[1]
            val shifted = items.map { it.copy(bounds = NodeBounds(it.bounds.left - ox, it.bounds.top - oy, it.bounds.right - ox, it.bounds.bottom - oy)) }
            val result = InPlaceLayout(resources.displayMetrics.density) { text, widthPx, sp -> staticLayout(text, widthPx, sp).height }
                .plan(shifted, mode, width, height)
            plan = result.placements; placedCount = plan.size; skippedCount = result.skipped
            pendingShifted = pending.map { NodeBounds(it.left - ox, it.top - oy, it.right - ox, it.bottom - oy) }
            sourceById = items.associate { it.id to it.source.ifEmpty { it.text } }
            layouts.clear(); dirty = false
        }

        private var pendingShifted: List<NodeBounds> = emptyList()
        private var sourceById: Map<String, String> = emptyMap()
        /**
         * First time each segment was drawn, for the fade-in. Keyed by the original text: a streamed
         * translation grows chunk by chunk, and keying by its text restarted the fade on every chunk
         * (visible flashing). Survives re-reads, so each segment fades in once.
         */
        private val firstSeen = HashMap<String, Long>()
        private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SKELETON }
        private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shimmerMatrix = android.graphics.Matrix()
        private var shimmerWidth = 0

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            if (dirty) replan()
            val density = resources.displayMetrics.density
            val cover = mode == DisplayMode.COVER
            val padH = ((if (cover) InPlaceLayout.COVER_PAD_H else InPlaceLayout.LABEL_PAD_H) * density).toInt()
            val padV = ((if (cover) InPlaceLayout.COVER_PAD_V else InPlaceLayout.LABEL_PAD_V) * density).toInt()
            val radius = (if (cover) 6 else 8) * density
            val now = SystemClock.uptimeMillis()
            var animating = false
            if (pendingShifted.isNotEmpty()) {
                updateShimmer(now)
                for (b in pendingShifted) drawSkeleton(canvas, b, radius)
                animating = true
            }
            for (p in plan) {
                val seen = firstSeen.getOrPut(sourceById[p.id] ?: p.text) { now }
                val alpha = ((now - seen) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                if (alpha < 1f) {
                    animating = true
                    canvas.saveLayerAlpha(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat(), (alpha * 255).toInt())
                }
                drawPlacement(canvas, p, cover, padH, padV, radius)
                if (alpha < 1f) canvas.restore()
            }
            if (animating) postInvalidateOnAnimation()
        }

        /** A highlight band sweeping left to right across the whole screen, one pass per [SHIMMER_MS]. */
        private fun updateShimmer(now: Long) {
            if (shimmerWidth != width) {
                shimmerWidth = width
                val band = width * 0.4f
                shimmerPaint.shader = android.graphics.LinearGradient(0f, 0f, band, 0f,
                    intArrayOf(0x00FFFFFF, SHIMMER_HIGHLIGHT, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
            }
            val band = width * 0.4f
            val progress = (now % SHIMMER_MS) / SHIMMER_MS.toFloat()
            shimmerMatrix.setTranslate(-band + progress * (width + band), 0f)
            shimmerPaint.shader?.setLocalMatrix(shimmerMatrix)
        }

        private fun drawSkeleton(canvas: Canvas, b: NodeBounds, radius: Float) {
            rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            canvas.drawRoundRect(rect, radius, radius, skeletonPaint)
            canvas.drawRoundRect(rect, radius, radius, shimmerPaint)
        }

        private fun drawPlacement(canvas: Canvas, p: Placement, cover: Boolean, padH: Int, padV: Int, radius: Float) {
            rect.set(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat())
            canvas.drawRoundRect(rect, radius, radius, boxPaint)
            val innerWidth = p.right - p.left - 2 * padH
            val innerHeight = p.bottom - p.top - 2 * padV
            val text = layouts.getOrPut(p.id to p.textSizeSp) {
                if (!p.clipped) staticLayout(p.text, innerWidth, p.textSizeSp) else {
                    val lineHeight = staticLayout("国", innerWidth, p.textSizeSp).height.coerceAtLeast(1)
                    staticLayout(p.text, innerWidth, p.textSizeSp, maxLines = (innerHeight / lineHeight).coerceAtLeast(1))
                }
            }
            canvas.save()
            // Cover: vertically centred in the original box; label: top-aligned.
            val dy = if (cover) ((innerHeight - text.height) / 2).coerceAtLeast(0) else 0
            canvas.clipRect(rect)
            canvas.translate((p.left + padH).toFloat(), (p.top + padV + dy).toFloat())
            text.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        /** Fixed dark box so a translation is never mistaken for the other person's own words. */
        const val BOX = 0xF20F1B33.toInt()
        const val BG = 0xF20F1B33.toInt()
        const val LINE = 0xFF2C3A57.toInt()
        const val PILL = 0xFF172440.toInt()
        const val TEXT = 0xFFEAF2FF.toInt()
        const val ACCENT = 0xFF5ED0FF.toInt()
        const val PRIMARY_TEXT = 0xFF08233A.toInt()
        const val DOT_OK = 0xFF67E3A1.toInt()
        const val DOT_WORKING = 0xFF5ED0FF.toInt()
        const val DOT_WARN = 0xFFFFC66B.toInt()
        /** Skeleton over original text while its translation is on the way: accent tint, original stays readable. */
        const val SKELETON = 0x335ED0FF
        const val SHIMMER_HIGHLIGHT = 0x66FFFFFF
        const val SHIMMER_MS = 1200L
        const val FADE_MS = 180L
    }
}
