# The node store at rest

The question this page answers: what is on the phone in plain text, what protects it, and
what this app can and cannot do about it in 1.2.0.

## What is on disk

| Path | Written by | Protected by |
| --- | --- | --- |
| `SharedPreferences/purewallet_seed` | `SecureStore` | AES-256-GCM with a key generated inside the Android Keystore, bound to the device lock where the phone allows it |
| `SharedPreferences/purewallet_settings` | `Prefs` | every value sealed with `SecretStore` (Keystore key); a value without the `v1:` marker is refused |
| `SharedPreferences/purewallet_payment_notes` | `PaymentNotes` | the same seal; a note that cannot be sealed is not saved at all |
| `files/ldk-node/<network>/` | ldk-node | **nothing at rest.** See below. |
| `cache/backups/*.zwbackup` | `BackupLayer` | AES-256-GCM with a key from the recovery phrase (PBKDF2, 210,000 rounds), deleted ten minutes after a share |
| `files/logs/ldk-node.log` | debug builds only | nothing, and it does not exist in a release build |

## The node store

`ldk-node` keeps its channel manager, channel monitors, on-chain wallet and its own keys seed
in a SQLite database plus a directory of monitors under `files/ldk-node/<network>/`. ldk-node
0.7.0 has no encrypted-store option: the files are what the library writes, and the Rust
`ldk` crate expects a `KVStore` it can read at start. Every Lightning wallet on Android that
embeds LDK has the same directory in the same shape.

So the store is readable by anything that can read the app's private data:

- **Not** another ordinary app: `files/` is the app's own sandbox, and the manifest sets
  `allowBackup="false"`, `fullBackupContent="false"` and keeps the store out of device-to-device
  transfer (`data_extraction_rules.xml`).
- **Not** a thief with a locked phone, on a device with file-based encryption (Android 7+, and
  the minimum here is 8.0): the files are encrypted with the device's own key while the phone
  is off or locked.
- **Yes** by: a rooted phone, `adb backup` from a phone where developer options allow it, a
  full-device forensic image with the credential, and any exploit that reaches app-private
  storage. On such a phone the channel monitors are enough to force-close channels and take
  the on-chain balance.

## Options this app considered

1. **Stop the node while the app is idle (background).** The node is what holds the channel
   keys decrypted; with it stopped, nothing is running that can sign. It does not remove the
   plaintext from the disk, so it changes the exposure of a *running process*, not of the
   files.
   *Implemented:* the user-facing `Stop node` in Settings, and the node never runs as a
   foreground service. *Not implemented:* an automatic idle stop, because a Lightning node that
   is not running cannot receive: a payment that arrives while the app is in the background
   would be failed by the sender, and a channel whose counterparty closes while this side is
   offline needs the node up to react at all. It is available as an explicit choice rather
   than a default.
2. **Encrypt the store at rest.** Wrap the whole `ldk-node` directory in a Keystore-sealed
   container and unpack it into memory (or a decrypted directory) at start.
   *Why it is not in 1.2.0:* LDK needs a real filesystem path, so the process would have to
   hold a decrypted copy anyway - which means either a second directory on disk that is
   plaintext while the node runs, or a FUSE-style in-memory filesystem this app does not have.
   The key would also have to be readable at start without the user, or the node could not
   come back on resume, which is exactly the property that makes the seed's own key worth
   binding to the device lock. A partial version - encrypt the store while the app is stopped
   - is a real option and needs a start/stop rewrite of the node's storage path, not a flag.
3. **Run the node in a separate process with its own storage.** Does not change what a
   forensic image contains, only who can read it while the app runs.
4. **Ask the user to keep channel amounts small.** The honest mitigation available today, and
   the one the app says out loud in the README: the exposure is the channel balance.

## What is done in 1.2.0

- The seed is sealed with a Keystore key that is bound to the device lock where the phone
  allows it, and a wallet whose key is unbound says so on the home screen and in Settings.
- Showing the phrase always opens the Keystore copy; no process-wide copy of the words exists.
- Exported channel backups exist only as ciphertext, their keys derived from the phrase, and
  the plaintext archive the export passes through is overwritten and deleted before the export
  returns.
- The node's own log file is never written in a release build.
- A wipe deletes the store, the logs, the backup cache and the Keystore keys together.

## What a user should take from this

Anyone who can read this app's private data on a rooted or unlocked phone can take the channel
balance. That is not a bug in this wallet; it is the boundary of what an on-device Lightning
node can promise without hardware it does not control. Keeping channel balances small, and
keeping the phone's own encryption and screen lock on, are the two things that do help.
