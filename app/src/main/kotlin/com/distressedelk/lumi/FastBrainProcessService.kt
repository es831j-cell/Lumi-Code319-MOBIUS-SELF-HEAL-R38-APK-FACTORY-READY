package com.distressedelk.lumi

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Code285 native inference boundary. Declared with android:process=":fastbrain" so
 * llama.cpp/native faults cannot take Lumi's foreground Activity down with them.
 */
class FastBrainProcessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(capacity = 4)
    @Volatile private var workerStarted = false
    @Volatile private var activePath: String? = null

    private data class Command(
        val action: String,
        val path: String,
        val contextSize: Int,
        val threads: Int,
        val prompt: String,
        val system: String,
        val maxTokens: Int,
        val sanitize: Boolean,
        val receiver: ResultReceiver
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val rr = receiver(intent) ?: return START_NOT_STICKY
        val cmd = Command(
            intent.action.orEmpty(),
            intent.getStringExtra("modelPath").orEmpty(),
            intent.getIntExtra("contextSize", 512),
            intent.getIntExtra("threads", 3),
            intent.getStringExtra("prompt").orEmpty(),
            intent.getStringExtra("systemPrompt").orEmpty(),
            intent.getIntExtra("maxTokens", 64),
            intent.getBooleanExtra("sanitize", true),
            rr
        )
        ensureWorker(cmd.path, cmd.contextSize, cmd.threads)
        if (commands.trySend(cmd).isFailure) sendError(rr, "Fast Brain worker queue is full")
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun receiver(intent: Intent): ResultReceiver? = intent.getParcelableExtra("receiver")

    @Synchronized
    private fun ensureWorker(path: String, contextSize: Int, threads: Int) {
        if (workerStarted && activePath == path) return
        if (workerStarted) return
        workerStarted = true
        activePath = path
        scope.launch {
            try {
                val model = Llama.loadModel(path, LlamaConfig(contextSize = contextSize, threads = threads))
                for (cmd in commands) {
                    try {
                        when (cmd.action) {
                            "com.distressedelk.lumi.fastbrain.WARM" -> cmd.receiver.send(3, Bundle().apply { putString("status", "loaded") })
                            "com.distressedelk.lumi.fastbrain.ASK" -> {
                                val result = Llama.complete(model, cmd.prompt, cmd.system, cmd.maxTokens)
                                val text = if (cmd.sanitize) LocalBrain.sanitizeVisibleReply(result.text) else result.text?.replace("\u0000", "")?.trim().orEmpty()
                                cmd.receiver.send(1, Bundle().apply { putString("text", text); putDouble("tps", result.tokensPerSecond.toDouble()) })
                            }
                            "com.distressedelk.lumi.fastbrain.PROBE" -> {
                                val system = "You are Lumi's local Fast Brain health check. Do not think aloud and do not emit <think> tags. Follow the instruction exactly. Output only the requested token and nothing else."
                                var result = Llama.complete(model, "User: Health probe. Reply with exactly READY. /no_think\nLumi:", system, 96)
                                var visible = LocalBrain.sanitizeVisibleReply(result.text)
                                var normalized = visible.lowercase().replace(Regex("[^a-z]"), "")
                                if (normalized != "ready") {
                                    result = Llama.complete(model, "Output exactly READY and stop. No explanation. /no_think", system, 128)
                                    visible = LocalBrain.sanitizeVisibleReply(result.text)
                                }
                                cmd.receiver.send(1, Bundle().apply { putString("text", visible); putDouble("tps", result.tokensPerSecond.toDouble()) })
                            }
                            else -> sendError(cmd.receiver, "Unknown Fast Brain action")
                        }
                    } catch (t: Throwable) {
                        sendError(cmd.receiver, t.message ?: t.javaClass.simpleName)
                    }
                }
                Llama.releaseModel(model)
            } catch (t: Throwable) {
                while (true) {
                    val pending = commands.tryReceive().getOrNull() ?: break
                    sendError(pending.receiver, t.message ?: t.javaClass.simpleName)
                }
            } finally {
                workerStarted = false
                activePath = null
            }
        }
    }

    private fun sendError(rr: ResultReceiver, message: String) {
        try { rr.send(2, Bundle().apply { putString("error", message) }) } catch (_: Throwable) {}
    }
}
