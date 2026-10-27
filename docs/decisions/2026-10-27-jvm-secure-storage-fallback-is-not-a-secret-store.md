---
title: "The JVM AES-GCM fallback is obfuscation, not a secret store"
date: 2026-10-27
status: accepted
tags: [security, secure-storage, desktop, jvm]
---

# The JVM AES-GCM fallback is obfuscation, not a secret store

## Context

`JvmSecureStorage` has two paths. On Linux with `secret-tool` present it stores keys in
libsecret, which is protected by the login keyring and by a key this process cannot derive
from public data. Everywhere else — Linux without libsecret, macOS, Windows — it falls back to
`~/.config/singularity/secure.bin`, an AES-GCM file whose key is PBKDF2 over
`System.getProperty("user.home")` with a fixed salt (`singularity-v1`).

Issue #1232 points out what that means: `user.home` is not a secret and the salt is constant,
so anyone who can read `secure.bin` can derive the key offline. The fallback is a last resort
that is used exactly on the hosts where it is the only store.

The obvious-looking fix — raise the PBKDF2 iteration count — is worse than useless here. The
KDF cost is not the weakness; the weakness is that the key material is public. Raising
iterations buys no attacker resistance, and because it re-derives a *different* key, every
existing `secure.bin` entry becomes undecryptable. Every read then fails its GCM tag, which is
the corruption signal, so `SecureStoreRecovery` erases the file and the next write starts
empty. That destroys `backup.device_key_block` — the key that decrypts existing remote
backups — to gain nothing.

## Idea

1. Add an OS-keyring backend for macOS/Windows and a user-supplied passphrase prompt for hosts
   with no keyring.
2. Accept the fallback as-is and say plainly what it is.

## Decision

Option 2. The fallback stays as it is, is documented as obfuscation rather than protection, and
`ITERATIONS` stays at `100_000` with a comment on the constant explaining why it must not be
raised casually.

Concretely:

- The class KDoc and the `deriveKey` shortcut comment state that the fallback is not a secret
  store.
- Nothing that grants access to a remote system may be *added* to the fallback without a
  keyring, because there is nowhere better for it to go. What is already there, and is
  therefore exposed on a host without libsecret, is more than API keys:
  - `OpenAiConfig.KEY_OPENAI` — an API key that bills.
  - `backup.device_key` and `backup.device_key_block` (`DeviceKeyStore`) — the key that
    decrypts existing remote backups.
  - `google_calendar:<userId>:{access,refresh,expires}` (`SecureStorageGoogleCredentialStore`)
    — a Google OAuth refresh token, which is an open-ended grant on the user's calendar and
    the sharpest case here. `GoogleCredentialStore`'s own KDoc claimed "libsecret on desktop"
    and was corrected in the same change.
- A new secret of that kind needs the passphrase path from option 1 first.
- `isHardwareBacked()` keeps reporting `false` on the fallback, which is the only signal
  callers get that the store is not protected. Do not change it to `true` to make a UI badge
  look better.
- Changing the KDF parameters requires storing a KDF version in the file header and decrypting
  with the old parameters first. Until that exists, the parameters are frozen.

## Rationale

Option 1 is the correct end state and is the wrong next change. macOS and Windows keyring
backends are new platform code with no test seam on this host, and a passphrase prompt is a new
user-facing flow (setup, unlock, forgot-passphrase recovery, what happens to remote backups
when it is lost). Neither is justified by a bug that only bites a local attacker who can
already read the user's home directory.

What the fallback does buy is real: it keeps secrets out of DataStore, out of logs, out of
screenshots and out of backups of the app data directory, which is where they were before
`2026-09-05-secret-storage-split`. That is worth keeping. Calling it "hardware-backed" was the
part that was wrong.

Raising the iteration count was actively harmful: it silently discards the backup device key on
every host using the fallback.

## Consequences

- A host without libsecret stores its secrets in a file readable by anyone who can read that
  user's home directory. This is accepted and now written down — including the Google refresh
  token, which is the consequence that matters most: a calendar grant, not a cached string.
- The AES-GCM layer still does real work: it bounds what a corrupted or hostile file can do
  (lengths are validated before allocation) and turns a failed write into a single
  erase-and-retry instead of a permanently broken store.
- The upgrade path is a keyring backend plus a passphrase flow; issue #1232 stays the tracker
  for it, narrowed to "credential storage on hosts without a keyring".

## Links

- `shared/src/jvmMain/kotlin/com/singularity/todo/core/security/JvmSecureStorage.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/SecureStoreRecovery.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/DeviceKeyStore.kt`
- ADR `2026-09-05-secret-storage-split`
- ADR `2026-10-19-startup-blob-reader-recovery`
- Issue #1232
