package com.xcloak.storevia.core.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

object DeviceAuth {
    /** True if the phone has a PIN, pattern, password or biometric lock set up. */
    fun isDeviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** The system "confirm your screen lock" screen, or null when the phone has no screen lock. */
    fun confirmIntent(context: Context, title: String, description: String?): Intent? {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) return null
        @Suppress("DEPRECATION")
        return km.createConfirmDeviceCredentialIntent(title, description)
    }
}
