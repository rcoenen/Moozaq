package com.moozaq

import kotlinx.serialization.Serializable

@Serializable
data class ListeningAggregate(
    val qualified_seconds: Long = 0,
    val qualified_sessions: Int = 0,
    val last_listened_at: Long? = null,
)

@Serializable
data class PersonalProfile(
    val ratings: Map<String, Int> = emptyMap(),
    val listening: Map<String, ListeningAggregate> = emptyMap(),
)
