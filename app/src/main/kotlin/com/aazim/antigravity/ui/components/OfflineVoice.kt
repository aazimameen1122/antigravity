package com.aazim.antigravity.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fully offline speech recognition (Vosk), independent of the device's
 * system speech service. The system SpeechRecognizer route kept failing
 * on the user's ROM with ERROR_CLIENT (code 5) — its Google recognition
 * service rejects this app — so voice input must not depend on it.
 *
 * The model is a one-time ~38 MB download (Indian-English small model),
 * stored in the app's private files dir; afterwards recognition is
 * 100% on-device and offline.
 */
object OfflineVoice {

    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
    private const val MODEL_ZIP_TOP = "vosk-model-small-en-in-0.4"
    private const val SAMPLE_RATE = 16000f

    @Volatile
    var model: Model? = null
        private set

    fun sampleRate(): Float = SAMPLE_RATE

    private fun modelDir(context: Context): File = File(context.filesDir, "vosk-model")

    fun isModelDownloaded(context: Context): Boolean =
        File(modelDir(context), "am").exists()

    /**
     * Downloads + unpacks the model on first use, then loads it.
     * Must be called from a background dispatcher; switches internally.
     */
    suspend fun ensureModel(context: Context): Model {
        model?.let { return it }
        return withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (!isModelDownloaded(context)) {
                downloadAndUnpack(context, dir)
            }
            val loaded = Model(dir.absolutePath)
            model = loaded
            loaded
        }
    }

    private fun downloadAndUnpack(context: Context, targetDir: File) {
        val cacheZip = File(context.cacheDir, "vosk-model.zip")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.inputStream.use { input ->
            FileOutputStream(cacheZip).use { output -> input.copyTo(output) }
        }
        val extractRoot = File(context.filesDir, "vosk-model-extract")
        if (extractRoot.exists()) extractRoot.deleteRecursively()
        extractRoot.mkdirs()
        ZipInputStream(cacheZip.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(extractRoot, entry.name)
                val canonicalRoot = extractRoot.canonicalPath
                require(outFile.canonicalPath.startsWith(canonicalRoot)) {
                    "Bad zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        cacheZip.delete()
        val extracted = File(extractRoot, MODEL_ZIP_TOP)
        check(extracted.exists()) { "Model folder missing after unpack" }
        if (targetDir.exists()) targetDir.deleteRecursively()
        check(extracted.renameTo(targetDir)) { "Could not move model into place" }
        extractRoot.deleteRecursively()
    }
}
