package com.aazim.antigravity.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Voice language for offline (Vosk) recognition.
 *
 * Verified 2026-11-22 against alphacephei.com (curl HEAD):
 * - English (India): vosk-model-small-en-in-0.4.zip — 200 OK
 * - Hindi: vosk-model-small-hi-0.22.zip — 200 OK, 44,458,845 bytes
 * - Urdu: NO Vosk model exists on alphacephei.com. Checked and got 404 for
 *   vosk-model-small-ur-0.24, vosk-model-small-ur-0.22, vosk-model-ur-0.22,
 *   vosk-model-ur-0.3 and vosk-model-ur-0.22_5gram. Urdu is therefore listed
 *   in the picker as unavailable (url == null) instead of pretending to work.
 */
enum class VoiceLanguage(
    val code: String,
    val displayName: String,
    val url: String?,
    val zipTop: String?,
    val dirName: String,
    val sizeLabel: String
) {
    EN_IN(
        code = "en-in",
        displayName = "English (India)",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
        zipTop = "vosk-model-small-en-in-0.4",
        dirName = "vosk-model-en-in",
        sizeLabel = "~38 MB"
    ),
    HINGLISH(
        code = "hinglish",
        displayName = "Hinglish (Roman)",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        zipTop = "vosk-model-small-hi-0.22",
        dirName = "vosk-model-hi",
        sizeLabel = "~42 MB"
    ),
    HI(
        code = "hi",
        displayName = "Hindi",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
        zipTop = "vosk-model-small-hi-0.22",
        dirName = "vosk-model-hi",
        sizeLabel = "~42 MB"
    ),
    UR(
        code = "ur",
        displayName = "Urdu",
        url = null,
        zipTop = null,
        dirName = "vosk-model-ur",
        sizeLabel = "No offline model available"
    );

    val isAvailable: Boolean get() = url != null

    /** Hinglish speaks Hindi but must land in the input as Roman text. */
    val romanOutput: Boolean get() = this == HINGLISH

    /** Hinglish and Hindi share the one downloaded Hindi model on disk. */
    val modelLanguage: VoiceLanguage get() = if (this == HINGLISH) HI else this

    companion object {
        fun fromCode(code: String?): VoiceLanguage =
            entries.firstOrNull { it.code == code } ?: EN_IN
    }
}

/**
 * Fully offline speech recognition (Vosk), independent of the device's
 * system speech service. Multi-language since v1.1.0: each language has
 * its own downloaded model under filesDir/<dirName> and its own cached
 * [Model] instance; switching language reloads/uses that language's model.
 * Models are one-time downloads; afterwards recognition is 100% offline.
 */
object OfflineVoice {

    private const val SAMPLE_RATE = 16000f
    private const val PREFS = "voice_prefs"
    private const val KEY_LANG = "voice_language"

    private val models = mutableMapOf<VoiceLanguage, Model>()

    @Volatile
    var currentLanguage: VoiceLanguage = VoiceLanguage.EN_IN
        private set

    /** Currently loaded model for [currentLanguage] (back-compat accessor). */
    val model: Model? get() = models[currentLanguage]

    fun modelFor(lang: VoiceLanguage): Model? = models[lang] ?: models[lang.modelLanguage]

    fun sampleRate(): Float = SAMPLE_RATE

    /** Apply the selected language's output script to recognised text. */
    fun postProcess(text: String, lang: VoiceLanguage): String =
        if (lang.romanOutput) HinglishTransliterator.transliterate(text) else text

