---
title: The secure-storage flag names the store, not the hardware
date: 2026-11-04
status: accepted
tags: [security, secure-storage, desktop, jvm]
---

# The secure-storage flag names the store, not the hardware

## Context

`SecureStoragePort` carried a member called `isHardwareBacked()`. On the desktop it
answered `IS_LINUX && secretToolAvailable()` — a keyring is there, and the host is
Linux. After the migration to java-keyring that value stopped meaning what the name
said, twice over:

- a keyring's presence says nothing about hardware backing. The Secret Service, the
  macOS Keychain and the Windows Credential Manager are all credential stores, and any
  of them may itself be software-backed — the flag could not answer the question its
  name asked;
- `IS_LINUX` made the answer wrong rather than merely narrow. java-keyring speaks to
  the macOS Keychain and to the Windows Credential Manager as well, so a macOS or a
  Windows host *with* a keyring answered `false` and looked like the fallback.

The flag had no production reader: only the fakes and the KDoc mentioned it. The
accepted decision `2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store`
fixed what it is *for*: it is the only signal a caller gets that the store is not the
obfuscation fallback, and it must keep answering `false` there.

## Idea

1. Rename the member to the fact it answers: the platform's own secure store holds the
   secrets, or the derived-file fallback does.
2. Split it into two members — one about hardware backing, one about a keyring being
   present.
3. Keep the name and correct the value behind it.

## Decision

Option 1. The member is `usesPlatformKeyStore()`. On the JVM it answers
`secretToolAvailable()`; the `IS_LINUX` conjunct and the constant behind it are gone.
On Android it stays `true`: the Keystore is the platform's own store.

The KDoc states what the flag does *not* claim — a credential store may be
software-backed, so the flag names the store and not the strength of the hardware
behind it.

## Rationale

Option 2 was rejected because a hardware-backing member cannot be answered honestly on
either platform: the desktop's keyring may be software-backed, and Android's
`MasterKey` is Keystore-backed but hardware-backed only on a device with a TEE or a
StrongBox. Such a member could only ever answer `false` on the desktop and an
unverifiable `true` on Android, and nothing reads it. One member that answers one
question truthfully is the smaller surface, and it is the one that keeps the 2026-10-27
decision's rule intact: the fallback still answers `false`.

Option 3 was rejected because the name is the defect. A member called
`isHardwareBacked()` invites a reader to believe the store is hardware-backed, and a
caller who has to know the implementation to read the name correctly had a naming
problem, not a value problem.

Dropping the OS-name conjunct is the part that changes behaviour: a macOS or a Windows
host with a keyring now answers `true` where it used to answer `false`. No production
code reads the flag, so nothing user-visible changes.

## Consequences

- The port member is `usesPlatformKeyStore()`, and it names the store rather than the
  hardware behind it. Never read it as a hardware-attestation claim, and never give it
  a name that says "hardware".
- The JVM answer follows the backend alone: the OS credential store when there is one,
  the derived-file fallback otherwise. Never gate it on `os.name` again — which
  credential stores exist is not the operating system's business.
- The fallback keeps answering `false`. That is the rule the 2026-10-27 decision fixed
  in force; do not change it to `true` to make a UI badge look better.
- A port implementor answers "which store holds the secret", not "how strong is the
  hardware".

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/security/SecureStoragePort.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/security/JvmSecureStorage.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/security/JvmSecureStorageKeyringTest.kt`
- Decision `2026-10-27-jvm-secure-storage-fallback-is-not-a-secret-store` — still in
  force for the fallback itself; only the flag's name and meaning changed.
- Issues #1676, #1680
