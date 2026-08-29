package com.dmsouzamenezes.actions.runtime.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class UiNodeSnapshot(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val bounds: String,
)

data class UiSnapshot(
    val packageName: String?,
    val windowTitle: String?,
    val nodes: List<UiNodeSnapshot>,
) {
    fun toCompactText(maxNodes: Int = 120): String = buildString {
        append("package=").append(packageName.orEmpty())
        append(" window=").append(windowTitle.orEmpty())
        append('\n')
        nodes.take(maxNodes).forEach { node ->
            append(node.nodeId)
            append(" class=").append(node.className.orEmpty())
            node.text?.takeIf { it.isNotBlank() }?.let { append(" text=\"").append(it).append('"') }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                append(" desc=\"").append(it).append('"')
            }
            node.viewId?.takeIf { it.isNotBlank() }?.let { append(" viewId=").append(it) }
            if (node.clickable) append(" clickable")
            if (node.editable) append(" editable")
            if (node.scrollable) append(" scrollable")
            if (!node.enabled) append(" disabled")
            append(" bounds=").append(node.bounds)
            append('\n')
        }
        if (nodes.size > maxNodes) {
            append("... ").append(nodes.size - maxNodes).append(" more nodes")
        }
    }
}

internal fun AccessibilityNodeInfo.toSnapshot(nodeId: String): UiNodeSnapshot {
    val rect = Rect()
    getBoundsInScreen(rect)
    return UiNodeSnapshot(
        nodeId = nodeId,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        className = className?.toString(),
        viewId = viewIdResourceName,
        clickable = isClickable,
        editable = isEditable,
        scrollable = isScrollable,
        enabled = isEnabled,
        bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
    )
}