    fun getSelectedLanguage(context: Context): VoiceLanguage {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, VoiceLanguage.EN_IN.code)
        return VoiceLanguage.fromCode(code).also { currentLanguage = it }
    }

    fun setSelectedLanguage(context: Context, lang: VoiceLanguage) {
        currentLanguage = lang
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.code).apply()
    }

    private fun modelDir(context: Context, lang: VoiceLanguage): File =
        File(context.filesDir, lang.dirName)

    /** Legacy v1.0.8/1.0.9 English model location. */
    private fun legacyDir(context: Context): File = File(context.filesDir, "vosk-model")

    fun isModelDownloaded(context: Context, lang: VoiceLanguage): Boolean {
        if (File(modelDir(context, lang), "am").exists()) return true
        // English installs from <=1.0.9 live in the legacy dir.
        if (lang == VoiceLanguage.EN_IN && File(legacyDir(context), "am").exists()) return true
        return false
    }

    fun isModelDownloaded(context: Context): Boolean =
        isModelDownloaded(context, getSelectedLanguage(context))

    suspend fun ensureModel(context: Context): Model =
        ensureModel(context, getSelectedLanguage(context))

    suspend fun ensureModel(context: Context, lang: VoiceLanguage): Model {
        require(lang.isAvailable) { "No offline Vosk model available for ${lang.displayName}" }
        modelFor(lang)?.let { currentLanguage = lang; return it }
        return withContext(Dispatchers.IO) {
            modelFor(lang)?.let { return@withContext it }
            val dir = modelDir(context, lang.modelLanguage)
            if (!File(dir, "am").exists()) {
                val legacy = legacyDir(context)
                if (lang == VoiceLanguage.EN_IN && File(legacy, "am").exists()) {
                    // Reuse the already-downloaded legacy English model.
                    val loaded = Model(legacy.absolutePath)
                    models[lang] = loaded
                    currentLanguage = lang
                    return@withContext loaded
                }
                downloadAndUnpack(context, lang, dir)
            }
            val loaded = Model(dir.absolutePath)
            models[lang] = loaded
            currentLanguage = lang
            loaded
        }
    }

    private fun downloadAndUnpack(context: Context, lang: VoiceLanguage, targetDir: File) {
        val url = lang.url ?: error("No model URL for ${lang.displayName}")
        val zipTop = lang.zipTop ?: error("No model zip layout for ${lang.displayName}")
        val cacheZip = File(context.cacheDir, "vosk-model-${lang.code}.zip")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.inputStream.use { input ->
            FileOutputStream(cacheZip).use { output -> input.copyTo(output) }
        }
        val extractRoot = File(context.filesDir, "vosk-model-extract-${lang.code}")
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
        val extracted = File(extractRoot, zipTop)
        check(extracted.exists()) { "Model folder missing after unpack" }
        if (targetDir.exists()) targetDir.deleteRecursively()
        check(extracted.renameTo(targetDir)) { "Could not move model into place" }
        extractRoot.deleteRecursively()
    }
}

/**
 * Devanagari -> casual Roman (Hinglish) transliteration.
 *
 * The Vosk Hindi model only outputs Devanagari. Hinglish mode runs its
 * output through here so spoken Hindi lands in the chat input the way
 * people actually type it: "मुझे क्या करना है" -> "mujhe kya karna hai",
 * never Devanagari. A common-word dictionary carries the spellings a
 * letter-by-letter pass gets wrong (bhai, nahi, video, chahiye...); the
 * letter pass handles everything else, with word-final long vowels
 * shortened the way Hinglish is typed (kyaa -> kya, rahaa -> raha).
 */
object HinglishTransliterator {

