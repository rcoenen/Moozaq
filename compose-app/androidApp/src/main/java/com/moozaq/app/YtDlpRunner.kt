package com.moozaq.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Called from the Python bridge in place of `python -m yt_dlp`. Metadata only: the bridge
 * passes --skip-download or --dump-single-json, and stdout carries the JSON it asked for. */
internal class YtDlpRunner(context: Context, private val diagnostics: AndroidDiagnostics) {
    private val app = context.applicationContext
    private val lock = Any()
    private var initialized = false
    @Volatile private var current: String? = null
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    private fun initialize() = synchronized(lock) {
        if (!initialized) { BundledExtractor.install(app); YoutubeDL.init(app); initialized = true }
    }

    /** Runs yt-dlp with the bridge's arguments and returns stdout. Blocks the calling Python thread. */
    fun run(argumentsJson: String, timeoutMs: Int): String {
        initialize()
        val arguments = JSONArray(argumentsJson).let { a -> List(a.length()) { a.getString(it) } }
        val urls = arguments.filter { it.startsWith("https://") || it.startsWith("http://") }
        require(urls.isNotEmpty()) { "yt-dlp was called without a URL." }
        val request = YoutubeDLRequest(urls)
        val options = arguments.filterNot { it in urls }
        var index = 0
        while (index < options.size) {
            val option = options[index]
            val value = options.getOrNull(index + 1)?.takeUnless { it.startsWith("-") }
            if (value == null) { request.addOption(option); index += 1 } else { request.addOption(option, value); index += 2 }
        }
        val process = UUID.randomUUID().toString()
        current = process
        // A blocked native subprocess ignores thread interruption; destroy it at the deadline.
        val deadline = watchdog.schedule({ YoutubeDL.destroyProcessById(process) }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            return YoutubeDL.execute(request, process).out
        } catch (e: Exception) {
            diagnostics.event("resolver.failed", "${e.javaClass.simpleName}: ${e.message}")
            throw IllegalStateException("YouTube could not resolve this source on the phone. Try again later.")
        } finally {
            deadline.cancel(false)
            if (current == process) current = null
        }
    }

    fun cancel() { current?.let { YoutubeDL.destroyProcessById(it) } }

    fun close() { cancel(); watchdog.shutdownNow() }
}
