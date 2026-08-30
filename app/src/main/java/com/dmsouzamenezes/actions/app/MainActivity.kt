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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.AgentRunResult
import com.dmsouzamenezes.actions.runtime.AndroidFunctionRuntimeSession
import com.dmsouzamenezes.actions.runtime.FunctionGemmaRuntimeFactory
import com.dmsouzamenezes.actions.runtime.PendingAgentRun
import com.dmsouzamenezes.actions.runtime.accessibility.AccessibilityRuntimeBridge
import com.dmsouzamenezes.actions.runtime.actions.OpenWifiSettingsAction
import java.io.File
import kotlinx.coroutines.launch

private const val MAX_AGENT_STEPS = 8

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
    val modelFile = remember(context) { File(context.filesDir, ModelDownloader.MODEL_FILE_NAME) }

    var prompt by remember { mutableStateOf("Abra o WhatsApp, leia a conversa e faça um resumo") }
    var status by remember { mutableStateOf("Verificando modelo FunctionGemma...") }
    var diagnostics by remember { mutableStateOf("Diagnóstico: inicializando.") }
    var busy by remember { mutableStateOf(false) }
    var downloadingModel by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(ModelDownloader.isValidModel(modelFile)) }
    var pendingAgent by remember { mutableStateOf<PendingAgentRun?>(null) }
    var session by remember { mutableStateOf<AndroidFunctionRuntimeSession?>(null) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    fun closeRuntimeSession() {
        pendingAgent?.close()
        pendingAgent = null
        session?.close()
        session = null
    }

    suspend fun downloadModel(force: Boolean) {
        downloadingModel = true
        busy = true
        modelReady = false
        status = if (force) {
            "Baixando novamente o FunctionGemma (~284 MB)..."
        } else {
            "Baixando FunctionGemma automaticamente (~284 MB)..."
        }
        diagnostics = "Fonte: LiteRT Community / mobile_actions_q8_ekv1024.litertlm"

        runCatching {
            ModelDownloader.ensureModel(context.applicationContext, force = force)
        }.onSuccess { file ->
            closeRuntimeSession()
            modelReady = true
            status = "Modelo pronto. O agente pode ser executado."
            diagnostics = "Modelo validado: ${file.length()} bytes; acessibilidade=${AccessibilityRuntimeBridge.isConnected}"
        }.onFailure { error ->
            modelReady = ModelDownloader.isValidModel(modelFile)
            status = "Falha ao baixar o modelo: ${error.message}"
            diagnostics = "${error::class.java.simpleName}. Verifique internet e toque em Baixar modelo novamente."
        }

        downloadingModel = false
        busy = false
    }

    LaunchedEffect(Unit) {
        if (ModelDownloader.isValidModel(modelFile)) {
            modelReady = true
            status = "Modelo FunctionGemma encontrado e validado."
            diagnostics = "Modelo: ${modelFile.length()} bytes; acessibilidade=${AccessibilityRuntimeBridge.isConnected}"
        } else {
            downloadModel(force = false)
        }
    }

    DisposableEffect(Unit) {
        onDispose { closeRuntimeSession() }
    }

    fun renderAgentResult(result: AgentRunResult) {
        when (result) {
            is AgentRunResult.Completed -> {
                pendingAgent = null
                val traceText = if (result.trace.isEmpty()) {
                    "nenhuma ferramenta executada"
                } else {
                    result.trace.joinToString(" → ") { step ->
                        val outcome = when (step.result) {
                            is ActionResult.Success -> "OK"
                            is ActionResult.ConfirmationRequired -> "CONFIRMAÇÃO"
                            is ActionResult.Failure -> "FALHA"
                        }
                        "${step.tool}[$outcome]"
                    }
                }
                diagnostics = "Trace: $traceText"
                status = result.response.ifBlank { "Tarefa concluída." }
            }

            is AgentRunResult.ConfirmationRequired -> {
                pendingAgent = result.pending
                diagnostics = "Trace antes da confirmação: " +
                    if (result.trace.isEmpty()) "nenhuma ferramenta concluída"
                    else result.trace.joinToString(" → ") { it.tool }
                status = "Confirmação necessária: ${result.summary}"
            }

            is AgentRunResult.Failure -> {
                pendingAgent = null
                diagnostics = "Falha do agente: ${result.code}; trace=" +
                    if (result.trace.isEmpty()) "vazio" else result.trace.joinToString(" → ") { it.tool }
                status = "Falha [${result.code}]: ${result.message}"
            }
        }
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

    fun getOrCreateSession(): AndroidFunctionRuntimeSession {
        check(ModelDownloader.isValidModel(modelFile)) { "Modelo FunctionGemma não está pronto." }
        return session ?: FunctionGemmaRuntimeFactory.create(
            context = context,
            modelPath = modelFile.absolutePath,
        ).also { session = it }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Android Function Agent", style = MaterialTheme.typography.headlineMedium)
        Text("LiteRT-LM + FunctionGemma 270M. O modelo é baixado automaticamente e executado no dispositivo.")

        if (downloadingModel) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Download automático em andamento. Não feche o aplicativo.", style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(
            onClick = { scope.launch { downloadModel(force = true) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (modelReady) "Baixar modelo novamente" else "Tentar baixar modelo novamente")
        }

        if (!cameraGranted) {
            OutlinedButton(
                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Permitir lanterna") }
        }

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (AccessibilityRuntimeBridge.isConnected) "Acessibilidade conectada" else "Ativar automação por acessibilidade")
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(diagnostics, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(
            onClick = {
                scope.launch {
                    diagnostics = "Teste direto: executando OpenWifiSettingsAction sem LLM..."
                    when (val result = OpenWifiSettingsAction.execute(ActionContext(context.applicationContext))) {
                        is ActionResult.Success -> diagnostics = "Teste direto OK: Android abriu Wi-Fi. Runtime/Intent está funcional."
                        is ActionResult.Failure -> diagnostics = "Teste direto FALHOU [${result.code}]: ${result.message}"
                        is ActionResult.ConfirmationRequired -> diagnostics = "Teste direto inesperadamente pediu confirmação."
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Teste direto: abrir Wi-Fi") }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("Comando") },
            enabled = !busy && pendingAgent == null,
        )

        Button(
            onClick = {
                scope.launch {
                    if (!ModelDownloader.isValidModel(modelFile)) {
                        status = "O modelo ainda não está pronto."
                        return@launch
                    }
                    pendingAgent?.close()
                    pendingAgent = null
                    busy = true
                    status = "Agente executando no dispositivo..."
                    diagnostics = "FunctionGemma=${modelFile.length()} bytes; acessibilidade=${AccessibilityRuntimeBridge.isConnected}"
                    runCatching {
                        getOrCreateSession().runtime.runAgent(text = prompt, maxSteps = MAX_AGENT_STEPS)
                    }.onSuccess(::renderAgentResult).onFailure {
                        diagnostics = "Exceção ${it::class.java.simpleName}: ${it.message}"
                        status = "Erro: ${it.message}"
                    }
                    busy = false
                }
            },
            enabled = !busy && modelReady && prompt.isNotBlank() && pendingAgent == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Executar tarefa") }

        pendingAgent?.let { pending ->
            Text("Confirmação necessária", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        pending.close()
                        pendingAgent = null
                        status = "Ação cancelada."
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancelar") }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Ação confirmada. Continuando tarefa..."
                            val currentSession = session
                            if (currentSession == null) {
                                pending.close()
                                pendingAgent = null
                                status = "Sessão do modelo não está disponível."
                            } else {
                                runCatching { currentSession.runtime.resumeAgent(pending, confirmed = true) }
                                    .onSuccess(::renderAgentResult)
                                    .onFailure {
                                        pending.close()
                                        pendingAgent = null
                                        diagnostics = "Erro ao retomar: ${it::class.java.simpleName}: ${it.message}"
                                        status = "Erro ao continuar agente: ${it.message}"
                                    }
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text("Confirmar e continuar") }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Modelo automático: ${ModelDownloader.MODEL_FILE_NAME}", style = MaterialTheme.typography.bodySmall)
    }
}
