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

    @Tool(description = "Turns the flashlight on.")
    fun turnOnFlashlight(): Map<String, String> = mapOf("result" to "pending")

    @Tool(description = "Turns the flashlight off.")
    fun turnOffFlashlight(): Map<String, String> = mapOf("result" to "pending")

    @Tool(description = "Creates a contact in the phone's contact list.")
    fun createContact(
        @ToolParam(description = "The first name of the contact.") firstName: String,
        @ToolParam(description = "The last name of the contact.") lastName: String,
        @ToolParam(description = "The phone number of the contact.") phoneNumber: String,
        @ToolParam(description = "The email address of the contact.") email: String,
    ): Map<String, String> = mapOf(
        "firstName" to firstName,
        "lastName" to lastName,
        "phoneNumber" to phoneNumber,
        "email" to email,
    )

    @Tool(description = "Sends an email.")
    fun sendEmail(
        @ToolParam(description = "The email address of the recipient.") to: String,
        @ToolParam(description = "The subject of the email.") subject: String,
        @ToolParam(description = "The body of the email.") body: String,
    ): Map<String, String> = mapOf("to" to to, "subject" to subject, "body" to body)

    @Tool(description = "Shows a location on the map.")
    fun showLocationOnMap(
        @ToolParam(
            description = "The location to search for. May be the name of a place, business or address."
        )
        location: String,
    ): Map<String, String> = mapOf("location" to location)

    @Tool(description = "Opens the Android Wi-Fi settings screen.")
    fun openWifiSettings(): Map<String, String> = mapOf("result" to "pending")

    @Tool(description = "Creates a new calendar event.")
    fun createCalendarEvent(
        @ToolParam(description = "Date and time in YYYY-MM-DDTHH:MM:SS format.") datetime: String,
        @ToolParam(description = "The title of the event.") title: String,
    ): Map<String, String> = mapOf("datetime" to datetime, "title" to title)

    @Tool(description = "Opens an installed Android application by its human-readable app name or package name.")
    fun openApp(
        @ToolParam(
            description = "Application name such as YouTube, WhatsApp or Chrome, or an Android package name."
        )
        appName: String,
    ): Map<String, String> = mapOf("appName" to appName)

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
