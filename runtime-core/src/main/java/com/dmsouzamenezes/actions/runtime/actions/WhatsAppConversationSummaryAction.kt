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
 * Foreground-only WhatsApp test skill.
 *
 * It opens WhatsApp, optionally searches for one user-requested conversation,
 * reads only text exposed in that visible conversation through Accessibility,
 * and returns a compact summary. It never sends, edits, deletes or forwards messages.
 */
data class WhatsAppConversationSummaryAction(
    val conversation: String? = null,
    val maxItems: Int = 30,
) : AndroidAction {
    override val id: String = "whatsapp_summarize_conversation"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String =
        "Open WhatsApp and read the selected visible conversation to create a local summary."

    override suspend fun execute(context: ActionContext): ActionResult {
        if (!AccessibilityRuntimeBridge.isConnected) {
            return ActionResult.Failure(
                "accessibility_not_connected",
                "Enable the Actions Runtime accessibility service before reading WhatsApp.",
            )
        }

        when (val launch = OpenAppAction("WhatsApp").execute(context)) {
            is ActionResult.Failure -> return launch
            else -> Unit
        }

        val home = waitForSnapshot { it.isWhatsApp() }
            ?: return ActionResult.Failure(
                "whatsapp_ui_timeout",
                "WhatsApp opened, but its accessibility UI was not available in time.",
            )

        val requested = conversation?.trim()?.takeIf { it.isNotBlank() }
        val chat = if (requested != null) {
            when (val result = openConversation(home, requested)) {
                is OpenConversationResult.Failure -> return ActionResult.Failure(result.code, result.message)
                is OpenConversationResult.Success -> result.snapshot
            }
        } else {
            if (!home.looksLikeConversation()) {
                return ActionResult.Failure(
                    "whatsapp_conversation_required",
                    "WhatsApp is open, but no conversation is selected. Open a chat or specify the conversation name.",
                )
            }
            home
        }

        val title = requested ?: chat.inferTitle() ?: "Conversa"
        val items = WhatsAppVisibleText.extract(chat, title, maxItems.coerceIn(1, 60))
        if (items.isEmpty()) {
            return ActionResult.Failure(
                "whatsapp_no_readable_messages",
                "The selected WhatsApp conversation did not expose readable message text in the visible accessibility tree.",
            )
        }

        val summary = buildSummary(title, items)
        return ActionResult.Success(
            message = summary,
            data = mapOf(
                "conversation" to title,
                "item_count" to items.size.toString(),
                "summary" to summary,
                "visible_text" to items.joinToString("\n") { "- $it" }.take(10_000),
            ),
        )
    }

    private suspend fun openConversation(home: UiSnapshot, query: String): OpenConversationResult {
        val search = home.nodes.firstOrNull { node ->
            node.enabled && node.matchesAny("search", "pesquisar", "buscar")
        } ?: return OpenConversationResult.Failure(
            "whatsapp_search_not_found",
            "Could not locate WhatsApp search.",
        )

        if (!AccessibilityRuntimeBridge.clickNode(search.nodeId)) {
            return OpenConversationResult.Failure(
                "whatsapp_search_click_failed",
                "Could not activate WhatsApp search.",
            )
        }

        val searchScreen = waitForSnapshot { it.nodes.any { node -> node.enabled && node.editable } }
            ?: return OpenConversationResult.Failure(
                "whatsapp_search_input_timeout",
                "WhatsApp search input did not appear.",
            )

        val input = searchScreen.nodes.first { it.enabled && it.editable }
        if (!AccessibilityRuntimeBridge.setText(input.nodeId, query)) {
            return OpenConversationResult.Failure(
                "whatsapp_search_text_failed",
                "Could not enter the conversation name in WhatsApp search.",
            )
        }

        delay(450)
        val results = AccessibilityRuntimeBridge.snapshot()
            ?: return OpenConversationResult.Failure(
                "whatsapp_search_results_unavailable",
                "WhatsApp search results were unavailable.",
            )

        val target = results.nodes.firstOrNull { node ->
            node.enabled && node.nodeId != input.nodeId && node.matchesAny(query)
        } ?: return OpenConversationResult.Failure(
            "whatsapp_conversation_not_found",
            "Could not find a WhatsApp conversation matching '$query'.",
        )

        if (!AccessibilityRuntimeBridge.clickNode(target.nodeId)) {
            return OpenConversationResult.Failure(
                "whatsapp_conversation_click_failed",
                "Found '$query' but could not open the conversation.",
            )
        }

        val chat = waitForSnapshot { snapshot ->
            snapshot.isWhatsApp() && snapshot.looksLikeConversation()
        } ?: return OpenConversationResult.Failure(
            "whatsapp_conversation_timeout",
            "The conversation was opened, but readable chat UI did not appear in time.",
        )

        return OpenConversationResult.Success(chat)
    }

    private suspend fun waitForSnapshot(
        attempts: Int = 16,
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

private sealed interface OpenConversationResult {
    data class Success(val snapshot: UiSnapshot) : OpenConversationResult
    data class Failure(val code: String, val message: String) : OpenConversationResult
}

internal object WhatsAppVisibleText {
    private val ignored = setOf(
        "whatsapp", "chats", "conversas", "updates", "atualizacoes", "calls", "ligacoes",
        "search", "pesquisar", "buscar", "message", "mensagem", "type a message",
        "digite uma mensagem", "send", "enviar", "voice message", "mensagem de voz",
    )

    fun extract(snapshot: UiSnapshot, title: String, maxItems: Int): List<String> {
        val seen = linkedSetOf<String>()
        val normalizedTitle = title.normalized()

        snapshot.nodes.asSequence()
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .filterNotNull()
            .map { it.trim() }
            .filter { it.length in 2..1500 }
            .filterNot { it.normalized() == normalizedTitle }
            .filterNot { it.normalized() in ignored }
            .filterNot { it.matches(Regex("^\\d{1,2}:\\d{2}(?:\\s?[ap]m)?$", RegexOption.IGNORE_CASE)) }
            .forEach { seen += it }

        return seen.toList().takeLast(maxItems.coerceAtMost(seen.size))
    }
}

private fun buildSummary(title: String, items: List<String>): String = buildString {
    append("Resumo de ").append(title).append(": ")
    append(items.size).append(if (items.size == 1) " item visível. " else " itens visíveis. ")
    items.takeLast(8).forEachIndexed { index, item ->
        if (index > 0) append(" | ")
        append(item.replace('\n', ' ').trim())
    }
}

private fun UiSnapshot.isWhatsApp(): Boolean =
    packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b" ||
        packageName?.contains("whatsapp", ignoreCase = true) == true

private fun UiSnapshot.looksLikeConversation(): Boolean =
    nodes.any { it.enabled && it.editable } || nodes.any { it.matchesAny("message", "mensagem") }

private fun UiSnapshot.inferTitle(): String? = nodes.asSequence()
    .mapNotNull { it.text?.trim() }
    .firstOrNull { text -> text.length in 2..80 && text.normalized() !in setOf("whatsapp", "conversas", "chats") }

private fun UiNodeSnapshot.matchesAny(vararg terms: String): Boolean {
    val haystack = listOfNotNull(text, contentDescription, viewId).joinToString(" ").normalized()
    return terms.any { haystack.contains(it.normalized()) }
}

private fun String.normalized(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
