package com.raspberryconnect.terminal.ssh

import android.content.Context

/**
 * Fingerprints are not secret (they're hashes of public keys), so a plain
 * (unencrypted) preferences file is sufficient here.
 */
class PrefsHostKeyStore(context: Context) : HostKeyStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getTrustedFingerprint(host: String, port: Int): String? =
        prefs.getString(key(host, port), null)

    override fun trust(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    override fun forget(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
    }

    private fun key(host: String, port: Int) = "$host:$port"

    companion object {
        private const val PREFS_NAME = "known_hosts"
    }
}
