package com.raspberryconnect.terminal.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val keepAliveInBackground: Boolean = true
) {
    fun validate(): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        if (name.isBlank()) errors += ValidationError.NAME_REQUIRED
        if (host.isBlank()) errors += ValidationError.HOST_REQUIRED
        if (port !in 1..65535) errors += ValidationError.PORT_INVALID
        if (username.isBlank()) errors += ValidationError.USERNAME_REQUIRED
        when (authType) {
            AuthType.PASSWORD -> if (password.isNullOrEmpty()) errors += ValidationError.PASSWORD_REQUIRED
            AuthType.PRIVATE_KEY -> if (privateKey.isNullOrBlank()) errors += ValidationError.KEY_REQUIRED
        }
        return errors
    }

    val isValid: Boolean get() = validate().isEmpty()
}

enum class ValidationError {
    NAME_REQUIRED,
    HOST_REQUIRED,
    PORT_INVALID,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,
    KEY_REQUIRED
}
