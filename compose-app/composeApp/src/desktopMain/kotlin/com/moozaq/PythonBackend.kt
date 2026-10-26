package com.moozaq

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun audioHelper(root: File): File {
    val configured = System.getenv("MOOZAQ_MAC_AUDIO_PLAYER")
    if (!configured.isNullOrBlank()) return File(configured)
    val runtimeContents = File(System.getProperty("java.home")).toPath().parent?.parent?.parent
    val bundled = runtimeContents?.resolve("Resources/MoozaqAudioPlayer")?.toFile()
    return if (bundled?.isFile == true && bundled.canExecute()) bundled
    else File(root, "compose-app/composeApp/build/macos/MoozaqAudioPlayer")
}

class PythonBackend(root: File) : BridgeBackend() {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val counter = AtomicLong()
    private val process = ProcessBuilder(
        File(root, "mac-spike/.venv/bin/python").absolutePath, "-u", File(root, "compose-app/bridge/server.py").absolutePath
    ).directory(root).redirectError(ProcessBuilder.Redirect.appendTo(File(root, "mac-spike/data/compose-engine.log"))).apply {
        environment()["MOOZAQ_MAC_AUDIO_PLAYER"] = audioHelper(root).absolutePath
    }.start()
    private val writer = process.outputStream.bufferedWriter()
    init {
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    val obj = json.parseToJsonElement(line).jsonObject
                    val id = obj["id"]?.jsonPrimitive?.longOrNull
                    val deferred = pending.remove(id) ?: return@forEach
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    if (error != null) deferred.completeExceptionally(IllegalStateException(error))
                    else deferred.complete(obj["result"] ?: JsonNull)
                } }
            } catch (_: Exception) { }
            finally { pending.values.forEach { it.completeExceptionally(IllegalStateException("Local playback engine disconnected. Reopen Moozaq.")) }; pending.clear() }
        }, "moozaq-engine-reader").apply { isDaemon = true; start() }
    }
    override suspend fun request(op: String, fields: Map<String, JsonElement>): JsonElement {
        val id = counter.incrementAndGet()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            withContext(Dispatchers.IO) {
                synchronized(writer) {
                    writer.write(buildJsonObject { put("id", id); put("op", op); fields.forEach { (k,v) -> put(k,v) } }.toString())
                    writer.newLine(); writer.flush()
                }
            }
            return withTimeout(10000) { result.await() }
        } finally { pending.remove(id) }
    }
    override fun close() {
        runCatching {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        runCatching { synchronized(writer) { writer.close() } } }
}
