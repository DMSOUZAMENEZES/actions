package com.dmsouzamenezes.actions.runtime.litert

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tool schema exposed to FunctionGemma.
 *
 * LiteRT-LM is configured with automaticToolCalling=false, so these methods are
 * schema carriers only. Android execution happens later through ToolRegistry,
 * PolicyEngine and ActionDispatcher.
 */
internal class FunctionGemmaTools : ToolSet {

    @Tool(description = "Opens an installed Android application by its human-readable app name or package name.")
    fun openApp(
        @ToolParam(
            description = "Application name such as YouTube, WhatsApp or Chrome, or an Android package name."
        )
        appName: String,
    ): Map<String, String> = mapOf("appName" to appName)

    @Tool(description = "Opens the Android Wi-Fi settings screen.")
    fun openWifiSettings(): Map<String, String> = mapOf("result" to "pending")

    @Tool(description = "Opens a web URL in an Android application capable of handling it.")
    fun openUrl(
        @ToolParam(description = "Absolute URL including scheme, for example https://example.com.")
        url: String,
    ): Map<String, String> = mapOf("url" to url)

    @Tool(description = "Opens the Android dialer with a phone number filled in. Does not place the call automatically.")
    fun dialNumber(
        @ToolParam(description = "Phone number to place in the dialer.")
        phoneNumber: String,
    ): Map<String, String> = mapOf("phoneNumber" to phoneNumber)
}
