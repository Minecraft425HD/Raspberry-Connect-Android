package com.raspberryconnect.terminal.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHostKeyStore : HostKeyStore {
    private val trusted = mutableMapOf<String, String>()

    override fun getTrustedFingerprint(host: String, port: Int): String? = trusted["$host:$port"]

    override fun trust(host: String, port: Int, fingerprint: String) {
        trusted["$host:$port"] = fingerprint
    }

    override fun forget(host: String, port: Int) {
        trusted.remove("$host:$port")
    }
}

class HostKeyVerificationTest {

    @Test
    fun `first connection to a host is reported as first use`() {
        val store = FakeHostKeyStore()
        val decision = HostKeyVerification.evaluate("pi.local", 22, "AA:BB:CC", store)
        assertTrue(decision is HostKeyDecision.FirstUse)
        assertEquals("AA:BB:CC", (decision as HostKeyDecision.FirstUse).fingerprint)
    }

    @Test
    fun `matching fingerprint on a known host is trusted`() {
        val store = FakeHostKeyStore()
        store.trust("pi.local", 22, "AA:BB:CC")
        val decision = HostKeyVerification.evaluate("pi.local", 22, "AA:BB:CC", store)
        assertEquals(HostKeyDecision.Trusted, decision)
    }

    @Test
    fun `changed fingerprint on a known host is reported as mismatch`() {
        val store = FakeHostKeyStore()
        store.trust("pi.local", 22, "AA:BB:CC")
        val decision = HostKeyVerification.evaluate("pi.local", 22, "11:22:33", store)
        assertTrue(decision is HostKeyDecision.Mismatch)
        val mismatch = decision as HostKeyDecision.Mismatch
        assertEquals("AA:BB:CC", mismatch.expected)
        assertEquals("11:22:33", mismatch.actual)
    }

    @Test
    fun `different ports on the same host are tracked independently`() {
        val store = FakeHostKeyStore()
        store.trust("pi.local", 22, "AA:BB:CC")
        val decision = HostKeyVerification.evaluate("pi.local", 2222, "AA:BB:CC", store)
        assertTrue(decision is HostKeyDecision.FirstUse)
    }

    @Test
    fun `forgetting a host resets it to first use`() {
        val store = FakeHostKeyStore()
        store.trust("pi.local", 22, "AA:BB:CC")
        store.forget("pi.local", 22)
        val decision = HostKeyVerification.evaluate("pi.local", 22, "AA:BB:CC", store)
        assertTrue(decision is HostKeyDecision.FirstUse)
    }
}
