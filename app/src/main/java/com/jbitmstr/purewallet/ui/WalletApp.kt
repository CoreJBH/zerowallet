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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jbitmstr.purewallet.WalletViewModel
import com.jbitmstr.purewallet.core.NodeService
import com.jbitmstr.purewallet.ui.screens.ScannerScreen
import com.jbitmstr.purewallet.ui.screens.SetupScreen
import com.jbitmstr.purewallet.ui.sheets.BackupSheet
import com.jbitmstr.purewallet.ui.sheets.ChannelsSheet
import com.jbitmstr.purewallet.ui.sheets.ReceiveSheet
import com.jbitmstr.purewallet.ui.sheets.SendSheet
import com.jbitmstr.purewallet.ui.sheets.SettingsSheet
import com.jbitmstr.purewallet.ui.theme.Pal

private enum class ActiveSheet { NONE, SETTINGS, SEND, RECEIVE, CHANNELS }

private enum class ScanPurpose { SEND, PEER }

@Composable
fun WalletApp(vm: WalletViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var sheet by remember { mutableStateOf(ActiveSheet.NONE) }
    var scanning by remember { mutableStateOf(false) }
    var scanPurpose by remember { mutableStateOf(ScanPurpose.SEND) }
    var sendPrefill by remember { mutableStateOf("") }
    var peerPrefill by remember { mutableStateOf("") }
    var backupWords by remember { mutableStateOf<String?>(null) }
    var backupVerify by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(state.running) {
        if (state.running) NodeService.start(context) else NodeService.stop(context)
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
                onCreate = {
                    val words = vm.createWallet()
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
            onBackup = {
                backupWords = vm.revealSeed()
                backupVerify = !state.backedUp
            },
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.SEND -> SendSheet(
            vm = vm,
            state = state,
            initialText = sendPrefill,
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
                backupWords = vm.revealSeed()
                backupVerify = !state.backedUp
            },
            onClose = { sheet = ActiveSheet.NONE }
        )
        ActiveSheet.CHANNELS -> ChannelsSheet(
            vm = vm,
            state = state,
            initialPeer = peerPrefill,
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
