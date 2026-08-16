package com.raspberryconnect.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryProfileStore : ProfileStore {
    var raw: String? = null
    override fun loadRaw(): String? = raw
    override fun saveRaw(json: String) {
        raw = json
    }
}

class ConnectionRepositoryTest {

    private fun profile(name: String = "Pi") = ConnectionProfile(
        name = name, host = "192.168.1.42", username = "pi", password = "secret"
    )

    @Test
    fun `empty store returns empty list`() {
        val repo = ConnectionRepository(InMemoryProfileStore())
        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `saved profile is returned by getAll and get`() {
        val repo = ConnectionRepository(InMemoryProfileStore())
        val saved = repo.save(profile())
        assertEquals(listOf(saved), repo.getAll())
        assertEquals(saved, repo.get(saved.id))
    }

    @Test
    fun `saving a profile with an existing id replaces it instead of duplicating`() {
        val repo = ConnectionRepository(InMemoryProfileStore())
        val original = repo.save(profile("Original"))
        repo.save(original.copy(name = "Renamed"))
        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("Renamed", all.first().name)
    }

    @Test
    fun `delete removes only the targeted profile`() {
        val repo = ConnectionRepository(InMemoryProfileStore())
        val a = repo.save(profile("A"))
        val b = repo.save(profile("B"))
        repo.delete(a.id)
        assertEquals(listOf(b), repo.getAll())
    }

    @Test
    fun `data survives a round trip through the underlying store`() {
        val store = InMemoryProfileStore()
        val repo1 = ConnectionRepository(store)
        val saved = repo1.save(
            ConnectionProfile(
                name = "Pi", host = "pi.local", port = 2222, username = "pi",
                authType = AuthType.PRIVATE_KEY, privateKey = "KEYDATA", passphrase = "pw",
                keepAliveInBackground = false
            )
        )

        val repo2 = ConnectionRepository(store)
        val reloaded = repo2.get(saved.id)
        assertEquals(saved, reloaded)
    }

    @Test
    fun `corrupted underlying json is treated as an empty list rather than crashing`() {
        val store = InMemoryProfileStore()
        store.raw = "{not valid json"
        val repo = ConnectionRepository(store)
        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `get returns null for an unknown id`() {
        val repo = ConnectionRepository(InMemoryProfileStore())
        assertNull(repo.get("does-not-exist"))
    }
}