    private val dictionary = mapOf(
        "मुझे" to "mujhe", "तुम्हें" to "tumhein", "हमें" to "hamein", "इन्हें" to "inhein",
        "उन्हें" to "unhein", "किसे" to "kise", "जिसे" to "jise",
        "क्या" to "kya", "क्यों" to "kyun", "कैसे" to "kaise", "कहाँ" to "kahan",
        "कहां" to "kahan", "यहाँ" to "yahan", "वहाँ" to "vahan", "अहाँ" to "ahan",
        "नहीं" to "nahi", "भाई" to "bhai", "चाहिए" to "chahiye",
        "वीडियो" to "video", "फोटो" to "photo", "मोबाइल" to "mobile",
        "इंटरनेट" to "internet", "कंप्यूटर" to "computer", "टाइम" to "time",
        "यार" to "yaar", "अरे" to "are", "चलो" to "chalo", "ठीक" to "thik",
        "बहुत" to "bahut", "अच्छा" to "achha", "अच्छी" to "achhi", "अच्छे" to "achhe",
        "करें" to "karein", "करो" to "karo", "करना" to "karna", "करने" to "karne",
        "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "हुआ" to "hua", "हुई" to "hui", "हुए" to "hue",
        "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere",
        "तेरा" to "tera", "तेरी" to "teri", "उसका" to "uska", "उसकी" to "uski",
        "इसका" to "iska", "इसकी" to "iski", "अपना" to "apna", "अपनी" to "apni",
        "कौन" to "kaun", "कब" to "kab", "अब" to "ab", "जब" to "jab", "तब" to "tab",
        "सब" to "sab", "लोग" to "log", "काम" to "kaam", "घर" to "ghar",
        "आज" to "aaj", "कल" to "kal", "पर" to "par", "में" to "mein",
        "से" to "se", "को" to "ko", "का" to "ka", "की" to "ki", "के" to "ke",
        "है" to "hai", "हैं" to "hain", "हो" to "ho", "था" to "tha", "थी" to "thi", "थे" to "the",
        "एक" to "ek", "दो" to "do", "तीन" to "teen", "चार" to "char", "पाँच" to "paanch",
        "ये" to "ye", "वो" to "vo", "यह" to "ye", "वह" to "vo",
        "और" to "aur", "या" to "ya", "भी" to "bhi", "ही" to "hi", "तो" to "to",
        "ना" to "na", "ने" to "ne", "पे" to "pe",
        "वाला" to "wala", "वाली" to "wali", "वाले" to "wale",
        "लिए" to "liye", "बिना" to "bina", "बाद" to "baad", "पहले" to "pehle",
        "अभी" to "abhi", "कभी" to "kabhi", "हमेशा" to "hamesha", "शायद" to "shayad",
        "लगता" to "lagta", "सकता" to "sakta", "सकते" to "sakte", "सकती" to "sakti",
        "देना" to "dena", "लेना" to "lena", "जाना" to "jana", "आना" to "aana",
        "देखो" to "dekho", "बोलो" to "bolo", "सुनो" to "suno", "बताओ" to "batao",
        "समझ" to "samajh", "पता" to "pata", "दोस्त" to "dost",
        "पैसा" to "paisa", "पैसे" to "paise", "बात" to "baat",
        "मैं" to "main", "हम" to "hum", "तुम" to "tum", "आप" to "aap",
        "मुझे" to "mujhe", "उसे" to "use", "इसे" to "ise", "उसे" to "use",
        "नो" to "no", "मतलब" to "matlab", "शुक्रिया" to "shukriya",
        "बनाओ" to "banao", "बना" to "bana", "दिखाओ" to "dikhao", "खोलो" to "kholo",
        "भेजो" to "bhejo", "लिखो" to "likho", "पढ़ो" to "padho"
    )

    /** Roman spellings of the dictionary words, for auto-detect scoring. */
    val commonWords: Set<String> get() = dictionary.values.toSet()

