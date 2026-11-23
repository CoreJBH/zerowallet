package com.corejbh.zerowallet.core

/**
 * When the wallet's open-lock gate is closed, free of Android APIs so its transitions can be
 * tested without an Activity.
 *
 * The gate belongs to one session: it is decided once, when the saved option is first read,
 * and after that only leaving the screen closes it. Deriving it from the option itself — as
 * `remember(openLockEnabled)` did — made every change of that state look like a new session:
 * turning lock-on-open on in Settings threw the user out to the lock screen in the middle of
 * the sheet they were in, and turning it off counted as a pass, so a wallet that was open
 * when the option came back on had never asked who was holding the phone.
 */
internal object AppLockGate {

    /** Whether the wallet is open, after the saved option was read for the first time. */
    fun afterStart(lockOnOpen: Boolean): Boolean = !lockOnOpen

    /**
     * Whether the wallet stays open when the option changes while the app is running.
     *
     * Turning it on does not close the gate under the user: flipping a switch proves nothing
     * about who is holding the phone, and the next time the app leaves the screen it asks.
     * Turning it off opens the gate, because there is no longer an option to ask for.
     */
    fun afterOptionChanged(open: Boolean, enforced: Boolean): Boolean = open || !enforced

    /** Whether the wallet is open after the app left the screen. */
    fun afterLeavingScreen(open: Boolean, enforced: Boolean): Boolean =
        if (enforced) false else open
}
