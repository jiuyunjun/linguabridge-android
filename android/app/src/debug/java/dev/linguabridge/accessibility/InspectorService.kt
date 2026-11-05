package dev.linguabridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.linguabridge.data.AssistantPrefs
import dev.linguabridge.data.FixtureExporter
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.AssistOutcome
import dev.linguabridge.data.api.AssistanceRepository
import dev.linguabridge.data.api.OpenAIDirectClient
import dev.linguabridge.data.api.PartialResult
import dev.linguabridge.domain.*
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Floating assistant. Window-state events are used as metadata only (package name) to decide
 * where the bubble is shown; event text/source is never read. Screen text is read once per
 * bubble tap, from a fresh active root of the foreground app, with sensitive/editable subtrees
 * excluded, and kept only in memory for the open panel.
 */
class InspectorService : AccessibilityService(), FloatingAssistant.Listener, InPlaceOverlay.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()
    private val gate = InspectionGate()
    private val repository = AssistanceRepository(allowDebugLoopback = true)
    /** Debug direct mode: set when android/openai.properties had a key at build time. */
    private val direct = OpenAIDirectClient.fromBuildConfig()
    private lateinit var prefs: AssistantPrefs
    private lateinit var assistant: FloatingAssistant
    private var inputMethods: Set<String> = emptySet()
    private var launchers: Set<String> = emptySet()
    private var foreground: String? = null
    private var hiddenUntilSwitch: String? = null
    private var captureJob: Job? = null
    private var translateJob: Job? = null
    private var replyJob: Job? = null
    private fun cancelWork() { captureJob?.cancel(); translateJob?.cancel(); replyJob?.cancel(); anchorJob?.cancel(); settleJob?.cancel() }
    private var expiry: Job? = null
    /** Screen text of the open panel only. Cleared when the panel closes or the app changes. */
    private var page: ReviewedPage? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshBubble() }
    private lateinit var inPlace: InPlaceOverlay
    /** The in-place translations came from the fixed demo response. */
    private var pageDemo = false
    /** Shown by the panel's translate tab instead of requesting a new translation (consumed once). */
    private var presetTranslate: (() -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        prefs = AssistantPrefs(this)
        assistant = FloatingAssistant(this, prefs, this)
        inPlace = InPlaceOverlay(this, this)
        serviceInfo = serviceInfo.apply {
            packageNames = null // all apps: the bubble follows the foreground app
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        inputMethods = getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        launchers = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        prefs.preferences.registerOnSharedPreferenceChangeListener(prefListener)
        InspectorState.update { InspectorStatus(connected = true, message = "悬浮球已就绪") }
    }

    // ---------- bubble visibility ----------

    private fun eligible(pkg: String) = CaptureTarget.isAllowedPackage(pkg) && pkg !in inputMethods &&
        pkg !in launchers && pkg !in prefs.hiddenPackages

    private fun refreshBubble() {
        if (!::assistant.isInitialized) return
        val pkg = foreground
        val show = prefs.bubbleEnabled && pkg != null && eligible(pkg) && pkg != hiddenUntilSwitch && !locked()
        if (show && !assistant.panelOpen) assistant.showBubble() else if (!show) assistant.hideBubble()
        if (!show && assistant.panelOpen) assistant.closePanel()
        if (!show && ::inPlace.isInitialized && inPlace.active) closeInPlace()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (::inPlace.isInitialized && inPlace.drawing) staleCheck(event)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Keyboards, the system shade/lock screen and our own overlay windows don't change which
        // app is in front; re-check visibility (e.g. keyguard) without switching the target.
        val ownActivity = pkg == packageName && event.className?.toString()?.startsWith("dev.linguabridge.") == true
        if (pkg in inputMethods || pkg in transientSystemWindows || (pkg == packageName && !ownActivity)) { refreshBubble(); return }
        if (pkg != foreground) {
            foreground = pkg
            hiddenUntilSwitch = null
            gate.invalidate()
            cancelWork()
            page = null
            if (::assistant.isInitialized && assistant.panelOpen) assistant.closePanel()
            if (::inPlace.isInitialized && inPlace.active) closeInPlace()
        }
        refreshBubble()
    }

    // ---------- in-place translations ----------

    /** Original text -> translation for the in-place session (memory only). */
    private var known: Map<String, String> = emptyMap()
    /** Text blocks of the latest local re-read; translations are drawn at these bounds. */
    private var anchored: List<ContextBlock> = emptyList()
    private var untranslated: List<ContextBlock> = emptyList()
    private var inPlaceDone = false
    /** Original-text keys of the in-flight in-place request: drawn as loading skeletons until translated. */
    private var requestedKeys: Set<String> = emptySet()
    private var anchorJob: Job? = null
    private var settleJob: Job? = null
    @Volatile private var anchorAgain = false

    /** Drawn bounds are one observation: a scroll, window or structural change re-anchors them. */
    private fun staleCheck(event: AccessibilityEvent) {
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> InPlaceInvalidation.Kind.SCROLLED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> InPlaceInvalidation.Kind.CONTENT_CHANGED
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> InPlaceInvalidation.Kind.WINDOW_STATE
            else -> return
        }
        val structural = event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED ||
            event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
        if (!InPlaceInvalidation.needsReanchor(kind, event.packageName?.toString(), foreground, packageName, structural)) return
        if (kind == InPlaceInvalidation.Kind.SCROLLED) {
            // Translations stay put while the app scrolls (no following); once it has stopped,
            // one read moves them straight to the new positions (design.md 15.10 A).
            lastScrollAt = android.os.SystemClock.uptimeMillis(); scrollSeq++
            settleJob?.cancel()
            settleJob = scope.launch { delay(SETTLE_READ_MS); requestAnchor() }
            return
        }
        // Content changes that come with a scroll wait for the settle read.
        if (android.os.SystemClock.uptimeMillis() - lastScrollAt < SETTLE_READ_MS) return
        requestAnchor()
    }

    /** Time and count of the foreground app's scroll events: reads that overlap a scroll are discarded. */
    private var lastScrollAt = 0L
    private var scrollSeq = 0

    /**
     * Re-reads the screen locally (nothing is uploaded) and redraws the known translations where
     * their original text is now. Reads run only while the app is not scrolling; a read that a
     * scroll event overlapped is discarded (measured: reads during a fling take up to ~640ms and
     * their positions belong to an unknown moment).
     */
    private fun requestAnchor() {
        if (assistant.panelOpen) return // the active window is ours; onPanelClosed re-reads
        if (anchorJob?.isActive == true) { anchorAgain = true; return }
        val pkg = foreground ?: return
        if (captureJob?.isActive == true) return
        anchorJob = scope.launch {
            do {
                anchorAgain = false
                if (assistant.panelOpen) return@launch
                val seq = scrollSeq
                val result = captureScreen(CaptureTarget(pkg))
                if (!inPlace.drawing || foreground != pkg || assistant.panelOpen) return@launch
                if (scrollSeq != seq) continue // the settle read scheduled by that scroll replaces it
                // A failed read (window changing mid-transition) draws nothing rather than stale boxes.
                anchored = (result as? CaptureResult.Success)?.let { InPlaceAnchor.blocks(it.snapshot) }.orEmpty()
                redrawInPlace()
                delay(ANCHOR_INTERVAL_MS)
            } while (anchorAgain && inPlace.drawing)
        }
    }

    private fun redrawInPlace() {
        if (!inPlace.drawing) return
        val result = InPlaceAnchor.anchor(anchored, known)
        untranslated = result.untranslated
        val pending = if (requestedKeys.isEmpty()) emptyList()
            else result.untranslated.filter { InPlaceAnchor.key(it.text) in requestedKeys }.map { it.bounds }
        inPlace.update(result.items, pending)
        if (inPlaceDone) inPlace.showDone(inPlace.placedCount, inPlace.skippedCount, pageDemo, untranslated.size)
    }

    /**
     * Scroll/content events are subscribed only while translations are drawn, and then delivered
     * without the service-side 100ms coalescing so scroll deltas are not lost.
     */
    private fun watchLayoutChanges(on: Boolean) {
        val types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (on) AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0)
        val timeout = if (on) 0L else IDLE_NOTIFICATION_TIMEOUT_MS
        runCatching {
            if (serviceInfo.eventTypes != types || serviceInfo.notificationTimeout != timeout)
                serviceInfo = serviceInfo.apply { eventTypes = types; notificationTimeout = timeout }
        }
    }

    /** The bubble stays visible: tapping it again turns the translations off. */
    private fun startInPlace(screen: ReviewedPage, mode: DisplayMode) {
        inPlace.show(mode)
        known = emptyMap(); anchored = screen.blocks; untranslated = emptyList(); inPlaceDone = false
        requestedKeys = screen.blocks.map { InPlaceAnchor.key(it.text) }.toSet()
        watchLayoutChanges(true)
        redrawInPlace() // loading skeletons right away
        translateJob = scope.launch {
            val request = pageRequest(screen)
            val outcome = call(request) { partial ->
                val streamed = InPlaceAnchor.translationsByText(screen.blocks, partial.translations)
                if (streamed.isNotEmpty()) onMain {
                    if (translateJob?.isActive == true && inPlace.drawing) {
                        known = known + streamed; redrawInPlace(); inPlace.showWorking("翻译中… ${streamed.size} 段")
                    }
                }
            }
            if (!inPlace.drawing) return@launch
            requestedKeys = emptySet()
            when (outcome) {
                is AssistOutcome.Error -> openPanelFromInPlace { assistant.showError(outcome.message, retry = true, screenshot = canScreenshot) }
                is AssistOutcome.Success -> {
                    val translated = InPlaceAnchor.translationsByText(screen.blocks, outcome.result.translations)
                    if (translated.isEmpty()) {
                        openPanelFromInPlace { assistant.showError("这一屏没有需要翻译的文字", retry = false, screenshot = canScreenshot) }
                        return@launch
                    }
                    pageDemo = outcome.result.demo
                    known = known + translated; inPlaceDone = true
                    redrawInPlace()
                }
            }
        }
    }

    /** User-triggered: translates only text that scrolled into view, then draws it in place too. */
    override fun onInPlaceTranslateNew() {
        if (translateJob?.isActive == true || !inPlace.drawing) return
        val blocks = InPlaceAnchor.requestBlocks(untranslated).takeIf { it.isNotEmpty() } ?: return
        val pkg = foreground ?: return
        inPlaceDone = false
        requestedKeys = blocks.map { InPlaceAnchor.key(it.text) }.toSet()
        inPlace.showWorking("正在翻译新内容…")
        redrawInPlace()
        translateJob = scope.launch {
            val request = AssistRequest(operation = AssistOperation.TRANSLATE_PAGE, targetLanguage = prefs.translateLanguage,
                pageType = "auto", sourcePackage = pkg, targetBlockId = "", userIntent = "", blocks = blocks)
            val outcome = call(request) { partial ->
                val streamed = InPlaceAnchor.translationsByText(blocks, partial.translations)
                if (streamed.isNotEmpty()) onMain {
                    if (translateJob?.isActive == true && inPlace.drawing) { known = known + streamed; redrawInPlace() }
                }
            }
            if (!inPlace.drawing) return@launch
            inPlaceDone = true; requestedKeys = emptySet()
            when (outcome) {
                is AssistOutcome.Error -> Toast.makeText(this@InspectorService, outcome.message, Toast.LENGTH_LONG).show()
                is AssistOutcome.Success -> {
                    known = known + InPlaceAnchor.translationsByText(blocks, outcome.result.translations)
                }
            }
            redrawInPlace()
        }
    }

    /** Leaves the in-place view for the panel, keeping the page so the panel can copy or reply. */
    private fun openPanelFromInPlace(translateView: () -> Unit) {
        val screen = page ?: return closeInPlace()
        translateJob?.cancel()
        anchorJob?.cancel()
        inPlace.close(); watchLayoutChanges(false)
        presetTranslate = translateView
        assistant.hideBubble()
        assistant.openPanel(FloatingAssistant.Tab.TRANSLATE, screen.blocks.size)
    }

    private fun closeInPlace() {
        translateJob?.cancel(); anchorJob?.cancel(); settleJob?.cancel()
        known = emptyMap(); anchored = emptyList(); untranslated = emptyList(); inPlaceDone = false; requestedKeys = emptySet()
        inPlace.close()
        watchLayoutChanges(false)
        if (!assistant.panelOpen) { page = null; gate.invalidate() }
        refreshBubble()
    }

    override fun onInPlaceClose() = closeInPlace()

    /**
     * Opens the reply panel over the in-place translations, which stay drawn (design.md 15.10 B).
     * Context is the text currently on screen (latest read), not the first capture.
     */
    override fun onInPlaceReply() {
        val pkg = foreground ?: return
        if (!inPlace.drawing || assistant.panelOpen) return
        val context = anchored.takeLast(PageContext.MAX_BLOCKS).mapIndexed { i, b -> b.copy(id = "b${i + 1}") }
        if (context.isEmpty()) { Toast.makeText(this, "当前屏幕没有读到文字", Toast.LENGTH_SHORT).show(); return }
        page = ReviewedPage(java.util.UUID.randomUUID().toString(), pkg, PageSource.ACCESSIBILITY, context, emptyList())
        val lines = context.mapNotNull { b -> known[InPlaceAnchor.key(b.text)]?.let { TranslatedLine(b.text, it) } }
        val demo = pageDemo
        presetTranslate = {
            if (lines.isEmpty()) assistant.showError("当前屏幕上还没有译文", retry = false) else assistant.showTranslations(lines, demo)
        }
        anchorJob?.cancel()
        assistant.hideBubble()
        assistant.openPanel(FloatingAssistant.Tab.REPLY, context.size)
    }

    override fun onInPlaceModeChange(mode: DisplayMode) { prefs.displayMode = mode }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // ---------- FloatingAssistant.Listener ----------

    override fun onBubbleTap() {
        if (inPlace.active) { closeInPlace(); return } // the bubble toggles in-place translations off
        val pkg = foreground ?: return
        if (captureJob?.isActive == true || !eligible(pkg) || locked()) return
        assistant.setBubbleBusy(true)
        // Reading the screen is local; nothing is uploaded until a tab needs it
        // (translate tab -> page translation, reply tab -> only when "生成回复" is tapped).
        captureJob = scope.launch {
            val result = captureScreen(CaptureTarget(pkg))
            assistant.setBubbleBusy(false)
            if (foreground != pkg) return@launch
            val screen = (result as? CaptureResult.Success)?.let { PageContext.screen(it.snapshot) }
            page = screen?.takeIf { it.blocks.isNotEmpty() }
            // In-place modes translate on tap unless the bubble is set to always open the reply tab.
            val mode = prefs.displayMode
            page?.let { if (mode.inPlace && prefs.openWith != "reply") { startInPlace(it, mode); return@launch } }
            assistant.hideBubble()
            val failed = page == null
            assistant.openPanel(if (failed || prefs.initialTab == "translate") FloatingAssistant.Tab.TRANSLATE else FloatingAssistant.Tab.REPLY,
                page?.blocks?.size ?: 0)
            when {
                result is CaptureResult.Failure -> assistant.showError(captureMessage(result.reason), retry = false, screenshot = canScreenshot)
                failed -> assistant.showError(
                    if (canScreenshot) "这个页面没有读到文字，可以用截图翻译" else "这个页面没有读到文字（可能是图片或 App 不开放无障碍内容）",
                    retry = false, screenshot = canScreenshot)
            }
        }
    }

    override fun onTranslateNeeded() {
        presetTranslate?.let { presetTranslate = null; it(); return }
        val screen = page ?: return
        if (translateJob?.isActive != true) translate(screen)
    }

    override fun onRetry() { page?.let { translate(it) } }

    private fun pageRequest(screen: ReviewedPage) = AssistRequest(operation = AssistOperation.TRANSLATE_PAGE,
        targetLanguage = prefs.translateLanguage, pageType = "auto", sourcePackage = screen.sourcePackage, targetBlockId = "",
        userIntent = "", blocks = screen.blocks)

    private fun lines(screen: ReviewedPage, translations: List<BlockTranslation>): List<TranslatedLine> {
        val byId = screen.blocks.associateBy { it.id }
        return translations.mapNotNull { t -> byId[t.id]?.let { TranslatedLine(it.text, t.text) } }
    }

    private fun translate(screen: ReviewedPage) {
        assistant.showWorking("正在翻译当前屏幕…")
        translateJob = scope.launch {
            val request = pageRequest(screen)
            val outcome = call(request) { partial ->
                val lines = lines(screen, partial.translations)
                if (lines.isNotEmpty()) onMain { if (translateJob?.isActive == true) assistant.showTranslationProgress(lines) }
            }
            when (outcome) {
                is AssistOutcome.Error -> assistant.showError(outcome.message, retry = true, screenshot = canScreenshot)
                is AssistOutcome.Success -> {
                    val lines = lines(screen, outcome.result.translations)
                    if (lines.isEmpty()) assistant.showError("这一屏没有需要翻译的文字", retry = false, screenshot = canScreenshot)
                    else assistant.showTranslations(lines, outcome.result.demo)
                }
            }
        }
    }

    // ---------- screenshot translation (direct mode, Android 11+) ----------

    private val canScreenshot get() = direct != null && Build.VERSION.SDK_INT >= 30
    private fun model(client: OpenAIDirectClient) = prefs.openAiModel.ifBlank { client.defaultModel }

    override fun onScreenshotTranslate() {
        val client = direct
        if (client == null || Build.VERSION.SDK_INT < 30) {
            assistant.showError("截图翻译需要 Android 11+ 和 OpenAI 直连模式", retry = false); return
        }
        val pkg = foreground ?: return
        translateJob?.cancel()
        translateJob = scope.launch {
            // Remove our own panel window so the screenshot shows only the app.
            assistant.detachPanel()
            delay(350)
            val bitmap = if (foreground == pkg && !locked()) shoot() else null
            assistant.reattachPanel()
            if (bitmap == null) { assistant.showError("截图失败（页面可能禁止截图，或操作太频繁）", retry = false); return@launch }
            assistant.showWorking("正在识别并翻译截图…")
            val jpeg = withContext(Dispatchers.Default) { encodeJpeg(bitmap) }
            val outcome = client.translateScreenshot(jpeg, prefs.translateLanguage, model(client)) { lines ->
                if (lines.isNotEmpty()) onMain {
                    if (translateJob?.isActive == true) assistant.showTranslationProgress(lines.map { TranslatedLine(it.original, it.translation) })
                }
            }
            when (outcome) {
                is OpenAIDirectClient.ImageOutcome.Error -> assistant.showError(outcome.message, retry = false, screenshot = true)
                is OpenAIDirectClient.ImageOutcome.Success ->
                    if (outcome.lines.isEmpty()) assistant.showError("截图里没有识别到需要翻译的文字", retry = false)
                    else {
                        // Screenshot-recognized text becomes the reply context (replaces the a11y read).
                        if (foreground == pkg) PageContext.fromScreenshotTexts(pkg, outcome.lines.map { it.original })
                            .takeIf { it.blocks.isNotEmpty() }?.let { page = it; assistant.setContextBlocks(it.blocks.size) }
                        assistant.showTranslations(outcome.lines.map { TranslatedLine(it.original, it.translation) }, demo = false, fromScreenshot = true)
                    }
            }
        }
    }

    /** One accessibility screenshot of the default display, copied to a software bitmap. */
    @androidx.annotation.RequiresApi(30)
    private suspend fun shoot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val copy = try {
                    Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null } finally { result.hardwareBuffer.close() }
                if (continuation.isActive) continuation.resume(copy)
            }
            override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
        })
    }

    /** Long side at most 1600 px, JPEG q80: legible text at a moderate upload size. Never written to disk. */
    private fun encodeJpeg(source: Bitmap): ByteArray {
        val scale = minOf(1f, 1600f / maxOf(source.width, source.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true) else source
        return java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
        }.also { if (scaled !== source) scaled.recycle(); source.recycle() }
    }

    override fun onGenerateReply(intent: String, tone: String) {
        val screen = page ?: return assistant.showError("页面内容已失效，请关闭后重新点悬浮球", retry = false)
        if (replyJob?.isActive == true) return
        assistant.showReplyWorking()
        replyJob = scope.launch {
            val budget = PageContext.MAX_BYTES - intent.toByteArray(Charsets.UTF_8).size
            // Keep the most recent (bottom) blocks that fit next to the user's intent.
            val blocks = screen.blocks.reversed().runningFold(0 to listOf<ContextBlock>()) { (used, acc), block ->
                (used + block.text.toByteArray(Charsets.UTF_8).size) to (acc + block)
            }.lastOrNull { it.first <= budget }?.second?.reversed().orEmpty()
            if (blocks.isEmpty()) { assistant.showError("回复要求太长，请缩短后重试", retry = false); return@launch }
            val request = AssistRequest(operation = AssistOperation.REPLY, targetLanguage = prefs.replyLanguage, pageType = "auto",
                sourcePackage = screen.sourcePackage, targetBlockId = "", userIntent = intent, blocks = blocks, tone = tone)
            val outcome = call(request) { partial ->
                if (partial.text.isNotBlank()) onMain { if (replyJob?.isActive == true) assistant.showReplyProgress(partial.text) }
            }
            when (outcome) {
                is AssistOutcome.Error -> assistant.showError(outcome.message, retry = false)
                is AssistOutcome.Success -> with(outcome.result) { assistant.showReply(text, notes, questions, demo) }
            }
        }
    }

    /** Posts streaming progress (delivered on a network thread) to the main thread, in order. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun onMain(block: () -> Unit) { mainHandler.post(block) }

    /** Direct mode streams progress to [onPartial]; the proxy returns the whole result at once. */
    private suspend fun call(request: AssistRequest, onPartial: ((PartialResult) -> Unit)? = null): AssistOutcome {
        direct?.let { return it.generate(request, model(it), onPartial) }
        val settings = withContext(Dispatchers.IO) { runCatching { ProxySettingsStore(this@InspectorService).load() }.getOrNull() }
            ?: return AssistOutcome.Error("还没配置翻译代理：打开 LinguaBridge 设置")
        return repository.generate(settings, request)
    }

    override fun onCopy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LinguaBridge", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    override fun onFill(text: String) {
        val pkg = page?.sourcePackage ?: return
        assistant.closePanel()
        scope.launch {
            delay(350) // let the target window regain input focus after our panel closes
            val filled = foreground == pkg && !locked() && runCatching {
                rootInActiveWindow?.let { root -> InputFiller.fill(root, pkg, text) } ?: false
            }.getOrDefault(false)
            if (!filled) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LinguaBridge", text))
                Toast.makeText(this@InspectorService, "没找到可填入的输入框，已复制，请手动粘贴", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBubbleHide() {
        hiddenUntilSwitch = foreground
        assistant.hideBubble()
        Toast.makeText(this, "已隐藏，切换 App 后恢复", Toast.LENGTH_SHORT).show()
    }

    override fun onPanelClosed() {
        translateJob?.cancel(); replyJob?.cancel()
        presetTranslate = null
        gate.invalidate()
        page = null
        refreshBubble()
        // Back to the in-place translations: catch up on anything that moved while the panel was open.
        if (::inPlace.isInitialized && inPlace.drawing) requestAnchor()
    }

    private fun captureMessage(reason: CaptureFailure) = when (reason) {
        CaptureFailure.LOCKED -> "屏幕已锁定"
        CaptureFailure.WRONG_PACKAGE, CaptureFailure.WINDOW_CHANGED, CaptureFailure.NOT_AUTHORIZED -> "页面已切换，请再点一次悬浮球"
        CaptureFailure.NO_WINDOW -> "读不到当前窗口，请稍后再试"
        CaptureFailure.READ_FAILED -> "读取页面失败，请稍后再试"
    }

    // ---------- one bounded capture of the foreground app ----------

    private suspend fun captureScreen(target: CaptureTarget, includeUnimportantViews: Boolean = false): CaptureResult {
        gate.arm()
        val ticket = gate.generation
        val result = try { queue.withLock {
            if (!gate.canCommit(ticket)) return@withLock CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED)
            if (locked()) return@withLock CaptureResult.Failure(CaptureFailure.LOCKED)
            withContext(Dispatchers.IO) {
                val originalFlags = serviceInfo.flags
                try {
                    if (includeUnimportantViews) serviceInfo = serviceInfo.apply { flags = originalFlags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
                    val collected = FreshRootInspection(expectedPackage = target.packageName).collect(
                        readRoot = { rootInActiveWindow?.let { InspectionRoot(it.windowId, AndroidInspectionNode(it)) } },
                        clearNodeCache = { if (Build.VERSION.SDK_INT >= 33) clearCache() else null },
                        pause = { delay(it) },
                        isCurrent = { gate.canCommit(ticket) && !locked() },
                        now = { android.os.SystemClock.elapsedRealtime() },
                    )
                    if (collected is CaptureResult.Success) CaptureResult.Success(collected.snapshot.copy(
                        capturedAtEpochMillis = System.currentTimeMillis(),
                        diagnostics = collected.snapshot.diagnostics.copy(serviceFlags = serviceInfo.flags, serviceCapabilities = serviceInfo.capabilities),
                    )) else collected
                } finally {
                    if (includeUnimportantViews) serviceInfo = serviceInfo.apply { flags = originalFlags }
                }
            }
        } } catch (cancelled: CancellationException) { gate.stop(); throw cancelled
        } catch (_: Exception) { CaptureResult.Failure(CaptureFailure.READ_FAILED) }
        val current = gate.canCommit(ticket)
        gate.stop()
        return if (current) result else CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED)
    }

    /** adb diagnostic (DUMP-protected receiver): one capture of the given foreground app, redacted export. */
    fun inspectFromAdb(target: CaptureTarget, appVersion: String, includeUnimportantViews: Boolean = false) {
        if (foreground != target.packageName) { FixtureExporter.writeAdbStatus(this, "FAILED_WRONG_PACKAGE"); return }
        FixtureExporter.writeAdbStatus(this, "RUNNING")
        expiry?.cancel()
        val job = scope.launch {
            when (val result = captureScreen(target, includeUnimportantViews)) {
                is CaptureResult.Success -> try {
                    FixtureExporter.export(this@InspectorService, result.snapshot, emptyMap(), appVersion, "adb-confirmed-page")
                    FixtureExporter.writeAdbStatus(this@InspectorService, "COMPLETE")
                } catch (_: Exception) { FixtureExporter.writeAdbStatus(this@InspectorService, "EXPORT_FAILED") }
                is CaptureResult.Failure -> FixtureExporter.writeAdbStatus(this@InspectorService, "FAILED_${result.reason.name}")
            }
        }
        expiry = scope.launch { delay(60_000); if (job.isActive) { job.cancel(); FixtureExporter.writeAdbStatus(this@InspectorService, "FAILED_TIMEOUT") } }
    }

    private fun shutdown(message: String) {
        cancelWork(); expiry?.cancel(); gate.stop(); page = null
        if (::inPlace.isInitialized) { inPlace.close(); watchLayoutChanges(false) }
        if (::assistant.isInitialized) assistant.destroy()
        InspectorState.update { it.copy(connected = false, message = message) }
    }

    override fun onInterrupt() { cancelWork(); gate.invalidate() }
    override fun onUnbind(intent: Intent?): Boolean {
        if (::prefs.isInitialized) prefs.preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        shutdown("服务未连接")
        instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        shutdown("服务已关闭")
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        /**
         * Windows that briefly cover the app without replacing it. The HyperOS screenshot
         * animation (com.miui.screenshot, verified on Xiaomi 25128PNA1C) takes focus; treating it
         * as an app switch hid the bubble, and no event re-showed it when the app regained focus.
         */
        /** Re-anchor reads are throttled to this while the app keeps scrolling. */
        private const val ANCHOR_INTERVAL_MS = 80L
        /** Read once more this long after the last scroll event: the final position is the true one. */
        private const val SETTLE_READ_MS = 250L
        /** Same as inspector_service.xml android:notificationTimeout. */
        private const val IDLE_NOTIFICATION_TIMEOUT_MS = 100L
        private val transientSystemWindows =setOf("com.android.systemui", "com.miui.screenshot")
        private var serviceReference = WeakReference<InspectorService>(null)
        var instance: InspectorService?
            get() = serviceReference.get()
            private set(value) { serviceReference = WeakReference(value) }
    }
}
