package com.dmsouzamenezes.actions.runtime

import android.content.Context

enum class ActionRisk {
    SAFE,
    SENSITIVE,
    DESTRUCTIVE,
}

data class ActionContext(
    val androidContext: Context,
)

sealed interface ActionResult {
    data class Success(
        val message: String? = null,
        val data: Map<String, String> = emptyMap(),
    ) : ActionResult

    data class ConfirmationRequired(
        val actionId: String,
        val summary: String,
    ) : ActionResult

    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : ActionResult
}

interface AndroidAction {
    val id: String
    val risk: ActionRisk
    val confirmationSummary: String?
        get() = null

    suspend fun execute(context: ActionContext): ActionResult
}
