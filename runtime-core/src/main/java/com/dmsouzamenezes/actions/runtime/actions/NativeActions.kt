package com.dmsouzamenezes.actions.runtime.actions

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction

data class OpenAppAction(
    val appName: String,
) : AndroidAction {
    override val id: String = "open_app"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult {
        val packageManager = context.androidContext.packageManager
        val trimmed = appName.trim()

        val packageName = packageManager.getLaunchIntentForPackage(trimmed)?.let { trimmed }
            ?: packageManager.getInstalledApplications(0)
                .asSequence()
                .mapNotNull { applicationInfo ->
                    val label = packageManager.getApplicationLabel(applicationInfo).toString()
                    if (label.equals(trimmed, ignoreCase = true)) applicationInfo.packageName else null
                }
                .firstOrNull()
            ?: return ActionResult.Failure(
                code = "app_not_installed",
                message = "Application not installed or not found: $appName",
            )

        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure(
                code = "app_not_launchable",
                message = "Application cannot be launched: $appName",
            )

        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.androidContext.startActivity(intent)
            ActionResult.Success(
                data = mapOf(
                    "app_name" to appName,
                    "package" to packageName,
                )
            )
        }.getOrElse {
            ActionResult.Failure("intent_failed", it.message ?: "Failed to open application", it)
        }
    }
}

data object OpenWifiSettingsAction : AndroidAction {
    override val id: String = "open_wifi_settings"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        context.androidContext.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ActionResult.Success()
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open Wi-Fi settings", it)
    }
}

data class OpenUrlAction(val url: String) : AndroidAction {
    override val id: String = "open_url"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        context.androidContext.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ActionResult.Success(data = mapOf("url" to url))
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open URL", it)
    }
}

data class DialNumberAction(val phoneNumber: String) : AndroidAction {
    override val id: String = "dial_number"
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val confirmationSummary: String = "Open dialer for $phoneNumber?"

    override suspend fun execute(context: ActionContext): ActionResult = runCatching {
        context.androidContext.startActivity(
            Intent(Intent.ACTION_DIAL, "tel:$phoneNumber".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ActionResult.Success(data = mapOf("phone_number" to phoneNumber))
    }.getOrElse {
        ActionResult.Failure("intent_failed", it.message ?: "Failed to open dialer", it)
    }
}
