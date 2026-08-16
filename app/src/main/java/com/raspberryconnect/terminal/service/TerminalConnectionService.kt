package com.raspberryconnect.terminal.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.raspberryconnect.terminal.App
import com.raspberryconnect.terminal.R
import com.raspberryconnect.terminal.data.ConnectionProfile
import com.raspberryconnect.terminal.ssh.HostKeyMismatchException
import com.raspberryconnect.terminal.ssh.PrefsHostKeyStore
import com.raspberryconnect.terminal.ssh.ReconnectPolicy
import com.raspberryconnect.terminal.ssh.SshConnectionConfig
import com.raspberryconnect.terminal.ssh.SshjTerminalTransport
import com.raspberryconnect.terminal.ssh.TerminalChannel
import com.raspberryconnect.terminal.ssh.TerminalTransport
import com.raspberryconnect.terminal.ui.TerminalActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Keeps the SSH session alive as a foreground service so the connection survives the
 * app being backgrounded (unlike a browser tab, which Android/Chrome will happily
 * suspend). Combines SSH-level keepalive pings, a partial wake lock while connected,
 * and automatic reconnect with backoff after unexpected drops.
 */
class TerminalConnectionService : LifecycleService() {

    private val binder = LocalBinder()
    private val transport: TerminalTransport = SshjTerminalTransport()
    private val reconnectPolicy = ReconnectPolicy()

    private var channel: TerminalChannel? = null
    private var readJob: Job? = null
    private var currentProfile: ConnectionProfile? = null
    private var userInitiatedDisconnect = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val output = _output.asSharedFlow()

    inner class LocalBinder : Binder() {
        fun getService(): TerminalConnectionService = this@TerminalConnectionService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect()
        }
        return START_NOT_STICKY
    }

    val currentProfileId: String? get() = currentProfile?.id

    fun connect(profile: ConnectionProfile) {
        userInitiatedDisconnect = true // stop any previous loop from reconnecting
        readJob?.cancel()
        closeChannel()
        releaseWakeLock()

        userInitiatedDisconnect = false
        currentProfile = profile
        startForegroundWithState(ConnectionState.Connecting(profile.name))
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            runConnectionLoop(profile)
        }
    }

    fun sendInput(bytes: ByteArray) {
        val out = channel?.output ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                // Read loop will observe the failure and drive reconnect/state updates.
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        channel?.resize(cols, rows)
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        readJob?.cancel()
        closeChannel()
        val name = currentProfile?.name.orEmpty()
        _state.value = ConnectionState.Disconnected(name)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runConnectionLoop(profile: ConnectionProfile) {
        var attempt = 0
        var everConnected = false
        while (currentCoroutineContext().isActive) {
            attempt++
            try {
                _state.value = if (!everConnected && attempt == 1) {
                    ConnectionState.Connecting(profile.name)
                } else {
                    ConnectionState.Reconnecting(profile.name, attempt)
                }
                updateNotification(_state.value)

                val hostKeyStore = PrefsHostKeyStore(applicationContext)
                val config = SshConnectionConfig(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authType = profile.authType,
                    password = profile.password,
                    privateKeyPem = profile.privateKey,
                    passphrase = profile.passphrase,
                    hostKeyStore = hostKeyStore
                )
                val newChannel = transport.connect(config)
                channel = newChannel
                acquireWakeLock()
                attempt = 0
                everConnected = true
                _state.value = ConnectionState.Connected(profile.name)
                updateNotification(_state.value)

                pumpOutput(newChannel)

                // pumpOutput returns when the channel closes (EOF / error).
                channel = null
                if (userInitiatedDisconnect || !profile.keepAliveInBackground) {
                    _state.value = ConnectionState.Disconnected(profile.name)
                    updateNotification(_state.value)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel = null
                if (userInitiatedDisconnect) return
                val hostKeyMismatch = findHostKeyMismatch(e)
                if (hostKeyMismatch != null) {
                    _state.value = ConnectionState.HostKeyRejected(
                        profile.name, hostKeyMismatch.expected, hostKeyMismatch.actual
                    )
                    updateNotification(_state.value)
                    return
                }
                _state.value = ConnectionState.Error(profile.name, e.message ?: e.javaClass.simpleName)
                updateNotification(_state.value)
            }

            if (userInitiatedDisconnect || !profile.keepAliveInBackground) return
            if (!reconnectPolicy.shouldRetry(attempt.coerceAtLeast(1))) return
            delay(reconnectPolicy.delayForAttempt(attempt.coerceAtLeast(1)))
        }
    }

    /**
     * sshj wraps exceptions thrown from a HostKeyVerifier (e.g. our TOFU check) inside
     * its own SSHException/TransportException, so a plain `catch (HostKeyMismatchException)`
     * never fires - this walks the cause chain to find it instead.
     */
    private fun findHostKeyMismatch(e: Throwable): HostKeyMismatchException? =
        generateSequence(e) { it.cause }.firstOrNull { it is HostKeyMismatchException } as? HostKeyMismatchException

    private suspend fun pumpOutput(channel: TerminalChannel) {
        val buffer = ByteArray(8192)
        withContext(Dispatchers.IO) {
            try {
                while (isActive) {
                    val read = channel.input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        _output.emit(buffer.copyOf(read))
                    }
                }
            } catch (e: IOException) {
                // Falls through: treated as a disconnect by the caller.
            }
        }
    }

    private fun closeChannel() {
        channel?.let { runCatching { it.close() } }
        channel = null
    }

    private fun startForegroundWithState(state: ConnectionState) {
        _state.value = state
        startForeground(NOTIFICATION_ID, buildNotification(state))
    }

    private fun updateNotification(state: ConnectionState) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ConnectionState): android.app.Notification {
        val text = when (state) {
            is ConnectionState.Connecting -> getString(R.string.notif_connecting, state.profileName)
            is ConnectionState.Connected -> getString(R.string.notif_connected, state.profileName)
            is ConnectionState.Reconnecting -> getString(R.string.notif_reconnecting, state.profileName)
            is ConnectionState.Disconnected -> getString(R.string.terminal_disconnected)
            is ConnectionState.Error -> state.message
            is ConnectionState.HostKeyRejected -> getString(R.string.host_key_dialog_title)
            ConnectionState.Idle -> getString(R.string.terminal_disconnected)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TerminalConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_disconnect_action), disconnectIntent)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiTerminal:ssh-session").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        readJob?.cancel()
        closeChannel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISCONNECT = "com.raspberryconnect.terminal.action.DISCONNECT"
        private const val NOTIFICATION_ID = 42
        // Renewed on every connect/reconnect attempt; caps worst-case drain if the
        // service is ever killed without onDestroy running.
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 12L * 60 * 60 * 1000

        fun bindIntent(context: Context) = Intent(context, TerminalConnectionService::class.java)
    }
}
