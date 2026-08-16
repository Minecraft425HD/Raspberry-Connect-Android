package com.raspberryconnect.terminal.service

sealed class ConnectionState {
    object Idle : ConnectionState()
    data class Connecting(val profileName: String) : ConnectionState()
    data class Connected(val profileName: String) : ConnectionState()
    data class Reconnecting(val profileName: String, val attempt: Int) : ConnectionState()
    data class Disconnected(val profileName: String) : ConnectionState()
    data class Error(val profileName: String, val message: String) : ConnectionState()
    data class HostKeyRejected(val profileName: String, val expected: String, val actual: String) : ConnectionState()
}
