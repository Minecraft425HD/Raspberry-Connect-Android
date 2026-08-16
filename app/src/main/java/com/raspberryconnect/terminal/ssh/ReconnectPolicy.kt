package com.raspberryconnect.terminal.ssh

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff with a cap, used by the connection service to retry after an
 * unexpected disconnect (e.g. the Pi went to sleep, mobile network handover, the
 * browser-tab-in-background style drop this app exists to avoid).
 */
class ReconnectPolicy(
    private val baseDelayMillis: Long = 1_000L,
    private val maxDelayMillis: Long = 30_000L,
    private val maxAttempts: Int = Int.MAX_VALUE
) {
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        val exponential = baseDelayMillis * 2.0.pow(attempt - 1).toLong()
        return min(exponential, maxDelayMillis)
    }

    fun shouldRetry(attempt: Int): Boolean = attempt <= maxAttempts
}
