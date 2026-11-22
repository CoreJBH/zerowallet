# Release signing

The release key for ZeroWallet is **not** in this repository, and must never be.

`keystore/zerowallet-release.jks` and `keystore/keystore.properties` are both ignored by
`.gitignore` (`*.jks`, `*.keystore`, `keystore.properties`). They are what a local
`./gradlew assembleRelease` reads; CI reads the same four values from repository secrets:

| Gradle property / secret | `keystore.properties` key | What it is |
| --- | --- | --- |
| `ZEROWALLET_KEYSTORE` | `storeFile` | path to the `.jks` |
| `ZEROWALLET_KEYSTORE_PASSWORD` | `storePassword` | store password |
| `ZEROWALLET_KEY_ALIAS` | `keyAlias` | key alias (`zerowallet`) |
| `ZEROWALLET_KEY_PASSWORD` | `keyPassword` | key password |

Back both files up somewhere you control. Android identifies an app's updates by the signing
key: losing it means no future build can install over the one already on a phone, and every
user has to remove the app and restore from the recovery phrase.

To give CI the key, base64 the keystore and add the four repository secrets:

```bash
base64 -w0 keystore/zerowallet-release.jks   # -> ZEROWALLET_KEYSTORE_BASE64
```

Until those secrets exist, `.github/ci/apk-release.yml` stops at its signing check with the
names of what is missing. That is deliberate: it does not fall back to a debug key, and it does
not publish an unsigned APK.
