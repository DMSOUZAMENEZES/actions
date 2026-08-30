package com.dmsouzamenezes.actions.runtime.litert

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tool schema exposed directly to FunctionGemma through the LiteRT-LM Kotlin API.
 *
 * automaticToolCalling remains disabled in the conversation because AndroidFunctionRuntime
 * owns policy checks and user confirmation. These methods therefore describe the callable
 * surface to the model; Android execution is performed by RuntimeToolCatalog/ActionDispatcher.
 */
internal class FunctionGemmaTools : ToolSet {

    @Tool(description = "Open Android Wi-Fi settings.")
    fun openWifiSettings(): Map<String, String> = pending()

    @Tool(description = "Open an installed Android application by app name or package name.")
    fun openApp(
        @ToolParam(description = "Human-readable app name such as WhatsApp or package name such as com.whatsapp.")
        appName: String,
    ): Map<String, String> = pending()

    @Tool(description = "Open an absolute web URL in an Android browser or compatible app.")
    fun openUrl(
        @ToolParam(description = "Absolute URL including scheme, for example https://example.com.")
        url: String,
    ): Map<String, String> = pending()

    @Tool(description = "Open the Android dialer with a phone number. This action may require user confirmation.")
    fun dialNumber(
        @ToolParam(description = "Phone number to place in the Android dialer.")
        phoneNumber: String,
    ): Map<String, String> = pending()

    @Tool(description = "Search YouTube for a query using the Android accessibility automation runtime.")
    fun youtubeSearch(
        @ToolParam(description = "Text to search for on YouTube.")
        query: String,
    ): Map<String, String> = pending()

    @Tool(description = "Read the currently active Android accessibility UI tree and return semantic node identifiers.")
    fun readUiTree(): Map<String, String> = pending()

    @Tool(description = "Click an Android UI element using a semantic node ID previously returned by readUiTree.")
    fun clickUiNode(
        @ToolParam(description = "Semantic node identifier returned by readUiTree.")
        nodeId: String,
        @ToolParam(description = "Optional human-readable label for the UI element.")
        label: String? = null,
    ): Map<String, String> = pending()

    @Tool(description = "Set text on an editable Android UI element using a semantic node ID.")
    fun setUiText(
        @ToolParam(description = "Semantic node identifier returned by readUiTree.")
        nodeId: String,
        @ToolParam(description = "Text to enter into the editable UI element.")
        text: String,
    ): Map<String, String> = pending()

    @Tool(description = "Scroll an Android UI element forward using a semantic node ID.")
    fun scrollUiForward(
        @ToolParam(description = "Semantic node identifier of a scrollable UI element.")
        nodeId: String,
    ): Map<String, String> = pending()

    @Tool(description = "Perform the Android Back global action using the accessibility service.")
    fun accessibilityBack(): Map<String, String> = pending()

    @Tool(description = "Turn the Android flashlight on.")
    fun flashlightOn(): Map<String, String> = pending()

    @Tool(description = "Turn the Android flashlight off.")
    fun flashlightOff(): Map<String, String> = pending()

    private fun pending(): Map<String, String> = mapOf("result" to "pending_runtime_execution")
}
