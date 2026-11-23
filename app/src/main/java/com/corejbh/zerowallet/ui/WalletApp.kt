package com.corejbh.zerowallet.ui

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
import com.corejbh.zerowallet.WalletViewModel
import com.corejbh.zerowallet.core.AppLock
import com.corejbh.zerowallet.core.Authorization
import com.corejbh.zerowallet.core.DeviceLockSettings
import com.corejbh.zerowallet.core.ScreenSecurity
import com.corejbh.zerowallet.core.SeedSealRequired
import com.corejbh.zerowallet.ui.screens.LockedScreen
import com.corejbh.zerowallet.ui.screens.ScannerScreen
import com.corejbh.zerowallet.ui.screens.SetupScreen
import com.corejbh.zerowallet.ui.sheets.BackupSheet
import com.corejbh.zerowallet.ui.sheets.ChannelsSheet
import com.corejbh.zerowallet.ui.sheets.ReceiveSheet
import com.corejbh.zerowallet.ui.sheets.SendSheet
import com.corejbh.zerowallet.ui.sheets.SettingsSheet
import com.corejbh.zerowallet.ui.theme.Pal
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private enum class ActiveSheet { NONE, SETTINGS, SEND, RECEIVE, CHANNELS }

private enum class ScanPurpose { SEND, PEER }

/**
 * The wallet window: which of its screens is up, and the live activity behind them.
 *
 * Everything that has to survive a rotation - whether the wallet is open, the draft counter
 * the sheets watch, the lock availability a prompt depends on - lives in [WalletViewModel].
 * What stays here is what belongs to one composition: which sheet is open, what the scanner
 * brought back, and the recovery phrase while its sheet is on screen.
 */
