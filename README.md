<p align="center">
  <img src="docs/icon.png" width="110" alt="PUREWALLET">
</p>

<h1 align="center">PUREWALLET</h1>

<p align="center"><b>Pure on-device P2P Lightning &amp; Bitcoin wallet. Zero LSPs. Zero data centers.</b></p>

<p align="center">
  <a href="https://github.com/JBITmstr/purewallet/releases/latest"><img alt="Android APK 1.0.0" src="https://img.shields.io/badge/Android-APK%201.0.0-3ddc84?style=flat-square"></a>
  <img alt="Kotlin 2.0.21" src="https://img.shields.io/badge/Kotlin-2.0.21-7f52ff?style=flat-square">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4?style=flat-square">
  <img alt="LDK Node 0.7.0" src="https://img.shields.io/badge/LDK%20Node-0.7.0-f7931a?style=flat-square">
  <img alt="minSdk 26" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square">
</p>

<p align="center">
  <img src="docs/preview.jpg" width="340" alt="PUREWALLET home screen on an Android phone: Lightning and on-chain balances, Scan / Send / Receive / Channels, empty payment history">
</p>

PUREWALLET is a native Android wallet (Kotlin + Jetpack Compose) built around
[LDK Node](https://github.com/lightningdevkit/ldk-node). The Lightning node, the on-chain
wallet and the keys all live on the phone: there is no account, no backend, no LSP and no
custodian anywhere in the payment path. Channels are opened peer to peer with a
`pubkey@host:port`, and the app keeps running in a foreground service so the node stays
reachable while it is off screen.

The look is deliberate True Black: `#000000` canvas, `#121417` / `#181A1F` elevated surfaces,
hairline `#23272F` borders, white type and green incoming amounts.

**Download.** Grab `purewallet.apk` from [Releases](https://github.com/JBITmstr/purewallet/releases/latest)
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
                                        └── foreground service keeps the node alive
```

- `core/NodeManager.kt` owns the single `Node` instance, its event loop and every wallet
  action. Balances, payments, channels and peers are read from the node, never invented.
- `core/SecureStore.kt` encrypts the BIP-39 mnemonic with a non-exportable Keystore key and
  Android backup is disabled for the app, so the ciphertext never leaves the device.
- `core/Bip39.kt` implements BIP-39 generation and validation in plain Kotlin, covered by
  unit tests against the official vectors.
- `core/NodeService.kt` is a `dataSync` foreground service, so peers stay connected while
  the app is not on screen.
- `core/Market.kt` fetches the BTC price and Esplora fee estimates over HTTPS.

## Security notes

- The mnemonic is generated with `java.security.SecureRandom` (128 bits) and stored only as
  AES-256-GCM ciphertext under a hardware-backed Keystore key.
- A wallet whose recovery phrase cannot be unlocked is never overwritten: the app refuses
  to replace it rather than starting a second wallet over the top of the first.
- The wallet does not phone home. No analytics, no crash reporting, no remote logging.
  Network requests are limited to the configured Esplora endpoint and public price
  endpoints.
- Channel funds need an on-chain reserve (about 1% of channel size) plus funding fees, and
  closing a channel costs an on-chain transaction.
- Losing the phone without the recovery phrase means losing the funds. Back up the words
  before receiving anything.

## Build

Requirements: JDK 17, the Android SDK (compileSdk 35), and a phone on Android 8.0
(API 26) or newer with an ARM CPU (`arm64-v8a` or `armeabi-v7a`).

```bash
# Debug APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Unit tests
./gradlew testDebugUnitTest
```

The repository also ships `.github/workflows/apk.yml`, which builds the same APK on Ubuntu
with JDK 17 and the Android SDK, runs the unit tests, and uploads the result as the
`purewallet.apk` artifact on every push to `main` and on `v*` tags.

## Verification

| Check | Command | Result |
| --- | --- | --- |
| Kotlin + Compose compilation | `./gradlew assembleDebug` | builds `app-debug.apk` |
| BIP-39 unit tests | `./gradlew testDebugUnitTest` | 10 tests, official vectors included |
| Workflow syntax | `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/apk.yml'))"` | parses |
| English-only repository | `python3 verify-english.py` | no non-English characters |
| Package / label | `aapt2 dump badging app-debug.apk` | `com.jbitmstr.purewallet`, label `PUREWALLET` |

## Limitations

- Testnet4 is not exposed by ldk-node 0.7.0, so Testnet3 is the test network offered.
- The LDK native library ships as an unstripped Rust build: the debug APK is about 55 MB
  for the two ARM ABIs.
- BOLT12 and inbound Lightning need network reachability; a phone behind carrier NAT cannot
  accept inbound connections without port forwarding.
- This is a self-custody wallet at `1.0.0`: keep amounts you are willing to lose while the
  peer-to-peer flows get real-world mileage.

## Repository layout

```
app/src/main/java/com/jbitmstr/purewallet/
  MainActivity.kt            single activity host
  PureWalletApp.kt           application graph
  WalletViewModel.kt         UI bridge
  core/                      node, seed, market data, QR, formatting, service
  ui/                        home screen, sheets, setup and scanner
app/src/test/                BIP-39 unit tests
docs/icon.png                launcher icon artwork
docs/preview.jpg             the screenshot above
.github/workflows/apk.yml    CI build
verify-english.py            English-only guard
```

## Contributors

- **[@JBITmstr](https://github.com/JBITmstr)** - creator, product direction and design
- **Gilbert ([Pickle](https://pickle.com))** - AI co-builder: architecture, Kotlin/Compose
  implementation, build verification and review

Third-party components: [LDK Node](https://github.com/lightningdevkit/ldk-node)
(MIT / Apache-2.0), [ZXing](https://github.com/zxing/zxing) (Apache-2.0), AndroidX and
CameraX (Apache-2.0).