    private val independentVowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au"
    )

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h"
    )

    private val nuktaForms = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z",
        'ड' to "d", 'ढ' to "dh", 'फ' to "f"
    )

    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
        'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au"
    )

    fun transliterate(text: String): String {
        if (text.none { it in '\u0900'..'\u097F' }) return text
        return text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { token ->
            if (token.isBlank() || token.none { it in '\u0900'..'\u097F' }) token
            else transliterateWord(token)
        }
    }

    private fun transliterateWord(word: String): String {
        dictionary[word]?.let { return it }
        // Strip punctuation the recogniser may attach, keep it aside.
        val core = word.trim()
        dictionary[core]?.let { return it }

        val out = StringBuilder()
        var i = 0
        while (i < core.length) {
            val c = core[i]
            when {
                consonants.containsKey(c) -> {
                    var base = consonants.getValue(c)
                    var j = i + 1
                    var hasNukta = false
                    if (j < core.length && core[j] == '़') { hasNukta = true; j++ }
                    if (hasNukta) base = nuktaForms[c] ?: base
                    when {
                        j < core.length && core[j] == '्' -> {
                            out.append(base); i = j + 1 // halant: no inherent vowel
                        }
                        j < core.length && matras.containsKey(core[j]) -> {
                            out.append(base).append(matras.getValue(core[j])); i = j + 1
                        }
                        j < core.length && (core[j] == 'ं' || core[j] == 'ँ') -> {
                            out.append(base).append("an"); i = j + 1
                        }
                        else -> { out.append(base).append("a"); i = j }
                    }
                }
                independentVowels.containsKey(c) -> {
                    out.append(independentVowels.getValue(c)); i++
                    if (i < core.length && (core[i] == 'ं' || core[i] == 'ँ')) { out.append("n"); i++ }
                }
                c == 'ं' || c == 'ँ' -> { out.append("n"); i++ }
                c == 'ः' -> { out.append("h"); i++ }
                c in '०'..'९' -> { out.append(('0' + (c - '०'))); i++ }
                c == '़' || c == '्' || matras.containsKey(c) -> i++ // stray combining mark
                else -> { out.append(c); i++ }
            }
        }
        var result = out.toString()
        // Casual Hinglish shortens word-final long vowels: kyaa->kya, naheen->nahin is
        // handled by the dictionary where it matters; here: aa->a, ee->i, oo->u at end.
        result = when {
            result.endsWith("aa") -> result.dropLast(1)
            result.endsWith("ee") -> result.dropLast(2) + "i"
            result.endsWith("oo") -> result.dropLast(2) + "u"
            else -> result
        }
        if (result.endsWith("aai")) result = result.dropLast(3) + "ai"
        return result
    }
}

/**
 * Auto language detection for the single mic button (v1.1.2).
 *
 * There is no reliable offline language-ID model in Vosk, so detection
 * runs BOTH downloaded recognizers (English + Hindi) on the same audio
 * and keeps the result that scores higher: average per-word confidence
 * from Vosk, plus a small bonus for common words of that language.
 * A Hindi win is returned transliterated to Hinglish (Roman); an
 * English win is returned as-is. The user never picks a language.
 */
object VoiceAutoDetect {

    private val commonEn = setOf(
        "the", "a", "an", "i", "you", "me", "my", "it", "is", "are", "am",
        "make", "made", "create", "build", "add", "show", "open", "close",
        "run", "write", "send", "search", "find", "get", "set", "new",
        "this", "that", "for", "to", "of", "and", "or", "in", "on", "with",
        "what", "how", "can", "do", "please", "hello", "yes", "no", "ok",
        "okay", "now", "today", "website", "app", "page", "file", "project",
        "code", "video", "photo", "image", "text", "one", "two", "go", "let"
    )

    private data class Scored(val text: String, val score: Double)

    private fun scoreFinal(json: String?, isHindi: Boolean): Scored {
        if (json.isNullOrBlank()) return Scored("", -1.0)
        return try {
            val obj = JSONObject(json)
            val raw = obj.optString("text").trim()
            if (raw.isBlank()) return Scored("", -1.0)
            val words = obj.optJSONArray("result")
            var conf = 0.5
            if (words != null && words.length() > 0) {
                var sum = 0.0
                for (i in 0 until words.length()) sum += words.optJSONObject(i)?.optDouble("conf", 0.5) ?: 0.5
                conf = sum / words.length()
            }
            val display = if (isHindi) HinglishTransliterator.transliterate(raw) else raw
            val tokens = display.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val common = if (isHindi) HinglishTransliterator.commonWords else commonEn
            val ratio = if (tokens.isEmpty()) 0.0 else tokens.count { it in common }.toDouble() / tokens.size
            Scored(display, conf + ratio * 0.15)
        } catch (e: Exception) { Scored("", -1.0) }
    }

    /** Pick the winning final result; a null json means that recognizer did not endpoint. */
    fun pickFinal(enJson: String?, hiJson: String?): String {
        val en = scoreFinal(enJson, isHindi = false)
        val hi = scoreFinal(hiJson, isHindi = true)
        return if (hi.score > en.score) hi.text else en.text
    }

