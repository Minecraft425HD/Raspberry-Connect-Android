package com.raspberryconnect.terminal.ssh

import com.raspberryconnect.terminal.data.AuthType
import java.io.InputStream
import java.io.OutputStream

data class SshConnectionConfig(
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
    val hostKeyStore: HostKeyStore,
    val initialCols: Int = 80,
    val initialRows: Int = 24,
    val keepAliveIntervalSeconds: Int = 15,
    val connectTimeoutMillis: Int = 10_000
)

/** A live shell channel: raw bytes in both directions plus PTY resize support. */
interface TerminalChannel : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    fun resize(cols: Int, rows: Int)
    fun isOpen(): Boolean
}

/** Opens an authenticated SSH session with an allocated PTY and interactive shell. */
interface TerminalTransport {
    fun connect(config: SshConnectionConfig): TerminalChannel
}
