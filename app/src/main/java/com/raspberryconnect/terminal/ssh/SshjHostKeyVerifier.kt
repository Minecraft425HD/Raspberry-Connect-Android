package com.raspberryconnect.terminal.ssh

import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** Thrown when a previously trusted host key no longer matches - do not retry silently. */
class HostKeyMismatchException(val host: String, val expected: String, val actual: String) :
    Exception("Host key for $host changed. Expected $expected but got $actual")

/**
 * Bridges sshj's [HostKeyVerifier] contract to the pure [HostKeyVerification] TOFU logic.
 * On first contact the key is trusted and stored; on a later mismatch the connection is
 * rejected and [HostKeyMismatchException] is thrown so the caller can surface a warning.
 */
class SshjHostKeyVerifier(private val store: HostKeyStore) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = SecurityUtils.getFingerprint(key)
        return when (val decision = HostKeyVerification.evaluate(hostname, port, fingerprint, store)) {
            is HostKeyDecision.Trusted -> true
            is HostKeyDecision.FirstUse -> {
                store.trust(hostname, port, decision.fingerprint)
                true
            }
            is HostKeyDecision.Mismatch -> {
                throw HostKeyMismatchException(hostname, decision.expected, decision.actual)
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
}
