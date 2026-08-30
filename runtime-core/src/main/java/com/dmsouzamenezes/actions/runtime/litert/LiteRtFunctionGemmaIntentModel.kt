package com.dmsouzamenezes.actions.runtime.litert

import android.content.Context
import android.util.Log
import com.dmsouzamenezes.actions.runtime.IntentModel
import com.dmsouzamenezes.actions.runtime.ModelDecision
import com.dmsouzamenezes.actions.runtime.RegisteredTool
import com.dmsouzamenezes.actions.runtime.UserRequest
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
 * The model receives real @Tool schemas from [FunctionGemmaTools]. We intentionally keep
 * automaticToolCalling disabled here so the existing Android runtime can apply policy and ask
 * for confirmation before sensitive actions are executed.
 */
class LiteRtFunctionGemmaIntentModel(
    context: Context,
    modelPath: String,
) : IntentModel, AutoCloseable {

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
        ensureInitialized()

        val allowedTools = tools.mapTo(mutableSetOf()) { it.name }
        val conversationConfig = ConversationConfig(
            systemInstruction = systemInstruction(),
            tools = listOf(tool(FunctionGemmaTools())),
            automaticToolCalling = false,
            samplerConfig = SamplerConfig(
                topK = 64,
                topP = 0.95,
                temperature = 0.0,
            ),
        )

        return withContext(Dispatchers.Default) {
            engine.createConversation(conversationConfig).use { conversation ->
                Log.d(TAG, "Prompt: ${request.text}")
                val response = conversation.sendMessage(request.text)
                Log.d(TAG, "Response: $response")
                Log.d(TAG, "Tool calls: ${response.toolCalls}")

                val toolCall = response.toolCalls.firstOrNull()
                if (toolCall == null) {
                    Log.w(TAG, "No tool call recognized")
                    return@use ModelDecision.NoAction(response.toString())
                }

                val runtimeName = toolCall.name.toRuntimeToolName()
                Log.d(TAG, "Tool call name=${toolCall.name} runtime=$runtimeName args=${toolCall.arguments}")

                if (runtimeName !in allowedTools) {
                    Log.w(TAG, "Tool unavailable in runtime: $runtimeName")
                    return@use ModelDecision.NoAction(
                        "Model requested unavailable tool: ${toolCall.name}"
                    )
                }

                ModelDecision.ToolCall(
                    name = runtimeName,
                    arguments = toolCall.arguments.mapValues { (_, value) ->
                        value.toRuntimeArgument()
                    },
                )
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
                "You are an Android action router. Use the provided tools whenever the user asks " +
                    "the device to perform an action. Choose only one tool for the current step."
            ),
            Content.Text(
                "Current date and time given in YYYY-MM-DDTHH:MM:SS format: $dateTime\n" +
                    "Day of week is $day"
            ),
        )
    }

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
