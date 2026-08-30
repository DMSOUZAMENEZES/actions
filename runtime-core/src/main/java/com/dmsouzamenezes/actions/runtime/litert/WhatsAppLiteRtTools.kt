package com.dmsouzamenezes.actions.runtime.litert

import android.content.Context
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.actions.WhatsAppConversationSummaryAction
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Native LiteRT-LM ToolSet dedicated to the WhatsApp proof-of-concept.
 *
 * Unlike the generic routing facade, this tool executes the Android action itself and returns the
 * execution result directly to LiteRT-LM, allowing the SDK's automatic tool loop to feed the result
 * back to FunctionGemma before the final response is generated.
 */
internal class WhatsAppLiteRtTools(
    context: Context,
) : ToolSet {
    private val appContext = context.applicationContext

    @Tool(
        description = "Open WhatsApp, optionally find one explicitly requested conversation, read only text exposed by the visible Android accessibility tree, and return a local summary. Never send, edit, delete, react to, or forward messages."
    )
    fun summarizeWhatsAppConversation(
        @ToolParam(
            description = "Contact or group name to search for. Use an empty string only when the requested WhatsApp conversation is already open."
        )
        conversation: String = "",
        @ToolParam(
            description = "Maximum number of visible text items to collect for the summary. Use 30 unless the user requests otherwise."
        )
        maxItems: Int = 30,
    ): Map<String, String> = runBlocking(Dispatchers.Default) {
        when (
            val result = WhatsAppConversationSummaryAction(
                conversation = conversation.trim().takeIf { it.isNotEmpty() },
                maxItems = maxItems.coerceIn(1, 60),
            ).execute(ActionContext(appContext))
        ) {
            is ActionResult.Success -> buildMap {
                put("success", "true")
                put("message", result.message.orEmpty())
                result.data.forEach { (key, value) -> put(key, value) }
            }

            is ActionResult.Failure -> mapOf(
                "success" to "false",
                "error_code" to result.code,
                "message" to result.message,
            )

            is ActionResult.ConfirmationRequired -> mapOf(
                "success" to "false",
                "confirmation_required" to "true",
                "action_id" to result.actionId,
                "message" to result.summary,
            )
        }
    }
}
