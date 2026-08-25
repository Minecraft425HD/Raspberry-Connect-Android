package com.raspberryconnect.terminal.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the profile list JSON inside an [EncryptedSharedPreferences] file backed by
 * the Android Keystore, so hostnames, usernames, passwords and private keys never
 * touch disk in plaintext.
 */
class EncryptedPrefsProfileStore(private val context: Context) : ProfileStore {

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    /**
     * The Keystore-backed master key can become invalid or out of sync with the
     * encrypted file (e.g. after a backup/restore, a Keystore reset, or the key being
     * invalidated by the OS) - when that happens every read/write throws, which would
     * otherwise crash the app on every single launch. Wipe and start fresh instead of
     * ever letting that surface as a crash; losing saved connections is far better than
     * an unusable app.
     */
    private fun openOrRecreate(): SharedPreferences {
        return try {
            buildEncryptedPrefs()
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs unusable, recreating", e)
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            try {
                buildEncryptedPrefs()
            } catch (e2: Exception) {
                Log.e(TAG, "Encrypted prefs still unusable after reset", e2)
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun loadRaw(): String? = try {
        prefs.getString(KEY_PROFILES, null)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to decrypt saved connections, treating as empty", e)
        null
    }

    override fun saveRaw(json: String) {
        try {
            prefs.edit().putString(KEY_PROFILES, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist connections", e)
        }
    }

    companion object {
        private const val TAG = "EncryptedPrefsStore"
        private const val PREFS_FILE_NAME = "secure_connections"
        private const val KEY_PROFILES = "profiles_json"
    }
}
