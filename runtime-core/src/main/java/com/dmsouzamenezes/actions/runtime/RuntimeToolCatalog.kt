package com.dmsouzamenezes.actions.runtime

import com.dmsouzamenezes.actions.runtime.actions.AccessibilityBackAction
import com.dmsouzamenezes.actions.runtime.actions.ClickUiNodeAction
import com.dmsouzamenezes.actions.runtime.actions.CreateCalendarEventAction
import com.dmsouzamenezes.actions.runtime.actions.CreateContactAction
import com.dmsouzamenezes.actions.runtime.actions.DialNumberAction
import com.dmsouzamenezes.actions.runtime.actions.OpenAppAction
import com.dmsouzamenezes.actions.runtime.actions.OpenUrlAction
import com.dmsouzamenezes.actions.runtime.actions.OpenWifiSettingsAction
import com.dmsouzamenezes.actions.runtime.actions.ReadUiTreeAction
import com.dmsouzamenezes.actions.runtime.actions.ScrollUiForwardAction
import com.dmsouzamenezes.actions.runtime.actions.SendEmailAction
import com.dmsouzamenezes.actions.runtime.actions.SetFlashlightAction
import com.dmsouzamenezes.actions.runtime.actions.SetUiTextAction
import com.dmsouzamenezes.actions.runtime.actions.ShowLocationOnMapAction
import com.dmsouzamenezes.actions.runtime.actions.YouTubeSearchAction

object RuntimeToolCatalog {
    fun createDefault(): ToolRegistry = ToolRegistry().apply {
        register(RegisteredTool("flashlight_on", "Turn the Android flashlight on.")) {
            SetFlashlightAction(enabled = true)
        }

        register(RegisteredTool("flashlight_off", "Turn the Android flashlight off.")) {
            SetFlashlightAction(enabled = false)
        }

        register(
            RegisteredTool("create_contact", "Open Android contact creation with supplied details.")
        ) { args ->
            CreateContactAction(
                firstName = args.required("firstName"),
                lastName = args.required("lastName"),
                phoneNumber = args.required("phoneNumber"),
                email = args.required("email"),
            )
        }

        register(RegisteredTool("send_email", "Open an email draft with recipient, subject and body.")) { args ->
            SendEmailAction(
                to = args.required("to"),
                subject = args.required("subject"),
                body = args.required("body"),
            )
        }

        register(RegisteredTool("show_location_on_map", "Show a named location, business or address on a map.")) { args ->
            ShowLocationOnMapAction(location = args.required("location"))
        }

        register(RegisteredTool("open_wifi_settings", "Open Android Wi-Fi settings.")) {
            OpenWifiSettingsAction
        }

        register(RegisteredTool("create_calendar_event", "Open calendar event creation for a title and date/time.")) { args ->
            CreateCalendarEventAction(
                datetime = args.required("datetime"),
                title = args.required("title"),
            )
        }

        register(
            RegisteredTool(
                name = "open_app",
                description = "Open an installed Android application by human-readable app name or package name.",
            )
        ) { args ->
            OpenAppAction(appName = args.required("appName"))
        }

        register(RegisteredTool("open_url", "Open an absolute web URL.")) { args ->
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

        register(
            RegisteredTool(
                name = "youtube_search",
                description = "Open YouTube, enter a search query and submit the search using the accessibility skill runtime.",
            )
        ) { args ->
            YouTubeSearchAction(query = args.required("query"))
        }

        register(
            RegisteredTool(
                name = "read_ui_tree",
                description = "Read the active Android accessibility UI tree and return semantic node IDs.",
            )
        ) {
            ReadUiTreeAction
        }

        register(
            RegisteredTool(
                name = "click_ui_node",
                description = "Click an Android UI element using a semantic node ID from read_ui_tree. Requires confirmation.",
            )
        ) { args ->
            ClickUiNodeAction(
                nodeId = args.required("nodeId"),
                label = args["label"],
            )
        }

        register(
            RegisteredTool(
                name = "set_ui_text",
                description = "Set text on an editable Android UI element using a semantic node ID. Requires confirmation.",
            )
        ) { args ->
            SetUiTextAction(
                nodeId = args.required("nodeId"),
                text = args.required("text"),
            )
        }

        register(
            RegisteredTool(
                name = "scroll_ui_forward",
                description = "Scroll a scrollable Android UI element forward using its semantic node ID.",
            )
        ) { args ->
            ScrollUiForwardAction(nodeId = args.required("nodeId"))
        }

        register(
            RegisteredTool(
                name = "accessibility_back",
                description = "Perform the Android Back global action through the accessibility service.",
            )
        ) {
            AccessibilityBackAction
        }
    }

    private fun Map<String, String>.required(name: String): String =
        this[name]?.takeIf { it.isNotBlank() }
            ?: error("Missing required tool argument: $name")
}
