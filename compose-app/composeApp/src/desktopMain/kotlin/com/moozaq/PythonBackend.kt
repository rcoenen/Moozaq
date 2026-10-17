package com.moozaq

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class PythonBackend(root: File) : PlayerBackend {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val counter = AtomicLong()
    private val process = ProcessBuilder(
        File(root, "mac-spike/.venv/bin/python").absolutePath, "-u", File(root, "compose-app/bridge/server.py").absolutePath
    ).directory(root).redirectError(ProcessBuilder.Redirect.appendTo(File(root, "mac-spike/data/compose-engine.log"))).start()
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
    private suspend fun request(op: String, fields: Map<String, JsonElement> = emptyMap()): JsonElement {
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
    override suspend fun snapshot() = json.decodeFromJsonElement<Snapshot>(request("state"))
    override suspend fun selectDevice(id: String) { request("select", mapOf("device" to JsonPrimitive(id))) }
    override suspend fun play(source: String) { request("play", mapOf("source" to JsonPrimitive(source))) }
    override suspend fun command(name: String, value: Double?) { request(name, value?.let { mapOf("value" to JsonPrimitive(it)) } ?: emptyMap()) }
    override suspend fun outputVolume(id: String, value: Double) { request("output_volume", mapOf("device" to JsonPrimitive(id), "value" to JsonPrimitive(value))) }
    override suspend fun memberVolume(id: String, value: Double) { request("member_volume", mapOf("member" to JsonPrimitive(id), "value" to JsonPrimitive(value))) }
    override suspend fun setPlaybackDevices(devices: List<String>, token: String) { request("set_playback_devices", mapOf("devices" to JsonArray(devices.map(::JsonPrimitive)), "token" to JsonPrimitive(token))) }
    override suspend fun dismissMembership() { request("dismiss_membership") }
    override suspend fun catalog() = json.decodeFromJsonElement<CatalogData>(request("catalog"))
    override suspend fun importChannel(url: String?) { request("import_channel", url?.let { mapOf("url" to JsonPrimitive(it)) } ?: emptyMap()) }
    override fun close() { runCatching { synchronized(writer) { writer.close() } } }
}
