package com.moozaq.app

import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.*
import kotlinx.serialization.json.*

/** A bounded receiver GET_STATUS only. No media channel, LAUNCH, LOAD or playback commands.
 * Used before the SDK route fallback so reopening cannot replace another receiver app.
 */
internal object PassiveReceiverProbe {
    fun defaultReceiverRunning(address:InetAddress,port:Int):Boolean {
        require(address.isSiteLocalAddress || address.isLinkLocalAddress)
        require(port in 1..65535)
        // Cast devices use self-signed local certificates. Scope this trust to the
        // discovered LAN socket only; this probe sends no credentials or media URLs.
        val ssl=SSLContext.getInstance("TLS").apply {init(null,arrayOf<TrustManager>(object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
        }),null)}
        (ssl.socketFactory.createSocket() as SSLSocket).use {socket->
            socket.soTimeout=2500
            socket.connect(InetSocketAddress(address,port),2500)
            socket.startHandshake()
            val input=DataInputStream(socket.inputStream)
            val output=DataOutputStream(socket.outputStream)
            fun send(namespace:String,payload:String) {
                val bytes=CastStatusWire.encode(namespace,payload)
                output.writeInt(bytes.size);output.write(bytes);output.flush()
            }
            send("urn:x-cast:com.google.cast.tp.connection","""{"type":"CONNECT","origin":{},"userAgent":"Moozaq passive recovery"}""")
            send("urn:x-cast:com.google.cast.receiver","""{"type":"GET_STATUS","requestId":1}""")
            val end=System.nanoTime()+5_000_000_000L
            repeat(20) {
                check(System.nanoTime()<end) {"Receiver status timed out"}
                val size=input.readInt();require(size in 1..131072)
                val bytes=ByteArray(size);input.readFully(bytes)
                val (namespace,payload)=CastStatusWire.decode(bytes)
                val json=runCatching {Json.parseToJsonElement(payload).jsonObject}.getOrNull() ?: return@repeat
                val type=json["type"]?.jsonPrimitive?.content
                if(namespace=="urn:x-cast:com.google.cast.tp.heartbeat" && type=="PING")
                    send(namespace,"""{"type":"PONG"}""")
                if(namespace=="urn:x-cast:com.google.cast.receiver" && type=="RECEIVER_STATUS") {
                    return hasDefaultReceiver(payload)
                }
            }
            error("Receiver did not report its application")
        }
    }
    internal fun hasDefaultReceiver(payload:String):Boolean {
        val json=Json.parseToJsonElement(payload).jsonObject
        val apps=json["status"]?.jsonObject?.get("applications")?.jsonArray ?: return false
        return apps.any {it.jsonObject["appId"]?.jsonPrimitive?.content=="CC1AD845"}
    }
}

/** Small CastMessage protobuf envelope; unknown fields are skipped, malformed frames rejected. */
internal object CastStatusWire {
    fun encode(namespace:String,payload:String,source:String="moozaq-status",destination:String="receiver-0"):ByteArray {
        val out=ByteArrayOutputStream()
        fun variable(value:Int) {var v=value;while(v>127) {out.write((v and 127) or 128);v=v ushr 7};out.write(v)}
        fun text(field:Int,value:String) {val b=value.toByteArray(Charsets.UTF_8);variable((field shl 3) or 2);variable(b.size);out.write(b)}
        out.write(8);out.write(0) // protocol_version CASTV2_1_0
        text(2,source);text(3,destination);text(4,namespace)
        out.write(40);out.write(0) // payload_type STRING
        text(6,payload)
        return out.toByteArray()
    }
    fun decode(bytes:ByteArray):Pair<String,String> {
        var pos=0;var namespace="";var payload=""
        fun variable():Int {
            var result=0
            for(shift in 0..28 step 7) {
                require(pos<bytes.size);val b=bytes[pos++].toInt() and 255
                result=result or ((b and 127) shl shift)
                if(b and 128==0) return result
            }
            error("Invalid Cast frame")
        }
        fun advance(n:Int) {require(n>=0 && n<=bytes.size-pos);pos+=n}
        while(pos<bytes.size) {
            val tag=variable();require(tag>0)
            when(tag and 7) {
                0->variable()
                1->advance(8)
                2->{val size=variable();val start=pos;advance(size)
                    if(tag ushr 3==4) namespace=bytes.copyOfRange(start,pos).toString(Charsets.UTF_8)
                    if(tag ushr 3==6) payload=bytes.copyOfRange(start,pos).toString(Charsets.UTF_8)
                }
                5->advance(4)
                else->error("Unsupported Cast field")
            }
        }
        return namespace to payload
    }
}
