package com.raspberryconnect.terminal.ssh

import com.raspberryconnect.terminal.data.AuthType
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream

class SshjTerminalTransport : TerminalTransport {

    override fun connect(config: SshConnectionConfig): TerminalChannel {
        val client = SSHClient(DefaultConfig())
        client.addHostKeyVerifier(SshjHostKeyVerifier(config.hostKeyStore))
        client.connectTimeout = config.connectTimeoutMillis

        client.connect(config.host, config.port)
        try {
            authenticate(client, config)
            // Only start sending keepalive SSH_MSG_IGNORE probes once the connection is
            // fully established and authenticated - setting this before connect() can
            // race with the initial key exchange and violate strict-KEX (no non-KEX
            // packets allowed before the first NEWKEYS).
            client.connection.keepAlive.keepAliveInterval = config.keepAliveIntervalSeconds

            val session = client.startSession()
            session.allocatePTY(
                "xterm-256color",
                config.initialCols,
                config.initialRows,
                0,
                0,
                emptyMap()
            )
            val shell = session.startShell()
            return SshjTerminalChannel(client, session, shell)
        } catch (t: Throwable) {
            client.disconnect()
            throw t
        }
    }

    private fun authenticate(client: SSHClient, config: SshConnectionConfig) {
        when (config.authType) {
            AuthType.PASSWORD -> client.authPassword(config.username, config.password.orEmpty())
            AuthType.PRIVATE_KEY -> {
                val passphrase = config.passphrase
                val keyProvider = if (passphrase.isNullOrEmpty()) {
                    client.loadKeys(config.privateKeyPem.orEmpty(), null, null)
                } else {
                    client.loadKeys(
                        config.privateKeyPem.orEmpty(),
                        null,
                        PasswordUtils.createOneOff(passphrase.toCharArray())
                    )
                }
                client.authPublickey(config.username, keyProvider)
            }
        }
    }
}

private class SshjTerminalChannel(
    private val client: SSHClient,
    private val session: Session,
    private val shell: Session.Shell
) : TerminalChannel {

    override val input: InputStream get() = shell.inputStream
    override val output: OutputStream get() = shell.outputStream

    override fun resize(cols: Int, rows: Int) {
        if (isOpen()) {
            shell.changeWindowDimensions(cols, rows, 0, 0)
        }
    }

    override fun isOpen(): Boolean = !shell.isEOF && client.isConnected

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
        runCatching { client.disconnect() }
    }
}
