package com.dmsouzamenezes.actions.runtime.actions

import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction

/**
 * Policy boundary for private WhatsApp reads.
 *
 * This action deliberately performs no UI automation. Its only purpose is to make the existing
 * PolicyEngine/UI confirmation flow authorize one subsequent LiteRT-LM WhatsApp tool turn.
 */
data class AuthorizeWhatsAppConversationReadAction(
    val conversation: String? = null,
) : AndroidAction {
    override val id: String = "whatsapp_summarize_conversation"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = buildString {
        append("Permitir que o agente abra o WhatsApp e leia as mensagens visíveis")
        conversation?.takeIf { it.isNotBlank() }?.let { append(" da conversa '$it'") }
        append(" para criar um resumo local? Nenhuma mensagem será enviada, editada ou apagada.")
    }

    override suspend fun execute(context: ActionContext): ActionResult = ActionResult.Success(
        message = "Leitura do WhatsApp autorizada para esta execução.",
        data = mapOf("authorized" to "true"),
    )
}
