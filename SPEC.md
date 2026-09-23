# Creds — Design Specification

Android password and credential manager. Local-only, zero-knowledge, offline-first.
Structured so the domain layer can move to Kotlin Multiplatform if iOS happens.

Status: design locked. Implementation in checkpoints (see §12).

---

## 1. Platform and modules

Pure Android (Kotlin, Compose). Pure-Kotlin modules hold everything portable, so an
iOS port later is an added sourceSet rather than a rewrite.

| Module | Type | Contents |
|---|---|---|
| `:core:model` | pure Kotlin | Item, Field, Template, Tag, value classes |
| `:core:domain` | pure Kotlin | generator, audit rules, TOTP, Enpass/CSV parsers, PSL matching |
| `:core:crypto` | Android lib | Argon2id, key hierarchy, Keystore, AES-GCM, secure memory |
| `:core:data` | Android lib | Room + SQLCipher, DAOs, repositories, DataStore prefs |
| `:core:ui` | Android lib | theme, design system, shared composables |
| `:feature:autofill` | Android lib | AutofillService, node classification, trust store |
| `:app` | application | navigation, feature screens, DI graph |

`:core:model` and `:core:domain` must not import anything from `android.*`.
That constraint is the entire iOS story; a lint rule enforces it.

- minSdk 28 (StrongBox, `setUnlockedDeviceRequired`, native BiometricPrompt)
- compileSdk 37.2, targetSdk 36
- Gradle 9.7.1 (checksum-pinned), AGP 9.4.0, Kotlin 2.2.10, KSP 2.2.10-2.0.2
- Hilt, Compose BOM 2026.08.00, single Activity + Navigation Compose

AGP 9 supplies Kotlin compilation itself, so no `kotlin-android` plugin is applied and
the Kotlin version is pinned to the KGP that AGP 9.4.0 bundles. Bumping Kotlin means
bumping AGP, or overriding the KGP classpath and re-checking KSP compatibility.

## 2. Storage model

Local-only. The vault never leaves the device except as a user-initiated encrypted export.
No account, no server, no telemetry, no analytics.

Records carry `uuid` + `updated_at` + soft-delete tombstones, so a sync layer can be added
later without a data migration.

## 3. Data model — templated typed fields

An item is a template plus an ordered list of typed fields. This is a superset of the
Enpass export format, so `backup.json` imports losslessly.

```
items(uuid PK, template, title, subtitle, note_enc, icon,
      favorite, archived, trashed, created_at, updated_at)

fields(uid PK, item_uuid FK, type, label, value_enc, sensitive, ord,
       deleted, updated_at, search_text, reuse_hmac, sha1_prefix)

field_history(id PK, field_uid FK, value_enc, replaced_at)

tags(id PK, name, color)
item_tags(item_uuid, tag_id)                       -- many-to-many

item_associations(item_uuid, kind, value, cert_sha256, confirmed_at)
audit_scores(field_uid PK, score, guesses_log10, checked_at)
```

Templates are built-in and fixed (login, card, bank account, note, wifi, identity,
passport, server, misc). They decide which fields the editor renders — what the item *is*.

Tags are user-created, free-form, many-to-many — where the user *filed* it.
The two are orthogonal: renaming a tag never touches item structure.

Smart lists: Favorites, Recently used, Weak, Reused, Breached, Trash.
Filters compose, e.g. `template=login AND tag=#work`.

## 4. Cryptography

```
master password --Argon2id(salt 128b, m=64MiB, t=3, p=2)--> MK (32B)

VK (32B, SecureRandom) is sealed independently by:
  [1] MK                          AES-256-GCM      always
  [2] Keystore biometric key      AES-256-GCM      if biometric unlock enabled
  [3] Argon2id(recovery phrase)   AES-256-GCM      if recovery kit opted in

VK --HKDF-SHA256("db")-----------> SQLCipher passphrase
VK --HKDF-SHA256("field"|uuid)---> per-item AES-256-GCM key -> value_enc
VK --HKDF-SHA256("reuse")--------> HMAC key for reuse fingerprints
```

