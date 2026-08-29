package com.dmsouzamenezes.actions.runtime

class ActionDispatcher(
    private val actionContext: ActionContext,
    private val policyEngine: PolicyEngine = DefaultPolicyEngine(),
) {
    suspend fun dispatch(
        action: AndroidAction,
        confirmed: Boolean = false,
    ): ActionResult {
        val decision = policyEngine.evaluate(action)

        if (!decision.allowed) {
            return ActionResult.Failure(
                code = "policy_denied",
                message = decision.reason ?: "Action denied by policy",
            )
        }

        if (decision.requiresConfirmation && !confirmed) {
            return ActionResult.ConfirmationRequired(
                actionId = action.id,
                summary = action.confirmationSummary
                    ?: decision.reason
                    ?: "Confirm action ${action.id}",
            )
        }

        return action.execute(actionContext)
    }
}
