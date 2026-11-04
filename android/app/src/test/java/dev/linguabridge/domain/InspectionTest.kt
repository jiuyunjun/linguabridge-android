package dev.linguabridge.domain

import dev.linguabridge.adapter.ParseResult
import dev.linguabridge.adapter.LineUiAdapter
import org.junit.Assert.*
import org.junit.Test

class InspectionTest {
    private class FakeNode(
        private val value: String = "hello",
        override val packageName: String? = LINE_PACKAGE,
        override val sensitive: Boolean = false,
        override val visible: Boolean = true,
        private val children: List<FakeNode> = emptyList(),
    ) : InspectionNode {
        var textReads = 0
        var released = false
        override val className = "synthetic.Node"
        override val resourceId: String? = null
        override val bounds = NodeBounds(0, 0, 100, 100)
        override val text get() = value.also { textReads++ }
        override val description: String? = null
        override val childCount get() = children.size
        override fun child(index: Int) = children[index]
        override fun release() { check(!released); released = true }
    }

    private fun collect(node: FakeNode, collector: UiSnapshotCollector = UiSnapshotCollector()) =
        (collector.collect(node, true, 7, 123L) as CaptureResult.Success).snapshot

    @Test fun disabledNeverReadsText() {
        val root = FakeNode()
        assertEquals(CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED), UiSnapshotCollector().collect(root, false, 1, 0))
        assertEquals(0, root.textReads)
        assertTrue(root.released)
    }
    @Test fun otherPackageNeverReadsText() {
        val root = FakeNode(packageName = "bank.synthetic")
        assertEquals(CaptureResult.Failure(CaptureFailure.WRONG_PACKAGE), UiSnapshotCollector().collect(root, true, 1, 0))
        assertEquals(0, root.textReads)
        assertTrue(root.released)
    }
    @Test fun previousWeChatTargetIsRejectedBeforeTextRead() {
        val root = FakeNode("old target", packageName = "com.tencent.mm")
        assertEquals(CaptureResult.Failure(CaptureFailure.WRONG_PACKAGE), UiSnapshotCollector().collect(root, true, 1, 0))
        assertEquals(0, root.textReads)
        assertTrue(root.released)
    }
    @Test fun explicitMailPackageIsSupportedWithoutLineRules() {
        val root = FakeNode("Synthetic email", packageName = "mail.synthetic")
        val result = UiSnapshotCollector(expectedPackage = "mail.synthetic").collect(root, true, 3, 0) as CaptureResult.Success
        assertEquals("mail.synthetic", result.snapshot.sourcePackage)
        assertEquals("Synthetic email", result.snapshot.nodes.single().text)
        assertTrue(root.released)
    }
    @Test fun previousTargetNestedInsideLineIsStillBlocked() {
        val old = FakeNode("old private content", packageName = "com.tencent.mm")
        assertEquals(1, collect(FakeNode(children = listOf(old))).nodes.size)
        assertEquals(0, old.textReads)
        assertTrue(old.released)
    }
    @Test fun sensitiveSubtreesAndInvisibleNodeTextAreExcludedBeforeTextRead() {
        val secretChild = FakeNode("typed-password")
        val password = FakeNode("password", sensitive = true, children = listOf(secretChild))
        val input = FakeNode("unsent", sensitive = true)
        val hidden = FakeNode("invisible", visible = false)
        val root = FakeNode(children = listOf(password, input, hidden))
        assertEquals(1, collect(root).nodes.size)
        assertEquals(0, password.textReads + input.textReads + hidden.textReads + secretChild.textReads)
        assertTrue(password.released && input.released && hidden.released && root.released)
    }
    @Test fun embeddedForeignPackageIsExcluded() {
        val foreign = FakeNode(packageName = "keyboard.synthetic")
        assertEquals(1, collect(FakeNode(children = listOf(foreign))).nodes.size)
        assertEquals(0, foreign.textReads)
    }
    @Test fun repeatedTextRemainsSeparateObservations() {
        val snapshot = collect(FakeNode(children = List(3) { FakeNode("OK") }))
        assertEquals(3, snapshot.nodes.count { it.text == "OK" })
        assertEquals(listOf("0/0", "0/1", "0/2"), snapshot.nodes.drop(1).map { it.path })
        assertEquals(123L, snapshot.capturedAtEpochMillis)
    }
    @Test fun nodeLimitAndDepthLimitAreReported() {
        assertTrue(collect(FakeNode(children = List(10) { FakeNode() }), UiSnapshotCollector(maxNodes = 2)).truncated)
        val root = FakeNode(children = listOf(FakeNode(children = listOf(FakeNode()))))
        assertEquals(1, collect(root, UiSnapshotCollector(maxDepth = 0)).nodes.size)
    }
    @Test fun textAndTotalCharacterBudgetsAreEnforced() {
        val snapshot = collect(FakeNode("123456789", children = listOf(FakeNode("abcdef"))),
            UiSnapshotCollector(maxTextLength = 5, maxTotalCharacters = 7))
        assertEquals(listOf("12345", "ab"), snapshot.nodes.map { it.text })
        assertTrue(snapshot.truncated)
    }
    @Test fun cancellationAndWindowChangeInvalidatePendingGeneration() {
        val gate = InspectionGate()
        gate.arm()
        val ticket = gate.generation
        assertTrue(gate.canCommit(ticket))
        gate.invalidate()
        assertFalse(gate.canCommit(ticket))
        val second = gate.generation
        gate.stop()
        assertFalse(gate.canCommit(second))
        gate.arm()
        assertFalse(gate.canCommit(ticket))
    }
    @Test fun exportHasNoOriginalTextFallback() {
        val original = collect(FakeNode("private chat"))
        assertEquals("[REDACTED]", original.redacted(emptyMap()).nodes.single().text)
        assertEquals("synthetic replacement", original.redacted(mapOf("0:text" to "synthetic replacement")).nodes.single().text)
        assertEquals("private chat", original.nodes.single().text)
    }
    @Test fun parserDoesNotPromoteToolbarDatesOrUnknownNodesToMessages() {
        val snapshot = collect(FakeNode("toolbar", children = listOf(FakeNode("2026-11-04"), FakeNode("OK"))))
        assertTrue(LineUiAdapter().parse(snapshot) is ParseResult.Unverified)
        assertEquals("UNVERIFIED", LineUiAdapter().compatibility)
    }
    @Test fun nullChildrenCannotExceedTraversalBudget() {
        val base = FakeNode()
        var requests = 0
        val root = object : InspectionNode by base {
            override val childCount = Int.MAX_VALUE
            override fun child(index: Int): InspectionNode? { requests++; return null }
        }
        val result = UiSnapshotCollector(maxNodes = 4).collect(root, true, 1, 0) as CaptureResult.Success
        assertEquals(3, requests)
        assertTrue(result.snapshot.truncated)
        assertTrue(base.released)
    }
    @Test fun readFailureReleasesRootAndReturnsTypedFailure() {
        val base = FakeNode()
        val root = object : InspectionNode by base {
            override val text: String get() = error("synthetic read failure")
        }
        assertEquals(CaptureResult.Failure(CaptureFailure.READ_FAILED), UiSnapshotCollector().collect(root, true, 1, 0))
        assertTrue(base.released)
    }
    @Test fun revocationDuringReadDiscardsPartialTree() {
        val base = FakeNode()
        var checks = 0
        val result = UiSnapshotCollector().collect(base, true, 1, 0) { ++checks < 4 }
        assertEquals(CaptureResult.Failure(CaptureFailure.NOT_AUTHORIZED), result)
        assertTrue(base.released)
    }
    @Test fun descriptionsAreAlsoRedactedByDefault() {
        val base = FakeNode()
        val root = object : InspectionNode by base {
            override val description = "private contact description"
        }
        val snapshot = (UiSnapshotCollector().collect(root, true, 1, 0) as CaptureResult.Success).snapshot
        assertEquals("[REDACTED]", snapshot.redacted(emptyMap()).nodes.single().description)
    }
    @Test fun invisibleRootDoesNotHideVisibleChildAndNeverReadsHiddenText() {
        val child = FakeNode("visible test bubble")
        val root = FakeNode("private root", visible = false, children = listOf(child))
        val snapshot = collect(root)
        assertEquals(listOf("visible test bubble"), snapshot.nodes.map { it.text })
        assertEquals("0/0", snapshot.nodes.single().path)
        assertEquals(2, snapshot.diagnostics.visitedNodes)
        assertEquals(ExclusionReason.NOT_VISIBLE, snapshot.diagnostics.rootExclusion)
        assertTrue(snapshot.diagnostics.excludedSubtrees.isEmpty())
        assertEquals(1, snapshot.diagnostics.invisibleNodesSkipped)
        assertEquals(0, root.textReads)
        assertEquals(1, child.textReads)
        assertTrue(child.released)
        assertTrue(root.released)
    }
    @Test fun sensitiveRootReportsReasonWithoutReadingText() {
        val base = FakeNode("private secret", sensitive = true)
        val root = object : InspectionNode by base {
            override val sensitiveReason = ExclusionReason.ACCESSIBILITY_DATA_SENSITIVE
        }
        val snapshot = (UiSnapshotCollector().collect(root, true, 1, 0) as CaptureResult.Success).snapshot
        assertTrue(snapshot.nodes.isEmpty())
        assertEquals(ExclusionReason.ACCESSIBILITY_DATA_SENSITIVE, snapshot.diagnostics.rootExclusion)
        assertEquals(0, base.textReads)
        assertTrue(base.released)
    }
    @Test fun subtreeCountsDoNotFalselyMarkRootAsExcluded() {
        val snapshot = collect(FakeNode(children = listOf(
            FakeNode(sensitive = true), FakeNode(sensitive = true), FakeNode(visible = false),
        )))
        assertEquals(1, snapshot.nodes.size)
        assertEquals(4, snapshot.diagnostics.visitedNodes)
        assertNull(snapshot.diagnostics.rootExclusion)
        assertEquals(mapOf(ExclusionReason.SENSITIVE_FIELD to 2),
            snapshot.diagnostics.excludedSubtrees)
        assertEquals(1, snapshot.diagnostics.invisibleNodesSkipped)
    }
    @Test fun redactionPreservesSafeDiagnosticCounts() {
        val snapshot = collect(FakeNode(children = listOf(FakeNode(sensitive = true))))
        val safe = snapshot.redacted(emptyMap())
        assertEquals(snapshot.diagnostics, safe.diagnostics)
        assertEquals("[REDACTED]", safe.nodes.single().text)
    }
    @Test fun invisibleSensitiveContainerStillBlocksEntireSubtree() {
        val child = FakeNode("unsent private draft")
        val root = FakeNode(sensitive = true, visible = false, children = listOf(child))
        val snapshot = collect(root)
        assertTrue(snapshot.nodes.isEmpty())
        assertEquals(ExclusionReason.SENSITIVE_FIELD, snapshot.diagnostics.rootExclusion)
        assertEquals(0, snapshot.diagnostics.childRequests)
        assertEquals(0, root.textReads + child.textReads)
    }
    @Test fun nestedInvisibleContainersRespectVisibilityOfEachDescendant() {
        val hiddenLeaf = FakeNode("hidden leaf", visible = false)
        val visibleLeaf = FakeNode("visible leaf")
        val root = FakeNode(visible = false, children = listOf(
            FakeNode(visible = false, children = listOf(hiddenLeaf, visibleLeaf)),
        ))
        val snapshot = collect(root)
        assertEquals(listOf("visible leaf"), snapshot.nodes.map { it.text })
        assertEquals(3, snapshot.diagnostics.invisibleNodesSkipped)
        assertEquals(0, hiddenLeaf.textReads)
    }
    @Test fun invisibleContainersConsumeNodeAndDepthBudgets() {
        val root = FakeNode(visible = false, children = listOf(FakeNode("visible")))
        val snapshot = collect(root, UiSnapshotCollector(maxNodes = 1))
        assertTrue(snapshot.nodes.isEmpty())
        assertTrue(snapshot.truncated)
        assertEquals(0, snapshot.diagnostics.childRequests)
        val second = collect(FakeNode(visible = false, children = listOf(FakeNode())),
            UiSnapshotCollector(maxDepth = 0))
        assertTrue(second.truncated)
        assertTrue(second.nodes.isEmpty())
    }
}
