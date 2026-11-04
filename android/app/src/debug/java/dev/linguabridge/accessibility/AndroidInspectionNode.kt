package dev.linguabridge.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import dev.linguabridge.domain.InspectionNode
import dev.linguabridge.domain.NodeBounds
import dev.linguabridge.domain.ExclusionReason

internal class AndroidInspectionNode(private val node: AccessibilityNodeInfo) : InspectionNode {
    override val packageName get() = node.packageName?.toString()
    override val visible get() = node.isVisibleToUser
    override val sensitive get() = sensitiveReason != null
    override val sensitiveReason get() = when {
        node.isPassword -> ExclusionReason.PASSWORD
        node.isEditable -> ExclusionReason.EDITABLE
        node.inputType != 0 -> ExclusionReason.INPUT_TYPE
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive -> ExclusionReason.ACCESSIBILITY_DATA_SENSITIVE
        node.className?.toString()?.contains("EditText", ignoreCase = true) == true -> ExclusionReason.EDIT_TEXT_CLASS
        else -> null
    }
    override val className get() = node.className?.toString()
    override val resourceId get() = node.viewIdResourceName
    override val bounds get(): NodeBounds {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
    override val text get() = node.text?.toString()
    override val description get() = node.contentDescription?.toString()
    override val childCount get() = node.childCount
    override fun child(index: Int) = node.getChild(index)?.let(::AndroidInspectionNode)
    @Suppress("DEPRECATION")
    override fun release() = node.recycle()
}
