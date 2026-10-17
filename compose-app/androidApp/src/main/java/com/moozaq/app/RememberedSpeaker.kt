package com.moozaq.app

import android.content.Context
import com.moozaq.Device

/** Identity only. Never persist a playback command, position, volume or signed URL. */
internal data class RememberedSpeaker(val deviceId:String, val routeId:String, val name:String, val kind:String, val model:String) {
    fun placeholder()=Device(routeId,name,model,kind,available=false)
}
internal class SpeakerPreferences(context:Context) {
    private val prefs=context.getSharedPreferences("cast-destination",Context.MODE_PRIVATE)
    fun read():RememberedSpeaker? {
        val id=prefs.getString("device",null) ?: return null
        val route=prefs.getString("route",null) ?: return null
        return RememberedSpeaker(id,route,prefs.getString("name","")!!,prefs.getString("kind","audio")!!,prefs.getString("model","")!!)
    }
    fun write(s:RememberedSpeaker) {
        prefs.edit().putString("device",s.deviceId).putString("route",s.routeId).putString("name",s.name)
            .putString("kind",s.kind).putString("model",s.model).apply()
    }
}

/** Back off read-only recovery, and invalidate an in-flight lookup on a new user selection. */
internal class RejoinPolicy {
    var generation=0L; private set
    private var attempts=0
    private var nextAt=0L
    fun replace() {generation++;attempts=0;nextAt=0}
    fun ready(now:Long)=now>=nextAt
    fun attempted(now:Long) {nextAt=now+minOf(30000L,5000L shl minOf(attempts++,3))}
    fun confirmed() {attempts=0;nextAt=0}
    fun invalidate() {generation++}
    fun accepts(ticket:Long)=ticket==generation
}
