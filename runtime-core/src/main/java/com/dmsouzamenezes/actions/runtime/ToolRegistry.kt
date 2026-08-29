package com.dmsouzamenezes.actions.runtime

class ToolRegistry {
    private val factories = linkedMapOf<String, (Map<String, String>) -> AndroidAction>()
    private val metadata = linkedMapOf<String, RegisteredTool>()

    fun register(
        tool: RegisteredTool,
        factory: (Map<String, String>) -> AndroidAction,
    ) {
        require(tool.name !in factories) { "Tool already registered: ${tool.name}" }
        metadata[tool.name] = tool
        factories[tool.name] = factory
    }

    fun tools(): Collection<RegisteredTool> = metadata.values

    fun createAction(call: ModelDecision.ToolCall): AndroidAction {
        val factory = factories[call.name]
            ?: error("Unknown tool: ${call.name}")
        return factory(call.arguments)
    }
}
