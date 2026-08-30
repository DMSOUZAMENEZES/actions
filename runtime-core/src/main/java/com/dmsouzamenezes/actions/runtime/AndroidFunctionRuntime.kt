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

data class AgentTraceStep(
    val tool: String,
    val arguments: Map<String, String>,
    val result: ActionResult,
)

/**
 * Suspended agent state while a sensitive action awaits user confirmation.
 * Closing this object releases the underlying LiteRT-LM conversation.
 */
class PendingAgentRun internal constructor(
    internal val request: UserRequest,
    internal val session: AgentModelSession,
    internal val turn: AgentModelTurn.ToolCall,
    internal val action: AndroidAction,
    internal val trace: List<AgentTraceStep>,
    internal val completedSteps: Int,
    internal val maxSteps: Int,
) : AutoCloseable {
    private var closed = false

    internal fun markClosed() {
        closed = true
    }

    override fun close() {
        if (!closed) {
            session.close()
            closed = true
        }
    }
}

sealed interface AgentRunResult {
    data class Completed(
        val response: String,
        val trace: List<AgentTraceStep>,
    ) : AgentRunResult

    data class ConfirmationRequired(
        val pending: PendingAgentRun,
        val actionId: String,
        val summary: String,
        val trace: List<AgentTraceStep>,
    ) : AgentRunResult

    data class Failure(
        val code: String,
        val message: String,
        val trace: List<AgentTraceStep> = emptyList(),
        val cause: Throwable? = null,
    ) : AgentRunResult
}

/**
 * High-level coordinator for on-device intent routing and Android action execution.
 *
 * The legacy plan/execute API remains available for one-shot integrations. [runAgent] adds a
 * stateful loop: model -> tool -> policy -> Android -> tool response -> model, until completion.
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

    suspend fun runAgent(
        text: String,
        maxSteps: Int = 8,
    ): AgentRunResult {
        if (text.isBlank()) {
            return AgentRunResult.Failure("empty_request", "Request cannot be blank")
        }
        if (maxSteps !in 1..32) {
            return AgentRunResult.Failure("invalid_step_limit", "maxSteps must be between 1 and 32")
        }

        val agentModel = intentModel as? AgentIntentModel
            ?: return AgentRunResult.Failure(
                "agent_not_supported",
                "Configured intent model does not support stateful agent sessions",
            )

        val request = UserRequest(text.trim())
        val session = try {
            agentModel.createAgentSession(toolRegistry.tools())
        } catch (t: Throwable) {
            return AgentRunResult.Failure(
                "agent_session_failed",
                t.message ?: "Failed to create agent session",
                cause = t,
            )
        }

        return try {
            val firstTurn = session.start(request)
            driveAgent(
                request = request,
                session = session,
                initialTurn = firstTurn,
                trace = emptyList(),
                completedSteps = 0,
                maxSteps = maxSteps,
            )
        } catch (t: Throwable) {
            session.close()
            AgentRunResult.Failure(
                "agent_failed",
                t.message ?: "Agent execution failed",
                cause = t,
            )
        }
    }

    suspend fun resumeAgent(
        pending: PendingAgentRun,
        confirmed: Boolean,
    ): AgentRunResult {
        if (!confirmed) {
            pending.close()
            return AgentRunResult.Failure(
                code = "action_cancelled",
                message = "Action cancelled by user",
                trace = pending.trace,
            )
        }

        return try {
            val result = dispatcher.dispatch(pending.action, confirmed = true)
            val newTrace = pending.trace + AgentTraceStep(
                tool = pending.turn.name,
                arguments = pending.turn.arguments,
                result = result,
            )
            val nextTurn = pending.session.continueWithToolResult(
                modelToolName = pending.turn.modelToolName,
                result = result.toToolResultPayload(),
            )
            pending.markClosed()
            driveAgent(
                request = pending.request,
                session = pending.session,
                initialTurn = nextTurn,
                trace = newTrace,
                completedSteps = pending.completedSteps + 1,
                maxSteps = pending.maxSteps,
            )
        } catch (t: Throwable) {
            pending.close()
            AgentRunResult.Failure(
                code = "agent_resume_failed",
                message = t.message ?: "Failed to resume agent",
                trace = pending.trace,
                cause = t,
            )
        }
    }

    private suspend fun driveAgent(
        request: UserRequest,
        session: AgentModelSession,
        initialTurn: AgentModelTurn,
        trace: List<AgentTraceStep>,
        completedSteps: Int,
        maxSteps: Int,
    ): AgentRunResult {
        var turn = initialTurn
        var currentTrace = trace
        var steps = completedSteps

        while (true) {
            when (turn) {
                is AgentModelTurn.Completed -> {
                    session.close()
                    return AgentRunResult.Completed(turn.response, currentTrace)
                }

                is AgentModelTurn.ToolCall -> {
                    if (steps >= maxSteps) {
                        session.close()
                        return AgentRunResult.Failure(
                            code = "agent_step_limit",
                            message = "Agent reached the maximum of $maxSteps tool steps",
                            trace = currentTrace,
                        )
                    }

                    val action = try {
                        toolRegistry.createAction(
                            ModelDecision.ToolCall(turn.name, turn.arguments)
                        )
                    } catch (t: Throwable) {
                        val failure = ActionResult.Failure(
                            code = "invalid_tool_call",
                            message = t.message ?: "Could not create Android action",
                            cause = t,
                        )
                        currentTrace = currentTrace + AgentTraceStep(
                            tool = turn.name,
                            arguments = turn.arguments,
                            result = failure,
                        )
                        steps += 1
                        turn = session.continueWithToolResult(
                            modelToolName = turn.modelToolName,
                            result = failure.toToolResultPayload(),
                        )
                        continue
                    }

                    when (val result = dispatcher.dispatch(action)) {
                        is ActionResult.ConfirmationRequired -> {
                            val pending = PendingAgentRun(
                                request = request,
                                session = session,
                                turn = turn,
                                action = action,
                                trace = currentTrace,
                                completedSteps = steps,
                                maxSteps = maxSteps,
                            )
                            return AgentRunResult.ConfirmationRequired(
                                pending = pending,
                                actionId = result.actionId,
                                summary = result.summary,
                                trace = currentTrace,
                            )
                        }

                        else -> {
                            currentTrace = currentTrace + AgentTraceStep(
                                tool = turn.name,
                                arguments = turn.arguments,
                                result = result,
                            )
                            steps += 1
                            turn = session.continueWithToolResult(
                                modelToolName = turn.modelToolName,
                                result = result.toToolResultPayload(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ActionResult.toToolResultPayload(): Map<String, Any?> = when (this) {
    is ActionResult.Success -> mapOf(
        "success" to true,
        "message" to message,
        "data" to data,
    )
    is ActionResult.ConfirmationRequired -> mapOf(
        "success" to false,
        "confirmation_required" to true,
        "action_id" to actionId,
        "summary" to summary,
    )
    is ActionResult.Failure -> mapOf(
        "success" to false,
        "error_code" to code,
        "message" to message,
    )
}
