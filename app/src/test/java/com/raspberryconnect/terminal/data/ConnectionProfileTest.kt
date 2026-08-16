package com.raspberryconnect.terminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {

    private fun password(
        name: String = "Pi",
        host: String = "192.168.1.42",
        port: Int = 22,
        username: String = "pi",
        password: String? = "secret"
    ) = ConnectionProfile(name = name, host = host, port = port, username = username, authType = AuthType.PASSWORD, password = password)

    @Test
    fun `valid password profile has no errors`() {
        assertTrue(password().isValid)
    }

    @Test
    fun `blank name is rejected`() {
        val errors = password(name = "  ").validate()
        assertEquals(listOf(ValidationError.NAME_REQUIRED), errors)
    }

    @Test
    fun `blank host is rejected`() {
        val errors = password(host = "").validate()
        assertEquals(listOf(ValidationError.HOST_REQUIRED), errors)
    }

    @Test
    fun `port out of range is rejected`() {
        assertEquals(listOf(ValidationError.PORT_INVALID), password(port = 0).validate())
        assertEquals(listOf(ValidationError.PORT_INVALID), password(port = 70000).validate())
    }

    @Test
    fun `password auth without password is rejected`() {
        val errors = password(password = null).validate()
        assertEquals(listOf(ValidationError.PASSWORD_REQUIRED), errors)
    }

    @Test
    fun `key auth without a key is rejected`() {
        val profile = ConnectionProfile(
            name = "Pi", host = "h", username = "pi",
            authType = AuthType.PRIVATE_KEY, privateKey = null
        )
        assertEquals(listOf(ValidationError.KEY_REQUIRED), profile.validate())
    }

    @Test
    fun `key auth with a key present is valid`() {
        val profile = ConnectionProfile(
            name = "Pi", host = "h", username = "pi",
            authType = AuthType.PRIVATE_KEY, privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----..."
        )
        assertTrue(profile.isValid)
    }

    @Test
    fun `multiple validation errors accumulate`() {
        val profile = ConnectionProfile(name = "", host = "", username = "", password = null)
        val errors = profile.validate()
        assertTrue(errors.contains(ValidationError.NAME_REQUIRED))
        assertTrue(errors.contains(ValidationError.HOST_REQUIRED))
        assertTrue(errors.contains(ValidationError.USERNAME_REQUIRED))
        assertTrue(errors.contains(ValidationError.PASSWORD_REQUIRED))
    }

    @Test
    fun `each profile gets a unique id by default`() {
        val a = password()
        val b = password()
        assertTrue(a.id != b.id)
    }
}
