package dev.linguabridge.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FreshRootInspectionTest {
    private class Node(
        override val visible: Boolean = true,
        override val sensitive: Boolean = false,
        override val packageName: String = LINE_PACKAGE,
    ) : InspectionNode {
        var reads = 0
        var released = false
        override val className = "synthetic.Root"
        override val resourceId: String? = null
        override val bounds = NodeBounds(0, 0, 100, 100)
        override val text get() = "VISIBLE_TEST".also { reads++ }
        override val description: String? = null
        override val childCount = 0
        override fun child(index: Int): InspectionNode? = error("no children")
        override fun release() { check(!released); released = true }
    }

    @Test fun staleRootIsComparedWithFreshRootAndTextIsTraversedOnce() = runBlocking {
        val baseline = Node(visible = false)
        val fresh = Node()
        val final = Node()
        val roots = ArrayDeque(listOf(baseline, fresh, final))
        var clears = 0
        val result = FreshRootInspection().collect({ InspectionRoot(7, roots.removeFirst()) },
            { clears++; true }, { error("ready root needs no wait") }, { true }, { 123L }) as CaptureResult.Success
        assertEquals(1, clears)
        assertEquals(2, result.snapshot.diagnostics.rootProbes.size)
        assertEquals(listOf(null, true), result.snapshot.diagnostics.rootProbes.map { it.cacheCleared })
        assertEquals(0, baseline.reads + final.reads)
        assertEquals(1, fresh.reads)
        assertTrue(listOf(baseline, fresh, final).all { it.released })
    }

    @Test fun emptyRootsUseBoundedRetriesAndNeverReadHiddenText() = runBlocking {
        val nodes = List(5) { Node(visible = false) }
        val roots = ArrayDeque(nodes)
        val waits = mutableListOf<Long>()
        var elapsed = 0L
        val result = FreshRootInspection().collect({ InspectionRoot(7, roots.removeFirst()) },
            { true }, { waits += it; elapsed += it }, { true }, { elapsed }) as CaptureResult.Success
        assertEquals(listOf(500L, 750L), waits)
        assertEquals(listOf(0L, 0L, 500L, 1250L), result.snapshot.diagnostics.rootProbes.map { it.elapsedMillis })
        assertTrue(result.snapshot.nodes.isEmpty())
        assertEquals(0, nodes.sumOf { it.reads })
        assertTrue(nodes.all { it.released })
    }

    @Test fun noAuthorizationDoesNotRequestAnyRoot() = runBlocking {
        val result = FreshRootInspection().collect({ error("unauthorized root read") },
            { error("unauthorized cache access") }, {}, { false }, { 0L })
        assertEquals(CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED), result)
    }

    @Test fun changedWindowFailsBeforeTextRead() = runBlocking {
        val baseline = Node(visible = false)
        val changed = Node()
        val roots = ArrayDeque(listOf(InspectionRoot(7, baseline), InspectionRoot(8, changed)))
        val result = FreshRootInspection().collect({ roots.removeFirst() }, { true }, {}, { true }, { 0L })
        assertEquals(CaptureResult.Failure(CaptureFailure.WINDOW_CHANGED), result)
        assertEquals(0, baseline.reads + changed.reads)
        assertTrue(baseline.released && changed.released)
    }

    @Test fun revocationDuringWaitStopsFurtherQueries() = runBlocking {
        var authorized = true
        val nodes = List(2) { Node(visible = false) }
        val roots = ArrayDeque(nodes)
        val result = FreshRootInspection().collect({ InspectionRoot(7, roots.removeFirst()) },
            { true }, { authorized = false }, { authorized }, { 0L })
        assertEquals(CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED), result)
        assertTrue(roots.isEmpty())
        assertTrue(nodes.all { it.released && it.reads == 0 })
    }

    @Test fun sensitiveRootRemainsBlockedAfterCacheClear() = runBlocking {
        val nodes = List(3) { Node(sensitive = true) }
        val roots = ArrayDeque(nodes)
        val result = FreshRootInspection().collect({ InspectionRoot(7, roots.removeFirst()) },
            { true }, { error("sensitive roots do not retry") }, { true }, { 0L }) as CaptureResult.Success
        assertTrue(result.snapshot.nodes.isEmpty())
        assertTrue(result.snapshot.diagnostics.rootProbes.all { it.className == null && it.childCount == null })
        assertTrue(nodes.all { it.released && it.reads == 0 })
    }
    @Test fun changingBackToWeChatBeforeCommitDiscardsLineSnapshot() = runBlocking {
        val baseline = Node(visible = false)
        val fresh = Node()
        val old = Node(packageName = "com.tencent.mm")
        val roots = ArrayDeque(listOf(baseline, fresh, old))
        val result = FreshRootInspection().collect({ InspectionRoot(7, roots.removeFirst()) },
            { true }, {}, { true }, { 0L })
        assertEquals(CaptureResult.Failure(CaptureFailure.WINDOW_CHANGED), result)
        assertEquals(0, old.reads)
        assertTrue(listOf(baseline, fresh, old).all { it.released })
    }
}
