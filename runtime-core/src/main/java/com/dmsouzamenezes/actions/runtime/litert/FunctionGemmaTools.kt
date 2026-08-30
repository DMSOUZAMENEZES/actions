package com.dmsouzamenezes.actions.runtime.litert

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet

/**
 * Minimal FunctionGemma proof-of-concept.
 *
 * Only the Wi-Fi settings function is exposed to the model so the first
 * end-to-end test matches the Google Mobile Actions tool definition as closely
 * as possible. Android execution remains in the existing runtime dispatcher.
 */
internal class FunctionGemmaTools : ToolSet {

    @Tool(description = "Opens the WiFi settings.")
    fun openWifiSettings(): Map<String, String> = mapOf("result" to "pending")
}
