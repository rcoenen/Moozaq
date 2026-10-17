package com.moozaq.app

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/** Bounded, private debug trail. Never records stream URLs, credentials or media payloads. */
internal class AndroidDiagnostics(context:Context) {
    private val file=File(context.filesDir,"cast-debug.log")
    private val writer=Executors.newSingleThreadExecutor()
    fun event(name:String,detail:String="") {
        val safe=detail.replace(Regex("https?://[^\\s\\\"<>]+"),"[URL omitted]").takeLast(5000)
        val line="${Instant.now()} $name $safe"
        Log.i("MoozaqCast",line)
        try { writer.execute {
            runCatching {
                if(file.length()>131072) {
                    val previous=File(file.parentFile,"cast-debug.previous.log")
                    previous.delete();file.renameTo(previous)
                }
                file.appendText(line+"\n")
            }
        } } catch(_:java.util.concurrent.RejectedExecutionException) { /* Late callback after shutdown. */ }
    }
    fun close() {writer.shutdown()}
}
