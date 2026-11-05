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

    @Test fun onlyForegroundStructuralChangesHideTranslations() {
        val fg = "jp.naver.line.android"; val own = "dev.linguabridge.debug"
        assertTrue(InPlaceInvalidation.shouldHide(Kind.SCROLLED, fg, fg, own, structural = false))
        assertTrue(InPlaceInvalidation.shouldHide(Kind.WINDOW_STATE, fg, fg, own, structural = false))
        assertTrue(InPlaceInvalidation.shouldHide(Kind.CONTENT_CHANGED, fg, fg, own, structural = true))
        assertFalse(InPlaceInvalidation.shouldHide(Kind.CONTENT_CHANGED, fg, fg, own, structural = false))
        assertFalse(InPlaceInvalidation.shouldHide(Kind.SCROLLED, own, own, own, structural = true))
        assertFalse(InPlaceInvalidation.shouldHide(Kind.SCROLLED, "com.android.systemui", fg, own, structural = true))
        assertFalse(InPlaceInvalidation.shouldHide(Kind.SCROLLED, null, fg, own, structural = true))
    }
}
