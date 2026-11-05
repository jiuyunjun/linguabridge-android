package dev.linguabridge.domain

import java.util.UUID

enum class PageSource { ACCESSIBILITY, SYNTHETIC, MANUAL, SCREENSHOT }

data class CaptureTarget(val packageName: String, val label: String = packageName) {
    init { require(isAllowedPackage(packageName)) { "请选择普通内容应用" } }
    companion object {
        private val pattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val denied = setOf("com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.miui.securitycenter")
        fun isAllowedPackage(value: String): Boolean = value.length <= 200 && pattern.matches(value) &&
            value !in denied && !value.startsWith("dev.linguabridge") &&
            !value.contains("inputmethod", ignoreCase = true)
    }
}

data class ContextBlock(val id: String, val path: String, val bounds: NodeBounds, val text: String)
data class ReviewedPage(val captureId: String, val sourcePackage: String, val source: PageSource,
                        val blocks: List<ContextBlock>, val notices: List<String>)

object PageContext {
    const val MAX_BYTES = 6000
    const val MAX_BLOCKS = 60
    fun from(snapshot: UiSnapshot): ReviewedPage {
        val output = mutableListOf<ContextBlock>()
        val notices = mutableListOf<String>()
        if (snapshot.truncated) notices += "原始页面检查已达到上限，内容可能不完整。"
        var budget = MAX_BYTES
        val candidates = snapshot.nodes.sortedWith(compareBy<InspectedNode> { it.bounds.top }.thenBy { it.bounds.left })
            .mapNotNull { node -> (node.text?.takeIf { it.isNotBlank() }
                ?: node.description?.takeIf { it.isNotBlank() })?.let { node to it } }
        for ((node, original) in candidates) {
            if (output.size >= MAX_BLOCKS || budget <= 0) { notices += "部分页面文字未加入：已达到本次上下文上限。"; break }
            val text = takeUtf8(takeCodePoints(original, 1500), budget)
            if (text.isBlank()) { notices += "部分页面文字未加入：剩余字节预算不足。"; break }
            if (text != original) notices += "有文字段落被截断，请在上传前核对。"
            output += ContextBlock("b${output.size + 1}", node.path, node.bounds, text)
            budget -= text.toByteArray(Charsets.UTF_8).size
        }
        return ReviewedPage(snapshot.captureId, snapshot.sourcePackage, snapshot.source, output.toList(), notices.distinct())
    }

    private val nonLanguage = Regex("""^[\p{N}\p{P}\p{S}\s]*$""")

    /**
     * Whole-screen text for the floating assistant: visible `text` fields only (icon descriptions
     * are skipped), top-to-bottom, without numbers/time-only labels or exact duplicates at the same
     * position. Repeated text at different positions is kept: it can be a real repeated message.
     */
    fun screen(snapshot: UiSnapshot): ReviewedPage = from(snapshot.copy(nodes = screenNodes(snapshot)))

    /** The text nodes [screen] keeps, before any upload budget is applied. */
    fun screenNodes(snapshot: UiSnapshot): List<InspectedNode> {
        val seen = HashSet<Pair<NodeBounds, String>>()
        return snapshot.nodes.filter { node ->
            val text = node.text?.trim().orEmpty()
            text.isNotEmpty() && !nonLanguage.matches(text) && seen.add(node.bounds to text)
        }.map { it.copy(description = null) }
    }

    /**
     * Context from screenshot-recognized text (top-to-bottom order as returned by the model).
     * No real node bounds exist, so bounds are synthetic and only encode order.
     */
    fun fromScreenshotTexts(sourcePackage: String, texts: List<String>): ReviewedPage {
        val nodes = texts.map { it.trim() }.filter { it.isNotEmpty() && !nonLanguage.matches(it) }.mapIndexed { i, text ->
            InspectedNode("s/$i", "s", "screenshot.Text", null, NodeBounds(0, i, 1, i + 1), text, null)
        }
        return from(UiSnapshot(System.currentTimeMillis(), -1, nodes, false, sourcePackage = sourcePackage, source = PageSource.SCREENSHOT))
    }

