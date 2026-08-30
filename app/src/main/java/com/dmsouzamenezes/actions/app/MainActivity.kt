package com.dmsouzamenezes.actions.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.dmsouzamenezes.actions.runtime.ActionContext
import com.dmsouzamenezes.actions.runtime.ActionResult
import com.dmsouzamenezes.actions.runtime.AgentRunResult
import com.dmsouzamenezes.actions.runtime.AndroidFunctionRuntimeSession
import com.dmsouzamenezes.actions.runtime.FunctionGemmaRuntimeFactory
import com.dmsouzamenezes.actions.runtime.PendingAgentRun
import com.dmsouzamenezes.actions.runtime.accessibility.AccessibilityRuntimeBridge
import com.dmsouzamenezes.actions.runtime.actions.OpenWifiSettingsAction
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_FILE_NAME = "mobile_actions_q8_ekv1024.litertlm"
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
    val modelFile = remember(context) { File(context.filesDir, MODEL_FILE_NAME) }

    var prompt by remember { mutableStateOf("Abra as configurações do Wi-Fi") }
    var status by remember {
        mutableStateOf(
            if (modelFile.exists()) "Modelo encontrado. Use Trocar modelo se precisar substituir."
            else "Selecione o arquivo $MODEL_FILE_NAME"
        )
    }
    var diagnostics by remember { mutableStateOf("Diagnóstico: aguardando teste.") }
    var busy by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(modelFile.exists()) }
    var pendingAgent by remember { mutableStateOf<PendingAgentRun?>(null) }
    var session by remember { mutableStateOf<AndroidFunctionRuntimeSession?>(null) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingAgent?.close()
            session?.close()
        }
    }

    fun renderAgentResult(result: AgentRunResult) {
        when (result) {
            is AgentRunResult.Completed -> {
                pendingAgent = null
                val steps = result.trace.size
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
                status = if (result.response.isBlank()) {
                    "Tarefa concluída em $steps etapa(s)."
                } else {
                    "Tarefa concluída em $steps etapa(s): ${result.response}"
                }
            }
            is AgentRunResult.ConfirmationRequired -> {
                pendingAgent = result.pending
                diagnostics = "Trace antes da confirmação: " +
                    if (result.trace.isEmpty()) "nenhuma ferramenta concluída" else result.trace.joinToString(" → ") { it.tool }
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

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            busy = true
            status = "Validando modelo LiteRT-LM..."
            val imported = runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }

                    require(displayName?.endsWith(".litertlm", ignoreCase = true) == true) {
                        "Arquivo inválido: selecione o modelo .litertlm, não o APK/ZIP do aplicativo."
                    }

                    val tempFile = File(context.cacheDir, "$MODEL_FILE_NAME.import")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Não foi possível abrir o arquivo selecionado")

                        require(tempFile.length() > 1024L) {
                            "O arquivo selecionado está vazio ou pequeno demais para ser um modelo LiteRT-LM."
                        }

                        val header = ByteArray(4)
                        FileInputStream(tempFile).use { stream -> stream.read(header) }
                        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                        require(!isZip) {
                            "Arquivo ZIP detectado. Extraia/baixe diretamente $MODEL_FILE_NAME e selecione o arquivo .litertlm."
                        }

                        if (modelFile.exists() && !modelFile.delete()) {
                            error("Não foi possível substituir o modelo anterior")
                        }
                        require(tempFile.renameTo(modelFile)) {
                            tempFile.copyTo(modelFile, overwrite = true)
                            tempFile.delete()
                            true
                        }
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
            }

            imported.onSuccess {
                pendingAgent?.close()
                pendingAgent = null
                session?.close()
                session = null
                modelReady = true
                diagnostics = "Modelo: ${modelFile.length()} bytes; acessibilidade=${AccessibilityRuntimeBridge.isConnected}"
                status = "Modelo importado e validado: $MODEL_FILE_NAME"
            }.onFailure {
                modelReady = modelFile.exists()
                diagnostics = "Falha de importação: ${it::class.java.simpleName}"
                status = "Modelo rejeitado: ${it.message}"
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
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Android Function Agent", style = MaterialTheme.typography.headlineMedium)
        Text("LiteRT-LM + MobileActions/FunctionGemma 270M. O modelo escolhe a função; o runtime aplica política e executa a ação Android.")

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (modelReady) "Trocar modelo" else "Selecionar modelo") }

            if (!cameraGranted) {
                OutlinedButton(
                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Permitir lanterna") }
            }
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
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
                    if (!modelFile.exists()) {
                        modelReady = false
                        status = "Selecione primeiro o arquivo $MODEL_FILE_NAME"
                        return@launch
                    }
                    pendingAgent?.close()
                    pendingAgent = null
                    busy = true
                    status = "Agente executando no dispositivo..."
                    diagnostics = "Inicializando/consultando FunctionGemma; modelo=${modelFile.length()} bytes; acessibilidade=${AccessibilityRuntimeBridge.isConnected}"
                    runCatching {
                        getOrCreateSession().runtime.runAgent(text = prompt, maxSteps = MAX_AGENT_STEPS)
                    }.onSuccess(::renderAgentResult).onFailure {
                        val detail = it.message.orEmpty()
                        diagnostics = "Exceção ${it::class.java.simpleName}: $detail"
                        status = if (detail.contains("magic number", ignoreCase = true) || detail.contains("PK")) {
                            "Modelo inválido. Toque em Trocar modelo e selecione $MODEL_FILE_NAME (.litertlm), não um ZIP/APK."
                        } else {
                            "Erro: ${it.message}"
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy && prompt.isNotBlank() && pendingAgent == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Executar tarefa") }

        pendingAgent?.let { pending ->
            Text("Confirmação necessária", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        pending.close(); pendingAgent = null; status = "Ação cancelada. O agente foi encerrado."
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
                                pending.close(); pendingAgent = null; status = "Sessão do modelo não está disponível."
                            } else {
                                runCatching { currentSession.runtime.resumeAgent(pending, confirmed = true) }
                                    .onSuccess(::renderAgentResult)
                                    .onFailure {
                                        pending.close(); pendingAgent = null
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
        Text("Modelo obrigatório: litert-community/functiongemma-270m-ft-mobile-actions / $MODEL_FILE_NAME", style = MaterialTheme.typography.bodySmall)
    }
}
