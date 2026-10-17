package com.moozaq.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A range-only request can succeed while an ordinary receiver fetch gets HTTP 403. */
internal suspend fun verifyDirectStream(url:String)=withContext(Dispatchers.IO) {
    val connection=URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout=15000;connection.readTimeout=15000
        // Deliberately no cookies, authorization, custom user agent or Range header.
        val status=connection.responseCode
        check(status in listOf(200,206)) {
            "The audio source rejected a direct stream request (HTTP $status). Playback was not changed."
        }
        connection.inputStream.use {check(it.read()>=0) {"The audio source returned no audio. Playback was not changed."}}
    } catch(e:IOException) {
        // IOException messages may contain signed URLs; keep those out of the UI/log.
        throw IllegalStateException("The direct audio stream could not be reached. Playback was not changed.")
    } finally {connection.disconnect()}
}
