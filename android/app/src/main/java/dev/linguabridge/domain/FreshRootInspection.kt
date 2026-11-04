package dev.linguabridge.domain

import kotlinx.coroutines.CancellationException

data class InspectionRoot(val windowId: Int, val node: InspectionNode)

/** One authorized inspection: bounded metadata probes followed by at most ONE text traversal. */
class FreshRootInspection(private val expectedPackage: String = LINE_PACKAGE,
                          private val collector: UiSnapshotCollector = UiSnapshotCollector(expectedPackage = expectedPackage)) {
    suspend fun collect(
        readRoot: () -> InspectionRoot?,
        clearNodeCache: () -> Boolean?,
        pause: suspend (Long) -> Unit,
        isCurrent: () -> Boolean,
        now: () -> Long,
    ): CaptureResult {
        val started = now()
        val probes = mutableListOf<RootProbeObservation>()
        var expectedWindow: Int? = null
        fun failure(reason: CaptureFailure) = CaptureResult.Failure(reason)
        try {
            // Baseline before clearing our accessibility client cache, then at most 3 fresh roots.
            val waits = listOf(0L, 0L, 500L, 750L)
            for ((attempt, wait) in waits.withIndex()) {
                if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                if (wait > 0) pause(wait)
                if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                val cleared = if (attempt == 0) null else clearNodeCache()
                if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                val root = readRoot() ?: return failure(CaptureFailure.NO_WINDOW)
                var collectorOwnsRoot = false
                try {
                    if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                    if (root.node.packageName != expectedPackage) return failure(CaptureFailure.WRONG_PACKAGE)
                    if (expectedWindow != null && expectedWindow != root.windowId) return failure(CaptureFailure.WINDOW_CHANGED)
                    expectedWindow = root.windowId
                    val sensitive = root.node.sensitiveReason
                    val visible = root.node.visible
                    // Do not inspect even structural fields of a sensitive subtree.
                    val childCount = if (sensitive == null) root.node.childCount else null
                    val className = if (sensitive == null) root.node.className?.take(200) else null
                    probes += RootProbeObservation((now() - started).coerceAtLeast(0), cleared,
                        root.windowId, visible, sensitive, childCount, className)
                    if (attempt == 0) continue
                    if (sensitive == null && !visible && childCount == 0 && attempt < waits.lastIndex) continue
                    if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                    collectorOwnsRoot = true
                    val result = collector.collect(root.node, true, root.windowId, now(), isCurrent)
                    if (result !is CaptureResult.Success) return result
                    if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                    val current = readRoot() ?: return failure(CaptureFailure.NO_WINDOW)
                    val unchanged = try {
                        current.node.packageName == expectedPackage && current.windowId == root.windowId
                    } finally { current.node.release() }
                    if (!unchanged) return failure(CaptureFailure.WINDOW_CHANGED)
                    if (!isCurrent()) return failure(CaptureFailure.NOT_AUTHORIZED)
                    return CaptureResult.Success(result.snapshot.copy(diagnostics = result.snapshot.diagnostics.copy(
                        rootProbes = probes.toList(),
                    )))
                } finally { if (!collectorOwnsRoot) root.node.release() }
            }
            return failure(CaptureFailure.NO_WINDOW)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(if (isCurrent()) CaptureFailure.READ_FAILED else CaptureFailure.NOT_AUTHORIZED)
        }
    }
}
