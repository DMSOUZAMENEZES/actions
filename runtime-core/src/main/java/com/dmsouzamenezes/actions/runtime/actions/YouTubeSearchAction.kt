package com.dmsouzamenezes.actions.runtime.actions

import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction
import com.dmsouzamenezes.actions.runtime.accessibility.AccessibilityRuntimeBridge
import com.dmsouzamenezes.actions.runtime.accessibility.UiNodeSnapshot
import com.dmsouzamenezes.actions.runtime.accessibility.UiSnapshot
import java.text.Normalizer
import kotlinx.coroutines.delay

/**
 * Constrained multi-step skill for a non-committing YouTube search.
 *
 * This action intentionally does not expose arbitrary UI tapping. It launches
 * YouTube, identifies search controls from the accessibility tree, enters the
 * requested query and submits it. Because its effect is limited to navigation
 * and search, it is classified SAFE.
 */
data class YouTubeSearchAction(
    val query: String,
) : AndroidAction {
    override val id: String = "youtube_search"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return ActionResult.Failure("empty_query", "YouTube search query cannot be blank.")
        }
        if (!AccessibilityRuntimeBridge.isConnected) {
            return ActionResult.Failure(
                code = "accessibility_not_connected",
                message = "Enable the Actions Runtime accessibility service before running UI skills.",
            )
        }

        when (val launchResult = OpenAppAction("YouTube").execute(context)) {
            is ActionResult.Failure -> return launchResult
            else -> Unit
        }

        val home = waitForSnapshot { snapshot ->
            snapshot.packageName?.contains("youtube", ignoreCase = true) == true
        } ?: return ActionResult.Failure(
            code = "youtube_ui_timeout",
            message = "YouTube opened, but its accessibility UI tree was not available in time.",
        )

        val searchControl = home.nodes.firstOrNull { node ->
            node.enabled && node.matchesAny("search", "pesquisar", "buscar")
        } ?: return ActionResult.Failure(
            code = "youtube_search_control_not_found",
            message = "Could not locate the YouTube search control in the current UI tree.",
        )

        if (!AccessibilityRuntimeBridge.clickNode(searchControl.nodeId)) {
            return ActionResult.Failure(
                code = "youtube_search_control_click_failed",
                message = "Found the YouTube search control but could not activate it.",
            )
        }

        val searchScreen = waitForSnapshot { snapshot ->
            snapshot.nodes.any { it.enabled && it.editable }
        } ?: return ActionResult.Failure(
            code = "youtube_search_input_timeout",
            message = "YouTube search opened, but an editable search field did not appear.",
        )

        val searchInput = searchScreen.nodes.firstOrNull { it.enabled && it.editable }
            ?: return ActionResult.Failure(
                code = "youtube_search_input_not_found",
                message = "Could not locate the editable YouTube search field.",
            )

        if (!AccessibilityRuntimeBridge.setText(searchInput.nodeId, trimmedQuery)) {
            return ActionResult.Failure(
                code = "youtube_query_entry_failed",
                message = "Could not enter the YouTube search query.",
            )
        }

        delay(150)
        if (AccessibilityRuntimeBridge.submitText(searchInput.nodeId)) {
            return ActionResult.Success(
                message = "YouTube search submitted",
                data = mapOf("query" to trimmedQuery),
            )
        }

        val afterText = AccessibilityRuntimeBridge.snapshot()
        val submitControl = afterText?.nodes?.firstOrNull { node ->
            node.enabled && node.clickable && node.nodeId != searchInput.nodeId &&
                node.matchesAny("search", "pesquisar", "buscar")
        }

        if (submitControl != null && AccessibilityRuntimeBridge.clickNode(submitControl.nodeId)) {
            return ActionResult.Success(
                message = "YouTube search submitted",
                data = mapOf("query" to trimmedQuery),
            )
        }

        return ActionResult.Failure(
            code = "youtube_search_submit_failed",
            message = "The query was entered, but the runtime could not submit the YouTube search.",
        )
    }

    private suspend fun waitForSnapshot(
        attempts: Int = 12,
        intervalMs: Long = 250,
        predicate: (UiSnapshot) -> Boolean,
    ): UiSnapshot? {
        repeat(attempts) {
            val snapshot = AccessibilityRuntimeBridge.snapshot()
            if (snapshot != null && predicate(snapshot)) return snapshot
            delay(intervalMs)
        }
        return null
    }
}

private fun UiNodeSnapshot.matchesAny(vararg terms: String): Boolean {
    val haystack = listOfNotNull(text, contentDescription, viewId)
        .joinToString(" ")
        .normalizedForMatch()
    return terms.any { term -> haystack.contains(term.normalizedForMatch()) }
}

private fun String.normalizedForMatch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
