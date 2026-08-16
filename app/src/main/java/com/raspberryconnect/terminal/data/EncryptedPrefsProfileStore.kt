package com.raspberryconnect.terminal.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the profile list JSON inside an [EncryptedSharedPreferences] file backed by
 * the Android Keystore, so hostnames, usernames, passwords and private keys never
 * touch disk in plaintext.
 */
class EncryptedPrefsProfileStore(context: Context) : ProfileStore {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun loadRaw(): String? = prefs.getString(KEY_PROFILES, null)

    override fun saveRaw(json: String) {
        prefs.edit().putString(KEY_PROFILES, json).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "secure_connections"
        private const val KEY_PROFILES = "profiles_json"
    }
}
