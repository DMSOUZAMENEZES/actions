package com.dmsouzamenezes.actions.runtime

data class PolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String? = null,
)

interface PolicyEngine {
    fun evaluate(action: AndroidAction): PolicyDecision
}

class DefaultPolicyEngine : PolicyEngine {
    override fun evaluate(action: AndroidAction): PolicyDecision =
        when (action.risk) {
            ActionRisk.SAFE -> PolicyDecision(
                allowed = true,
                requiresConfirmation = false,
            )

            ActionRisk.SENSITIVE -> PolicyDecision(
                allowed = true,
                requiresConfirmation = true,
                reason = "Sensitive action requires user confirmation",
            )

            ActionRisk.DESTRUCTIVE -> PolicyDecision(
                allowed = true,
                requiresConfirmation = true,
                reason = "Destructive action requires explicit user confirmation",
            )
        }
}
