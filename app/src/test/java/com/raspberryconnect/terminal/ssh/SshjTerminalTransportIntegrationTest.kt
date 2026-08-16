package com.raspberryconnect.terminal.ssh

import com.raspberryconnect.terminal.data.AuthType
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Runs the real sshj client against a real (embedded) SSH server, over a local socket -
 * no mocking of the protocol. This is what actually proves auth, PTY allocation and
 * the shell channel work end to end, without needing a physical Raspberry Pi.
 */
class SshjTerminalTransportIntegrationTest {

    private lateinit var server: SshServer
    private lateinit var hostKeyFile: File
    private val transport: TerminalTransport = SshjTerminalTransport()

    @Before
    fun startServer() {
        hostKeyFile = File.createTempFile("test-host-key", ".ser")
        hostKeyFile.deleteOnExit()

        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
        server.passwordAuthenticator =
            org.apache.sshd.server.auth.password.PasswordAuthenticator { username, password, _ ->
                username == TEST_USER && password == TEST_PASSWORD
            }
        server.shellFactory = ShellFactory { EchoCommand() }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(true)
        hostKeyFile.delete()
    }

    private fun configFor(hostKeyStore: HostKeyStore, password: String = TEST_PASSWORD) = SshConnectionConfig(
        host = "127.0.0.1",
        port = server.port,
        username = TEST_USER,
        authType = AuthType.PASSWORD,
        password = password,
        hostKeyStore = hostKeyStore,
        initialCols = 80,
        initialRows = 24,
        connectTimeoutMillis = 5000
    )

    @Test(timeout = 20_000)
    fun `authenticates and exchanges data over a PTY shell channel`() {
        val channel = transport.connect(configFor(InMemoryHostKeyStore()))
        try {
            assertTrue(channel.isOpen())

            channel.output.write("hello raspberry\n".toByteArray())
            channel.output.flush()

            val received = readUntil(channel.input, "hello raspberry\n")
            assertEquals("hello raspberry\n", received)
        } finally {
            channel.close()
        }
    }

    @Test(timeout = 20_000)
    fun `wrong password is rejected`() {
        var threw = false
        try {
            transport.connect(configFor(InMemoryHostKeyStore(), password = "wrong")).close()
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("expected authentication failure to throw", threw)
    }

    @Test(timeout = 20_000)
    fun `resize does not throw while the channel is open`() {
        val channel = transport.connect(configFor(InMemoryHostKeyStore()))
        try {
            channel.resize(120, 40)
        } finally {
            channel.close()
        }
    }

    @Test(timeout = 20_000)
    fun `host key is trusted on first use and reused on the next connection`() {
        val store = InMemoryHostKeyStore()
        transport.connect(configFor(store)).close()
        assertTrue(store.getTrustedFingerprint("127.0.0.1", server.port) != null)

        // Second connection with the same store must succeed via the Trusted path.
        transport.connect(configFor(store)).close()
    }

    @Test(timeout = 20_000)
    fun `tampered stored fingerprint causes the connection to be rejected`() {
        val store = InMemoryHostKeyStore()
        transport.connect(configFor(store)).close()
        store.trust("127.0.0.1", server.port, "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")

        var threw = false
        try {
            transport.connect(configFor(store)).close()
        } catch (e: Exception) {
            threw = generateSequence(e as Throwable?) { it.cause }.any { it is HostKeyMismatchException }
        }
        assertTrue("expected HostKeyMismatchException somewhere in the cause chain", threw)
    }

    private fun readUntil(input: InputStream, expected: String): String {
        val expectedBytes = expected.toByteArray()
        val buffer = ByteArray(1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < expectedBytes.size) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TEST_USER = "testuser"
        private const val TEST_PASSWORD = "testpass"
    }
}

private class InMemoryHostKeyStore : HostKeyStore {
    private val trusted = mutableMapOf<String, String>()
    override fun getTrustedFingerprint(host: String, port: Int): String? = trusted["$host:$port"]
    override fun trust(host: String, port: Int, fingerprint: String) {
        trusted["$host:$port"] = fingerprint
    }
    override fun forget(host: String, port: Int) {
        trusted.remove("$host:$port")
    }
}

/** Simplest possible server-side shell: echoes back every byte it receives. */
private class EchoCommand : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var exitCallback: ExitCallback
    private var thread: Thread? = null

    override fun setInputStream(input: InputStream) {
        this.input = input
    }

    override fun setOutputStream(output: OutputStream) {
        this.output = output
    }

    override fun setErrorStream(err: OutputStream) {
        // unused
    }

    override fun setExitCallback(callback: ExitCallback) {
        this.exitCallback = callback
    }

    override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
        thread = Thread({
            try {
                val buffer = ByteArray(1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
                exitCallback.onExit(0)
            } catch (e: IOException) {
                exitCallback.onExit(1, e.message ?: "io error")
            }
        }, "echo-command").apply {
            isDaemon = true
            start()
        }
    }

    override fun destroy(channel: ChannelSession) {
        thread?.interrupt()
    }
}
