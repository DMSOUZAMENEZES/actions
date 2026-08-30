package com.dmsouzamenezes.actions.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dmsouzamenezes.actions.runtime.ActionPlan
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.AndroidFunctionRuntimeSession
import com.dmsouzamenezes.actions.runtime.FunctionGemmaRuntimeFactory
import com.dmsouzamenezes.actions.runtime.PlanningResult
import com.dmsouzamenezes.actions.runtime.accessibility.AccessibilityRuntimeBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_FILE_NAME = "mobile_actions_q8_ekv1024.litertlm"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RuntimeDemoScreen()
                }
            }
        }
    }
}

@Composable
private fun RuntimeDemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelFile = remember(context) { File(context.filesDir, MODEL_FILE_NAME) }

    var prompt by remember { mutableStateOf("Abra as configurações do Wi-Fi") }
    var status by remember {
        mutableStateOf(
            if (modelFile.exists()) "Modelo pronto: ${modelFile.absolutePath}"
            else "Selecione o arquivo $MODEL_FILE_NAME"
        )
    }
    var busy by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(modelFile.exists()) }
    var pendingPlan by remember { mutableStateOf<ActionPlan?>(null) }
    var session by remember { mutableStateOf<AndroidFunctionRuntimeSession?>(null) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        status = if (granted) {
            "Permissão de câmera concedida; a lanterna pode ser controlada."
        } else {
            "Permissão de câmera negada; comandos de lanterna retornarão erro de permissão."
        }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            busy = true
            status = "Copiando modelo para o armazenamento privado do app..."
            val copied = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Não foi possível abrir o arquivo selecionado")
                }
            }

            copied.onSuccess {
                session?.close()
                session = null
                pendingPlan = null
                modelReady = true
                status = "Modelo pronto: ${modelFile.absolutePath}"
            }.onFailure {
                modelReady = modelFile.exists()
                status = "Falha ao importar modelo: ${it.message}"
            }
            busy = false
        }
    }

    fun getOrCreateSession(): AndroidFunctionRuntimeSession {
        return session ?: FunctionGemmaRuntimeFactory.create(
            context = context,
            modelPath = modelFile.absolutePath,
        ).also { session = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Android Function Runtime", style = MaterialTheme.typography.headlineMedium)
        Text(
            "LiteRT-LM + MobileActions/FunctionGemma 270M. O modelo escolhe a função; " +
                "o runtime aplica política e executa a ação Android."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (modelReady) "Trocar modelo" else "Selecionar modelo")
            }

            if (!cameraGranted) {
                OutlinedButton(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Permitir lanterna")
                }
            }
        }

        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (AccessibilityRuntimeBridge.isConnected) {
                    "Acessibilidade conectada"
                } else {
                    "Ativar automação por acessibilidade"
                }
            )
        }

        Text(status, style = MaterialTheme.typography.bodySmall)

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("Comando") },
            enabled = !busy,
        )

        Button(
            onClick = {
                scope.launch {
                    if (!modelFile.exists()) {
                        modelReady = false
                        status = "Selecione primeiro o arquivo $MODEL_FILE_NAME"
                        return@launch
                    }

                    busy = true
                    pendingPlan = null
                    status = "Interpretando comando no dispositivo..."

                    runCatching {
                        val runtime = getOrCreateSession().runtime
                        when (val planning = runtime.plan(prompt)) {
                            is PlanningResult.NoAction -> {
                                status = "Nenhuma ação: ${planning.response}"
                            }
                            is PlanningResult.Failure -> {
                                status = "Falha de planejamento [${planning.code}]: ${planning.message}"
                            }
                            is PlanningResult.Planned -> {
                                when (val result = runtime.execute(planning.plan)) {
                                    is ActionResult.ConfirmationRequired -> {
                                        pendingPlan = planning.plan
                                        status = result.summary
                                    }
                                    else -> status = result.render()
                                }
                            }
                        }
                    }.onFailure {
                        status = "Erro: ${it.message}"
                    }
                    busy = false
                }
            },
            enabled = !busy && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Executar comando")
        }

        pendingPlan?.let { plan ->
            Text("Confirmação necessária", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        pendingPlan = null
                        status = "Ação cancelada."
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val currentSession = session
                            status = if (currentSession == null) {
                                "Sessão do modelo não está disponível."
                            } else {
                                currentSession.runtime.execute(plan, confirmed = true).render()
                            }
                            pendingPlan = null
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    Text("Confirmar")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Modelo esperado: litert-community/functiongemma-270m-ft-mobile-actions / $MODEL_FILE_NAME",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun ActionResult.render(): String = when (this) {
    is ActionResult.Success -> buildString {
        append(message ?: "Ação executada com sucesso")
        if (data.isNotEmpty()) append(" — $data")
    }
    is ActionResult.ConfirmationRequired -> "Confirmação necessária: $summary"
    is ActionResult.Failure -> "Falha [$code]: $message"
}
