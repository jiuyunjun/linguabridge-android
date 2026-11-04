package dev.linguabridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.linguabridge.data.AssistantPrefs
import dev.linguabridge.data.FixtureExporter
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.AssistOutcome
import dev.linguabridge.data.api.AssistanceRepository
import dev.linguabridge.domain.*
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Floating assistant. Window-state events are used as metadata only (package name) to decide
 * where the bubble is shown; event text/source is never read. Screen text is read once per
 * bubble tap, from a fresh active root of the foreground app, with sensitive/editable subtrees
 * excluded, and kept only in memory for the open panel.
 */
class InspectorService : AccessibilityService(), FloatingAssistant.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()
    private val gate = InspectionGate()
    private val repository = AssistanceRepository(allowDebugLoopback = true)
    private lateinit var prefs: AssistantPrefs
    private lateinit var assistant: FloatingAssistant
    private var inputMethods: Set<String> = emptySet()
    private var launchers: Set<String> = emptySet()
    private var foreground: String? = null
    private var hiddenUntilSwitch: String? = null
    private var work: Job? = null
    private var expiry: Job? = null
    /** Screen text of the open panel only. Cleared when the panel closes or the app changes. */
    private var page: ReviewedPage? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshBubble() }

    override fun onServiceConnected() {
        instance = this
        prefs = AssistantPrefs(this)
        assistant = FloatingAssistant(this, prefs, this)
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Keyboards, the system shade/lock screen and our own overlay windows don't change which
        // app is in front; re-check visibility (e.g. keyguard) without switching the target.
        val ownActivity = pkg == packageName && event.className?.toString()?.startsWith("dev.linguabridge.") == true
        if (pkg in inputMethods || pkg == "com.android.systemui" || (pkg == packageName && !ownActivity)) { refreshBubble(); return }
        if (pkg != foreground) {
            foreground = pkg
            hiddenUntilSwitch = null
            gate.invalidate()
            work?.cancel()
            page = null
            if (::assistant.isInitialized && assistant.panelOpen) assistant.closePanel()
        }
        refreshBubble()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // ---------- FloatingAssistant.Listener ----------

    override fun onBubbleTap() {
        val pkg = foreground ?: return
        if (work?.isActive == true || !eligible(pkg) || locked()) return
        assistant.setBubbleBusy(true)
        work = scope.launch {
            val result = captureScreen(CaptureTarget(pkg))
            assistant.setBubbleBusy(false)
            if (foreground != pkg) return@launch
            assistant.hideBubble()
            assistant.openPanel()
            when (result) {
                is CaptureResult.Failure -> assistant.showError(captureMessage(result.reason), retry = false)
                is CaptureResult.Success -> {
                    val screen = PageContext.screen(result.snapshot)
                    if (screen.blocks.isEmpty()) {
                        assistant.showError("这个页面没有读到可翻译的文字（可能是图片或 App 不开放无障碍内容）", retry = false)
                    } else {
                        page = screen
                        translate(screen)
                    }
                }
            }
        }
    }

    override fun onRetry() { page?.let { translate(it) } }

    private fun translate(screen: ReviewedPage) {
        assistant.showWorking("正在翻译当前屏幕…")
        work = scope.launch {
            val request = AssistRequest(operation = AssistOperation.TRANSLATE_PAGE, targetLanguage = prefs.translateLanguage,
                pageType = "auto", sourcePackage = screen.sourcePackage, targetBlockId = "", userIntent = "", blocks = screen.blocks)
            when (val outcome = call(request)) {
                is AssistOutcome.Error -> assistant.showError(outcome.message, retry = true)
                is AssistOutcome.Success -> {
                    val byId = screen.blocks.associateBy { it.id }
                    val lines = outcome.result.translations.mapNotNull { t -> byId[t.id]?.let { TranslatedLine(it.text, t.text) } }
                    if (lines.isEmpty()) assistant.showError("这一屏没有需要翻译的文字", retry = false)
                    else assistant.showTranslations(lines, outcome.result.demo)
                }
            }
        }
    }

    override fun onGenerateReply(intent: String, tone: String) {
        val screen = page ?: return assistant.showError("页面内容已失效，请关闭后重新点悬浮球", retry = false)
        if (work?.isActive == true) return
        assistant.showReplyWorking()
        work = scope.launch {
            val budget = PageContext.MAX_BYTES - intent.toByteArray(Charsets.UTF_8).size
            // Keep the most recent (bottom) blocks that fit next to the user's intent.
            val blocks = screen.blocks.reversed().runningFold(0 to listOf<ContextBlock>()) { (used, acc), block ->
                (used + block.text.toByteArray(Charsets.UTF_8).size) to (acc + block)
            }.lastOrNull { it.first <= budget }?.second?.reversed().orEmpty()
            if (blocks.isEmpty()) { assistant.showError("回复要求太长，请缩短后重试", retry = false); return@launch }
            val request = AssistRequest(operation = AssistOperation.REPLY, targetLanguage = prefs.replyLanguage, pageType = "auto",
                sourcePackage = screen.sourcePackage, targetBlockId = "", userIntent = intent, blocks = blocks, tone = tone)
            when (val outcome = call(request)) {
                is AssistOutcome.Error -> assistant.showError(outcome.message, retry = false)
                is AssistOutcome.Success -> with(outcome.result) { assistant.showReply(text, notes, questions, demo) }
            }
        }
    }

    private suspend fun call(request: AssistRequest): AssistOutcome {
        val settings = withContext(Dispatchers.IO) { runCatching { ProxySettingsStore(this@InspectorService).load() }.getOrNull() }
            ?: return AssistOutcome.Error("还没配置翻译代理：打开“上下文助手”App 设置")
        return repository.generate(settings, request)
    }

    override fun onCopy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("上下文助手", text))
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
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("上下文助手", text))
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
        work?.cancel()
        gate.invalidate()
        page = null
        refreshBubble()
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
        work?.cancel(); expiry?.cancel(); gate.stop(); page = null
        if (::assistant.isInitialized) assistant.destroy()
        InspectorState.update { it.copy(connected = false, message = message) }
    }

    override fun onInterrupt() { work?.cancel(); gate.invalidate() }
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
        private var serviceReference = WeakReference<InspectorService>(null)
        var instance: InspectorService?
            get() = serviceReference.get()
            private set(value) { serviceReference = WeakReference(value) }
    }
}
