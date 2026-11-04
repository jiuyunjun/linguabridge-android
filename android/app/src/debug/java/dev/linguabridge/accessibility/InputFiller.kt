package dev.linguabridge.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes a user-approved draft into the target app's input field. Never reads the field's
 * existing text, never clicks send. Returns false when no writable field is found or the
 * app rejects ACTION_SET_TEXT, so the caller can fall back to the clipboard.
 */
internal object InputFiller {
    private const val MAX_NODES = 400

    @Suppress("DEPRECATION")
    fun fill(root: AccessibilityNodeInfo, expectedPackage: String, text: String): Boolean {
        if (root.packageName?.toString() != expectedPackage) return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = if (focused != null && writable(focused)) focused else {
            focused?.recycle()
            findLowestEditable(root)
        } ?: return false
        return try {
            if (target.packageName?.toString() != expectedPackage) return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally { target.recycle() }
    }

    private fun writable(node: AccessibilityNodeInfo) =
        node.isEditable && node.isVisibleToUser && !node.isPassword && node.isEnabled

    /** Chat composers sit at the bottom; pick the lowest visible editable field. */
    @Suppress("DEPRECATION")
    private fun findLowestEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until root.childCount) root.getChild(i)?.let(queue::addLast)
        val rect = Rect()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++visited > MAX_NODES) { node.recycle(); continue }
            if (writable(node)) {
                node.getBoundsInScreen(rect)
                if (rect.bottom > bestBottom) { best?.recycle(); best = node; bestBottom = rect.bottom; continue }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            if (node !== best) node.recycle()
        }
        return best
    }
}