    private fun partialText(json: String?): String = try {
        JSONObject(json ?: "{}").optString("partial").trim()
    } catch (e: Exception) { "" }

    private fun scorePartial(display: String, isHindi: Boolean): Double {
        if (display.isBlank()) return -1.0
        val tokens = display.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return -1.0
        val common = if (isHindi) HinglishTransliterator.commonWords else commonEn
        val ratio = tokens.count { it in common }.toDouble() / tokens.size
        return ratio + minOf(tokens.size, 4) * 0.02
    }

    /** Live partial: pick by common-word ratio; ties go to the longer text. */
    fun pickPartial(enJson: String?, hiJson: String?): String {
        val en = partialText(enJson)
        val hiRaw = partialText(hiJson)
        val hi = if (hiRaw.isBlank()) "" else HinglishTransliterator.transliterate(hiRaw)
        val enScore = scorePartial(en, isHindi = false)
        val hiScore = scorePartial(hi, isHindi = true)
        return when {
            hiScore > enScore -> hi
            enScore > hiScore -> en
            hi.length > en.length -> hi
            else -> en
        }
    }
}

/**
 * One voice session feeding the same microphone audio to the English
 * and Hindi recognizers in parallel. Segments are committed through
 * [onSegment] as endpointing fires; live guesses go through
 * [onPartial]. [stopAndGetFinal] returns the winning tail segment.
 */
class DualVoiceSession(
    private val enModel: org.vosk.Model,
    private val hiModel: org.vosk.Model,
    private val onPartial: (String) -> Unit,
    private val onSegment: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var enRecognizer: Recognizer? = null
    private var hiRecognizer: Recognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            var recorder: android.media.AudioRecord? = null
            try {
                val rate = 16000
                val minBuf = android.media.AudioRecord.getMinBufferSize(
                    rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                @Suppress("MissingPermission")
                val rec = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 8192)
                )
                recorder = rec
                val enRec = Recognizer(enModel, rate.toFloat()).apply { setWords(true) }
                val hiRec = Recognizer(hiModel, rate.toFloat()).apply { setWords(true) }
                enRecognizer = enRec
                hiRecognizer = hiRec
                rec.startRecording()
                val buffer = ByteArray(4096)
                while (running) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val enFinal = enRec.acceptWaveForm(buffer, read)
                    val hiFinal = hiRec.acceptWaveForm(buffer, read)
                    if (enFinal || hiFinal) {
                        val picked = VoiceAutoDetect.pickFinal(
                            if (enFinal) enRec.result else null,
                            if (hiFinal) hiRec.result else null
                        )
                        if (picked.isNotBlank()) mainHandler.post { onSegment(picked) }
                    } else {
                        val partial = VoiceAutoDetect.pickPartial(enRec.partialResult, hiRec.partialResult)
                        if (partial.isNotBlank()) mainHandler.post { onPartial(partial) }
                    }
                }
                try { rec.stop() } catch (e: Exception) { /* already stopped */ }
                rec.release()
            } catch (e: Exception) {
                try { recorder?.release() } catch (ignored: Exception) { /* noop */ }
                mainHandler.post { onError(e.message ?: "offline recognizer error") }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopAndGetFinal(): String {
        if (thread == null) return ""
        running = false
        try { thread?.join(900) } catch (e: Exception) { /* interrupt */ }
        thread = null
        val enRec = enRecognizer
        val hiRec = hiRecognizer
        enRecognizer = null
        hiRecognizer = null
        var picked = ""
        try {
            if (enRec != null && hiRec != null) {
                picked = VoiceAutoDetect.pickFinal(enRec.finalResult, hiRec.finalResult)
            }
        } catch (e: Exception) { /* no final available */ }
        try { enRec?.close() } catch (e: Exception) { /* noop */ }
        try { hiRec?.close() } catch (e: Exception) { /* noop */ }
        return picked
    }
}
