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
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ActionsFunctionGemma"

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

    override suspend fun process(request: UserRequest, tools: Collection<RegisteredTool>): ModelDecision {
        val session = createAgentSession(tools)
        return try {
            when (val turn = session.start(request)) {
                is AgentModelTurn.Completed -> ModelDecision.NoAction(turn.response)
                is AgentModelTurn.ToolCall -> ModelDecision.ToolCall(turn.name, turn.arguments)
            }
        } finally {
            session.close()
        }
    }

    override suspend fun createAgentSession(tools: Collection<RegisteredTool>): AgentModelSession {
        ensureInitialized()
        val allowedTools = tools.mapTo(mutableSetOf()) { it.name }
        val config = ConversationConfig(
            systemInstruction = systemInstruction(),
            tools = listOf(tool(FunctionGemmaTools())),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.0),
        )
        val conversation = withContext(Dispatchers.Default) { engine.createConversation(config) }
        return LiteRtAgentSession(conversation, allowedTools)
    }

    private class LiteRtAgentSession(
        private val conversation: Conversation,
        private val allowedTools: Set<String>,
    ) : AgentModelSession {
        private var closed = false
        private var deterministicToolPending = false

        override suspend fun start(request: UserRequest): AgentModelTurn {
            deterministicNativeRoute(request.text, allowedTools)?.let {
                deterministicToolPending = true
                Log.d(TAG, "Deterministic native route: ${it.name}")
                return it
            }
            return send(Message.user(request.text))
        }

        override suspend fun continueWithToolResult(
            modelToolName: String,
            result: Map<String, Any?>,
        ): AgentModelTurn {
            if (deterministicToolPending) {
                deterministicToolPending = false
                val success = result["success"] == true
                val message = result["message"]?.toString().orEmpty()
                return AgentModelTurn.Completed(
                    if (success) message.ifBlank { "Ação executada com sucesso." }
                    else message.ifBlank { "A ação não pôde ser executada." }
                )
            }
            return send(Message.tool(Contents.of(Content.ToolResponse(modelToolName, result))))
        }

        private suspend fun send(message: Message): AgentModelTurn = withContext(Dispatchers.Default) {
            check(!closed) { "Agent model session is already closed" }
            val response = conversation.sendMessage(message)
            Log.d(TAG, "Agent response: $response")
            Log.d(TAG, "Agent tool calls: ${response.toolCalls}")
            val call = response.toolCalls.firstOrNull()
                ?: return@withContext AgentModelTurn.Completed(cleanModelText(response.toString()))
            val runtimeName = call.name.toRuntimeToolName()
            if (runtimeName !in allowedTools) {
                Log.w(TAG, "Tool unavailable in runtime: $runtimeName")
                return@withContext AgentModelTurn.Completed("Model requested unavailable tool: ${call.name}")
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
        if (engine.isInitialized()) engine.close()
        initialized = false
    }

    private fun systemInstruction(): Contents {
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val day = now.format(DateTimeFormatter.ofPattern("EEEE"))
        return Contents.of(
            Content.Text(
                "You are an Android action agent. Use exactly the most specific provided tool for the user's request. " +
                    "Native Android actions always take precedence over accessibility primitives. For Wi-Fi settings " +
                    "use openWifiSettings; never use setUiText, clickUiNode, or readUiTree. Use accessibility primitives " +
                    "only when no dedicated native or high-level skill exists. Prefer constrained high-level skills. " +
                    "When the user asks to read or summarize one WhatsApp conversation, use whatsappSummarizeConversation. " +
                    "Do not use generic click/read tools to scan multiple private conversations. Stop when complete."
            ),
            Content.Text(
                "Current date and time given in YYYY-MM-DDTHH:MM:SS format: $dateTime\nDay of week is $day"
            ),
        )
    }
}

private fun deterministicNativeRoute(
    text: String,
    allowedTools: Set<String>,
): AgentModelTurn.ToolCall? {
    val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .trim()

    fun route(runtimeName: String, modelName: String): AgentModelTurn.ToolCall? =
        if (runtimeName in allowedTools) {
            AgentModelTurn.ToolCall(runtimeName, modelName, emptyMap())
        } else null

    if (("wifi" in normalized || "wi-fi" in normalized) &&
        listOf("configur", "ajuste", "setting", "abr").any { it in normalized }
    ) {
        return route("open_wifi_settings", "openWifiSettings")
    }

    if (("lanterna" in normalized || "flashlight" in normalized) &&
        listOf("lig", "acend", "ativ", "on").any { it in normalized }
    ) {
        return route("flashlight_on", "flashlightOn")
    }

    if (("lanterna" in normalized || "flashlight" in normalized) &&
        listOf("deslig", "apag", "desativ", "off").any { it in normalized }
    ) {
        return route("flashlight_off", "flashlightOff")
    }

    return null
}

private fun cleanModelText(raw: String): String = raw
    .replace(Regex("(?i)_?<escape>\\.?"), "")
    .replace(Regex("(?i)<escape>"), "")
    .lines()
    .map { it.trim() }
    .filter { it.isNotBlank() && it != "." }
    .joinToString("\n")
    .ifBlank { "Tarefa concluída." }

private fun String.toRuntimeToolName(): String = when (this) {
    "openWifiSettings" -> "open_wifi_settings"
    "openApp" -> "open_app"
    "openUrl" -> "open_url"
    "dialNumber" -> "dial_number"
    "youtubeSearch" -> "youtube_search"
    "whatsappSummarizeConversation" -> "whatsapp_summarize_conversation"
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
