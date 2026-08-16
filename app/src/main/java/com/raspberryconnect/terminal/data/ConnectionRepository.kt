package com.raspberryconnect.terminal.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * CRUD logic for saved [ConnectionProfile]s. Pure Kotlin aside from the injected
 * [ProfileStore], so it can be exercised with an in-memory fake in unit tests.
 */
class ConnectionRepository(private val store: ProfileStore) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(): List<ConnectionProfile> {
        val raw = store.loadRaw() ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): ConnectionProfile? = getAll().firstOrNull { it.id == id }

    /** Inserts a new profile or replaces the existing one with the same id. */
    fun save(profile: ConnectionProfile): ConnectionProfile {
        val current = getAll().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current += profile
        }
        persist(current)
        return profile
    }

    fun delete(id: String) {
        val current = getAll().filterNot { it.id == id }
        persist(current)
    }

    private fun persist(profiles: List<ConnectionProfile>) {
        store.saveRaw(json.encodeToString(profiles))
    }
}
