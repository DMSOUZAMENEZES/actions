package com.dmsouzamenezes.actions.runtime.actions

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.ActionRisk
import com.dmsouzamenezes.actions.runtime.AndroidAction

data class SetFlashlightAction(
    val enabled: Boolean,
) : AndroidAction {
    override val id: String = if (enabled) "flashlight_on" else "flashlight_off"
    override val risk: ActionRisk = ActionRisk.SAFE

    override suspend fun execute(context: ActionContext): ActionResult {
        if (
            ContextCompat.checkSelfPermission(
                context.androidContext,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure(
                code = "camera_permission_required",
                message = "CAMERA permission is required to control the flashlight",
            )
        }

        val cameraManager = context.androidContext.getSystemService(CameraManager::class.java)
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrElse {
            return ActionResult.Failure(
                code = "camera_query_failed",
                message = it.message ?: "Failed to inspect camera flash capability",
                cause = it,
            )
        } ?: return ActionResult.Failure(
            code = "flash_unavailable",
            message = "No camera with a flashlight is available",
        )

        return runCatching {
            cameraManager.setTorchMode(cameraId, enabled)
            ActionResult.Success(data = mapOf("enabled" to enabled.toString()))
        }.getOrElse {
            ActionResult.Failure(
                code = "flashlight_failed",
                message = it.message ?: "Failed to control flashlight",
                cause = it,
            )
        }
    }
}
