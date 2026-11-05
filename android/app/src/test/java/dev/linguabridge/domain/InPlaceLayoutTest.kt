package dev.linguabridge.domain

import dev.linguabridge.domain.InPlaceInvalidation.Kind
import org.junit.Assert.*
import org.junit.Test

class InPlaceLayoutTest {
    /** Fake measurement: 10px per character at 10sp, wrapped to the width; one line is 2×sp px tall. */
    private val measure = { text: String, width: Int, sp: Float ->
        val perLine = maxOf(1, (width / sp).toInt())
        val lines = (text.length + perLine - 1) / perLine
        (lines * sp * 2).toInt()
    }
    private val layout = InPlaceLayout(density = 1f, measure = measure)
    private fun item(id: String, l: Int, t: Int, r: Int, b: Int, text: String = "译文") = InPlaceItem(id, NodeBounds(l, t, r, b), text)

    @Test fun translationsJoinBlocksByIdAndDropUnknownOrBlank() {
        val blocks = listOf(ContextBlock("b1", "0", NodeBounds(0, 0, 100, 20), "A"), ContextBlock("b2", "1", NodeBounds(0, 30, 100, 50), "B"))
        val items = InPlaceLayout.items(blocks, listOf(BlockTranslation("b2", "乙"), BlockTranslation("b9", "x"),
            BlockTranslation("b1", " "), BlockTranslation("b2", "dup")))
        assertEquals(listOf(InPlaceItem("b2", NodeBounds(0, 30, 100, 50), "乙")), items)
    }

    @Test fun repeatedTextAtDifferentPositionsStaysSeparate() {
        val plan = layout.plan(listOf(item("b1", 0, 0, 200, 40, "好"), item("b2", 0, 50, 200, 90, "好")), DisplayMode.COVER, 400, 800)
        assertEquals(listOf("b1", "b2"), plan.placements.map { it.id })
    }

    @Test fun coverUsesOriginalRectClippedToScreenAndShrinksTextToFit() {
        val plan = layout.plan(listOf(item("b1", -10, 100, 500, 140, "短"), item("b2", 0, 200, 112, 240, "这是一段比原文长很多的译文")),
            DisplayMode.COVER, 400, 800)
        val first = plan.placements[0]
        assertEquals(listOf(0, 100, 400, 140), listOf(first.left, first.top, first.right, first.bottom))
        assertEquals(15f, first.textSizeSp); assertFalse(first.clipped)
        val second = plan.placements[1]
        assertTrue(second.textSizeSp < 15f)
    }

    @Test fun coverMarksTextClippedWhenEvenMinimumSizeDoesNotFit() {
        val plan = layout.plan(listOf(item("b1", 0, 0, 40, 20, "非常非常非常非常长的一段译文内容")), DisplayMode.COVER, 400, 800)
        assertEquals(11f, plan.placements.single().textSizeSp)
        assertTrue(plan.placements.single().clipped)
    }

    @Test fun offscreenAndEmptyBoundsAreNotDrawn() {
        val plan = layout.plan(listOf(item("off", 0, 900, 100, 950), item("zero", 10, 10, 10, 30), item("left", -100, 0, 0, 20),
            item("ok", 0, 0, 100, 40)), DisplayMode.COVER, 400, 800)
        assertEquals(listOf("ok"), plan.placements.map { it.id })
    }

    @Test fun parentContainingAChildIsDroppedSoTextIsNotDrawnTwice() {
        val plan = layout.plan(listOf(item("parent", 0, 0, 300, 100), item("child", 10, 10, 290, 50), item("same", 10, 10, 290, 50)),
            DisplayMode.COVER, 400, 800)
        assertEquals(listOf("child"), plan.placements.map { it.id })
    }

    @Test fun labelGoesBelowOriginalOrAboveNearTheBottom() {
        val plan = layout.plan(listOf(item("top", 0, 100, 200, 140), item("bottom", 0, 760, 200, 798)), DisplayMode.LABEL, 400, 800)
        val top = plan.placements.first { it.id == "top" }
        assertEquals(142, top.top)
        val bottom = plan.placements.first { it.id == "bottom" }
        assertTrue(bottom.bottom <= 760)
        assertEquals(InPlaceLayout.LABEL_SP, bottom.textSizeSp)
    }

    @Test fun overlappingLabelsAreSkippedAndCounted() {
        val plan = layout.plan(listOf(item("a", 0, 100, 200, 120), item("b", 0, 122, 200, 140)), DisplayMode.LABEL, 400, 800)
        assertEquals(listOf("a"), plan.placements.map { it.id })
        assertEquals(1, plan.skipped)
    }

    @Test fun labelStaysInsideScreenHorizontally() {
        val p = layout.plan(listOf(item("a", 380, 100, 420, 120)), DisplayMode.LABEL, 400, 800).placements.single()
        assertTrue(p.left >= InPlaceLayout.LABEL_MARGIN && p.right <= 400 - InPlaceLayout.LABEL_MARGIN)
        assertTrue(p.right - p.left >= InPlaceLayout.LABEL_MIN_WIDTH)
    }

