package com.moozaq.app

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DirectStreamProbeTest {
    @Test fun rejectsRangeOnlyStreamBeforeItCanBeLoaded()=runBlocking {
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        server.soTimeout=3000
        val responder=launch(Dispatchers.IO) {
            server.accept().use {socket ->
                socket.soTimeout=3000
                val reader=socket.getInputStream().bufferedReader()
                val headers=generateSequence {reader.readLine()}.takeWhile {it.isNotEmpty()}.toList()
                // Old Android source: ranged probes pass, ordinary receiver fetches fail.
                val status=if(headers.any {it.startsWith("Range:",ignoreCase=true)}) "206 Partial Content" else "403 Forbidden"
                socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray())
            }
        }
        try {
            val error=runCatching {verifyDirectStream("http://127.0.0.1:${server.localPort}/audio")}.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("HTTP 403"))
            responder.join()
        } finally {server.close();responder.cancelAndJoin()}
    }
}