- Changing the master password re-seals VK. The database is never rekeyed.
- Sensitive values carry their own AES-GCM layer *inside* the encrypted database, so a
  decrypted page, a stale WAL, or a heap dump does not expose plaintext.
- Keystore key: `setUserAuthenticationRequired(true)`,
  `setInvalidatedByBiometricEnrollment(true)`, `setUnlockedDeviceRequired(true)`,
  StrongBox when the device provides it.
- VK is zeroed on lock; secrets travel as `CharArray`/`ByteArray` and are wiped after use.

## 5. Search and audit over encrypted data

Encrypted values cannot be queried, so derived columns do the work:

- `sensitive = 0` → `search_text` holds the value, indexed in FTS5. Titles, usernames,
  emails, URLs and notes are what people actually search by.
- `sensitive = 1` → `search_text` is NULL. Secrets are never indexed.
- `reuse_hmac = HMAC-SHA256(VK_reuse, NFKC(value))` → reuse detection is a `GROUP BY`
  with zero decryption.
- `sha1_prefix` → breach lookup without decrypting.

### Strength engine

`PasswordStrengthEstimator` is a pure-Kotlin interface in `:core:domain`.
Android implementation wraps zxcvbn (`com.nulab-inc:zxcvbn`) for real pattern matching —
dictionary words, keyboard walks, l33t substitution, dates, repeats — and crack-time
estimates. Swapping the implementation is how iOS gets the same scores.

### Checks (v1)

| Check | Rule |
|---|---|
| Weak | zxcvbn score ≤ 2 |
| Reused | `reuse_hmac` shared by more than one item |
| Breached | bloom hit, or HIBP range match when enabled |
| Stale | password unchanged for more than 365 days |
| Missing 2FA | login has a URL but no `totp` field |
| Insecure URL | `http://` |

Aggregated into a 0–100 vault health score. Every finding has a fix action.

Deferred (revisit — the vault is 25% finance items): expired cards, trivial PINs,
duplicate items, empty passwords.

## 6. Breach checking

Offline first: a bundled bloom filter of the top ~1M breached passwords (~2MB asset,
~0.1% false positive rate). Results are instant, require no consent, and generate no
traffic. This covers essentially every human-chosen password.

Online is opt-in and off by default: HIBP k-anonymity range query, five SHA-1 hex
characters leave the device, `Add-Padding: true` so response size leaks nothing.
The app is fully functional if it never receives network access.

## 7. Autofill

Full Autofill Framework. `minSdk 28`, inline suggestions on 30+ with dropdown fallback.

```
onFillRequest -> classify nodes (autofillHints first, then heuristics)
              -> resolve owner via trust store
              -> locked?   auth dataset "Unlock to fill" -> BiometricPrompt
              -> unlocked? inline (API 30+) / dropdown
onSaveRequest -> "Save login for bank.com?" -> new item
```

Datasets: login (username + password), TOTP code, payment card, identity.

### Trust model

Native apps are matched on package name **and** the SHA-256 of the signing certificate,
recorded on the item the first time the user confirms a fill (trust on first use).
A repackaged or spoofed app has a different certificate hash and receives nothing.

Browsers: only packages on a curated allowlist may use `webDomain`, matched at eTLD+1
against a bundled Public Suffix List. `login.bank.com` matches `bank.com`;
`bank.com.evil.co` never does.

A mismatch degrades to "no suggestion". It never degrades to a wrong fill.

Passkeys (Credential Manager provider, Android 14+) are milestone 2, not v1.

## 8. Generator

Three modes, all drawing from `SecureRandom` with **rejection sampling, never modulo** —
modulo bias is the classic generator bug.

- **Random** — length 8–128, per-class toggles, exclude ambiguous `l I 1 O 0`,
  optional require-one-of-each (enforced by rejection, so the guarantee does not skew
  the distribution)
- **Passphrase** — EFF large wordlist (7776 words, ~100KB asset), word count, separator,
  capitalization, optional injected digit
- **PIN** — numeric, configurable length

