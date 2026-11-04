package dev.linguabridge.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.linguabridge.accessibility.InspectorState
import dev.linguabridge.data.AssistantPrefs
import dev.linguabridge.data.FixtureExporter
import dev.linguabridge.data.ProxySettingsStore
import dev.linguabridge.data.api.OpenAIDirectClient
import dev.linguabridge.data.api.ProxySettings
import dev.linguabridge.domain.CaptureTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0F1B33)
private val Card = Color(0xFF172440)
private val Line = Color(0xFF2C3A57)
private val TextMain = Color(0xFFEAF2FF)
private val Muted = Color(0xFFA8B7D4)
private val Accent = Color(0xFF5ED0FF)
private val AccentDim = Color(0xFF19304D)
private val OnAccent = Color(0xFF08233A)
private val Ok = Color(0xFF67E3A1)
private val Warn = Color(0xFFFFD36B)
private val Danger = Color(0xFFFF8B8B)

/** Settings only. Translation and replies happen in the floating bubble over other apps. */
class InspectorActivity : ComponentActivity() {
    private var resumeEpoch by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); resumeEpoch++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, background = Bg, surface = Bg,
                onSurface = TextMain, onBackground = TextMain, outline = Line)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { SettingsScreen() }
            }
        }
    }

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    @Composable
    private fun SettingsScreen() {
        val status by InspectorState.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val store = remember { ProxySettingsStore(this) }
        val prefs = remember { AssistantPrefs(this) }
        val direct = remember { OpenAIDirectClient.fromBuildConfig() }
        var enabled by remember { mutableStateOf(false) }
        var settings by remember { mutableStateOf<ProxySettings?>(null) }
        var baseUrl by remember { mutableStateOf("") }
        var tokenInput by remember { mutableStateOf("") }
        var editingProxy by remember { mutableStateOf(false) }
        var translateLanguage by remember { mutableStateOf(prefs.translateLanguage) }
        var replyLanguage by remember { mutableStateOf(prefs.replyLanguage) }
        var bubbleEnabled by remember { mutableStateOf(prefs.bubbleEnabled) }
        var hidden by remember { mutableStateOf(prefs.hiddenPackages) }
        var apps by remember { mutableStateOf<List<CaptureTarget>>(emptyList()) }
        var chooseApp by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        var model by remember { mutableStateOf(prefs.openAiModel.ifBlank { direct?.defaultModel.orEmpty() }) }
        var chooseModel by remember { mutableStateOf(false) }
        var openWith by remember { mutableStateOf(prefs.openWith) }

        LaunchedEffect(resumeEpoch) {
            enabled = serviceEnabled()
            val loaded = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
            settings = loaded; baseUrl = loaded?.baseUrl.orEmpty(); tokenInput = ""
        }
        LaunchedEffect(Unit) {
            val keyboards = getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
            apps = withContext(Dispatchers.IO) {
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .mapNotNull { info -> info.activityInfo.packageName.takeIf { CaptureTarget.isAllowedPackage(it) && it !in keyboards }
                        ?.let { CaptureTarget(it, info.loadLabel(packageManager).toString()) } }
                    .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
        }
        fun label(pkg: String) = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg

        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text("上下文翻译 · 回复", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                Text("LinguaBridge", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("配置一次后，在任意 App 里点悬浮球即可翻译整屏，或写一句话生成回复。", color = Muted, fontSize = 14.sp)
            }

            Section {
                StatusRow(if (enabled && status.connected) Ok else Warn, "无障碍服务",
                    if (enabled && status.connected) "悬浮球已就绪" else "未开启：在系统设置里打开“LinguaBridge”") {
                    SmallButton("系统设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
                HorizontalDivider(color = Line)
                if (direct != null) StatusRow(if (model.isNotBlank()) Ok else Warn, "OpenAI 直连（调试）",
                    (if (model.isBlank()) "未选择模型" else "模型 $model") + " · Key 在本 APK 内，勿外传") {
                    SmallButton("更换模型", Modifier.testTag("choose-model")) { chooseModel = true }
                }
                else StatusRow(if (settings != null) Ok else Warn, "翻译代理", settings?.baseUrl ?: "未配置") {
                    SmallButton(if (editingProxy) "收起" else "修改", Modifier.testTag("edit-proxy")) { editingProxy = !editingProxy }
                }
                if (direct == null && (editingProxy || settings == null)) {
                    OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth().testTag("proxy-url"),
                        label = { Text("代理根地址（HTTPS）") }, singleLine = true)
                    OutlinedTextField(tokenInput, { tokenInput = it }, Modifier.fillMaxWidth().testTag("proxy-token"),
                        label = { Text(if (settings == null) "设备 Token" else "新设备 Token（留空保留）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Text("Debug 版仅允许本机 http://127.0.0.1 联调；OpenAI 密钥只放在代理服务器上。", color = Muted, fontSize = 12.sp)
                    Button({
                        scope.launch {
                            notice = try {
                                val candidate = ProxySettings(baseUrl.trim(), tokenInput.ifEmpty { settings?.deviceToken.orEmpty() })
                                withContext(Dispatchers.IO) { store.save(candidate) }
                                settings = candidate; tokenInput = ""; editingProxy = false
                                "代理已保存，Token 已加密"
                            } catch (invalid: IllegalArgumentException) { invalid.message ?: "配置无效" }
                            catch (_: Exception) { "无法安全保存配置" }
                        }
                    }, Modifier.fillMaxWidth().height(48.dp).testTag("save-proxy"), shape = RoundedCornerShape(12.dp)) { Text("保存代理配置") }
                }
            }

            Section {
                Text("翻译成", fontWeight = FontWeight.Medium)
                ChipRow(AssistantPrefs.TRANSLATE_LANGUAGES, translateLanguage, "translate") { translateLanguage = it; prefs.translateLanguage = it }
                Text("点悬浮球后打开", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistantPrefs.OPEN_WITH.forEach { (id, name) ->
                        FilterChip(id == openWith, { openWith = id; prefs.openWith = id }, label = { Text(name) },
                            modifier = Modifier.testTag("open-$id"), shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim, selectedLabelColor = Accent, labelColor = TextMain))
                    }
                }
                Text("只回复时不会请求翻译；切到“翻译”标签才翻译。", color = Muted, fontSize = 12.sp)
                Text("回复用", fontWeight = FontWeight.Medium)
                ChipRow(AssistantPrefs.REPLY_LANGUAGES, replyLanguage, "reply") { replyLanguage = it; prefs.replyLanguage = it }
            }

            Section {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("显示悬浮球")
                        Text("长按悬浮球可临时隐藏；拖动可调整位置", color = Muted, fontSize = 12.sp)
                    }
                    Switch(bubbleEnabled, { bubbleEnabled = it; prefs.bubbleEnabled = it }, Modifier.testTag("bubble-switch"))
                }
                HorizontalDivider(color = Line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("不显示悬浮球的 App")
                        Text("建议加入银行、支付、密码管理等 App", color = Muted, fontSize = 12.sp)
                    }
                    SmallButton("添加") { chooseApp = true }
                }
                hidden.sortedBy { label(it).lowercase() }.forEach { pkg ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label(pkg), Modifier.weight(1f), fontSize = 14.sp)
                        TextButton({ hidden = hidden - pkg; prefs.hiddenPackages = hidden }) { Text("移除", color = Muted) }
                    }
                }
            }

            Text((if (direct != null) "点悬浮球时读取当前屏幕可见文字，直接发到 OpenAI。" else "点悬浮球时读取当前屏幕可见文字，发到你的代理（再到 OpenAI）。") + "密码框、输入框里的文字不读；原文不保存。回复只在你点“填入”时写进输入框，不会自动发送。",
                color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            if (notice.isNotEmpty()) Text(notice, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp).testTag("notice"))
            TextButton({
                scope.launch {
                    notice = try {
                        withContext(Dispatchers.IO) { store.clear(); prefs.clear(); FixtureExporter.clear(this@InspectorActivity) }
                        settings = null; baseUrl = ""; translateLanguage = prefs.translateLanguage; replyLanguage = prefs.replyLanguage
                        bubbleEnabled = prefs.bubbleEnabled; hidden = prefs.hiddenPackages
                        model = direct?.defaultModel.orEmpty(); openWith = prefs.openWith
                        "本机配置和导出已清空"
                    } catch (_: Exception) { "部分数据清理失败，可在系统中清除应用数据" }
                }
            }, Modifier.testTag("clear-all")) { Text("清空本机配置", color = Danger) }
        }

        if (chooseModel && direct != null) {
            var loading by remember { mutableStateOf(true) }
            var ids by remember { mutableStateOf<List<String>>(emptyList()) }
            var error by remember { mutableStateOf("") }
            var search by remember { mutableStateOf("") }
            var reload by remember { mutableIntStateOf(0) }
            LaunchedEffect(reload) {
                loading = true; error = ""
                when (val result = direct.listChatModels()) {
                    is OpenAIDirectClient.ModelList.Success -> ids = result.ids
                    is OpenAIDirectClient.ModelList.Error -> error = result.message
                }
                loading = false
            }
            AlertDialog(onDismissRequest = { chooseModel = false }, title = { Text("选择模型") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("来自你的 API Key 可用的 /v1/models，只列出聊天类模型（按名称规则过滤）。", color = Muted, fontSize = 12.sp)
                    OutlinedTextField(search, { search = it }, label = { Text("搜索") }, singleLine = true)
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("  正在获取…", color = Muted)
                        }
                        error.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(error, color = Danger, modifier = Modifier.weight(1f)); TextButton({ reload++ }) { Text("重试") }
                        }
                        ids.isEmpty() -> Text("没有找到聊天模型", color = Muted)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp).testTag("model-list")) {
                        items(ids.filter { search.isBlank() || it.contains(search.trim(), true) }, key = { it }) { id ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(id == model, { model = id; prefs.openAiModel = id; chooseModel = false; notice = "已切换到 $id" })
                                Text(id, Modifier.weight(1f), fontSize = 14.sp)
                                if (id == direct.defaultModel) Text("默认", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ chooseModel = false }) { Text("关闭") } }, dismissButton = {
                if (direct.defaultModel.isNotBlank() && model != direct.defaultModel) TextButton({
                    prefs.openAiModel = ""; model = direct.defaultModel; chooseModel = false; notice = "已恢复默认模型 ${direct.defaultModel}"
                }) { Text("恢复默认") }
            })
        }

        if (chooseApp) {
            var search by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { chooseApp = false }, title = { Text("在这些 App 中不显示悬浮球") }, text = {
                Column {
                    OutlinedTextField(search, { search = it }, label = { Text("搜索应用") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apps.filter { it.packageName !in hidden && (search.isBlank() || it.label.contains(search, true)) }, key = { it.packageName }) { app ->
                            TextButton({ hidden = hidden + app.packageName; prefs.hiddenPackages = hidden; chooseApp = false },
                                Modifier.fillMaxWidth()) { Text(app.label, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }, confirmButton = { TextButton({ chooseApp = false }) { Text("关闭") } })
        }
    }

    @Composable
    private fun Section(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }

    @Composable
    private fun StatusRow(dot: Color, title: String, subtitle: String, action: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 2)
            }
            action()
        }
    }

    @Composable
    private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        OutlinedButton(onClick, modifier.heightIn(min = 40.dp), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) { Text(text, fontSize = 13.sp) }
    }

    @Composable
    private fun ChipRow(options: List<String>, selected: String, tagPrefix: String, onSelect: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { id ->
                FilterChip(id == selected, { onSelect(id) }, label = { Text(AssistantPrefs.LANGUAGE_LABELS.getValue(id)) },
                    modifier = Modifier.testTag("$tagPrefix-$id"), shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim, selectedLabelColor = Accent, labelColor = TextMain))
            }
        }
    }
}
