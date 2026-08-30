package com.dmsouzamenezes.actions.runtime.litert

import android.content.Context
import android.util.Log
import com.dmsouzamenezes.actions.runtime.AgentIntentModel
import com.dmsouzamenezes.actions.runtime.AgentModelSession
import com.dmsouzamenezes.actions.runtime.AgentModelTurn
import com.dmsouzamenezes.actions.runtime.ModelDecision
import com.dmsouzamenezes.actions.runtime.RegisteredTool
import com.dmsouzamenezes.actions.runtime.UserRequest
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ActionsFunctionGemma"

/**
 * On-device FunctionGemma router backed by the official LiteRT-LM Kotlin tool API.
 *
 * Tool execution is intentionally manual: Android side effects pass through the runtime policy
 * layer first, then their result is returned to the same LiteRT-LM conversation. This enables
 * multi-step agent behavior without bypassing confirmations.
 */
class LiteRtFunctionGemmaIntentModel(
    context: Context,
    modelPath: String,
) : AgentIntentModel, AutoCloseable {

    private val initMutex = Mutex()
    private var initialized = false

    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            maxNumTokens = 1024,
            cacheDir = context.applicationContext.cacheDir.absolutePath,
        )
    )

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                withContext(Dispatchers.Default) {
                    Log.d(TAG, "Initializing LiteRT-LM engine")
                    engine.initialize()
                    Log.d(TAG, "LiteRT-LM engine initialized")
                }
                initialized = true
            }
        }
    }

    override suspend fun process(
        request: UserRequest,
        tools: Collection<RegisteredTool>,
    ): ModelDecision {
        val session = createAgentSession(tools)
        return try {
            when (val turn = session.start(request)) {
                is AgentModelTurn.Completed -> ModelDecision.NoAction(turn.response)
                is AgentModelTurn.ToolCall -> ModelDecision.ToolCall(
                    name = turn.name,
                    arguments = turn.arguments,
                )
            }
        } finally {
            session.close()
        }
    }

    override suspend fun createAgentSession(
        tools: Collection<RegisteredTool>,
    ): AgentModelSession {
        ensureInitialized()
        val allowedTools = tools.mapTo(mutableSetOf()) { it.name }
        val config = ConversationConfig(
            systemInstruction = systemInstruction(),
            tools = listOf(tool(FunctionGemmaTools())),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = 0.0,
            ),
        )

        val conversation = withContext(Dispatchers.Default) {
            engine.createConversation(config)
        }
        return LiteRtAgentSession(conversation, allowedTools)
    }

    private class LiteRtAgentSession(
        private val conversation: Conversation,
        private val allowedTools: Set<String>,
    ) : AgentModelSession {

        private var closed = false

        override suspend fun start(request: UserRequest): AgentModelTurn =
            send(Message.user(request.text))

        override suspend fun continueWithToolResult(
            modelToolName: String,
            result: Map<String, Any?>,
        ): AgentModelTurn {
            val toolMessage = Message.tool(
                Contents.of(Content.ToolResponse(modelToolName, result))
            )
            return send(toolMessage)
        }

        private suspend fun send(message: Message): AgentModelTurn = withContext(Dispatchers.Default) {
            check(!closed) { "Agent model session is already closed" }
            val response = conversation.sendMessage(message)
            Log.d(TAG, "Agent response: $response")
            Log.d(TAG, "Agent tool calls: ${response.toolCalls}")

            val call = response.toolCalls.firstOrNull()
                ?: return@withContext AgentModelTurn.Completed(response.toString())

            val runtimeName = call.name.toRuntimeToolName()
            if (runtimeName !in allowedTools) {
                Log.w(TAG, "Tool unavailable in runtime: $runtimeName")
                return@withContext AgentModelTurn.Completed(
                    "Model requested unavailable tool: ${call.name}"
                )
            }

            AgentModelTurn.ToolCall(
                name = runtimeName,
                modelToolName = call.name,
                arguments = call.arguments.mapValues { (_, value) -> value.toRuntimeArgument() },
            )
        }

        override fun close() {
            if (!closed) {
                conversation.close()
                closed = true
            }
        }
    }

    override fun close() {
        if (engine.isInitialized()) {
            engine.close()
        }
        initialized = false
    }

    private fun systemInstruction(): Contents {
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val day = now.format(DateTimeFormatter.ofPattern("EEEE"))

        return Contents.of(
            Content.Text(
                "You are an Android action agent. Use the provided tools to complete the user's " +
                    "request. After each tool result, decide whether another tool is required. " +
                    "For multi-step UI work, inspect the current UI with readUiTree before clicking " +
                    "or editing nodes. Stop calling tools when the requested task is complete."
            ),
            Content.Text(
                "Current date and time given in YYYY-MM-DDTHH:MM:SS format: $dateTime\n" +
                    "Day of week is $day"
            ),
        )
    }

    companion object {
        private fun String.toRuntimeToolName(): String = when (this) {
            "openWifiSettings" -> "open_wifi_settings"
            "openApp" -> "open_app"
            "openUrl" -> "open_url"
            "dialNumber" -> "dial_number"
            "youtubeSearch" -> "youtube_search"
            "readUiTree" -> "read_ui_tree"
            "clickUiNode" -> "click_ui_node"
            "setUiText" -> "set_ui_text"
            "scrollUiForward" -> "scroll_ui_forward"
            "accessibilityBack" -> "accessibility_back"
            "flashlightOn" -> "flashlight_on"
            "flashlightOff" -> "flashlight_off"
            else -> this
        }

        private fun Any?.toRuntimeArgument(): String = when (this) {
            null -> ""
            is String -> this
            is Number, is Boolean -> toString()
            is List<*> -> joinToString(",") { it.toRuntimeArgument() }
            else -> toString()
        }
    }
}
