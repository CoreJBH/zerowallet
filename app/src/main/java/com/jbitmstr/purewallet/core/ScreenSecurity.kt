package com.jbitmstr.purewallet.core

import android.app.Activity
import android.view.WindowManager

/**
 * One place that turns screenshots and the recent-apps thumbnail on or off for the
 * wallet window. With `FLAG_SECURE` set Android also blocks screen recording of the app
 * and blanks the preview in Recents, which is what keeps a recovery phrase out of them.
 */
object ScreenSecurity {

    fun apply(activity: Activity, secure: Boolean) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
