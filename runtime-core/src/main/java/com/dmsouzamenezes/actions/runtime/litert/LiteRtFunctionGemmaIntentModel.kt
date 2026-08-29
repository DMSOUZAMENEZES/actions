package com.dmsouzamenezes.actions.runtime.litert

import android.content.Context
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

/**
 * On-device intent model backed by LiteRT-LM + FunctionGemma/MobileActions.
 *
 * Tool execution is deliberately disabled inside LiteRT-LM. The model only
 * selects a function and extracts its arguments. The runtime executes the
 * resulting AndroidAction after policy evaluation.
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
            cacheDir = context.applicationContext.cacheDir.absolutePath,
        )
    )

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                withContext(Dispatchers.Default) {
                    engine.initialize()
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
                val response = conversation.sendMessage(request.text)
                val toolCall = response.toolCalls.firstOrNull()

                if (toolCall == null) {
                    return@use ModelDecision.NoAction(response.toString())
                }

                val runtimeName = toolCall.name.toRuntimeToolName()
                if (runtimeName !in allowedTools) {
                    return@use ModelDecision.NoAction(
                        "Model requested unavailable tool: ${toolCall.name}"
                    )
                }

                ModelDecision.ToolCall(
                    name = runtimeName,
                    arguments = toolCall.arguments.mapValues { (_, value) -> value?.toString().orEmpty() },
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
                "You are an Android function-calling router. Select only a provided function. " +
                    "Do not claim an action was executed. The Android runtime executes it after policy checks. " +
                    "Prefer a constrained high-level skill such as searchYouTube when it exactly matches the user's goal. " +
                    "Use low-level accessibility node tools only when no constrained skill matches, and read the UI tree before using node IDs."
            ),
            Content.Text("Current local date and time: $dateTime. Day of week: $day."),
        )
    }

    private fun String.toRuntimeToolName(): String = when (this) {
        "turnOnFlashlight" -> "flashlight_on"
        "turnOffFlashlight" -> "flashlight_off"
        "createContact" -> "create_contact"
        "sendEmail" -> "send_email"
        "showLocationOnMap" -> "show_location_on_map"
        "openWifiSettings" -> "open_wifi_settings"
        "createCalendarEvent" -> "create_calendar_event"
        "openApp" -> "open_app"
        "openUrl" -> "open_url"
        "dialNumber" -> "dial_number"
        "searchYouTube" -> "youtube_search"
        "readUiTree" -> "read_ui_tree"
        "clickUiNode" -> "click_ui_node"
        "setUiText" -> "set_ui_text"
        "scrollUiForward" -> "scroll_ui_forward"
        "accessibilityBack" -> "accessibility_back"
        else -> this
    }
}
