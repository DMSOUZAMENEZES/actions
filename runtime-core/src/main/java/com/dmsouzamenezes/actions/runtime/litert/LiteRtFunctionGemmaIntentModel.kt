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
private const val WHATSAPP_RUNTIME_TOOL = "whatsapp_summarize_conversation"

class LiteRtFunctionGemmaIntentModel(
    context: Context,
    modelPath: String,
) : AgentIntentModel, AutoCloseable {

    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private var initialized = false

    private val engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            maxNumTokens = 1024,
            cacheDir = appContext.cacheDir.absolutePath,
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
        return LiteRtAgentSession(
            engine = engine,
            conversation = conversation,
            allowedTools = allowedTools,
            appContext = appContext,
        )
    }

    private class LiteRtAgentSession(
        private val engine: Engine,
        private val conversation: Conversation,
        private val allowedTools: Set<String>,
        private val appContext: Context,
    ) : AgentModelSession {
        private var closed = false
        private var deterministicToolPending = false
        private var pendingWhatsAppRequest: String? = null

        override suspend fun start(request: UserRequest): AgentModelTurn {
            if (WHATSAPP_RUNTIME_TOOL in allowedTools && isWhatsAppSummaryRequest(request.text)) {
                val conversationName = extractWhatsAppConversation(request.text)
                pendingWhatsAppRequest = request.text
                Log.d(TAG, "WhatsApp read requires policy confirmation before automatic LiteRT-LM tool execution")
                return AgentModelTurn.ToolCall(
                    name = WHATSAPP_RUNTIME_TOOL,
                    modelToolName = "summarizeWhatsAppConversation",
                    arguments = buildMap {
                        if (!conversationName.isNullOrBlank()) put("conversation", conversationName)
                        put("maxItems", "30")
                    },
                )
            }

            deterministicNativeRoute(request.text, allowedTools)?.let {
                deterministicToolPending = true
                Log.d(TAG, "Deterministic route: ${it.name} ${it.arguments}")
                return it
            }
            return send(Message.user(request.text))
        }

        private suspend fun runNativeWhatsAppTurn(text: String): AgentModelTurn =
            withContext(Dispatchers.Default) {
                check(!closed) { "Agent model session is already closed" }
                val config = ConversationConfig(
                    systemInstruction = Contents.of(
                        Content.Text(
                            "You are a local Android WhatsApp assistant. For any request to read or summarize WhatsApp messages, " +
                                "you MUST call summarizeWhatsAppConversation. The tool is read-only and only reads text exposed " +
                                "by the Android accessibility tree. Never claim messages were read when the tool reports failure. " +
                                "After the tool returns success, answer in Portuguese with a concise useful summary based only on " +
                                "the tool result. Do not invent messages or participants."
                        )
                    ),
                    tools = listOf(tool(WhatsAppLiteRtTools(appContext))),
                    automaticToolCalling = true,
                    samplerConfig = SamplerConfig(topK = 16, topP = 0.9, temperature = 0.0),
                )

                engine.createConversation(config).use { nativeConversation ->
                    val response = nativeConversation.sendMessage(Message.user(text))
                    Log.d(TAG, "Native WhatsApp final response: $response")
                    AgentModelTurn.Completed(cleanModelText(response.toString()))
                }
            }

        override suspend fun continueWithToolResult(
            modelToolName: String,
            result: Map<String, Any?>,
        ): AgentModelTurn {
            pendingWhatsAppRequest?.let { originalRequest ->
                pendingWhatsAppRequest = null
                val success = result["success"] == true
                val authorized = (result["data"] as? Map<*, *>)?.get("authorized")?.toString() == "true"
                if (!success || !authorized) {
                    val message = result["message"]?.toString().orEmpty()
                    return AgentModelTurn.Completed(
                        message.ifBlank { "A leitura do WhatsApp não foi autorizada." }
                    )
                }
                Log.d(TAG, "WhatsApp read authorized; entering native LiteRT-LM automatic tool loop")
                return runNativeWhatsAppTurn(originalRequest)
            }

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
                    "When the user asks to read or summarize one WhatsApp conversation, always use whatsappSummarizeConversation; " +
                    "do not stop after openApp(WhatsApp). Do not use generic click/read tools to scan multiple private conversations. " +
                    "Stop when complete."
            ),
            Content.Text(
                "Current date and time given in YYYY-MM-DDTHH:MM:SS format: $dateTime\nDay of week is $day"
            ),
        )
    }
}

private fun isWhatsAppSummaryRequest(text: String): Boolean {
    val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return "whatsapp" in normalized &&
        listOf("resum", "ler", "leia", "mensagen", "mensagem", "conversa").any { it in normalized }
}

private fun extractWhatsAppConversation(text: String): String? {
    val patterns = listOf(
        Regex("(?i)conversa\\s+(?:com|de)\\s+(.+)$"),
        Regex("(?i)mensagens?\\s+(?:com|de|do|da)\\s+(.+)$"),
        Regex("(?i)whatsapp\\s+(?:com|de|do|da)\\s+(.+)$"),
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', '!', '?', ',', ';', ':')
            ?.takeIf { it.isNotBlank() && it.length <= 120 }
    }
}

private fun deterministicNativeRoute(
    text: String,
    allowedTools: Set<String>,
): AgentModelTurn.ToolCall? {
    val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .trim()

    fun route(
        runtimeName: String,
        modelName: String,
        arguments: Map<String, String> = emptyMap(),
    ): AgentModelTurn.ToolCall? =
        if (runtimeName in allowedTools) {
            AgentModelTurn.ToolCall(runtimeName, modelName, arguments)
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
    "whatsappSummarizeConversation", "summarizeWhatsAppConversation" -> WHATSAPP_RUNTIME_TOOL
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
