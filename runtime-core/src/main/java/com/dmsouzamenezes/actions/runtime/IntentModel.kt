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
