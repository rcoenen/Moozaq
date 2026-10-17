package com.moozaq.app

import kotlinx.coroutines.withTimeoutOrNull

private class Completed<T>(val value:T)

/** A receiver timeout is a visible failure; caller cancellation still cancels normally. */
internal suspend fun <T> receiverDeadline(
    timeoutMs:Long, message:String, action:suspend ()->T
):T = (withTimeoutOrNull(timeoutMs) { Completed(action()) }
    ?: throw IllegalStateException(message)).value