    fun sampleMail(): UiSnapshot = UiSnapshot(System.currentTimeMillis(), -1, listOf(
        InspectedNode("0/0", "0", "synthetic.Text", null, NodeBounds(0, 0, 500, 60), "Subject: Rescheduling our meeting", null),
        InspectedNode("0/1", "0", "synthetic.Text", null, NodeBounds(0, 70, 500, 120), "From: Alice (fictional example)", null),
        InspectedNode("0/2", "0", "synthetic.Text", null, NodeBounds(0, 130, 500, 400), "Could we move our meeting to next week? Please let me know when you are available.", null),
    ), false, sourcePackage = "demo.synthetic", source = PageSource.SYNTHETIC)
}

fun takeCodePoints(value: String, count: Int): String =
    if (value.codePointCount(0, value.length) <= count) value else value.substring(0, value.offsetByCodePoints(0, count))

fun takeUtf8(value: String, byteLimit: Int): String {
    var end = 0
    var used = 0
    while (end < value.length) {
        val next = end + Character.charCount(value.codePointAt(end))
        val size = value.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (used + size > byteLimit) break
        used += size; end = next
    }
    return value.substring(0, end)
}

enum class AssistOperation { TRANSLATE, TRANSLATE_PAGE, REPLY }

/** Same tone ids as the LinguaBridge extension. */
val REPLY_TONES = setOf("", "formal", "casual", "snarky", "rude")
data class AssistRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val operation: AssistOperation,
    val targetLanguage: String,
    val pageType: String,
    val sourcePackage: String,
    val targetBlockId: String,
    val userIntent: String,
    val blocks: List<ContextBlock>,
    val tone: String = "",
) {
    fun validate() {
        require(targetLanguage in setOf("zh", "ja", "en") || (operation == AssistOperation.REPLY && targetLanguage == "auto")) { "请选择目标语言" }
        require(tone in REPLY_TONES && (tone.isEmpty() || operation == AssistOperation.REPLY)) { "语气无效" }
        require(pageType in setOf("auto", "chat", "email", "article", "other")) { "页面类型无效" }
        require(CaptureTarget.isAllowedPackage(sourcePackage)) { "页面来源无效" }
        require(blocks.isNotEmpty() && blocks.size <= 60) { "请选择 1–60 段上下文" }
        require(blocks.map { it.id }.distinct().size == blocks.size) { "段落标识重复，请重新检查页面" }
        require(blocks.all { it.text.isNotBlank() && it.text.codePointCount(0, it.text.length) <= 1500 }) { "每段文字须为 1–1500 字符" }
        require(userIntent.codePointCount(0, userIntent.length) <= 2000) { "回复要求最多 2000 字符" }
        require((blocks.sumOf { it.text.toByteArray(Charsets.UTF_8).size } + userIntent.toByteArray(Charsets.UTF_8).size) <= PageContext.MAX_BYTES) { "上下文与回复要求超出 6000 字节预算，请取消选择或缩短文字" }
        when (operation) {
            AssistOperation.TRANSLATE -> {
                require(userIntent.isEmpty()) { "翻译模式不接受回复要求" }
                require(blocks.any { it.id == targetBlockId }) { "请在选中的上下文中指定翻译目标" }
            }
            AssistOperation.TRANSLATE_PAGE -> require(userIntent.isEmpty() && targetBlockId.isEmpty()) { "整屏翻译不接受目标段落或回复要求" }
            AssistOperation.REPLY -> require(userIntent.isNotBlank()) { "请输入回复意图或草稿" }
        }
        require(targetBlockId.isEmpty() || blocks.any { it.id == targetBlockId }) { "目标文字不在选中范围" }
    }
}

data class BlockTranslation(val id: String, val text: String)

data class AssistResult(val text: String, val notes: String, val needsClarification: Boolean,
                        val questions: List<String>, val model: String, val demo: Boolean,
                        val translations: List<BlockTranslation> = emptyList())
