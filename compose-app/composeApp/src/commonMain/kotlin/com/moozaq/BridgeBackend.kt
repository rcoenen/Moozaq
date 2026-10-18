package com.moozaq

import kotlinx.serialization.json.*

/** The Python bridge protocol (compose-app/bridge/server.py), independent of how requests reach it.
 * The Mac sends them over the bridge process's stdio; Android calls the embedded interpreter. */
abstract class BridgeBackend : PlayerBackend {
    protected val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    /** Sends one bridge request and returns its `result`, or throws with the bridge's `error`. */
    protected abstract suspend fun request(op: String, fields: Map<String, JsonElement> = emptyMap()): JsonElement
    override suspend fun snapshot() = json.decodeFromJsonElement<Snapshot>(request("state"))
    override suspend fun selectDevice(id: String) { request("select", mapOf("device" to JsonPrimitive(id))) }
    override suspend fun play(source: String) { request("play", mapOf("source" to JsonPrimitive(source))) }
    override suspend fun command(name: String, value: Double?) { request(name, value?.let { mapOf("value" to JsonPrimitive(it)) } ?: emptyMap()) }
    override suspend fun outputVolume(id: String, value: Double) { request("output_volume", mapOf("device" to JsonPrimitive(id), "value" to JsonPrimitive(value))) }
    override suspend fun memberVolume(id: String, value: Double) { request("member_volume", mapOf("member" to JsonPrimitive(id), "value" to JsonPrimitive(value))) }
    override suspend fun setPlaybackDevices(devices: List<String>, token: String) { request("set_playback_devices", mapOf("devices" to JsonArray(devices.map(::JsonPrimitive)), "token" to JsonPrimitive(token))) }
    override suspend fun dismissMembership() { request("dismiss_membership") }
    override suspend fun catalog() = json.decodeFromJsonElement<CatalogData>(request("catalog"))
    override suspend fun importChannel(url: String?) { request("import_channel", url?.let { mapOf("url" to JsonPrimitive(it)) } ?: emptyMap()) }
}