@Composable
fun WalletApp(vm: WalletViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lock by vm.lock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val activity = remember(context) { AppLock.activityOf(context) }
    val appLock = remember(activity) { activity?.let { AppLock(it) } }
    // Until the wallet is loaded it is not known whether one exists, so the gate stays
    // armed; after that only a real wallet is worth locking. Onboarding is never gated.
    val lockArmed = if (state.bootstrapped) state.seedPresent else true
    // Locking the app every time it opens is opt-in in Settings. Sending funds and showing
    // the recovery phrase authenticate on their own, whether or not that option is on.
    //
    // Whether the phone can ask at all is read from the wallet session, not from a value
    // remembered for this composition: the session re-reads it at every resume, so a screen
    // lock removed while the app sat in the background takes the gate with it instead of
    // leaving a lock screen no prompt can open. The first frame is covered by the value the
    // session reads when it is created.
    val openLockEnabled = state.lockOnOpen && lock.lockAvailable && lockArmed
    // A wallet whose key is bound to the device lock cannot be read at all until the user
    // authenticates, so the lock screen comes up before anything of the wallet is shown.
    val lockedBySeed = state.seedLocked

    var sheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var scanning by remember { mutableStateOf(false) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.SEND) }
    var sendPrefill by remember { mutableStateOf("") }
    var peerPrefill by remember { mutableStateOf("") }
    // The phrase is a value of this composition on purpose: it is dropped when the sheet
    // closes and when the app leaves the screen, and it never becomes part of the state a
    // ViewModel (or a saved bundle) carries across a configuration change.
    var backupWords by remember { mutableStateOf<String?>(null) }
    var backupVerify by remember { mutableStateOf(false) }

    fun unlockWith(reason: String) {
        val appLock = appLock
        if (appLock == null || !openLockEnabled) {
            vm.onOpenUnlocked()
            return
        }
        val available = appLock.isAvailable()
        vm.onLockAvailability(available)
        appLock.authenticate("Unlock ZeroWallet", reason) { ok ->
            if (ok) {
                vm.onOpenUnlocked()
                // The same authentication also unseals the wallet's key, so the lock screen
                // does not have to ask a second time for what it just proved.
                if (state.seedLocked) vm.unlockSeed()
            } else {
                vm.onOpenLockDenied(
                    if (appLock.isAvailable()) {
                        "Authentication was not completed."
                    } else {
                        "This phone has no screen lock, so the wallet cannot be unlocked. Set one in Android settings first."
                    }
                )
            }
        }
    }

    // Ask as soon as the lock screen appears, including after a relock. Nothing is asked
    // while the wallet is still being loaded or does not exist yet. A wallet that arrived
    // locked has its own prompt below, which also unseals its key; raising this one as well
    // would only make the two supersede each other.
    LaunchedEffect(openLockEnabled, lock.unlocked, state.bootstrapped, lockedBySeed) {
        if (openLockEnabled && !lock.unlocked && state.bootstrapped && !lockedBySeed) {
            unlockWith("Authenticate to open your wallet.")
        }
    }

    // The gate is settled when the saved option is first known; the ViewModel remembers that
    // it was, so a rotation does not turn a settings change into a new session.
    LaunchedEffect(openLockEnabled, state.bootstrapped) {
        if (!state.bootstrapped) return@LaunchedEffect
        vm.settleOpenLock(openLockEnabled)
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
                    vm.onScreenStopped(openLockEnabled)
                }
                // Android can freeze or kill the node while the app is away, and a start
                // that failed leaves nothing running. Coming back asks for it again. The
                // authentication options are re-read here too, so removing the screen lock
                // outside the app takes the lock gate with it instead of leaving a prompt
                // that can no longer succeed.
                Lifecycle.Event.ON_RESUME -> {
                    vm.onScreenResumed(appLock?.isAvailable() == true)
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
     *
     * The answer is delivered, not thrown away. [Authorization] is the only way a screen gets
     * to run a privileged body, and it runs that body only for a reported success; every
     * path answers exactly once - authenticated, cancelled, or no lock to authenticate with -
     * because a sheet that holds a draft has to know that the authentication was cancelled
     * rather than still being in flight (the system's PIN screen takes the app off screen
     * while it is up).
     */
    val authorization = Authorization { reason, action ->
        val lock = appLock
        val available = lock?.isAvailable() == true
        vm.onLockAvailability(available)
        if (lock == null || !available) {
            vm.reportScreenLockMissing()
            action(false)
        } else {
            lock.authenticate("Confirm it's you", reason) { ok ->
                if (ok) {
                    action(true)
                } else {
                    action(false)
                    scope.launch { snackbarHostState.showSnackbar("Authentication was not completed.") }
                }
            }
        }
    }

    /** [Authorization.guarded] as a suspend call, for the one flow that needs its answer inline. */
    suspend fun authenticateForSeal(): Boolean = suspendCancellableCoroutine { continuation ->
        authorization.guarded(
            reason = "Authenticate to let this phone's Keystore seal your recovery phrase.",
            onDenied = { if (continuation.isActive) continuation.resume(false) },
            action = { if (continuation.isActive) continuation.resume(true) }
        )
    }

    /**
     * Runs a wallet creation or restore, asking for the device lock once if the Keystore's
     * key needs a fresh authentication before it will seal the phrase.
     *
     * The wallet key is generated inside the Keystore and bound to the screen lock, so the
     * seal can land outside its validity window. Nothing is written in that case
     * ([SeedSealRequired]), which is what makes a second attempt safe: the first one changed
     * nothing on this phone. A prompt that the user dismisses writes nothing either, and the
     * setup screen says so instead of leaving a half-made wallet behind.
     */
    suspend fun <T> withSealAuthentication(block: suspend () -> T): T = try {
        block()
    } catch (required: SeedSealRequired) {
        if (!authenticateForSeal()) {
            throw IllegalStateException(
                "Authentication was not completed, so nothing was written. Try again when you can confirm it is you."
            )
        }
        block()
    }

    /**
     * The same for a call that reports its failure as a [Result] instead of throwing.
     *
     * A restore has to keep reporting ordinary failures (a phrase that does not check out, a
     * node that will not start) as a Result, so its "authenticate first" arrives that way
     * too. Only that one failure is turned into a prompt; everything else is handed back as
     * it came.
     */
    suspend fun <T> withSealAuthenticationResult(block: suspend () -> Result<T>): Result<T> {
        val first = block()
        if (first.exceptionOrNull() !is SeedSealRequired) return first
        if (!authenticateForSeal()) {
            return Result.failure(
                IllegalStateException(
                    "Authentication was not completed, so nothing was changed on this phone."
                )
            )
        }
        return block()
    }

    /**
     * Opens the wallet after the device-lock prompt. The Keystore key that seals the phrase
     * needs a recent authentication, and this is the one place that provides it.
     */
    fun unlockSeed() {
        val appLock = appLock
        if (appLock == null || !appLock.isAvailable()) {
            vm.onLockAvailability(false)
            vm.onSeedUnlockDenied(
                "This phone cannot authenticate right now. Set a screen lock, then try again."
            )
            return
        }
        appLock.authenticate(
            "Unlock ZeroWallet",
            "Authenticate to open the wallet on this phone."
        ) { ok ->
            if (ok) {
                // The same prompt that unseals the key also satisfies lock-on-open.
                vm.onSeedUnlocked()
                vm.unlockSeed()
            } else {
                vm.onSeedUnlockDenied("Authentication was not completed.")
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
            error = lock.seedError,
            onUnlock = { unlockSeed() }
        )
        return
    }

    if (openLockEnabled && !lock.unlocked) {
        LockedScreen(
            error = lock.error,
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
                    // Sealing the phrase is a Keystore operation off the main thread; when the
                    // key is bound to the lock it may ask for it first, and the same call is
                    // then made again with nothing of the first attempt on this phone.
                    val words = withSealAuthentication { vm.createWallet(wordCount) }
                    backupWords = words
                    backupVerify = true
                },
                onRestore = { phrase -> withSealAuthenticationResult { vm.restoreWallet(phrase) } }
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
                onChannels = { sheet = ActiveSheet.CHANNELS }
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
            lockAvailable = lock.lockAvailable,
            onBackup = {
                authorization.guarded("Authenticate to view your recovery phrase.") {
                    backupWords = vm.revealSeed()
                    backupVerify = !state.backedUp
                }
            },
            authorization = authorization,
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.SEND -> SendSheet(
            vm = vm,
            state = state,
            initialText = sendPrefill,
            backgrounded = lock.backgrounded,
            authorization = authorization,
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
            backgrounded = lock.backgrounded,
            onBackup = {
                authorization.guarded("Authenticate to view your recovery phrase.") {
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
            authorization = authorization,
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
    if (lock.needsScreenLock) {
        AlertDialog(
            onDismissRequest = { vm.dismissScreenLockNotice() },
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
                    vm.dismissScreenLockNotice()
                    DeviceLockSettings.open(context)
                }) {
                    Text("Open settings", color = Pal.AccentBright)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissScreenLockNotice() }) {
                    Text("Not now", color = Pal.TextSecondary)
                }
            }
        )
    }
}
