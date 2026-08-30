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

    // Public LiteRT Community mirror containing the deployment-ready Q8/EKV1024 model.
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm?download=true"

    private const val MIN_EXPECTED_BYTES = 200L * 1024L * 1024L

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) {
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
        if (partial.exists()) partial.delete()

        try {
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ActionsRuntime/1.0 Android")
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }

            try {
                connection.connect()
                val code = connection.responseCode
                require(code in 200..299) {
                    "Falha ao baixar FunctionGemma: HTTP $code"
                }

                val contentType = connection.contentType.orEmpty().lowercase()
                require("text/html" !in contentType && "application/json" !in contentType) {
                    "Servidor retornou conteúdo inesperado ($contentType) em vez do modelo LiteRT-LM."
                }

                val total = connection.contentLengthLong.takeIf { it > 0L }
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
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
                "Download concluído, mas o arquivo não parece ser um modelo LiteRT-LM válido."
            }

            if (target.exists() && !target.delete()) {
                error("Não foi possível substituir o modelo local anterior.")
            }
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

    fun isValidModel(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_EXPECTED_BYTES) return false

        val header = ByteArray(16)
        val read = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(-1)
        if (read < 4) return false

        // Reject common wrong downloads: ZIP/APK and HTML error/login pages.
        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        val prefix = header.take(read).toByteArray().toString(Charsets.US_ASCII).trimStart().lowercase()
        val isHtml = prefix.startsWith("<html") || prefix.startsWith("<!doctype")
        return !isZip && !isHtml
    }
}
