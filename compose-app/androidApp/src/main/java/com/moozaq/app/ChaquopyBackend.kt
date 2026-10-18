package com.moozaq.app

import android.content.Context
import android.net.wifi.WifiManager
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.moozaq.BridgeBackend
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** The Mac's Python Cast bridge, running in this process. Same requests and replies as
 * PythonBackend on the Mac; only the transport differs (direct calls instead of stdio). */
class ChaquopyBackend(context: Context) : BridgeBackend() {
    private val app = context.applicationContext
    private val diagnostics = AndroidDiagnostics(app)
    private val runner = YtDlpRunner(app, diagnostics)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counter = AtomicLong()
    private val calls = Executors.newSingleThreadExecutor { Thread(it, "moozaq-bridge-requests").apply { isDaemon = true } }
    // zeroconf needs multicast; Android drops it without a lock. Held only while visible.
    private val multicast = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("moozaq-cast-discovery").apply { setReferenceCounted(false) }
    private val bridge: Deferred<PyObject> = scope.async { start() }

    private fun start(): PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        // Chaquopy exports PYTHONPATH for its own interpreter, which reads it only at startup.
        // The bundled yt-dlp runs a different Python in a child process; inheriting this path
        // makes it import Chaquopy's 3.13 standard library and fail ("bad magic number").
        android.system.Os.unsetenv("PYTHONPATH")
        val dir = installBridge()
        diagnostics.event("bridge.starting", "python=${Python.getInstance().getModule("sys")["version"]}")
        val module = Python.getInstance().getModule("android_bridge")
        try {
            module.callAttr("start", dir.absolutePath, runner)
        } catch (e: Exception) {
            diagnostics.event("bridge.failed", e.message.orEmpty())
            throw IllegalStateException("Local playback engine could not start. Reopen Moozaq.", e)
        }
        diagnostics.event("bridge.started")
        return module
    }

    /** Copies the bundled bridge sources to real files once per APK install. data/ is kept. */
    private fun installBridge(): File {
        val dir = File(app.filesDir, "bridge")
        val stamp = File(dir, ".installed")
        val installed = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        if (!stamp.isFile || stamp.readText() != installed) {
            dir.mkdirs()
            app.assets.list("bridge").orEmpty().forEach { name ->
                app.assets.open("bridge/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            stamp.writeText(installed)
        }
        val catalog = File(dir, "data/channel-catalog.json")
        if (!catalog.isFile) {
            catalog.parentFile?.mkdirs()
            app.assets.open("catalog.json").use { input -> catalog.outputStream().use { input.copyTo(it) } }
        }
        return dir
    }

    override suspend fun request(op: String, fields: Map<String, JsonElement>): JsonElement {
        val module = bridge.await()
        val id = counter.incrementAndGet()
        val line = buildJsonObject { put("id", id); put("op", op); fields.forEach { (k, v) -> put(k, v) } }.toString()
        // Like the Mac's stdio loop: one request at a time, and a caller that gives up after 10 s
        // is released even if the bridge is still busy. A Python call cannot be interrupted.
        val result = CompletableDeferred<String>()
        calls.execute {
            if (result.isCompleted) return@execute
            try { result.complete(module.callAttr("request", line).toString()) } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        val reply = try { withTimeout(10_000) { result.await() } } catch (e: TimeoutCancellationException) {
            result.cancel()
            diagnostics.event("bridge.timeout", op)
            throw IllegalStateException("Local playback engine did not answer. Try again.")
        }
        val obj = json.parseToJsonElement(reply).jsonObject
        obj["error"]?.jsonPrimitive?.contentOrNull?.let { throw IllegalStateException(it) }
        return obj["result"] ?: JsonNull
    }

    fun foreground(active: Boolean) {
        if (active) multicast.acquire() else if (multicast.isHeld) multicast.release()
    }

    override fun close() {
        scope.launch {
            runCatching { bridge.await().callAttr("close") }
            runner.close()
            calls.shutdown()
            diagnostics.close()
            scope.cancel()
        }
        if (multicast.isHeld) multicast.release()
    }
}
