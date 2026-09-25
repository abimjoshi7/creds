# Vaultesque privacy policy

Effective 25 September 2026.

Vaultesque is an offline password manager for Android, published by Abim Joshi
(contact: abimjoshi7@gmail.com). This policy covers the Vaultesque app,
package `com.abimatwork.vaultesque`.

## The short version

Vaultesque does not collect, transmit, sell or share your data. There is no account,
no server, no cloud sync, no analytics and no crash reporting. Everything you store stays
on your phone, encrypted with a key that only your master password can open.

## What the app stores, and where

- **Your vault**: logins, cards, notes, attachments and every other item you add. It is
  kept in an encrypted database (SQLCipher, AES-256) in the app's private storage on your
  phone. The key is derived from your master password with Argon2id and is never written
  anywhere in the clear. We cannot read your vault and cannot recover it if you forget
  your master password.
- **Settings**: lock timeouts, generator options and similar preferences, stored in the
  app's private storage. They contain no vault contents.
- **Biometric unlock** (optional): a second copy of your vault key sealed by a key held in
  Android Keystore, released only by your fingerprint or face. Your biometric data never
  reaches the app; Android handles it.

Android backup is turned off for the app, so none of this goes to Google Drive or any
device-to-device transfer.

## Network use

Vaultesque makes one kind of network request, and only if you switch it on:

- **Online breach check (off by default).** When enabled in Password health, the app
  checks your passwords against the Have I Been Pwned "Pwned Passwords" service using
  k-anonymity: it sends only the first 5 characters of each password's SHA-1 hash to
  `api.pwnedpasswords.com` and compares the returned list on your phone. Your passwords,
  usernames and full hashes never leave the device. That service's own policy applies
  to the request: https://haveibeenpwned.com/Privacy

With the check off, the app makes no network requests at all. The built-in offline breach
check runs entirely on your phone.

## Autofill

When you enable Vaultesque as your Autofill service, Android shows the app the structure
of forms in other apps and websites so it can offer matching logins. This is processed on
your phone, only to fill or save a login, and is never stored except for what you choose
to save: the package name and signing certificate of apps and the web domains you confirm
as trusted for an item.

## Exports and attachments

Backups (`.vault` files) are encrypted with a password you choose, and are saved only where
you pick with Android's file picker. "Save a copy" of an attachment writes a plaintext file
only after a warning and only to a location you choose. What happens to those files after
that is up to you and the storage you chose.

## Permissions

- `INTERNET`: used only for the optional online breach check above.
- Biometric / fingerprint: used only for the optional biometric unlock.

## Children

Vaultesque is not directed at children under 13 and does not knowingly collect any data
from anyone.

## Deleting your data

Uninstalling the app, or clearing its storage in Android settings, permanently deletes the
vault from your phone. There is no copy anywhere else unless you exported one.

## Changes

If this policy changes, the new version will be published at this address with a new
effective date.

## Contact

abimjoshi7@gmail.com
