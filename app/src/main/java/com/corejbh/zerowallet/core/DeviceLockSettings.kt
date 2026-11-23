package com.corejbh.zerowallet.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Where the user sets the phone's screen lock.
 *
 * The wallet cannot set a PIN itself, and a phone with no screen lock cannot authenticate
 * anyone: sending, showing the recovery phrase and wiping stay blocked until one exists.
 * Opening the right settings screen is the one thing the app can do to help.
 */
object DeviceLockSettings {

    fun open(context: Context) {
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }.isSuccess || runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) {
            Toast.makeText(
                context,
                "Open Settings, then Security, to set a screen lock",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
