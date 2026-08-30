package com.dmsouzamenezes.actions.runtime.actions

import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction
import com.dmsouzamenezes.actions.runtime.accessibility.AccessibilityRuntimeBridge

data object ReadUiTreeAction : AndroidAction {
    override val id: String = "read_ui_tree"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult {
        if (!AccessibilityRuntimeBridge.isConnected) {
            return ActionResult.Failure(
                code = "accessibility_not_connected",
                message = "Accessibility service is not enabled or connected.",
            )
        }
        val snapshot = AccessibilityRuntimeBridge.snapshot()
            ?: return ActionResult.Failure(
                code = "ui_unavailable",
                message = "No active accessibility window is available.",
            )
        return ActionResult.Success(
            message = "UI tree captured",
            data = mapOf(
                "package" to snapshot.packageName.orEmpty(),
                "window" to snapshot.windowTitle.orEmpty(),
                "ui" to snapshot.toCompactText(),
            ),
        )
    }
}

data class ClickUiNodeAction(
    val nodeId: String,
    val label: String? = null,
) : AndroidAction {
    override val id: String = "click_ui_node"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String =
        "Allow tap on ${label?.takeIf { it.isNotBlank() } ?: "UI element $nodeId"}?"

    override suspend fun execute(context: ActionContext): ActionResult =
        if (AccessibilityRuntimeBridge.clickNode(nodeId)) {
            ActionResult.Success(data = mapOf("nodeId" to nodeId))
        } else {
            ActionResult.Failure(
                code = "ui_click_failed",
                message = "Could not click UI node $nodeId.",
            )
        }
}

data class SetUiTextAction(
    val nodeId: String,
    val text: String,
) : AndroidAction {
    override val id: String = "set_ui_text"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = "Allow text entry into UI element $nodeId?"

    override suspend fun execute(context: ActionContext): ActionResult =
        if (AccessibilityRuntimeBridge.setText(nodeId, text)) {
            ActionResult.Success(data = mapOf("nodeId" to nodeId, "text" to text))
        } else {
            ActionResult.Failure(
                code = "ui_text_failed",
                message = "Could not set text on UI node $nodeId.",
            )
        }
}

data class ScrollUiForwardAction(
    val nodeId: String,
) : AndroidAction {
    override val id: String = "scroll_ui_forward"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult =
        if (AccessibilityRuntimeBridge.scrollForward(nodeId)) {
            ActionResult.Success(data = mapOf("nodeId" to nodeId))
        } else {
            ActionResult.Failure(
                code = "ui_scroll_failed",
                message = "Could not scroll UI node $nodeId.",
            )
        }
}

data object AccessibilityBackAction : AndroidAction {
    override val id: String = "accessibility_back"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult =
        if (AccessibilityRuntimeBridge.back()) {
            ActionResult.Success()
        } else {
            ActionResult.Failure(
                code = "ui_back_failed",
                message = "Could not perform Android back action through accessibility.",
            )
        }
}
