package com.dmsouzamenezes.actions.runtime.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ActionsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityRuntimeBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityRuntimeBridge.detach(this)
        super.onDestroy()
    }

    fun snapshot(): UiSnapshot? {
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<UiNodeSnapshot>()
        walk(root, "0", nodes)
        val activeWindow = windows.firstOrNull { it.isActive }
        return UiSnapshot(
            packageName = root.packageName?.toString(),
            windowTitle = activeWindow?.title?.toString(),
            nodes = nodes,
        )
    }

    fun clickNode(nodeId: String): Boolean =
        findNode(nodeId)?.let { node ->
            try {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable && current.isEnabled) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    current = current.parent
                }
                false
            } finally {
                node.recycleSafely()
            }
        } ?: false

    fun setText(nodeId: String, text: String): Boolean =
        findNode(nodeId)?.let { node ->
            try {
                if (!node.isEditable || !node.isEnabled) return false
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                node.recycleSafely()
            }
        } ?: false

    fun scrollForward(nodeId: String): Boolean =
        findNode(nodeId)?.let { node ->
            try {
                if (!node.isScrollable || !node.isEnabled) return false
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } finally {
                node.recycleSafely()
            }
        } ?: false

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun walk(
        node: AccessibilityNodeInfo,
        nodeId: String,
        output: MutableList<UiNodeSnapshot>,
    ) {
        output += node.toSnapshot(nodeId)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                walk(child, "$nodeId.$index", output)
            } finally {
                child.recycleSafely()
            }
        }
    }

    private fun findNode(nodeId: String): AccessibilityNodeInfo? {
        if (nodeId.isBlank()) return null
        val parts = nodeId.split('.')
        if (parts.firstOrNull() != "0") return null
        var current = rootInActiveWindow ?: return null
        for (part in parts.drop(1)) {
            val index = part.toIntOrNull() ?: run {
                current.recycleSafely()
                return null
            }
            val child = current.getChild(index) ?: run {
                current.recycleSafely()
                return null
            }
            current.recycleSafely()
            current = child
        }
        return current
    }
}

object AccessibilityRuntimeBridge {
    @Volatile
    private var service: ActionsAccessibilityService? = null

    val isConnected: Boolean
        get() = service != null

    internal fun attach(service: ActionsAccessibilityService) {
        this.service = service
    }

    internal fun detach(service: ActionsAccessibilityService) {
        if (this.service === service) this.service = null
    }

    fun snapshot(): UiSnapshot? = service?.snapshot()

    fun clickNode(nodeId: String): Boolean = service?.clickNode(nodeId) == true

    fun setText(nodeId: String, text: String): Boolean = service?.setText(nodeId, text) == true

    fun scrollForward(nodeId: String): Boolean = service?.scrollForward(nodeId) == true

    fun back(): Boolean = service?.performBack() == true
}

@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.recycleSafely() {
    recycle()
}
