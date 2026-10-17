package com.moozaq.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.security.MessageDigest

/** Pin the resolver with the APK; never download executable updates at runtime. */
internal object BundledExtractor {
    const val VERSION="2026.08.19"
    private const val SHA256="1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
    private fun digest(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input ->
            val buffer=ByteArray(16384)
            while(true) {val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count)}
        }
        return hash.digest().joinToString("") {"%02x".format(it)}
    }
    @Synchronized fun install(context:Context) {
        val base=File(context.noBackupFilesDir,YoutubeDL.baseName)
        val folder=File(base,YoutubeDL.ytdlpDirName)
        val binary=File(folder,YoutubeDL.ytdlpBin)
        if(binary.isFile && digest(binary)==SHA256) return
        check(folder.isDirectory || folder.mkdirs()) {"Could not prepare the source resolver."}
        val pending=File(folder,"yt-dlp.pending")
        try {
            context.resources.openRawResource(R.raw.ytdlp).use {input ->pending.outputStream().use {input.copyTo(it)}}
            check(digest(pending)==SHA256) {"The bundled source resolver failed verification."}
            check(pending.renameTo(binary)) {"Could not install the bundled source resolver."}
        } finally {pending.delete()}
    }
}
