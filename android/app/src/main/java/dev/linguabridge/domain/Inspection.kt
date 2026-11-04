package dev.linguabridge.domain

// Default synthetic/test target. Production captures always supply the explicitly chosen package.
const val LINE_PACKAGE = "jp.naver.line.android"

data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Immutable observations, never message identities or sent timestamps. */
data class InspectedNode(
    val path: String,
    val parentPath: String?,
    val className: String?,
    val resourceId: String?,
    val bounds: NodeBounds,
    val text: String?,
    val description: String?,
)

data class UiSnapshot(
    val capturedAtEpochMillis: Long,
    val windowId: Int,
    val nodes: List<InspectedNode>,
    val truncated: Boolean,
    val diagnostics: InspectionDiagnostics = InspectionDiagnostics(),
    val sourcePackage: String = LINE_PACKAGE,
    val source: PageSource = PageSource.ACCESSIBILITY,
    val captureId: String = java.util.UUID.randomUUID().toString(),
)

/** Safe counts/root metadata only; no text, descriptions or fields from blocked subtrees. */
data class InspectionDiagnostics(
    val visitedNodes: Int = 0,
    val excludedSubtrees: Map<ExclusionReason, Int> = emptyMap(),
    val rootExclusion: ExclusionReason? = null,
    val invisibleNodesSkipped: Int = 0,
    val childRequests: Int = 0,
    val nullChildren: Int = 0,
    val rootProbes: List<RootProbeObservation> = emptyList(),
    val serviceFlags: Int? = null,
    val serviceCapabilities: Int? = null,
)

/** Metadata of an explicitly authorized LINE root; never its text, description or title. */
data class RootProbeObservation(
    val elapsedMillis: Long,
    val cacheCleared: Boolean?,
    val windowId: Int,
    val visible: Boolean,
    val sensitiveReason: ExclusionReason?,
    val childCount: Int?,
    val className: String?,
)

enum class ExclusionReason(val explanation: String) {
    FOREIGN_PACKAGE("节点不属于本次选定应用"),
    NOT_VISIBLE("节点被系统标记为不可见"),
    SENSITIVE_FIELD("节点被标记为敏感字段"),
    PASSWORD("密码字段"),
    EDITABLE("可编辑字段"),
    INPUT_TYPE("节点带有输入类型"),
    EDIT_TEXT_CLASS("输入框控件"),
    ACCESSIBILITY_DATA_SENSITIVE("系统将节点的无障碍数据标记为敏感"),
}

sealed interface CaptureResult {
    data class Success(val snapshot: UiSnapshot) : CaptureResult
    data class Failure(val reason: CaptureFailure) : CaptureResult
}

enum class CaptureFailure { NOT_AUTHORIZED, WRONG_PACKAGE, NO_WINDOW, LOCKED, WINDOW_CHANGED, READ_FAILED }

/** A test seam. Sensitive subtrees must be excluded BEFORE any text accessor is called. */
interface InspectionNode {
    val packageName: String?
    val visible: Boolean
    val sensitive: Boolean
    val sensitiveReason: ExclusionReason?
        get() = if (sensitive) ExclusionReason.SENSITIVE_FIELD else null
    val className: String?
    val resourceId: String?
    val bounds: NodeBounds
    val text: String?
    val description: String?
    val childCount: Int
    fun child(index: Int): InspectionNode?
    fun release()
}

class UiSnapshotCollector(
    private val maxNodes: Int = 400,
    private val maxDepth: Int = 24,
    private val maxTextLength: Int = 1000,
    private val maxTotalCharacters: Int = 16000,
    private val expectedPackage: String = LINE_PACKAGE,
) {
    init {
        require(maxNodes > 0 && maxDepth >= 0 && maxTextLength > 0 && maxTotalCharacters > 0)
        require(CaptureTarget.isAllowedPackage(expectedPackage))
    }

    // Owns and releases root, including failure paths. This is a debug tree, NOT parsed messages.
    fun collect(root: InspectionNode, authorized: Boolean, windowId: Int, now: Long,
                isCurrent: () -> Boolean = { true }): CaptureResult {
        val nodes = mutableListOf<InspectedNode>()
        var truncated = false
        var visited = 0
        var childRequests = 0
        var characters = 0
        val exclusions = mutableMapOf<ExclusionReason, Int>()
        var rootExclusion: ExclusionReason? = null
        var invisibleNodesSkipped = 0
        var nullChildren = 0
        fun bounded(value: String?): String? {
            if (value == null) return null
            val budget = minOf(maxTextLength, maxTotalCharacters - characters)
            if (value.codePointCount(0, value.length) > budget) truncated = true
            return takeCodePoints(value, budget).also { characters += it.codePointCount(0, it.length) }
        }
        fun visit(node: InspectionNode, path: String, parent: String?, depth: Int, release: Boolean = true) {
            try {
                check(isCurrent())
                if (visited >= maxNodes || depth > maxDepth) { truncated = true; return }
                visited++
                val exclusion = when {
                    node.packageName != expectedPackage -> ExclusionReason.FOREIGN_PACKAGE
                    else -> node.sensitiveReason
                }
                if (exclusion != null) {
                    exclusions[exclusion] = exclusions.getOrDefault(exclusion, 0) + 1
                    if (depth == 0) rootExclusion = exclusion
                    return
                }
                // Visibility describes this node, not every descendant in a provider's tree.
                // Check sensitive/package gates above before traversing even an invisible container.
                if (node.visible) {
                    val className = node.className?.take(200)
                    val resourceId = node.resourceId?.take(300)
                    val bounds = node.bounds
                    check(isCurrent())
                    val text = bounded(node.text)
                    check(isCurrent())
                    nodes += InspectedNode(path, parent, className, resourceId, bounds, text, bounded(node.description))
                } else {
                    invisibleNodesSkipped++
                    if (depth == 0) rootExclusion = ExclusionReason.NOT_VISIBLE
                }
                if (depth >= maxDepth) {
                    if (node.childCount > 0) truncated = true
                    return
                }
                for (i in 0 until node.childCount) {
                    if (visited >= maxNodes || childRequests >= maxNodes - 1) { truncated = true; break }
                    childRequests++
                    val child = node.child(i)
                    if (child == null) nullChildren++ else visit(child, "$path/$i", path, depth + 1)
                }
            } finally { if (release) node.release() }
        }
        return try {
            if (!authorized || !isCurrent()) {
                CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED)
            } else if (root.packageName != expectedPackage) {
                CaptureResult.Failure(CaptureFailure.WRONG_PACKAGE)
            } else {
                visit(root, "0", null, 0, release = false)
                CaptureResult.Success(UiSnapshot(now, windowId, nodes.toList(), truncated,
                    InspectionDiagnostics(visited, exclusions.toMap(), rootExclusion,
                        invisibleNodesSkipped, childRequests, nullChildren), sourcePackage = expectedPackage))
            }
        } catch (_: Exception) {
            CaptureResult.Failure(if (isCurrent()) CaptureFailure.READ_FAILED else CaptureFailure.NOT_AUTHORIZED)
        } finally { root.release() }
    }
}

/** No persistent consent and no title-based identity. Each inspection needs a fresh confirmation. */
class InspectionGate {
    @Volatile var generation: Long = 0; private set
    @Volatile var armed: Boolean = false; private set
    fun arm() { generation++; armed = true }
    fun stop() { generation++; armed = false }
    fun invalidate() { generation++ }
    fun canCommit(ticket: Long) = armed && ticket == generation
}
