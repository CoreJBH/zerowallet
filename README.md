<p align="center">
  <img src="docs/icon.png" width="110" alt="ZeroWallet">
</p>

<h1 align="center">ZeroWallet</h1>

<p align="center"><b>Pure on-device P2P Lightning &amp; Bitcoin wallet. Zero LSPs. Zero servers. Zero telemetry.</b></p>

<p align="center">
  <a href="https://github.com/CoreJBH/zerowallet/releases/latest"><img alt="Android APK" src="https://img.shields.io/badge/Android-APK-3ddc84?style=flat-square"></a>
  <img alt="Kotlin 2.0.21" src="https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4?style=flat-square">
  <img alt="LDK Node 0.7.0" src="https://img.shields.io/badge/LDK%20Node-0.7.0-f7931a?style=flat-square">
  <img alt="minSdk 26" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square">
</p>

<p align="center">
  <img src="docs/preview.jpg" width="340" alt="ZeroWallet home screen on an Android phone: Lightning and on-chain balances, Scan / Send / Receive / Channels, empty payment history">
</p>

ZeroWallet is a native Android wallet (Kotlin + Jetpack Compose) built around
[LDK Node](https://github.com/lightningdevkit/ldk-node). The Lightning node, the on-chain
wallet and the keys all live on the phone: there is no account, no backend, no LSP and no
custodian anywhere in the payment path. Channels are opened peer to peer with a
`pubkey@host:port`, and the app keeps running in a foreground service so the node stays
reachable while it is off screen.

The name is the design rule: zero LSPs, zero servers, zero telemetry.

The look is deliberate True Black: `#000000` canvas, `#121417` / `#181A1F` elevated surfaces,
hairline `#23272F` borders, white type and green incoming amounts.

**Download.** Grab the APK from [Releases](https://github.com/CoreJBH/zerowallet/releases/latest)
and install it (allow "install unknown apps" for the app you open it with). Debug-signed,
so a reinstall needs the previous build removed first.

---

## What it does

- **Bitcoin mainnet by default.** Signet and Testnet3 are selectable in Settings for
  testing; a small network badge appears only when the wallet is not on mainnet.
- **On-chain wallet.** Receive to a freshly derived address, send with Normal/Fast fee
  priorities taken from the network fee estimator, or sweep the whole balance.
- **Lightning.** Create BOLT11 invoices and BOLT12 offers, pay BOLT11 invoices, BOLT12
  offers and BOLT12 invoices, and use unified BIP-21 / Lightning QR requests.
- **Direct P2P channels.** Connect to a peer with `pubkey@host:port`, open a channel from
  the on-chain balance, then close cooperatively or force close. No LSP, no just-in-time
  channel service, no channel-opening fee to a third party.
- **QR everywhere.** Camera scanning for invoices, offers, BIP-21 URIs and peer addresses;
  QR rendering for everything the wallet hands out.
- **Live fiat value.** BTC/KRW (with a USD fallback) from public price endpoints, and a
  graceful dash instead of a stale number when the phone is offline.
- **Real history.** Payments are read from the node's own database; local invoice notes
  stay on the device.
- **12 or 24 words.** A new wallet can be created from 128-bit (12 words) or 256-bit
  (24 words) entropy; both restore the same way.

## Security

- **App lock.** The wallet asks for a biometric or the device screen lock when it opens and
  before anything that moves money: sending a payment, funding or closing a channel, and
  showing the recovery phrase. One prompt accepts either a strong biometric or the screen
  lock on Android 11+; older versions ask for the fingerprint and offer the screen lock as
  the fallback. The lock can be turned off in Settings, and relocks whenever the app leaves
  the screen.
- **No screenshots, no Recents thumbnail.** The window is `FLAG_SECURE`, so the wallet is
  blocked from screenshots and screen recording and blanked in the recent-apps list. The
  recovery phrase forces it on even if the setting is off.
- **Seed at rest.** The BIP-39 mnemonic is generated with `java.security.SecureRandom`,
  its entropy buffer is zeroed right after the words exist, and it is stored only as
  AES-256-GCM ciphertext under a non-exportable Android Keystore key. Android backup is
  disabled for the app.
- **HTTPS-only chain source.** A custom Esplora endpoint must be `https://`; plain `http://`
  is accepted only for a node on the phone itself (`127.0.0.1`, `::1`, `localhost`).
  Credentials, query strings and fragments in the URL are rejected, and a stored value that
  no longer passes is ignored in favour of the built-in default.
- **Bounded logs.** `logs/ldk-node.log` is capped at 2 MB and rotated with two archives while
  the node is stopped, so a long-running install cannot fill the phone with logs.
- **No phone-home.** No analytics, no crash reporting, no remote logging. Network requests
  are limited to the configured Esplora endpoint and public price endpoints.

## Architecture

```
Compose UI  ──  WalletViewModel  ──  NodeManager (singleton)
                                        │
                                        ├── ldk-node 0.7.0 (Kotlin/UniFFI + Rust .so)
                                        │     ├── BOLT11 / BOLT12 payments
                                        │     ├── channel manager, peer connections
                                        │     └── BDK on-chain wallet
                                        │
                                        ├── Esplora chain source (HTTPS)
                                        ├── gossip: Lightning P2P (default) or RGS snapshot
                                        ├── seed: AES-256-GCM, key in Android Keystore
                                        ├── app lock: BiometricPrompt + device credential
                                        └── foreground service keeps the node alive
```

- `core/NodeManager.kt` owns the single `Node` instance, its event loop and every wallet
  action. Balances, payments, channels and peers are read from the node, never invented.
- `core/SecureStore.kt` encrypts the BIP-39 mnemonic with a non-exportable Keystore key and
  Android backup is disabled for the app, so the ciphertext never leaves the device.
- `core/AppLock.kt` wraps `BiometricPrompt` with the screen-lock fallback and exposes the
  availability check the UI uses to explain the setting on phones without a lock.
- `core/ScreenSecurity.kt` applies `FLAG_SECURE` to the wallet window.
- `core/EsploraUrl.kt` holds the endpoint rules; `core/Prefs.kt` refuses to store anything
  that fails them.
- `core/NodeLog.kt` rotates the LDK log file with a size cap.
- `core/Bip39.kt` implements BIP-39 generation and validation in plain Kotlin, covered by
  unit tests against the official vectors.
- `core/NodeService.kt` is a `dataSync` foreground service, so peers stay connected while
  the app is not on screen.
- `core/Market.kt` fetches the BTC price and Esplora fee estimates over HTTPS.

## Known limits

- **A recovery phrase restores on-chain bitcoin, not open channels.** Channel state lives on
  this phone (`ldk-node` storage) and there is no SCB or VSS cloud backup. A lost or wiped
  phone loses the funds that are in open channels. The app says so before showing the
  phrase and at the top of the channel screen; keep channel amounts small.
- Channel funds need an on-chain reserve (about 1% of channel size) plus funding fees, and
  closing a channel costs an on-chain transaction.
- Testnet4 is not exposed by ldk-node 0.7.0, so Testnet3 is the test network offered.
- The LDK native library ships as an unstripped Rust build: the debug APK is about 55 MB
  for the two ARM ABIs.
- BOLT12 and inbound Lightning need network reachability; a phone behind carrier NAT cannot
  accept inbound connections without port forwarding.
- This is a self-custody wallet at `1.1.0`: keep amounts you are willing to lose while the
  peer-to-peer flows get real-world mileage.

## Build

Requirements: JDK 17, the Android SDK (compileSdk 35), and a phone on Android 8.0
(API 26) or newer with an ARM CPU (`arm64-v8a` or `armeabi-v7a`).

```bash
# Debug APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Unit tests and lint
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The repository also ships `.github/workflows/apk.yml`, which builds the same APK on Ubuntu
with JDK 17 and the Android SDK, runs the unit tests, and uploads the result as a build
artifact on every push to `main` and on `v*` tags (the workflow keeps its original
`purewallet.apk` artifact name).

## Verification

| Check | Command | Result |
| --- | --- | --- |
| Kotlin + Compose compilation | `./gradlew assembleDebug` | builds `app-debug.apk` |
| Unit tests | `./gradlew testDebugUnitTest` | 32 tests: BIP-39 vectors, endpoint rules, log rotation |
| Android lint | `./gradlew lintDebug` | no errors |
| English-only repository | `python3 verify-english.py` | no non-English characters |
| Package / label | `aapt2 dump badging app-debug.apk` | `com.jbitmstr.purewallet`, label `ZeroWallet` |

## Repository layout

```
app/src/main/java/com/jbitmstr/purewallet/
  MainActivity.kt            single activity host, screenshot blocking
  PureWalletApp.kt           application graph
  WalletViewModel.kt         UI bridge
  core/                      node, seed, app lock, endpoint rules, log rotation, market data
  ui/                        home screen, lock screen, sheets, setup and scanner
app/src/test/                BIP-39, endpoint and log rotation unit tests
docs/icon.png                launcher icon artwork
docs/preview.jpg             the screenshot above
.github/workflows/apk.yml    CI build
verify-english.py            English-only guard
```

The Kotlin package and `applicationId` stay `com.jbitmstr.purewallet` so an install over an
older build keeps the same app data and the same Android Keystore key that encrypts the
seed.

## Contributors

- **[@CoreJBH](https://github.com/CoreJBH)** - creator, product direction and design
- **Gilbert ([Pickle](https://pickle.com))** - AI co-builder: architecture, Kotlin/Compose
  implementation, build verification and review

Third-party components: [LDK Node](https://github.com/lightningdevkit/ldk-node)
(MIT / Apache-2.0), [ZXing](https://github.com/zxing/zxing) (Apache-2.0), AndroidX and
CameraX (Apache-2.0).
