package com.dmsouzamenezes.actions.runtime

data class UserRequest(val text: String)

data class RegisteredTool(
    val name: String,
    val description: String,
)

sealed interface ModelDecision {
    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>,
    ) : ModelDecision

    data class NoAction(val response: String) : ModelDecision
}

interface IntentModel {
    suspend fun process(
        request: UserRequest,
        tools: Collection<RegisteredTool>,
    ): ModelDecision
}

/** A single model turn inside a stateful agent conversation. */
sealed interface AgentModelTurn {
    data class ToolCall(
        /** Runtime tool name, e.g. open_app. */
        val name: String,
        /** Original LiteRT-LM tool name, e.g. openApp. */
        val modelToolName: String,
        val arguments: Map<String, String>,
    ) : AgentModelTurn

    data class Completed(val response: String) : AgentModelTurn
}

/**
 * Stateful model conversation used by the Android agent loop.
 *
 * Tool execution stays outside the model so policy/confirmation can be applied before Android
 * side effects. After execution, the result is returned to this same conversation as a tool
 * response, allowing the model to select the next action.
 */
interface AgentModelSession : AutoCloseable {
    suspend fun start(request: UserRequest): AgentModelTurn

    suspend fun continueWithToolResult(
        modelToolName: String,
        result: Map<String, Any?>,
    ): AgentModelTurn
}

/** Intent models that support a persistent tool-use conversation implement this interface. */
interface AgentIntentModel : IntentModel {
    suspend fun createAgentSession(
        tools: Collection<RegisteredTool>,
    ): AgentModelSession
}
