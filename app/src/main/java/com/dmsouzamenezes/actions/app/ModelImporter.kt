package com.dmsouzamenezes.actions.app

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ModelImporter {
    suspend fun importModel(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, ModelDownloader.MODEL_FILE_NAME)
        val partial = File(context.cacheDir, "${ModelDownloader.MODEL_FILE_NAME}.import")

        if (partial.exists()) partial.delete()

        try {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri)
                ?: error("Não foi possível abrir o arquivo selecionado.")

            input.buffered().use { source ->
                partial.outputStream().buffered().use { destination ->
                    source.copyTo(destination, bufferSize = 1024 * 1024)
                    destination.flush()
                }
            }

            require(ModelDownloader.isValidModel(partial)) {
                "O arquivo selecionado não parece ser o modelo LiteRT-LM correto. Selecione mobile-actions_q8_ekv1024.litertlm (~284 MB)."
            }

            if (target.exists() && !target.delete()) {
                error("Não foi possível substituir o modelo instalado anteriormente.")
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }

            require(ModelDownloader.isValidModel(target)) {
                "O modelo foi copiado, mas falhou na validação final."
            }

            target
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }
}
