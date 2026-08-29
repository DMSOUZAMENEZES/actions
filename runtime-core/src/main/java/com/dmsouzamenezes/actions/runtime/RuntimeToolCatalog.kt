package com.dmsouzamenezes.actions.runtime

import com.dmsouzamenezes.actions.runtime.actions.DialNumberAction
import com.dmsouzamenezes.actions.runtime.actions.OpenAppAction
import com.dmsouzamenezes.actions.runtime.actions.OpenUrlAction
import com.dmsouzamenezes.actions.runtime.actions.OpenWifiSettingsAction

object RuntimeToolCatalog {
    fun createDefault(): ToolRegistry = ToolRegistry().apply {
        register(
            RegisteredTool(
                name = "open_app",
                description = "Open an installed Android application by human-readable app name or package name.",
            )
        ) { args ->
            OpenAppAction(appName = args.required("appName"))
        }

        register(
            RegisteredTool(
                name = "open_wifi_settings",
                description = "Open Android Wi-Fi settings.",
            )
        ) {
            OpenWifiSettingsAction
        }

        register(
            RegisteredTool(
                name = "open_url",
                description = "Open an absolute web URL.",
            )
        ) { args ->
            OpenUrlAction(url = args.required("url"))
        }

        register(
            RegisteredTool(
                name = "dial_number",
                description = "Open the Android dialer with a phone number. Requires confirmation.",
            )
        ) { args ->
            DialNumberAction(phoneNumber = args.required("phoneNumber"))
        }
    }

    private fun Map<String, String>.required(name: String): String =
        this[name]?.takeIf { it.isNotBlank() }
            ?: error("Missing required tool argument: $name")
}
