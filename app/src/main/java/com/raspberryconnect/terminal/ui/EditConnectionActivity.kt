package com.raspberryconnect.terminal.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.raspberryconnect.terminal.R
import com.raspberryconnect.terminal.data.AuthType
import com.raspberryconnect.terminal.data.ConnectionProfile
import com.raspberryconnect.terminal.data.ConnectionRepository
import com.raspberryconnect.terminal.data.EncryptedPrefsProfileStore
import com.raspberryconnect.terminal.data.ValidationError
import com.raspberryconnect.terminal.databinding.ActivityEditConnectionBinding

class EditConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditConnectionBinding
    private lateinit var repository: ConnectionRepository
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        repository = ConnectionRepository(EncryptedPrefsProfileStore(applicationContext))
        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)

        setupAuthToggle()

        editingId?.let { id ->
            repository.get(id)?.let { populate(it) }
            binding.toolbar.title = getString(R.string.edit_connection)
            binding.buttonDelete.visibility = android.view.View.VISIBLE
        }

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonDelete.setOnClickListener { confirmDelete() }
    }

    private fun setupAuthToggle() {
        binding.toggleAuthType.check(binding.buttonAuthPassword.id)
        updateAuthFieldsVisibility(AuthType.PASSWORD)
        binding.toggleAuthType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val type = if (checkedId == binding.buttonAuthKey.id) AuthType.PRIVATE_KEY else AuthType.PASSWORD
            updateAuthFieldsVisibility(type)
        }
    }

    private fun updateAuthFieldsVisibility(type: AuthType) {
        val isKey = type == AuthType.PRIVATE_KEY
        binding.layoutPassword.visibility = if (isKey) android.view.View.GONE else android.view.View.VISIBLE
        binding.layoutPrivateKey.visibility = if (isKey) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutPassphrase.visibility = if (isKey) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun populate(profile: ConnectionProfile) {
        binding.inputName.setText(profile.name)
        binding.inputHost.setText(profile.host)
        binding.inputPort.setText(profile.port.toString())
        binding.inputUsername.setText(profile.username)
        binding.inputPassword.setText(profile.password)
        binding.inputPrivateKey.setText(profile.privateKey)
        binding.inputPassphrase.setText(profile.passphrase)
        binding.switchKeepAlive.isChecked = profile.keepAliveInBackground
        val checkedButton = if (profile.authType == AuthType.PRIVATE_KEY) binding.buttonAuthKey.id else binding.buttonAuthPassword.id
        binding.toggleAuthType.check(checkedButton)
        updateAuthFieldsVisibility(profile.authType)
    }

    private fun save() {
        val authType = if (binding.toggleAuthType.checkedButtonId == binding.buttonAuthKey.id) {
            AuthType.PRIVATE_KEY
        } else {
            AuthType.PASSWORD
        }
        val profile = ConnectionProfile(
            id = editingId ?: java.util.UUID.randomUUID().toString(),
            name = binding.inputName.text?.toString().orEmpty().trim(),
            host = binding.inputHost.text?.toString().orEmpty().trim(),
            port = binding.inputPort.text?.toString()?.toIntOrNull() ?: 22,
            username = binding.inputUsername.text?.toString().orEmpty().trim(),
            authType = authType,
            password = binding.inputPassword.text?.toString(),
            privateKey = binding.inputPrivateKey.text?.toString(),
            passphrase = binding.inputPassphrase.text?.toString()?.ifBlank { null },
            keepAliveInBackground = binding.switchKeepAlive.isChecked
        )

        clearErrors()
        val errors = profile.validate()
        if (errors.isNotEmpty()) {
            showErrors(errors)
            return
        }

        repository.save(profile)
        finish()
    }

    private fun clearErrors() {
        binding.layoutName.error = null
        binding.layoutHost.error = null
        binding.layoutPort.error = null
        binding.layoutUsername.error = null
        binding.layoutPassword.error = null
        binding.layoutPrivateKey.error = null
    }

    private fun showErrors(errors: List<ValidationError>) {
        for (error in errors) {
            when (error) {
                ValidationError.NAME_REQUIRED -> binding.layoutName.error = getString(R.string.error_name_required)
                ValidationError.HOST_REQUIRED -> binding.layoutHost.error = getString(R.string.error_host_required)
                ValidationError.PORT_INVALID -> binding.layoutPort.error = getString(R.string.error_port_invalid)
                ValidationError.USERNAME_REQUIRED -> binding.layoutUsername.error = getString(R.string.error_username_required)
                ValidationError.PASSWORD_REQUIRED -> binding.layoutPassword.error = getString(R.string.error_password_required)
                ValidationError.KEY_REQUIRED -> binding.layoutPrivateKey.error = getString(R.string.error_key_required)
            }
        }
    }

    private fun confirmDelete() {
        val id = editingId ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.action_delete) + "?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.delete(id)
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }
}
