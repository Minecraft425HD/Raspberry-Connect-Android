package com.raspberryconnect.terminal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installBouncyCastleProvider()
        createNotificationChannel()
    }

    /**
     * Android ships its own stripped-down "BC" security provider (part of AOSP) which
     * is missing/incomplete for several algorithms sshj needs for modern SSH key
     * exchange and keys - notably X25519/Curve25519 (used by curve25519-sha256 KEX and
     * ed25519 keys), causing "no such algorithm: X25519 for provider BC". Swap it for
     * the real, full bcprov-jdk18on implementation bundled with the app.
     */
    private fun installBouncyCastleProvider() {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            // Never let provider setup crash app startup; worst case SSH connections
            // fail later with a clear error instead of the app being unusable.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "ssh_connection_channel"
    }
}
