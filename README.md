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
`pubkey@host:port`, and nothing of the app ever appears in the notification bar: no
foreground service, no ongoing notification. When the wallet comes back to the screen it
reconnects and resyncs by itself.

The name is the design rule: zero LSPs, zero servers, zero telemetry.

The look is deep navy (Prussian blue): a `#060D24` canvas, `#0A1535` cards,
`#0E1C47` raised surfaces, hairline `#1C2F66` borders, royal-blue `#3B82F6` actions with
`#60A5FA` highlights, ice-white `#F1F5FF` type and green incoming amounts.

**Download.** Grab the APK from [Releases](https://github.com/CoreJBH/zerowallet/releases/latest)
and install it (allow "install unknown apps" for the app you open it with). Debug-signed,
so a reinstall needs the previous build removed first.

---

## What it does

- **Bitcoin mainnet by default.** Signet and Testnet3 are selectable in Settings for
  testing; a small network badge appears only when the wallet is not on mainnet.
- **Blockstream's Esplora as the default chain source, with automatic fallback.** Mainnet
  starts on `https://blockstream.info/api`. That endpoint can time out from a phone network,
  and ldk-node then fails the whole start with `FeerateEstimationUpdateTimeout`: so when the
  saved endpoint does not answer, the same start is retried on `https://mempool.space/api`
  and then `https://mempool.emzy.de/api`, and the node says which server it is reading. The
  endpoint that came up last is tried second next time, so the timeout is not paid again. An
  endpoint typed into Settings is the user's own choice and is never swapped for a public
  one. A different Esplora endpoint can be typed into Settings, and saving it restarts the
  node so the new source is in use right away instead of waiting for the next start.
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
- **One numbered box per word when restoring.** The phrase is entered in a 12- or 24-box grid
  (three columns, one word per box) instead of a single long field. As soon as the letters
  typed in a box name exactly one BIP-39 word, the box fills itself and the caret jumps to the
  next one; ambiguous prefixes (`act` -> act, action, actor, actress, actual) show tappable
  suggestion chips right above the keyboard. Deleting never triggers a jump, backspace in an
  empty box steps back one box, and pasting a whole phrase (spaces, newlines, commas or a
  numbered list) spreads it over the boxes, switching the grid to 24 words when the paste is
  longer than 12.
- **Encrypted channel backup.** The node store (channel manager and monitors, on-chain wallet,
  node keys) is packed into one `.zwbackup` file sealed with AES-256-GCM under a key derived
  from the recovery phrase. Android's own file picker saves it to Google Drive or anywhere
  else, or the share sheet does, with no Google Play Services and no OAuth. Restoring it on a
  replacement phone after entering the phrase brings the open channels back.
- **Never ₿0 while it starts.** A start takes seconds (the node opens its store and syncs
  with Esplora). The last known on-chain and Lightning balance, block height, sync time and
  payment list are kept on the device after every real sync and shown while the node comes
  up, so opening the wallet or switching back never flashes a zero balance. The first real
  sync replaces them.
- **A node that stays up and says why it stops.** A start that fails no longer leaves a bare
  "Stopped" and a zero balance: the reason is shown under the node status in Settings and as
  a banner on the home screen with a Try again button. If port 9735 cannot be bound (another
  node holds it, or the phone refuses it), the node is started again without a listening
  address so on-chain sync, payments and open channels keep working - and without a node
  alias, which LDK Node only accepts alongside a listening address. Every start and stop
  goes through one lock, so a restart picked while the node is coming up is queued instead
  of dropped. Coming back to the wallet quietly syncs a node that survived the screen being
  elsewhere, brings back one Android killed, and respects a node the user stopped - that
  stop is written to disk, so a killed process does not undo it.

## Security

- **Authentication before money moves.** Sending a payment, funding or closing a channel
  and showing the recovery phrase always ask for a biometric or the device screen lock
  first. One prompt accepts either a strong biometric or the screen lock on Android 11+;
  older versions ask for the fingerprint and offer the screen lock as the fallback. A phone
  with no screen lock cannot ask, and says so in Settings.
- **Optional lock on open.** Off by default, a switch in Settings makes the app ask every
  time it opens and after coming back from the background. The prompt is lifecycle-driven:
  it restarts cleanly on resume, so the Unlock button cannot go dead after backgrounding.
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
  are limited to the configured Esplora endpoint (or another public Esplora when the saved
  one is itself a public default that did not answer) and public price endpoints.

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
                                        └── last known balance cache (Prefs)
```

- `core/NodeManager.kt` owns the single `Node` instance, its event loop and every wallet
  action. Balances, payments, channels and peers are read from the node, never invented.
  A mutex serialises every start and stop.
- `core/NodeLifecycle.kt` holds the start and stop rules as plain Kotlin - when a return to
  the foreground brings the node back, when it only resyncs, when "Stop node" keeps it down,
  and the order of listening attempts (port 9735, then none) - so they are unit tested
  without a phone.
- `core/EsploraFallback.kt` orders the endpoints a start tries and tells a chain-source
  failure from a Lightning-port failure, with no Android APIs so it is unit tested.
- `core/WalletCache.kt` encodes the last known balances and history and hydrates the wallet
  state with them while the node starts.
- `core/SecureStore.kt` encrypts the BIP-39 mnemonic with a non-exportable Keystore key and
  Android backup is disabled for the app, so the ciphertext never leaves the device.
- `core/AppLock.kt` wraps `BiometricPrompt` with the screen-lock fallback and exposes the
  availability check the UI uses to explain the setting on phones without a lock.
- `core/ScreenSecurity.kt` applies `FLAG_SECURE` to the wallet window.
- `core/EsploraUrl.kt` holds the endpoint rules; `core/Prefs.kt` refuses to store anything
  that fails them.
- `core/FeeEstimates.kt` reads the standard Esplora `/fee-estimates` reply (confirmation
  target to sat/vB), so fee estimates work on any Esplora endpoint rather than only on
  mempool.space's numbered helper.
- `core/NodeLog.kt` rotates the LDK log file with a size cap.
- `core/Bip39.kt` implements BIP-39 generation and validation in plain Kotlin, covered by
  unit tests against the official vectors.
- `core/Market.kt` fetches the BTC price and Esplora fee estimates over HTTPS.

## Known limits

- **A recovery phrase restores on-chain bitcoin, not open channels.** Channel state lives on
  this phone (`ldk-node` storage). There is no hosted SCB or VSS service: the phone only
  writes a channel backup when the user saves one (Settings, encrypted with the phrase), and
  nobody recovers it for them. Without such a file a lost phone loses the funds in open
  channels. A backup is a snapshot too, so a channel that changed after it was saved may close
  when restored. The app says so before showing the phrase and at the top of the channel
  screen; keep channel amounts small.
- Channel funds need an on-chain reserve (about 1% of channel size) plus funding fees, and
  closing a channel costs an on-chain transaction.
- Testnet4 is not exposed by ldk-node 0.7.0, so Testnet3 is the test network offered.
- The LDK native library ships as an unstripped Rust build: the debug APK is about 55 MB
  for the two ARM ABIs.
- BOLT12 and inbound Lightning need network reachability; a phone behind carrier NAT cannot
  accept inbound connections without port forwarding.
- This is a self-custody wallet at `1.1.8`: keep amounts you are willing to lose while the
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
| Unit tests | `./gradlew testDebugUnitTest` | 157 tests: BIP-39 vectors, word suggestions and the restore-word grid (auto-completion, jumps, paste), backup encryption round trip and refusals, endpoint rules and the mainnet default, Esplora fee estimates, restart-on-endpoint-change, multi-Esplora fallback order and dedup, chain-source failure detection, cached balance hydration and history round trip, node start/stop rules (foreground recovery, quiet resync, manual stop, port 9735 fallback), log rotation, lock lifecycle |
| Android lint | `./gradlew lintDebug` | no errors |
| English-only repository | `python3 verify-english.py` | no non-English characters |
| Package / label | `aapt2 dump badging app-debug.apk` | `com.jbitmstr.purewallet`, label `ZeroWallet` |

## Repository layout

```
app/src/main/java/com/jbitmstr/purewallet/
  MainActivity.kt            single activity host, screenshot blocking
  PureWalletApp.kt           application graph
  WalletViewModel.kt         UI bridge
  core/                      node, node lifecycle rules, seed, app lock, channel backup, endpoint rules, log rotation, market data
  ui/                        home screen, lock screen, sheets, word-box setup and scanner
app/src/test/                BIP-39, restore-word grid, channel backup, endpoint, node lifecycle, log rotation and lock lifecycle tests
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
