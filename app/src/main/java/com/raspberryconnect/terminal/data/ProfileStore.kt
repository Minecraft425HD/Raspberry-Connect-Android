package com.raspberryconnect.terminal.data

/**
 * Abstracts the raw persistence of the profile list so [ConnectionRepository]'s
 * logic can be unit tested on the JVM without touching the Android Keystore.
 */
interface ProfileStore {
    fun loadRaw(): String?
    fun saveRaw(json: String)
}
