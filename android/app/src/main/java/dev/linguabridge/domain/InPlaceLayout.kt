package dev.linguabridge.domain

/** How a page translation is shown: in the panel, or drawn over the app at the original text. */
enum class DisplayMode(val id: String, val label: String) {
    PANEL("panel", "面板"),
    COVER("cover", "原位覆盖"),
    LABEL("label", "就近标签");

    val inPlace get() = this != PANEL

    companion object {
        fun of(id: String?): DisplayMode = entries.firstOrNull { it.id == id } ?: PANEL
    }
}

/** One translated block at its on-screen bounds (overlay coordinates, px). */
data class InPlaceItem(val id: String, val bounds: NodeBounds, val text: String)

/** Where one translation is drawn. [clipped]: text did not fit even at the minimum size. */
data class Placement(val id: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
                     val textSizeSp: Float, val text: String, val clipped: Boolean)

/** [skipped]: visible items that could not be placed without overlapping another label. */
data class InPlacePlan(val placements: List<Placement>, val skipped: Int)

/**
 * Pure layout for in-place translations. Text measurement is injected so the rules are testable
 * without Android: [measure] returns the wrapped text height in px for a width in px and a size in sp.
 */
class InPlaceLayout(
    private val density: Float,
    private val measure: (text: String, widthPx: Int, sp: Float) -> Int,
) {
    private fun dp(value: Int) = (value * density).toInt()

    fun plan(items: List<InPlaceItem>, mode: DisplayMode, screenWidth: Int, screenHeight: Int): InPlacePlan {
        require(mode.inPlace) { "panel mode has no in-place layout" }
        val visible = innermost(items.filter { it.text.isNotBlank() && onScreen(it.bounds, screenWidth, screenHeight) })
        return if (mode == DisplayMode.COVER) InPlacePlan(visible.map { cover(it, screenWidth, screenHeight) }, 0)
        else labels(visible, screenWidth, screenHeight)
    }

    private fun onScreen(b: NodeBounds, w: Int, h: Int) =
        b.right > b.left && b.bottom > b.top && b.right > 0 && b.bottom > 0 && b.left < w && b.top < h

    /**
     * Parent and child nodes can both carry text (e.g. a bubble and its inner TextView). Keep the
     * innermost: drop a block whose bounds contain another visible block, and later blocks with
     * exactly the same bounds as an earlier one.
     */
    private fun innermost(items: List<InPlaceItem>): List<InPlaceItem> = items.filterIndexed { i, item ->
        items.withIndex().none { (j, other) ->
            j != i && (if (other.bounds == item.bounds) j < i else contains(item.bounds, other.bounds))
        }
    }

    private fun contains(outer: NodeBounds, inner: NodeBounds) = outer.left <= inner.left && outer.top <= inner.top &&
        outer.right >= inner.right && outer.bottom >= inner.bottom

    /** Opaque box over the original rect; the text shrinks from 15sp to 11sp, then is clipped. */
    private fun cover(item: InPlaceItem, w: Int, h: Int): Placement {
        val left = item.bounds.left.coerceAtLeast(0); val top = item.bounds.top.coerceAtLeast(0)
        val right = item.bounds.right.coerceAtMost(w); val bottom = item.bounds.bottom.coerceAtMost(h)
        val innerWidth = (right - left - 2 * dp(COVER_PAD_H)).coerceAtLeast(1)
        val innerHeight = bottom - top - 2 * dp(COVER_PAD_V)
        val size = SIZES.firstOrNull { measure(item.text, innerWidth, it) <= innerHeight }
        return Placement(item.id, left, top, right, bottom, size ?: SIZES.last(), item.text, clipped = size == null)
    }

    /**
     * Small label under the original (above it if it would run off the bottom). A label that would
     * overlap an already placed one is skipped and counted, never stacked on top.
     */
    private fun labels(items: List<InPlaceItem>, w: Int, h: Int): InPlacePlan {
        val placed = mutableListOf<Placement>()
        var skipped = 0
        val margin = dp(LABEL_MARGIN); val gap = dp(LABEL_GAP)
        for (item in items.sortedWith(compareBy<InPlaceItem> { it.bounds.top }.thenBy { it.bounds.left })) {
            val width = (item.bounds.right - item.bounds.left).coerceIn(minOf(dp(LABEL_MIN_WIDTH), w - 2 * margin), w - 2 * margin)
            val left = item.bounds.left.coerceIn(margin, w - margin - width)
            val height = measure(item.text, width - 2 * dp(LABEL_PAD_H), LABEL_SP) + 2 * dp(LABEL_PAD_V)
            val below = item.bounds.bottom + gap
            val top = when {
                below + height <= h -> below
                item.bounds.top - gap - height >= 0 -> item.bounds.top - gap - height
                else -> (h - height).coerceAtLeast(0)
            }
            val candidate = Placement(item.id, left, top, left + width, (top + height).coerceAtMost(h), LABEL_SP, item.text,
                clipped = top + height > h)
            if (placed.any { overlaps(it, candidate) }) skipped++ else placed += candidate
        }
        return InPlacePlan(placed, skipped)
    }

    private fun overlaps(a: Placement, b: Placement) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    companion object {
        val SIZES = listOf(15f, 14f, 13f, 12f, 11f)
        const val LABEL_SP = 13f
        const val COVER_PAD_H = 6
        const val COVER_PAD_V = 2
        const val LABEL_PAD_H = 8
        const val LABEL_PAD_V = 4
        const val LABEL_GAP = 2
        const val LABEL_MARGIN = 4
        const val LABEL_MIN_WIDTH = 96

        /** Joins translations to the captured blocks by id; unknown ids and blank translations are dropped. */
        fun items(blocks: List<ContextBlock>, translations: List<BlockTranslation>): List<InPlaceItem> {
            val byId = blocks.associateBy { it.id }
            return translations.mapNotNull { t -> byId[t.id]?.takeIf { t.text.isNotBlank() }?.let { InPlaceItem(t.id, it.bounds, t.text) } }
                .distinctBy { it.id }
        }
    }
}

/**
 * When drawn translations must disappear: their bounds are a one-time observation, so any scroll,
 * window change or structural change in the foreground app makes them stale. Text-only changes
 * (a clock, a typing indicator) do not move layout and are ignored. Events from other packages,
 * including this app's own overlay windows, never hide them.
 */
object InPlaceInvalidation {
    enum class Kind { SCROLLED, CONTENT_CHANGED, WINDOW_STATE }

    fun shouldHide(kind: Kind, eventPackage: String?, foreground: String?, ownPackage: String, structural: Boolean): Boolean =
        eventPackage != null && eventPackage == foreground && eventPackage != ownPackage && when (kind) {
            Kind.SCROLLED, Kind.WINDOW_STATE -> true
            Kind.CONTENT_CHANGED -> structural
        }
}
