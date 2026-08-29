package com.dmsouzamenezes.actions.runtime.actions

import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.net.toUri
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

data class CreateContactAction(
    val firstName: String,
    val lastName: String,
    val phoneNumber: String,
    val email: String,
) : AndroidAction {
    override val id: String = "create_contact"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = "Open contact creation for $firstName $lastName?"

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName".trim())
            putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
            putExtra(ContactsContract.Intents.Insert.EMAIL, email)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.androidContext.startActivity(intent)
        ActionResult.Success(data = mapOf("name" to "$firstName $lastName".trim()))
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open contact creation", it)
    }
}

data class SendEmailAction(
    val to: String,
    val subject: String,
    val body: String,
) : AndroidAction {
    override val id: String = "send_email"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = "Open an email draft addressed to $to?"

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:${android.net.Uri.encode(to)}".toUri()
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.androidContext.startActivity(intent)
        ActionResult.Success(data = mapOf("to" to to, "subject" to subject))
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open email draft", it)
    }
}

data class ShowLocationOnMapAction(
    val location: String,
) : AndroidAction {
    override val id: String = "show_location_on_map"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        val encoded = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
        val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.androidContext.startActivity(intent)
        ActionResult.Success(data = mapOf("location" to location))
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open map", it)
    }
}

data class CreateCalendarEventAction(
    val datetime: String,
    val title: String,
) : AndroidAction {
    override val id: String = "create_calendar_event"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = "Open calendar event '$title' at $datetime?"

    override suspend fun execute(context: ActionContext): ActionResult {
        val startMillis = runCatching {
            LocalDateTime.parse(datetime)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrElse {
            return ActionResult.Failure(
                code = "invalid_datetime",
                message = "Expected datetime in YYYY-MM-DDTHH:MM:SS format: $datetime",
                cause = it,
            )
        }

        return runCatching {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 60 * 60 * 1000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.androidContext.startActivity(intent)
            ActionResult.Success(data = mapOf("title" to title, "datetime" to datetime))
        }.getOrElse {
            ActionResult.Failure("intent_failed", it.message ?: "Failed to open calendar event", it)
        }
    }
}
