# Releasing Vaultesque on Google Play

Everything the Play Console asks for lives in this folder:

| File | For |
| --- | --- |
| `listing.md` | Store listing text, category, contact details |
| `app-content.md` | App content answers: data safety, content rating, ads, target audience |
| `privacy-policy.md` | Privacy policy, served from GitHub as the policy URL |
| `graphics/` | 512×512 icon and 1024×500 feature graphic |
| `screenshots/` | Six 1080×2400 phone screenshots, sample data only |

## 1. Signing key: decide this before the first upload

The release key is `~/.android/creds-release.jks` (certificate `CN=Creds, O=Creds`,
SHA-256 `bb:55:91:57:44:54:70:c4:35:92:8b:7e:cd:7f:5a:39:fe:8f:00:71:3c:b6:a2:1f:ed:dc:37:75:20:21:46:b2`).
The Vaultesque already sideloaded on the POCO F1 is signed with it and holds a live vault.

Play App Signing re-signs every app. **If Play generates its own app signing key, the Play
build cannot update the sideloaded app**: Android refuses the signature change, and the only
way onto the Play build would be uninstalling, which erases the vault.

So on the first release, under *App integrity → App signing*, pick **"Use a different key"
→ "Export and upload a key from Java keystore"** and upload `creds-release.jks` with the
PEPK tool Play provides. Play then signs with the same key the phone already trusts. After
that, create a separate upload key if you want one; losing the upload key is recoverable,
the app signing key is not.

Back up `creds-release.jks` and its passwords somewhere off this machine before uploading.
Without it no update can ever be signed again for the sideloaded install.

Alternative, if you would rather let Google generate the key: first export a `.vault`
backup on the phone, then uninstall the sideloaded app, install from Play, and import.

## 2. Build

Signing properties come from `~/.gradle/gradle.properties`
(`CREDS_RELEASE_STORE_FILE`, `CREDS_RELEASE_STORE_PASSWORD`, `CREDS_RELEASE_KEY_ALIAS`,
`CREDS_RELEASE_KEY_PASSWORD`).

```sh
./gradlew test lintRelease bundleRelease
# upload app/build/outputs/bundle/release/app-release.aab
```

Bump `versionCode` in `app/build.gradle.kts` for every upload; Play rejects a reused code.

Checks already done for 1.0.0 (versionCode 2):

- targetSdk 36, minSdk 28
- All native libraries (SQLCipher, Argon2, DataStore, graphics-path) are 16 KB page-aligned
  in both zip and ELF segments, as Play requires for targetSdk 35+
- R8 minified release installs and runs: vault create, item add, generator, settings,
  master password change, lock, unlock
- Permissions: `INTERNET`, `USE_BIOMETRIC`, `USE_FINGERPRINT` only; no advertising ID
- Android backup disabled; cleartext traffic disabled; not debuggable
- The R8 mapping file is bundled into the AAB, so Play deobfuscates crash stacks

## 3. Play Console, first time

1. Create app: name Vaultesque, app, free, default language English (US).
2. App content: follow `app-content.md`.
3. Store listing: follow `listing.md`, upload `graphics/` and `screenshots/`.
4. App signing: see section 1.
5. New personal developer accounts must run a **closed test with at least 12 testers for
   14 consecutive days** before production access is granted. Start a closed testing
   track with the AAB, add testers by email or Google Group, and apply for production
   once the 14 days are up.
6. Then roll out to production, ideally staged (e.g. 20%).

## 4. Before each later release

- `./gradlew test lintRelease` and the instrumented suites (see below)
- Bump `versionCode` and `versionName`
- Recheck `app-content.md` if networking, permissions or SDKs changed

## Testing on the POCO F1

The phone holds a real vault in the installed Vaultesque. Never install debug builds or older
code over it and never uninstall it. Library test APKs are safe, since each is its own package:

```sh
./gradlew :core:crypto:assembleDebugAndroidTest :core:data:assembleDebugAndroidTest
adb install -r -t core/crypto/build/outputs/apk/androidTest/debug/crypto-debug-androidTest.apk
adb install -r -t core/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
adb shell am instrument -w dev.creds.vault.core.crypto.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w dev.creds.vault.core.data.test/androidx.test.runner.AndroidJUnitRunner
```

The app's UI suite needs the debug app itself, so run it on an emulator:

```sh
./gradlew connectedDebugAndroidTest   # with only the emulator attached
```
