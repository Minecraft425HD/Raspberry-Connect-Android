package com.raspberryconnect.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `delay doubles with each attempt`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1000, maxDelayMillis = 60_000)
        assertEquals(1000L, policy.delayForAttempt(1))
        assertEquals(2000L, policy.delayForAttempt(2))
        assertEquals(4000L, policy.delayForAttempt(3))
        assertEquals(8000L, policy.delayForAttempt(4))
    }

    @Test
    fun `delay is capped at maxDelayMillis`() {
        val policy = ReconnectPolicy(baseDelayMillis = 1000, maxDelayMillis = 5000)
        assertEquals(5000L, policy.delayForAttempt(10))
    }

    @Test
    fun `shouldRetry respects maxAttempts`() {
        val policy = ReconnectPolicy(maxAttempts = 3)
        assertTrue(policy.shouldRetry(1))
        assertTrue(policy.shouldRetry(3))
        assertFalse(policy.shouldRetry(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempt below 1 is rejected`() {
        ReconnectPolicy().delayForAttempt(0)
    }
}