Live entropy readout and zxcvbn score. The last 20 generated values are kept encrypted
with a 24-hour expiry, so "I generated it, changed it on the site, then lost it" is not a
way to lock yourself out.

## 9. Lock and hardening

| Policy | Default |
|---|---|
| Lock on backgrounding | yes, after a 60s grace period (autofill round-trips) |
| Lock on screen off | yes |
| Idle timeout | 5 minutes |
| Failed-unlock backoff | exponential, 1s → 300s cap |
| Wipe after N failures | available, off |
| `FLAG_SECURE` | on, user-toggleable |
| Clipboard | `EXTRA_IS_SENSITIVE`, cleared after 30s |
| `allowBackup` | false |
| Cleartext traffic | disabled |
| Root / debugger detected | dismissible warning banner, never a block |

Blocking on root is trivially bypassed and only punishes honest power users, so the app
warns and continues.

## 10. Recovery

There is no password reset, no backdoor, and nothing on any server to subpoena.

Setup states this plainly, then offers an optional recovery kit: a 24-word BIP39-style
phrase (256 bits) that seals a third copy of VK, shown once, intended for paper.
Off by default — it is a second full-strength door, and a phrase screenshotted into a
notes app is strictly worse than no phrase.

Encrypted export is the recommended backup for everyone.

## 11. Import and export

```
in:   enpass .json   lossless, dedicated parser
      *.csv          column-mapping UI (Chrome, Firefox, Bitwarden, LastPass, KeePass)
      .vault         our own encrypted format

out:  .vault         Argon2id + AES-256-GCM, the default and only one-tap option
      .json / .csv   PLAINTEXT — typed confirmation, explicit warning
```

Import parses in memory, dedupes by (title, username), previews a diff, then commits.
No intermediate plaintext file ever touches disk.

`backup.json` in this repo — 16 items, 5 categories, 45 text and 21 section fields,
9 TOTP secrets, 3 payment cards — is the v1 import acceptance test.

## 12. Build checkpoints

Each checkpoint is independently buildable and reviewed before the next begins.

1. Gradle scaffold, version catalog, 7 modules — `./gradlew tasks`
2. Crypto: Argon2id, key hierarchy, Keystore, secure memory — unit tests with known-answer vectors
3. Data: SQLCipher Room schema, DAOs, repositories — schema test
4. Setup, unlock, lock policy — installable
5. Vault list, templates, tags, search
6. Item view and edit, TOTP, field history
7. Generator
8. Audit engine and dashboard
9. Autofill and trust store
10. Enpass import — run last, against finished code, with the real vault

## 13. Assumptions taken without asking

Raise any of these and they change.

- Application ID `dev.creds.vault`, app name **Creds**
- Hilt for DI; single Activity; Navigation Compose; Material 3 with dynamic color on 31+
- `com.lambdapioneer.argon2kt:argon2kt` 1.6.0 for Argon2id (JNI, no NDK build required)
- `net.zetetic:sqlcipher-android` 4.18.0 with Room 2.8.4
- kotlinx-serialization for parsers; OkHttp only for the opt-in HIBP call
- No crash reporting and no analytics SDK of any kind
- `backup.json` is treated as live credential data: never logged, never printed,
  never copied outside this directory
- Selecting several tags narrows with AND, like every other filter dimension
- Recently used, Weak, Reused and Breached smart lists are hidden until the data behind
  them exists (checkpoints 6 and 8); the query builder refuses them rather than guessing
