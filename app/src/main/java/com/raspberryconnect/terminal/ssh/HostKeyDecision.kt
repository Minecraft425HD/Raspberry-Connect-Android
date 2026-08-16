package com.raspberryconnect.terminal.ssh

sealed class HostKeyDecision {
    /** Fingerprint matches the one stored from a previous connection. */
    object Trusted : HostKeyDecision()

    /** First time we've ever seen a key for this host:port. Caller should store it. */
    data class FirstUse(val fingerprint: String) : HostKeyDecision()

    /** The key changed since we last trusted it - possible MITM or reinstalled host. */
    data class Mismatch(val expected: String, val actual: String) : HostKeyDecision()
}

/**
 * Pure trust-on-first-use decision logic, independent of sshj or Android so it
 * can be unit tested directly.
 */
object HostKeyVerification {
    fun evaluate(host: String, port: Int, fingerprint: String, store: HostKeyStore): HostKeyDecision {
        val trusted = store.getTrustedFingerprint(host, port)
            ?: return HostKeyDecision.FirstUse(fingerprint)

        return if (trusted == fingerprint) {
            HostKeyDecision.Trusted
        } else {
            HostKeyDecision.Mismatch(expected = trusted, actual = fingerprint)
        }
    }
}
