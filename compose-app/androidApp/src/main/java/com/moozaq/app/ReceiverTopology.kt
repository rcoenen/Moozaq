package com.moozaq.app

import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/** Passive local Nest metadata; never opens a Cast media channel or launches an application. */
internal object ReceiverTopology {
    data class Groups(val dynamic:Set<String>,val configured:Set<String>,val configuredKnown:Boolean)

    fun groups(address:InetAddress):Groups {
        require(address.isSiteLocalAddress || address.isLinkLocalAddress) {"Receiver must be on the local network."}
        // Nest's local setup endpoint has a self-signed certificate. This trust context is
        // scoped to this one discovered LAN IP, carries no credentials and never redirects.
        val ssl=SSLContext.getInstance("TLS").apply {init(null,arrayOf<TrustManager>(object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
        }),null)}
        val host=address.hostAddress!!.let {if(":" in it) "[$it]" else it}
        val connection=URL("https://$host:8443/setup/eureka_info?params=multizone").openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory=ssl.socketFactory
            connection.hostnameVerifier=javax.net.ssl.HostnameVerifier {_,_->true}
            connection.instanceFollowRedirects=false;connection.connectTimeout=1500;connection.readTimeout=1500
            check(connection.responseCode==200)
            val text=connection.inputStream.bufferedReader().use {reader->
                val result=StringBuilder();val buffer=CharArray(4096)
                while(true) {
                    val n=reader.read(buffer);if(n<0) break
                    check(result.length+n<=131072) {"Unexpectedly large receiver metadata."}
                    result.append(buffer,0,n)
                }
                result.toString()
            }
            val multizone=JSONObject(text).optJSONObject("multizone")
            fun arrayIds(name:String):Set<String>?=multizone?.optJSONArray(name)?.let {rows->
                (0 until rows.length()).mapNotNull {rows.optJSONObject(it)?.optString("uuid")}
                    .map {canonicalDeviceId(it)}.toSet()
            }
            val configured=arrayIds("configured_groups") ?: arrayIds("groups")
            return Groups(arrayIds("dynamic_groups").orEmpty(),configured.orEmpty(),configured!=null)
        } finally {connection.disconnect()}
    }
    fun canonicalDeviceId(id:String)=id.substringAfter("cast:").replace("-","").lowercase()
}
