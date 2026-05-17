package com.yourapp.ai  // ← deinen Package-Namen anpassen

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Singleton that manages the on-device LLM via MediaPipe.
 * Call initialize() once (e.g. in Application.onCreate or on first use).
 */
class LlmManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: LlmManager? = null

        fun get(context: Context): LlmManager =
            instance ?: synchronized(this) {
                instance ?: LlmManager(context.applicationContext).also { instance = it }
            }

        // Gemma 2B int4 GPU – ~1.5 GB, good quality, fast on modern Android GPUs
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-gpu-int4/float16/1/gemma-2b-it-gpu-int4.bin"
        const val MODEL_FILE = "gemma-2b-it-gpu-int4.bin"
    }

    private var llm: LlmInference? = null

    var state: State = State.NOT_LOADED
        private set

    enum class State { NOT_LOADED, DOWNLOADING, LOADING, READY, ERROR }

    val modelFile: File get() = File(context.filesDir, MODEL_FILE)

    val isDownloaded: Boolean
        get() = modelFile.exists() && modelFile.length() > 500_000_000L

    // ── Download ─────────────────────────────────────────────────────────────

    suspend fun downloadModel(onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            state = State.DOWNLOADING
            try {
                val conn = URL(MODEL_URL).openConnection()
                conn.connect()
                val total = conn.contentLengthLong
                val tmp   = File(context.filesDir, "$MODEL_FILE.tmp")
                conn.getInputStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf   = ByteArray(8192)
                        var read  = 0L
                        var bytes: Int
                        while (input.read(buf).also { bytes = it } != -1) {
                            output.write(buf, 0, bytes)
                            read += bytes
                            if (total > 0) onProgress((read * 100 / total).toInt())
                        }
                    }
                }
                tmp.renameTo(modelFile)
                Result.success(Unit)
            } catch (e: Exception) {
                state = State.ERROR
                Result.failure(e)
            }
        }

    // ── Initialize ───────────────────────────────────────────────────────────

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        state = State.LOADING
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(800)
                .setTopK(40)
                .setTemperature(0.75f)
                .setRandomSeed(42)
                .build()
            llm   = LlmInference.createFromOptions(context, options)
            state = State.READY
            Result.success(Unit)
        } catch (e: Exception) {
            state = State.ERROR
            Result.failure(e)
        }
    }

    // ── Generate ─────────────────────────────────────────────────────────────

    fun generateAsync(
        prompt:  String,
        onToken: (token: String, done: Boolean) -> Unit
    ) {
        requireReady()
        llm!!.generateResponseAsync(
            prompt,
            object : LlmInference.LlmInferenceResultListener {
                override fun onResult(partial: String?, done: Boolean) {
                    onToken(partial ?: "", done)
                }
                override fun onError(e: Exception) {
                    onToken("❌ Fehler: ${e.message}", true)
                }
            }
        )
    }

    private fun requireReady() {
        check(state == State.READY) { "LLM not ready (state=$state)" }
    }

    fun close() {
        llm?.close()
        llm   = null
        state = State.NOT_LOADED
    }
}
