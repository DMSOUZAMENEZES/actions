package com.dmsouzamenezes.actions.runtime

/** A planned Android action that has passed model routing but not necessarily policy confirmation. */
data class ActionPlan(
    val request: UserRequest,
    val decision: ModelDecision.ToolCall,
    val action: AndroidAction,
)

sealed interface PlanningResult {
    data class Planned(val plan: ActionPlan) : PlanningResult
    data class NoAction(val response: String) : PlanningResult
    data class Failure(val code: String, val message: String, val cause: Throwable? = null) : PlanningResult
}

/**
 * High-level coordinator for on-device intent routing and Android action execution.
 *
 * Planning and execution are separate so confirmation never requires running the
 * language model twice. UI code should retain ActionPlan while asking the user
 * for confirmation and then call execute(plan, confirmed = true).
 */
class AndroidFunctionRuntime(
    private val intentModel: IntentModel,
    private val toolRegistry: ToolRegistry,
    private val dispatcher: ActionDispatcher,
) {
    suspend fun plan(text: String): PlanningResult {
        if (text.isBlank()) {
            return PlanningResult.Failure("empty_request", "Request cannot be blank")
        }

        return runCatching {
            val request = UserRequest(text.trim())
            when (val decision = intentModel.process(request, toolRegistry.tools())) {
                is ModelDecision.NoAction -> PlanningResult.NoAction(decision.response)
                is ModelDecision.ToolCall -> PlanningResult.Planned(
                    ActionPlan(
                        request = request,
                        decision = decision,
                        action = toolRegistry.createAction(decision),
                    )
                )
            }
        }.getOrElse {
            PlanningResult.Failure(
                code = "planning_failed",
                message = it.message ?: "Failed to plan Android action",
                cause = it,
            )
        }
    }

    suspend fun execute(
        plan: ActionPlan,
        confirmed: Boolean = false,
    ): ActionResult = dispatcher.dispatch(plan.action, confirmed)
}
