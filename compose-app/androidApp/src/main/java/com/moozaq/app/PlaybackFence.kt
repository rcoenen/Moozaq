package com.moozaq.app

/** Main-thread generation fence: cancelling preparation must make a late LOAD impossible. */
internal class PlaybackFence {
    private var generation=0L
    fun next():Long = ++generation
    fun isCurrent(ticket:Long)=ticket==generation
}

/** Same content-bound identity format as the Mac engine; titles alone never identify a stream. */
internal fun contentHash(content:String)=java.security.MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
internal fun verifiedSource(content:String?,source:String?,hash:String?):String? =
    source?.takeIf {content!=null && hash==contentHash(content) && Regex("https://www\\.youtube\\.com/watch\\?v=[\\w-]{11}").matches(it)}
