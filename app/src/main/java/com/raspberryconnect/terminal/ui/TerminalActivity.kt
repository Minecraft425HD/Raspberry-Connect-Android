package com.raspberryconnect.terminal.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.raspberryconnect.terminal.R
import com.raspberryconnect.terminal.data.ConnectionProfile
import com.raspberryconnect.terminal.data.ConnectionRepository
import com.raspberryconnect.terminal.data.EncryptedPrefsProfileStore
import com.raspberryconnect.terminal.databinding.ActivityTerminalBinding
import com.raspberryconnect.terminal.service.ConnectionState
import com.raspberryconnect.terminal.service.TerminalConnectionService
import com.raspberryconnect.terminal.ssh.PrefsHostKeyStore
import com.raspberryconnect.terminal.terminal.KeyMapper
import com.raspberryconnect.terminal.terminal.TerminalEmulator
import com.raspberryconnect.terminal.terminal.TerminalInputListener
import kotlinx.coroutines.launch

class TerminalActivity : AppCompatActivity(), TerminalInputListener {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var repository: ConnectionRepository
    private lateinit var profile: ConnectionProfile
    private val emulator = TerminalEmulator(80, 24)

    private var service: TerminalConnectionService? = null
    private var hostKeyDialogShowing = false
    private var observersStarted = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as TerminalConnectionService.LocalBinder).getService()
            service = bound
            if (bound.currentProfileId != profile.id) {
                bound.connect(profile)
            }
            // onServiceConnected fires again on every bindService() call (e.g. each time
            // the app returns to the foreground) even though the same long-running
            // service is still connected - only ever attach the flow collectors once,
            // or every reconnect adds another subscriber and output gets fed twice.
            if (!observersStarted) {
                observersStarted = true
                observeService(bound)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        repository = ConnectionRepository(EncryptedPrefsProfileStore(applicationContext))
        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
        val loaded = id?.let { repository.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        profile = loaded
        binding.toolbar.title = profile.name

        binding.terminalView.attach(emulator, this)
        buildExtraKeysRow()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val imeVisible = ViewCompat.getRootWindowInsets(binding.root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (imeVisible) {
                    binding.terminalView.hideKeyboard()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val intent = TerminalConnectionService.bindIntent(this)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(serviceConnection) }
        service = null
    }

    private fun observeService(bound: TerminalConnectionService) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bound.state.collect { state -> render(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bound.output.collect { bytes ->
                    emulator.feed(bytes)
                    binding.terminalView.onScreenUpdated()
                }
            }
        }
    }

    private fun render(state: ConnectionState) {
        binding.toolbar.subtitle = when (state) {
            is ConnectionState.Connecting -> getString(R.string.terminal_connecting)
            is ConnectionState.Connected -> null
            is ConnectionState.Reconnecting -> getString(R.string.terminal_reconnecting)
            is ConnectionState.Disconnected -> getString(R.string.terminal_disconnected)
            is ConnectionState.Error -> state.message
            is ConnectionState.HostKeyRejected -> getString(R.string.host_key_dialog_title)
            ConnectionState.Idle -> null
        }
        if (state is ConnectionState.HostKeyRejected && !hostKeyDialogShowing) {
            showHostKeyMismatchDialog(state)
        }
    }

    private fun showHostKeyMismatchDialog(state: ConnectionState.HostKeyRejected) {
        hostKeyDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.host_key_dialog_title)
            .setMessage(getString(R.string.host_key_changed_message, profile.host, state.expected, state.actual))
            .setCancelable(false)
            .setPositiveButton(R.string.action_trust) { _, _ ->
                hostKeyDialogShowing = false
                PrefsHostKeyStore(applicationContext).trust(profile.host, profile.port, state.actual)
                service?.connect(profile)
            }
            .setNegativeButton(R.string.action_reject) { _, _ ->
                hostKeyDialogShowing = false
                finish()
            }
            .show()
    }

    // TerminalInputListener

    override fun onInput(bytes: ByteArray) {
        service?.sendInput(bytes)
    }

    override fun onTerminalSizeChanged(cols: Int, rows: Int) {
        service?.resize(cols, rows)
    }

    private fun buildExtraKeysRow() {
        val row = binding.extraKeysRow
        addExtraKey(row, "Esc") { sendBytes(byteArrayOf(0x1B)) }
        addExtraKey(row, "Tab") { sendBytes(byteArrayOf(0x09)) }
        val ctrlButton = addExtraKey(row, "Ctrl") { }
        ctrlButton.setOnClickListener {
            binding.terminalView.stickyCtrl = !binding.terminalView.stickyCtrl
            ctrlButton.isChecked = binding.terminalView.stickyCtrl
        }
        addExtraKey(row, "↑") { sendSpecialKey(KeyEvent.KEYCODE_DPAD_UP) }
        addExtraKey(row, "↓") { sendSpecialKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        addExtraKey(row, "←") { sendSpecialKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        addExtraKey(row, "→") { sendSpecialKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        addExtraKey(row, getString(R.string.action_paste)) { binding.terminalView.pasteFromClipboard() }
        addExtraKey(row, "|") { sendBytes(byteArrayOf('|'.code.toByte())) }
        addExtraKey(row, "~") { sendBytes(byteArrayOf('~'.code.toByte())) }
        addExtraKey(row, "/") { sendBytes(byteArrayOf('/'.code.toByte())) }
        addExtraKey(row, "-") { sendBytes(byteArrayOf('-'.code.toByte())) }
    }

    private fun addExtraKey(row: android.widget.LinearLayout, label: String, onClick: () -> Unit): MaterialButton {
        val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isCheckable = label == "Ctrl"
            minWidth = 0
            minimumWidth = 0
            setPadding(28, 8, 28, 8)
            setTextColor(ContextCompat.getColor(context, R.color.terminal_fg))
            setOnClickListener { onClick() }
        }
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.marginEnd = 8
        row.addView(button, params)
        return button
    }

    private fun sendSpecialKey(keyCode: Int) {
        val bytes = KeyMapper.map(
            keyCode = keyCode,
            unicodeChar = 0,
            ctrl = false,
            shift = false,
            alt = false,
            applicationCursorKeys = emulator.applicationCursorKeys
        )
        if (bytes != null) sendBytes(bytes)
    }

    private fun sendBytes(bytes: ByteArray) {
        service?.sendInput(bytes)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }
}
