package com.jbitmstr.purewallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.AppLock
import com.jbitmstr.purewallet.core.NodeService
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
    val lockAvailable = remember(activity) { appLock?.isAvailable() == true }
    // Until the wallet is loaded it is not known whether one exists, so the gate stays
    // armed; after that only a real wallet is worth locking. Onboarding is never gated.
    val lockArmed = if (state.bootstrapped) state.seedPresent else true
    // Locking the app every time it opens is opt-in in Settings. Sending funds and showing
    // the recovery phrase authenticate on their own, whether or not that option is on.
    val openLockEnabled = state.lockOnOpen && lockAvailable && lockArmed
    var unlocked by remember(openLockEnabled) { mutableStateOf(!openLockEnabled) }
    var unlockError by remember { mutableStateOf<String?>(null) }

    var sheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var scanning by remember { mutableStateOf(false) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.SEND) }
    var sendPrefill by remember { mutableStateOf("") }
    var peerPrefill by remember { mutableStateOf("") }
    var backupWords by remember { mutableStateOf<String?>(null) }
    var backupVerify by remember { mutableStateOf(false) }

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
            } else {
                unlockError = "Authentication was not completed."
            }
        }
    }

    // Ask as soon as the lock screen appears, including after a relock. Nothing is asked
    // while the wallet is still being loaded or does not exist yet.
    LaunchedEffect(openLockEnabled, unlocked, state.bootstrapped) {
        if (openLockEnabled && !unlocked && state.bootstrapped) {
            unlockWith("Authenticate to open your wallet.")
        }
    }

    // Lock again whenever the app leaves the screen. The prompt itself keeps its own
    // lifecycle: it resets on stop and restarts on resume, so this cannot strand it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, openLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (openLockEnabled) {
                    unlocked = false
                    // Never leave the recovery phrase on screen behind a locked wallet.
                    backupWords = null
                }
                // Android can freeze or kill the node while the app is away, and a start
                // that failed leaves nothing running. Coming back asks for it again.
                Lifecycle.Event.ON_RESUME -> vm.onAppForeground()
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
     * Runs [action] only after the user proves it is them: payments, channel funding and
     * showing the recovery phrase. This always applies when the phone can authenticate,
     * independent of the optional lock-on-open. A phone with no screen lock cannot ask,
     * so the action runs directly.
     */
    val authorize: (String, () -> Unit) -> Unit = { reason, action ->
        val lock = appLock
        if (!lockAvailable || lock == null) {
            action()
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

    // Screenshots and the Recents thumbnail stay blocked. With the setting on the whole
    // wallet is covered; the recovery phrase forces it on even when the setting is off.
    val secureScreen = state.screenProtection || backupWords != null
    LaunchedEffect(activity, secureScreen) {
        activity?.let { ScreenSecurity.apply(it, secureScreen) }
    }

    // The node keeps running and stays in the foreground while the app is locked: only the
    // UI is gated, not the Lightning node.
    LaunchedEffect(state.running) {
        if (state.running) NodeService.start(context) else NodeService.stop(context)
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

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                onCreate = { wordCount ->
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
}
