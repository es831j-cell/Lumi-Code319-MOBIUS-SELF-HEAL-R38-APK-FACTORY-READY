package com.distressedelk.lumi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Code285: main-process proxy for the Fast Brain.
 * Native llama inference now lives in FastBrainProcessService (:fastbrain process),
 * so a native crash can kill only the inference worker and not Lumi's foreground UI.
 */
object LocalBrain {
    interface Callback {
        fun onReply(text: String, tokensPerSecond: Double)
        fun onError(message: String)
    }

    private const val ACTION_WARM = "com.distressedelk.lumi.fastbrain.WARM"
    private const val ACTION_ASK = "com.distressedelk.lumi.fastbrain.ASK"
    private const val ACTION_PROBE = "com.distressedelk.lumi.fastbrain.PROBE"
    private const val EXTRA_RECEIVER = "receiver"
    private const val RESULT_OK = 1
    private const val RESULT_ERROR = 2
    private const val RESULT_WARM = 3
    private const val REQUEST_TIMEOUT_MS = 4500L
    private const val COLD_START_TIMEOUT_MS = 65000L

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false
    @Volatile private var inFlight = false
    @Volatile private var lastRequestStartedAt = 0L
    private val rejectedRequests = AtomicLong(0L)
    private val main = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<Long, Boolean>()
    private val serial = AtomicLong(0L)

    @JvmStatic fun initialize(context: Context) { appContext = context.applicationContext }
    @JvmStatic fun isLoaded(): Boolean = loaded
    @JvmStatic fun isBusy(): Boolean = inFlight
    @JvmStatic fun lastRequestAgeMs(): Long = if (!inFlight || lastRequestStartedAt <= 0L) 0L else System.currentTimeMillis() - lastRequestStartedAt
    @JvmStatic fun rejectedRequestCount(): Long = rejectedRequests.get()

    @JvmStatic fun warm(modelPath: String, contextSize: Int, threads: Int) {
        val ctx = appContext ?: return
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RESULT_WARM || resultCode == RESULT_OK) loaded = true
                if (resultCode == RESULT_ERROR) loaded = false
            }
        }
        start(ctx, ACTION_WARM, modelPath, contextSize, threads, null, null, 0, true, receiver)
    }

    @JvmStatic fun probe(modelPath: String, contextSize: Int, threads: Int, callback: Callback) {
        dispatch(ACTION_PROBE, modelPath, contextSize, threads, "", "", 128, true, callback)
    }

    @JvmStatic fun askRaw(modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, callback: Callback) {
        dispatch(ACTION_ASK, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, false, callback)
    }

    @JvmStatic fun ask(modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, callback: Callback) {
        dispatch(ACTION_ASK, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, true, callback)
    }

    private fun dispatch(action: String, modelPath: String, contextSize: Int, threads: Int, prompt: String, systemPrompt: String, maxTokens: Int, sanitize: Boolean, callback: Callback) {
        val ctx = appContext
        if (ctx == null) {
            rejectedRequests.incrementAndGet()
            callback.onError("Fast Brain worker is not initialized")
            return
        }
        val id = serial.incrementAndGet()
        active[id] = true
        inFlight = true
        lastRequestStartedAt = System.currentTimeMillis()
        val receiver = object : ResultReceiver(main) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (active.remove(id) == null) return
                inFlight = active.isNotEmpty()
                when (resultCode) {
                    RESULT_OK -> {
                        loaded = true
                        callback.onReply(resultData?.getString("text").orEmpty(), resultData?.getDouble("tps", 0.0) ?: 0.0)
                    }
                    else -> {
                        loaded = false
                        callback.onError(resultData?.getString("error") ?: "Fast Brain worker failed")
                    }
                }
            }
        }
        try {
            start(ctx, action, modelPath, contextSize, threads, prompt, systemPrompt, maxTokens, sanitize, receiver)
        } catch (t: Throwable) {
            active.remove(id); inFlight = active.isNotEmpty(); loaded = false
            callback.onError(t.message ?: t.javaClass.simpleName)
            return
        }
        val timeoutMs = if (action == ACTION_PROBE || !loaded) COLD_START_TIMEOUT_MS else REQUEST_TIMEOUT_MS
        main.postDelayed({
            if (active.remove(id) != null) {
                inFlight = active.isNotEmpty(); loaded = false
                callback.onError("Fast Brain worker did not answer within ${timeoutMs}ms; Lumi stayed open and routed around it")
            }
        }, timeoutMs)
    }

    private fun start(ctx: Context, action: String, modelPath: String, contextSize: Int, threads: Int, prompt: String?, systemPrompt: String?, maxTokens: Int, sanitize: Boolean, receiver: ResultReceiver) {
        val i = Intent(ctx, FastBrainProcessService::class.java).setAction(action)
            .putExtra("modelPath", modelPath)
            .putExtra("contextSize", contextSize)
            .putExtra("threads", threads)
            .putExtra("prompt", prompt)
            .putExtra("systemPrompt", systemPrompt)
            .putExtra("maxTokens", maxTokens)
            .putExtra("sanitize", sanitize)
            .putExtra(EXTRA_RECEIVER, receiver)
        ctx.startService(i)
    }

    @JvmStatic fun sanitizeVisibleReply(raw: String?): String {
        if (raw == null) return ""
        var out = raw.replace("\u0000", "").trim()
        out = out.replace(Regex("<think\\b[^>]*>.*?</think\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        val close = out.lowercase().lastIndexOf("</think>")
        if (close >= 0) out = out.substring(close + 8).trim()
        out = out.replace(Regex("<think\\b[^>]*>.*$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "").trim()
        out = out.replace(Regex("\\s*/(?:no_?think|no_?talent|think)\\b", RegexOption.IGNORE_CASE), "").trim()
        return out
    }
}
