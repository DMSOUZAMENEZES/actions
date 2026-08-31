package com.dmsouzamenezes.actions.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ModelDownloader {
    const val MODEL_FILE_NAME = "mobile_actions_q8_ekv1024.litertlm"

    private const val MODEL_URL =
        "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm?download=true"

    private const val MIN_EXPECTED_BYTES = 200L * 1024L * 1024L
    private const val MAX_REDIRECTS = 10

    data class Progress(val downloadedBytes: Long, val totalBytes: Long?) {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0L }
                ?.let { (downloadedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    }

    suspend fun ensureModel(
        context: Context,
        force: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, MODEL_FILE_NAME)
        if (!force && isValidModel(target)) return@withContext target

        val partial = File(context.cacheDir, "$MODEL_FILE_NAME.download")
        partial.delete()

        try {
            val connection = openFollowingRedirects(MODEL_URL)
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText().take(300) }
                    }.getOrNull().orEmpty()
                    error("Falha ao baixar FunctionGemma: HTTP $code${if (detail.isBlank()) "" else " - $detail"}")
                }

                val contentType = connection.contentType.orEmpty().lowercase()
                require("text/html" !in contentType && "application/json" !in contentType) {
                    "Servidor retornou $contentType em vez do modelo LiteRT-LM."
                }

                val total = connection.contentLengthLong.takeIf { it > 0L }
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= 2L * 1024L * 1024L) {
                                onProgress(Progress(downloaded, total))
                                lastReported = downloaded
                            }
                        }
                        output.flush()
                        onProgress(Progress(downloaded, total))
                    }
                }
            } finally {
                connection.disconnect()
            }

            require(isValidModel(partial)) {
                "Download terminou com ${partial.length()} bytes, mas o arquivo recebido não é o modelo LiteRT-LM esperado."
            }

            if (target.exists() && !target.delete()) error("Não foi possível substituir o modelo local anterior.")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            require(isValidModel(target)) { "Falha ao instalar o modelo baixado." }
            target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ActionsRuntime/1.1 Android")
                setRequestProperty("Accept", "application/octet-stream,*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()
            when (connection.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308 -> {
                    val location = connection.getHeaderField("Location")
                        ?: run {
                            connection.disconnect()
                            error("Redirect HTTP ${connection.responseCode} sem cabeçalho Location")
                        }
                    val next = URL(current, location)
                    connection.disconnect()
                    if (redirectCount >= MAX_REDIRECTS) error("Muitos redirecionamentos ao baixar o modelo")
                    current = next
                }
                else -> return connection
            }
        }
        error("Não foi possível resolver a URL final do modelo")
    }

    fun isValidModel(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_EXPECTED_BYTES) return false
        val header = ByteArray(16)
        val read = runCatching { FileInputStream(file).use { it.read(header) } }.getOrDefault(-1)
        if (read < 4) return false
        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        val prefix = header.take(read).toByteArray().toString(Charsets.US_ASCII).trimStart().lowercase()
        val isHtml = prefix.startsWith("<html") || prefix.startsWith("<!doctype")
        return !isZip && !isHtml
    }
}
