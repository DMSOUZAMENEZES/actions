package com.dmsouzamenezes.actions.runtime

import com.dmsouzamenezes.actions.runtime.actions.AccessibilityBackAction
import com.dmsouzamenezes.actions.runtime.actions.AuthorizeWhatsAppConversationReadAction
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
        register(RegisteredTool("create_contact", "Open Android contact creation with supplied details.")) { args ->
            CreateContactAction(args.required("firstName"), args.required("lastName"), args.required("phoneNumber"), args.required("email"))
        }
        register(RegisteredTool("send_email", "Open an email draft with recipient, subject and body.")) { args ->
            SendEmailAction(args.required("to"), args.required("subject"), args.required("body"))
        }
        register(RegisteredTool("show_location_on_map", "Show a named location, business or address on a map.")) { args ->
            ShowLocationOnMapAction(args.required("location"))
        }
        register(RegisteredTool("open_wifi_settings", "Open Android Wi-Fi settings.")) { OpenWifiSettingsAction }
        register(RegisteredTool("create_calendar_event", "Open calendar event creation for a title and date/time.")) { args ->
            CreateCalendarEventAction(args.required("datetime"), args.required("title"))
        }
        register(RegisteredTool("open_app", "Open an installed Android application by human-readable app name or package name.")) { args ->
            OpenAppAction(args.required("appName"))
        }
        register(RegisteredTool("open_url", "Open an absolute web URL.")) { args -> OpenUrlAction(args.required("url")) }
        register(RegisteredTool("dial_number", "Open the Android dialer with a phone number. Requires confirmation.")) { args ->
            DialNumberAction(args.required("phoneNumber"))
        }
        register(RegisteredTool("youtube_search", "Open YouTube, enter a search query and submit the search using the accessibility skill runtime.")) { args ->
            YouTubeSearchAction(args.required("query"))
        }
        register(
            RegisteredTool(
                "whatsapp_summarize_conversation",
                "Authorize one local read-only WhatsApp conversation summary. Private-message reading requires confirmation before the LiteRT-LM tool executes."
            )
        ) { args ->
            AuthorizeWhatsAppConversationReadAction(
                conversation = args["conversation"]?.takeIf { it.isNotBlank() },
            )
        }
        register(RegisteredTool("read_ui_tree", "Read the active Android accessibility UI tree and return semantic node IDs.")) { ReadUiTreeAction }
        register(RegisteredTool("click_ui_node", "Click an Android UI element using a semantic node ID from read_ui_tree. Requires confirmation.")) { args ->
            ClickUiNodeAction(args.required("nodeId"), args["label"])
        }
        register(RegisteredTool("set_ui_text", "Set text on an editable Android UI element using a semantic node ID. Requires confirmation.")) { args ->
            SetUiTextAction(args.required("nodeId"), args.required("text"))
        }
        register(RegisteredTool("scroll_ui_forward", "Scroll a scrollable Android UI element forward using its semantic node ID.")) { args ->
            ScrollUiForwardAction(args.required("nodeId"))
        }
        register(RegisteredTool("accessibility_back", "Perform the Android Back global action through the accessibility service.")) { AccessibilityBackAction }
    }

    private fun Map<String, String>.required(name: String): String =
        this[name]?.takeIf { it.isNotBlank() } ?: error("Missing required tool argument: $name")
}
