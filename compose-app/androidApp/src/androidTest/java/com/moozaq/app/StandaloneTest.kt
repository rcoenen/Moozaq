package com.moozaq.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only hardware checks: no device selection, LOAD, transport or volume commands. */
@RunWith(AndroidJUnit4::class)
class StandaloneTest {
    @Test fun inspectReceiverSourceCompatibility()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val cm=context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val sources=OnDeviceSources(context)
        val data=sources.execute("https://www.youtube.com/watch?v=Xdho5SVxEVI",listOf(
            "--no-playlist" to null,"-f" to "bestaudio[ext=m4a][protocol=https]/bestaudio[protocol=https]"))
        fun field(key:String)=(data[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val url=field("url")!!
        val uri=android.net.Uri.parse(url)
        instrumentation.sendStatus(0,Bundle().apply {
            putString("sourceProbe","wifi=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)} vpn=${caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)} ipHash=${contentHash(uri.getQueryParameter("ip").orEmpty())} format=${field("format_id")} codec=${field("acodec")} client=${uri.getQueryParameter("c")} protocol=${field("protocol")}")
        })
        for(useRange in listOf(true,false)) {
            val origin="https://www.gstatic.com"
            val detail=withContext(Dispatchers.IO) {
                val c=java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout=15000;c.readTimeout=15000;if(useRange) c.setRequestProperty("Range","bytes=0-15")
                    c.setRequestProperty("User-Agent","Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 CrKey/1.56.500000")
                    origin?.let {c.setRequestProperty("Origin",it)}
                    val status=c.responseCode
                    assertTrue("Receiver-style HTTP request must succeed (range=$useRange, status=$status)", status in listOf(200,206))
                    val signature=if(status in listOf(200,206)) c.inputStream.use {input ->
                        val bytes=ByteArray(16);val count=input.read(bytes)
                        if(count>=8) bytes.copyOfRange(4,8).decodeToString() else "short"
                    } else "none"
                    "range=$useRange origin=${origin!=null} http=$status type=${c.contentType} cors=${c.getHeaderField("Access-Control-Allow-Origin")} signature=$signature"
                } finally {c.disconnect()}
            }
            instrumentation.sendStatus(0,Bundle().apply {putString("sourceProbe",detail)})
        }
    }
    @Test fun inspectFailedSourceNetworkBinding()=runBlocking {
        val sources=OnDeviceSources(InstrumentationRegistry.getInstrumentation().targetContext)
        for(forceIpv4 in listOf(false,true)) {
            val options=listOf("--no-playlist" to null,"-f" to "bestaudio[ext=m4a][protocol=https]/bestaudio[protocol=https]") +
                if(forceIpv4) listOf("--force-ipv4" to null) else emptyList()
            val data=sources.execute("https://www.youtube.com/watch?v=Xdho5SVxEVI",options)
            val url=(data["url"] as kotlinx.serialization.json.JsonPrimitive).content
            val ip=android.net.Uri.parse(url).getQueryParameter("ip").orEmpty()
            val family=if(':' in ip) "IPv6" else if('.' in ip) "IPv4" else "unspecified"
            val status=withContext(Dispatchers.IO) {
                val c=java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout=15000;c.readTimeout=15000;c.setRequestProperty("Range","bytes=0-15")
                    c.responseCode.also {if(it in listOf(200,206)) c.inputStream.use {input->assertTrue(input.read()>-1)}}
                } finally {c.disconnect()}
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("networkProbe","forceIpv4=$forceIpv4 signedAddressFamily=$family phoneHttp=$status")
            })
        }
    }
    @Test fun resolvesAudioOnPhoneWithoutMac()=runBlocking {
        val sources=OnDeviceSources(InstrumentationRegistry.getInstrumentation().targetContext)
        val library=sources.readCatalog()
        assertTrue(library.mixes.count {(it.duration?:0.0)>=3600}>100)
        val audio=sources.resolve("https://www.youtube.com/watch?v=7vy0H3ERRiA")
        assertTrue(audio.url.startsWith("https://"))
        assertTrue(audio.mime.startsWith("audio/"))
        assertTrue((audio.duration?:0.0)>=3600)
        assertTrue(audio.title.isNotBlank())
        verifyDirectStream(audio.url)
        withContext(Dispatchers.IO) {
            val connection=java.net.URL(audio.url).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout=15000;connection.readTimeout=15000
                connection.setRequestProperty("Range","bytes=0-1023")
                assertTrue(connection.responseCode in listOf(200,206))
                connection.inputStream.use {assertTrue(it.read(ByteArray(16))>0)}
            } finally {connection.disconnect()}
        }
        // Do not log the signed stream URL.
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
            putString("stream","On-device audio-only resolution succeeded: ${audio.mime}")
        })
    }
    @Test fun discoversSpeakersWithoutSelectingOrPlaying()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val backend=withContext(Dispatchers.Main) {AndroidBackend(context,automaticRecovery=false).also {it.foreground(true)}}
        try {
            val snapshot=withTimeout(45000) {
                var s=backend.snapshot()
                while(s.devices.isEmpty()) {delay(1000);s=backend.snapshot()}
                s
            }
            assertTrue(snapshot.devices.isNotEmpty())
            assertFalse(snapshot.busy)
            delay(4000) // Allow passive local topology classification to complete.
            val classified=backend.snapshot()
            assertTrue(classified.devices.any {it.name=="All speakers" && !it.temporary})
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("discovered",classified.devices.joinToString {"${it.name} (${it.cast_type}, temporary=${it.temporary})"})
            })
        } finally {withContext(Dispatchers.Main) {backend.close()}}
    }
}
