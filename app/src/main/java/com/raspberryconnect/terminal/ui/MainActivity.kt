package com.raspberryconnect.terminal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.raspberryconnect.terminal.R
import com.raspberryconnect.terminal.data.ConnectionProfile
import com.raspberryconnect.terminal.data.ConnectionRepository
import com.raspberryconnect.terminal.data.EncryptedPrefsProfileStore
import com.raspberryconnect.terminal.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConnectionRepository
    private lateinit var adapter: ConnectionListAdapter

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repository = ConnectionRepository(EncryptedPrefsProfileStore(applicationContext))

        adapter = ConnectionListAdapter(
            onClick = { openTerminal(it) },
            onEdit = { openEditor(it.id) },
            onDelete = { confirmDelete(it) }
        )
        binding.recyclerConnections.layoutManager = LinearLayoutManager(this)
        binding.recyclerConnections.adapter = adapter

        binding.fabAdd.setOnClickListener { openEditor(null) }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val profiles = repository.getAll()
        adapter.submitList(profiles)
        binding.textEmpty.visibility = if (profiles.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openEditor(id: String?) {
        val intent = Intent(this, EditConnectionActivity::class.java)
        if (id != null) intent.putExtra(EditConnectionActivity.EXTRA_PROFILE_ID, id)
        startActivity(intent)
    }

    private fun openTerminal(profile: ConnectionProfile) {
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra(TerminalActivity.EXTRA_PROFILE_ID, profile.id)
        startActivity(intent)
    }

    private fun confirmDelete(profile: ConnectionProfile) {
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setMessage(getString(R.string.action_delete) + "?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.delete(profile.id)
                refreshList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
