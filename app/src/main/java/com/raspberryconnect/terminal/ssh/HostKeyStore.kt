package com.raspberryconnect.terminal.ssh

/**
 * Persists the trusted SHA-256 fingerprint per host:port so we can implement
 * trust-on-first-use (TOFU) host key checking.
 */
interface HostKeyStore {
    fun getTrustedFingerprint(host: String, port: Int): String?
    fun trust(host: String, port: Int, fingerprint: String)
    fun forget(host: String, port: Int)
}