- Trashed items leave the FTS index, so the trash is searched by title and subtitle only
- Tag names are trimmed, lose a leading `#`, cap at 32 code points, and are unique
  ignoring case (Unicode case folding, not SQLite's ASCII-only `NOCASE`)
- A list or card subtitle is only ever drawn from non-sensitive fields: it is plaintext
  in the database and indexed, so "•••• 1234" is deliberately not offered
- Debug builds carry an "Add sample items" action with invented data; release builds
  compile a stub
- Tapping an item opens a read-only view; editing is a separate, explicit step. Secrets
  there start masked behind a fixed-width mask that does not reveal their length
- Every copied value, secret or not, is marked sensitive and cleared after 30s. The clear
  is best effort: it is lost if the process dies first, and when Android will not let a
  backgrounded app read the clipboard it clears without checking the clip is still ours
- Field history is kept for sensitive fields only and is read from the item view
- Locking discards an unsaved edit: a draft is plaintext, and it does not outlive the vault
- Any item can gain or lose fields beyond its template's defaults; removed fields are
  tombstoned with their history, and imported section headings are kept
- The generator remembers a value only when it is copied or put into an item, not every
  draw; the same value taken twice in a row is kept once. Expired values are deleted the
  next time the list is read or written, so one can outlive its 24 hours on disk,
  still encrypted, until then
- Random-password symbols leave out quotes, backslash, backtick and space, which break
  sign-up forms, shell pastes and CSV exports
- The entropy readout describes the generator's options, exactly: "at least one of each"
  is counted by inclusion–exclusion, not approximated. Passphrase capitalisation and
  separator add nothing; an injected digit adds log₂(words × 10)
- The EFF wordlist is bundled verbatim, SHA-256
  `addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e`, and parsed strictly
- Generator options are stored in plain DataStore outside the vault: they describe a
  policy, not a secret
- The editor offers the generator on password fields only
- The audit treats `password` and card transaction-password fields as passwords. PINs sit
  out weak, breached, reused and stale until the deferred trivial-PIN check exists: every
  four-digit PIN would otherwise be flagged, and an audit that flags everything is ignored
- Archived items are left out of the report and the health score; trashed items never
  count as a second use of a password
- Health score: each audited item starts at 100 and loses breached 100, reused 60, weak 50,
  stale 15, insecure website 15, missing 2FA 10 — each kind once per item, floored at 0.
  The vault score is the average. A vault with nothing to audit has no score, not 100
- `http://` websites on local hosts (localhost, private IPv4 ranges, `.local`, `.lan`,
  `.internal`, `.home.arpa`) are not flagged: there is no https to switch them to
- Missing 2FA applies to logins with a website only, and cannot be dismissed yet
- "Use https" applies in one tap, as an ordinary edit, so it shows in the item and can be
  edited back
- Websites marked sensitive skip the URL checks, since reading them would mean decrypting
- The offline breach list is SecLists' `xato-net-10-million-passwords-1000000.txt` at
  commit `c205c36a445bff37f8e58a9ec829105cd4975c58` (MIT; SHA-256 `424a3e03…af51`),
  built into a 1.8MB Bloom filter at 0.1% false positives by
  `./gradlew :core:domain:buildBreachFilter -Pinput=<list> -Psha256=<hash>`
- The online check runs only for passwords not checked since they last changed, so an
  unchanged vault makes no requests even with it on. A failure leaves offline results in
  place, and an online miss never clears an offline hit
- Cached audit results record the rules version that produced them; a build that changes
  the rules or the breach list rescores the whole vault
- Autofill matches websites against an item's saved website fields and confirmed domains
  at eTLD+1, using the Public Suffix List (github.com/publicsuffix/list at commit
  `3955e3ec29b94c3cca7bd4509c5f14a7c0959e26`, private domains included, so two
  `github.io` sites are different sites). Websites marked sensitive are not matched
- The browser allowlist is Google's Credential Manager privileged-apps list (release
  signatures only), minus non-browsers, debug builds, and `com.android.browser`, whose
  listed key is the public AOSP test key. A browser off the list is treated as an app
- Apps are never matched by name or by website. The first fill in an app goes through
  "Search Creds…", which asks before remembering the app's package and signing key;
  saving a login from an app counts as that confirmation. A rotated key keeps trust only
  through the platform-verified signing lineage
- Payment cards and identities are offered on any card or address form, since they are
  not tied to a site; they still fill only when chosen
- Creds declares launcher apps visible (`<queries>`) so it can read the signing key of
  the app being filled, rather than requesting `QUERY_ALL_PACKAGES`
- PIN-type fields and new-password fields are never filled; a new password is what gets
  offered for saving
- Trusted apps and sites are listed on the item and can be removed there
