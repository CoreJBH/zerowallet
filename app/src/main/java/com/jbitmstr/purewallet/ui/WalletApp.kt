package com.jbitmstr.purewallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.AppLock
import com.jbitmstr.purewallet.core.AppLockGate
import com.jbitmstr.purewallet.core.DeviceLockSettings
import com.jbitmstr.purewallet.core.ScreenSecurity
import com.jbitmstr.purewallet.ui.screens.LockedScreen
import com.jbitmstr.purewallet.ui.screens.ScannerScreen
import com.jbitmstr.purewallet.ui.screens.SetupScreen
import com.jbitmstr.purewallet.ui.sheets.BackupSheet
import com.jbitmstr.purewallet.ui.sheets.ChannelsSheet
import com.jbitmstr.purewallet.ui.sheets.ReceiveSheet
import com.jbitmstr.purewallet.ui.sheets.SendSheet
import com.jbitmstr.purewallet.ui.sheets.SettingsSheet
import com.jbitmstr.purewallet.ui.theme.Pal
import kotlinx.coroutines.launch

private enum class ActiveSheet { NONE, SETTINGS, SEND, RECEIVE, CHANNELS }

private enum class ScanPurpose { SEND, PEER }

@Composable
fun WalletApp(vm: WalletViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activity = remember(context) { AppLock.activityOf(context) }
    val appLock = remember(activity) { activity?.let { AppLock(it) } }
    // Whether the phone can authenticate is not a fact to cache: a screen lock can be
    // removed while the app sits in the background, and the gate must not stay "available"
    // after that. It is re-read on every resume below.
    var lockAvailable by remember(activity) { mutableStateOf(appLock?.isAvailable() == true) }
    // Until the wallet is loaded it is not known whether one exists, so the gate stays
    // armed; after that only a real wallet is worth locking. Onboarding is never gated.
    val lockArmed = if (state.bootstrapped) state.seedPresent else true
    // Locking the app every time it opens is opt-in in Settings. Sending funds and showing
    // the recovery phrase authenticate on their own, whether or not that option is on.
    val openLockEnabled = state.lockOnOpen && lockAvailable && lockArmed
    // Whether the wallet is open, and whether that decision has been made for this session.
    // It is NOT keyed on the option: see [AppLockGate] for why. `unlocked` starts closed so a
    // wallet whose option is on never shows a frame of itself before the settings arrive.
    var unlocked by remember { mutableStateOf(false) }
    var gateStarted by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf<String?>(null) }
    // A wallet whose key is bound to the device lock cannot be read at all until the user
    // authenticates, so the lock screen comes up before anything of the wallet is shown.
    val lockedBySeed = state.seedLocked
    var seedUnlockError by remember { mutableStateOf<String?>(null) }
    // Raised when a sensitive action was asked for on a phone with no screen lock.
    var needsScreenLock by remember { mutableStateOf(false) }

    var sheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var scanning by remember { mutableStateOf(false) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.SEND) }
    var sendPrefill by remember { mutableStateOf("") }
    var peerPrefill by remember { mutableStateOf("") }
    var backupWords by remember { mutableStateOf<String?>(null) }
    var backupVerify by remember { mutableStateOf(false) }
    // Bumped every time the app leaves the screen. The sheets that hold a draft watch it and
    // drop what they hold; the counter keeps them composed, so nothing in flight (a payment
    // waiting on its authentication, a backup waiting on the file picker) is cancelled by it.
    var backgrounded by remember { mutableIntStateOf(0) }

    fun unlockWith(reason: String) {
        val lock = appLock
        if (lock == null || !openLockEnabled) {
            unlocked = true
            return
        }
        unlockError = null
        lock.authenticate("Unlock ZeroWallet", reason) { ok ->
            if (ok) {
                unlocked = true
                unlockError = null
                // The same authentication also unseals the wallet's key, so the lock screen
                // does not have to ask a second time for what it just proved.
                if (state.seedLocked) vm.unlockSeed()
            } else {
                unlockError = if (lock.isAvailable()) {
                    "Authentication was not completed."
                } else {
                    "This phone has no screen lock, so the wallet cannot be unlocked. Set one in Android settings first."
                }
            }
        }
    }

    // Ask as soon as the lock screen appears, including after a relock. Nothing is asked
    // while the wallet is still being loaded or does not exist yet.
    LaunchedEffect(openLockEnabled, unlocked, state.bootstrapped, lockedBySeed) {
        // A wallet that arrived locked has its own prompt below, which also unseals its key;
        // raising this one as well would only make the two supersede each other.
        if (openLockEnabled && !unlocked && state.bootstrapped && !lockedBySeed) {
            unlockWith("Authenticate to open your wallet.")
        }
    }

    // The gate is settled once, when the saved option is first read: a wallet that had
    // lock-on-open on opens behind the lock screen, one that had it off opens straight away.
    // After that the option can be toggled in Settings without throwing the user out or
    // counting as a pass; leaving the screen is what closes it again.
    LaunchedEffect(openLockEnabled, state.bootstrapped) {
        if (!state.bootstrapped) return@LaunchedEffect
        unlocked = if (!gateStarted) {
            gateStarted = true
            AppLockGate.afterStart(openLockEnabled)
        } else {
            AppLockGate.afterOptionChanged(unlocked, openLockEnabled)
        }
    }

    // Lock again whenever the app leaves the screen. The prompt itself keeps its own
    // lifecycle: it resets on stop and restarts on resume, so this cannot strand it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, openLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The recovery phrase is never left on screen for a backgrounded app, whether
                // or not lock-on-open is on: the option decides whether returning asks for
                // authentication, not whether the words stay up for anyone who picks the
                // phone up next.
                Lifecycle.Event.ON_PAUSE -> {
                    backupWords = null
                    backupVerify = false
                }
                Lifecycle.Event.ON_STOP -> {
                    backupWords = null
                    backupVerify = false
                    // The phrase this process had opened goes with the screen: the next read
                    // (showing it, a restart, a backup) goes through the Keystore again, so
                    // a key bound to the device lock asks for a fresh authentication.
                    vm.onAppBackground()
                    // Pasted or scanned input is only needed at the moment its sheet opens, so
                    // it is dropped here: a sheet opened after the app was away never starts
                    // from something that was typed before it left the screen. The sheets that
                    // are open do the same with their own drafts, under their own names.
                    sendPrefill = ""
                    peerPrefill = ""
                    backgrounded += 1
                    if (gateStarted) {
                        unlocked = AppLockGate.afterLeavingScreen(unlocked, openLockEnabled)
                    }
                }
                // Android can freeze or kill the node while the app is away, and a start
                // that failed leaves nothing running. Coming back asks for it again. The
                // authentication options are re-read here too, so removing the screen lock
                // outside the app takes the lock gate with it instead of leaving a prompt
                // that can no longer succeed.
                Lifecycle.Event.ON_RESUME -> {
                    lockAvailable = appLock?.isAvailable() == true
                    vm.onAppForeground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(appLock) {
        onDispose { appLock?.dispose() }
    }

    /**
     * Runs [action] only after the user proves it is them: payments, channel funding, showing
     * the recovery phrase, restoring a channel backup and wiping the wallet. This applies
     * whether or not the optional lock-on-open is on.
     *
     * A phone with no screen lock cannot prove anything, so the action does not run: the
     * wallet asks for the lock to be set instead. Letting it through would leave every
     * sensitive action open to whoever is holding the phone, which is exactly the case this
     * check exists for.
     */
    val authorize: (String, () -> Unit) -> Unit = { reason, action ->
        val lock = appLock
        val available = lock?.isAvailable() == true
        lockAvailable = available
        if (lock == null || !available) {
            needsScreenLock = true
        } else {
            lock.authenticate("Confirm it's you", reason) { ok ->
                if (ok) {
                    action()
                } else {
                    scope.launch { snackbarHostState.showSnackbar("Authentication was not completed.") }
                }
            }
        }
    }

    /**
     * Opens the wallet after the device-lock prompt. The Keystore key that seals the phrase
     * needs a recent authentication, and this is the one place that provides it.
     */
    fun unlockSeed() {
        val lock = appLock
        if (lock == null || !lock.isAvailable()) {
            lockAvailable = false
            seedUnlockError = "This phone cannot authenticate right now. Set a screen lock, then try again."
            return
        }
        seedUnlockError = null
        lock.authenticate(
            "Unlock ZeroWallet",
            "Authenticate to open the wallet on this phone."
        ) { ok ->
            if (ok) {
                // The same prompt that unseals the key also satisfies lock-on-open.
                unlocked = true
                unlockError = null
                vm.unlockSeed()
            } else {
                seedUnlockError = "Authentication was not completed."
            }
        }
    }

    // A wallet that arrived locked (its key needs the device lock) asks for that as soon as
    // its screen appears, so the user is not left looking at a button.
    LaunchedEffect(lockedBySeed) {
        if (lockedBySeed) unlockSeed()
    }

    // Screenshots and the Recents thumbnail stay blocked. With the setting on the whole
    // wallet is covered; the recovery phrase and the setup/restore screens force it on even
    // when the setting is off, because both show words of the seed.
    val secureScreen = state.screenProtection || backupWords != null || state.setupRequired
    LaunchedEffect(activity, secureScreen) {
        activity?.let { ScreenSecurity.apply(it, secureScreen) }
    }

    // The node keeps running while the app is locked: only the UI is gated, not the Lightning
    // node. Nothing is ever shown in the notification bar, by the user's request.

    // The wallet exists but its sealed key has not been opened yet: nothing of the wallet is
    // shown until the user authenticates, and the retry lives on this screen. This comes
    // before the lock-on-open screen because its prompt is the one that unseals the key.
    if (lockedBySeed) {
        LockedScreen(
            error = seedUnlockError,
            onUnlock = { unlockSeed() }
        )
        return
    }

    if (openLockEnabled && !unlocked) {
        LockedScreen(
            error = unlockError,
            onUnlock = { unlockWith("Authenticate to open your wallet.") }
        )
        return
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    BackHandler(enabled = scanning || sheet != ActiveSheet.NONE) {
        if (scanning) {
            scanning = false
        } else {
            sheet = ActiveSheet.NONE
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Pal.Background)
    ) {
        if (state.setupRequired) {
            SetupScreen(
                unlockError = state.unlockError,
                unlockMessage = state.error,
                onCreate = { wordCount ->
                    // Sealing the phrase generates a Keystore key pair off the main thread.
                    val words = vm.createWallet(wordCount)
                    backupWords = words
                    backupVerify = true
                },
                onRestore = { phrase -> vm.restoreWallet(phrase) }
            )
        } else {
            HomeScreen(
                state = state,
                onMenu = { sheet = ActiveSheet.SETTINGS },
                onRefresh = { vm.refresh() },
                onRestart = { vm.restartNode() },
                onScan = {
                    scanPurpose = ScanPurpose.SEND
                    scanning = true
                },
                onSend = {
                    sendPrefill = ""
                    sheet = ActiveSheet.SEND
                },
                onReceive = { sheet = ActiveSheet.RECEIVE },
                onChannels = { sheet = ActiveSheet.CHANNELS },
                onSwap = { sheet = ActiveSheet.CHANNELS }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        if (scanning) {
            ScannerScreen(
                title = if (scanPurpose == ScanPurpose.SEND) {
                    "Scan a payment request"
                } else {
                    "Scan a peer address"
                },
                onResult = { value ->
                    scanning = false
                    if (scanPurpose == ScanPurpose.SEND) {
                        sendPrefill = value
                        sheet = ActiveSheet.SEND
                    } else {
                        peerPrefill = value
                        sheet = ActiveSheet.CHANNELS
                    }
                },
                onClose = { scanning = false }
            )
        }
    }

    when (sheet) {
        ActiveSheet.SETTINGS -> SettingsSheet(
            vm = vm,
            state = state,
            lockAvailable = lockAvailable,
            onBackup = {
                authorize("Authenticate to view your recovery phrase.") {
                    backupWords = vm.revealSeed()
                    backupVerify = !state.backedUp
                }
            },
            onAuthorize = authorize,
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.SEND -> SendSheet(
            vm = vm,
            state = state,
            initialText = sendPrefill,
            backgrounded = backgrounded,
            onAuthorize = authorize,
            onClose = { sheet = ActiveSheet.NONE },
            onScan = {
                sheet = ActiveSheet.NONE
                scanPurpose = ScanPurpose.SEND
                scanning = true
            }
        )
        ActiveSheet.RECEIVE -> ReceiveSheet(
            vm = vm,
            state = state,
            backgrounded = backgrounded,
            onBackup = {
                authorize("Authenticate to view your recovery phrase.") {
                    backupWords = vm.revealSeed()
                    backupVerify = !state.backedUp
                }
            },
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.CHANNELS -> ChannelsSheet(
            vm = vm,
            state = state,
            initialPeer = peerPrefill,
            onAuthorize = authorize,
            onScan = {
                sheet = ActiveSheet.NONE
                scanPurpose = ScanPurpose.PEER
                scanning = true
            },
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.NONE -> Unit
    }

    val words = backupWords
    if (words != null) {
        BackupSheet(
            mnemonic = words,
            requireVerification = backupVerify,
            onConfirmed = { vm.confirmBackup() },
            onDismiss = { backupWords = null }
        )
    }

    // A sensitive action was asked for on a phone with no screen lock. It did not run, and
    // the only way forward is the Android setting that lets the wallet ask again.
    if (needsScreenLock) {
        AlertDialog(
            onDismissRequest = { needsScreenLock = false },
            containerColor = Pal.SurfaceElevated,
            titleContentColor = Pal.TextPrimary,
            textContentColor = Pal.TextSecondary,
            title = { Text("Set a screen lock first") },
            text = {
                Text(
                    "Sending, funding a channel, showing the recovery phrase and wiping the " +
                        "wallet all ask the phone to confirm that it is you. This phone has no " +
                        "PIN, pattern or password, so there is nothing to confirm with and the " +
                        "action is blocked.\n\nSet one in Android settings and come back - " +
                        "receiving still works in the meantime.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needsScreenLock = false
                    DeviceLockSettings.open(context)
                }) {
                    Text("Open settings", color = Pal.AccentBright)
                }
            },
            dismissButton = {
                TextButton(onClick = { needsScreenLock = false }) {
                    Text("Not now", color = Pal.TextSecondary)
                }
            }
        )
    }
}