    @Test fun panelModeHasNoLayoutAndUnknownIdsFallBackToPanel() {
        assertThrows(IllegalArgumentException::class.java) { layout.plan(emptyList(), DisplayMode.PANEL, 400, 800) }
        assertEquals(DisplayMode.PANEL, DisplayMode.of("bogus"))
        assertEquals(DisplayMode.PANEL, DisplayMode.of(null))
        assertEquals(DisplayMode.LABEL, DisplayMode.of("label"))
    }

    @Test fun onlyForegroundStructuralChangesReanchorTranslations() {
        val fg = "jp.naver.line.android"; val own = "dev.linguabridge.debug"
        assertTrue(InPlaceInvalidation.needsReanchor(Kind.SCROLLED, fg, fg, own, structural = false))
        assertTrue(InPlaceInvalidation.needsReanchor(Kind.WINDOW_STATE, fg, fg, own, structural = false))
        assertTrue(InPlaceInvalidation.needsReanchor(Kind.CONTENT_CHANGED, fg, fg, own, structural = true))
        assertFalse(InPlaceInvalidation.needsReanchor(Kind.CONTENT_CHANGED, fg, fg, own, structural = false))
        assertFalse(InPlaceInvalidation.needsReanchor(Kind.SCROLLED, own, own, own, structural = true))
        assertFalse(InPlaceInvalidation.needsReanchor(Kind.SCROLLED, "com.android.systemui", fg, own, structural = true))
        assertFalse(InPlaceInvalidation.needsReanchor(Kind.SCROLLED, null, fg, own, structural = true))
    }

    private fun block(id: String, top: Int, text: String) = ContextBlock(id, id, NodeBounds(0, top, 300, top + 40), text)
    private fun node(path: String, top: Int, text: String?) =
        InspectedNode(path, "0", "android.widget.TextView", null, NodeBounds(0, top, 300, top + 40), text, null)

    @Test fun translationsFollowTheirOriginalTextAfterScrolling() {
        val first = listOf(block("b1", 100, "Hello"), block("b2", 200, "How are you?"))
        val known = InPlaceAnchor.translationsByText(first, listOf(BlockTranslation("b1", "你好"), BlockTranslation("b2", "你好吗？")))
        // Scrolled up by 150px: "Hello" left the screen, "How are you?" moved, new text appeared below.
        val scrolled = InPlaceAnchor.blocks(UiSnapshot(0, 1, listOf(
            node("0/1", 50, "How are you?"), node("0/2", 400, "See you tomorrow"), node("0/3", 450, "12:30")), false))
        val result = InPlaceAnchor.anchor(scrolled, known)
        assertEquals(listOf(NodeBounds(0, 50, 300, 90) to "你好吗？"), result.items.map { it.bounds to it.text })
        assertEquals(listOf("See you tomorrow"), result.untranslated.map { it.text })
    }

    @Test fun sourceKeyStaysTheSameWhileAStreamedTranslationGrows() {
        val current = listOf(block("a1", 0, "  How are you? "))
        val early = InPlaceAnchor.anchor(current, mapOf("How are you?" to "你好")).items.single()
        val later = InPlaceAnchor.anchor(current, mapOf("How are you?" to "你好吗？最近怎么样")).items.single()
        assertNotEquals(early.text, later.text)
        assertEquals("How are you?", early.source)
        assertEquals(early.source, later.source)
    }

    @Test fun repeatedOriginalTextIsDrawnAtEveryPositionButCountedOnceAsNew() {
        val known = mapOf("OK" to "好的")
        val current = listOf(block("a1", 0, "OK"), block("a2", 50, "OK"), block("a3", 100, "Thanks"), block("a4", 150, "Thanks"))
        val result = InPlaceAnchor.anchor(current, known)
        assertEquals(listOf("a1", "a2"), result.items.map { it.id })
        assertEquals(listOf("a3"), result.untranslated.map { it.id })
    }

    @Test fun anchorMatchesTrimmedTextAndIgnoresBlankTranslations() {
        val known = InPlaceAnchor.translationsByText(listOf(block("b1", 0, "  Hi "), block("b2", 50, "Bye")),
            listOf(BlockTranslation("b1", "嗨"), BlockTranslation("b2", " "), BlockTranslation("b9", "x")))
        assertEquals(mapOf("Hi" to "嗨"), known)
        assertEquals(listOf("嗨"), InPlaceAnchor.anchor(listOf(block("a1", 300, "Hi")), known).items.map { it.text })
    }

    @Test fun newContentRequestStaysWithinUploadLimitsAndIsRenumbered() {
        val big = "x".repeat(1500)
        val blocks = InPlaceAnchor.requestBlocks(List(10) { block("a$it", it * 50, big + it) })
        assertTrue(blocks.sumOf { it.text.toByteArray().size } <= PageContext.MAX_BYTES)
        assertEquals(3, blocks.size)
        assertEquals(listOf("b1", "b2", "b3"), blocks.map { it.id })
        assertTrue(InPlaceAnchor.requestBlocks(List(100) { block("a$it", it, "t$it") }).size <= PageContext.MAX_BLOCKS)
    }
}
