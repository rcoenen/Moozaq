package com.moozaq.app

/** Every attach/detach invalidates callbacks, even when the SDK reuses a client. */
internal class ConnectionEpoch {
    var value=0L; private set
    fun advance():Long=++value
    fun accepts(ticket:Long)=ticket==value
}

/** At most one outstanding status poll; lost replies expire without accepting late ACKs. */
internal class StatusRequestGate(private val timeoutMs:Long=6000) {
    private var sequence=0L
    private var pending:Long?=null
    private var startedAt=0L
    fun begin(now:Long):Long? {
        if(pending!=null && now-startedAt<timeoutMs) return null
        return (++sequence).also {pending=it;startedAt=now}
    }
    fun complete(ticket:Long,now:Long):Boolean {
        if(pending!=ticket) return false
        pending=null
        return now-startedAt<timeoutMs
    }
    fun clear() {pending=null;sequence++}
}

/** A transport acknowledgement cannot confirm playback of a replacement mix. */
internal fun requireSameMedia(expected:String?,actual:String?) {
    check(actual==null || actual==expected) {"The mix changed on the speaker. Read its current playback before trying again."}
}
internal fun seekConfirmed(position:Double,requested:Double,wasPaused:Boolean,playing:Boolean,paused:Boolean)=
    kotlin.math.abs(position-requested)<3 && if(wasPaused) paused else playing

internal data class CastSessionActivity(
    val connected:Boolean=false,
    val connecting:Boolean=false,
    val resuming:Boolean=false,
    val suspended:Boolean=false,
) {
    fun blocksFallbackSelection()=connected || connecting || resuming || suspended
}
