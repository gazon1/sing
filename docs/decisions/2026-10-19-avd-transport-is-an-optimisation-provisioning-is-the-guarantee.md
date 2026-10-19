---
title: "The AVD transport is an optimisation; provisioning is the guarantee"
date: 2026-10-19
status: accepted
tags: ["ci", "android", "emulator", "e2e"]
---

## Context

ADR `2026-10-19-avd-cache-is-the-transport-between-runners` decided that the GitHub
cache is the transport between the job that provisions an AVD and the jobs that boot
it, because runners do not share a filesystem and `needs` carries values, not state.
That decision was implemented, and the transport it produced **did not carry
anything**. Run `39253664636` is the measurement: all four Maestro shards failed,
each within a second of the others, with

```
ERROR    | 'emulator' binary not found in PATH.
ensure-emulator could not produce a ready device.
```

The producer job had executed that exact binary ninety seconds earlier.

Three defects sat behind that one message, and each was invisible while the one in
front of it was present:

| defect | why it was invisible |
|---|---|
| the cache paths were `${{ env.ANDROID_HOME }}/…` | the `env` context does not carry the runner's own environment variables, so the list expanded to `/system-images`, `/emulator` and `/platform-tools` at the filesystem root — a plausible-looking path list that names nothing |
| the key was re-assembled on each side | producer and consumer spelled the key from the same three pieces, so they agreed — about a key that was never written by the paths that mattered |
| the entry that existed held 1659 bytes | a cache **hit** means "do not save", so the empty entry was permanent: the next run found it, accepted it, and had nothing to restore |

A fourth defect was not in the cache at all. The emulator module boots the AVD named
by `AVD` and defaults to `Medium_Phone` — an AVD this workflow never creates. The
shard job never set `AVD`, so even a perfectly transported emulator would have booted
the right binary against the wrong AVD, and that failure would have looked like a
boot problem rather than a misnamed one.

The previous ADR named the missing piece itself: "**A cache miss on the consumer is
fatal, and there is no fallback.** … A `cache-hit == 'false'` branch that installs on
demand would close it. **That is the obvious next piece of work and it is not in this
change.**"

## Idea

A cache is an optimisation. A dependency on a cache is a dependency on an eviction
policy that belongs to somebody else. The thing that must be true for the Maestro
suite to run is not "the cache was warm" — it is "this runner has an emulator and
the AVD the workflow names".

## Decision

**The cache is the fast path; provisioning is the guarantee.**

1. **One place resolves the SDK root and builds the key.** `_setup-avd.yml` does it
   once, in a step whose outputs the job publishes, and the consumers read
   `needs.setup-avd.outputs.{sdk_root,cache_key,cache_key_prefix}` verbatim. No
   side re-spells a path or a key.
2. **The key carries a version.** `avd-<avd>-<device>-<system-image>-v3`. A cache
   entry written under an older layout cannot be repaired in place — keys are
   immutable — so a change to the set of cached paths retires the old entry by
   changing the key.
3. **The cache carries the SDK, not the AVD.** The cached paths are
   `system-images` and `emulator`. The AVD is created where it is used: its
   `userdata.img` is a sparse, mutable image, and it did not survive the
   cache — the first consumer of the entry that carried it found the emulator
   and the system image already installed — sdkmanager had nothing to
   download — and still had to create the AVD itself, in half a second.
   Carrying a mutable image between runners was never working; what it did was
   make the entry look like it carried the AVD.
   `platform-tools` is not cached either: the runner image ships it, and the
   image's copy is root-owned, so restoring over it made tar fail with
   "Cannot utime: Operation not permitted" — a cache that cannot be restored
   cleanly reports a failure nobody can act on.
4. **One script defines "provisioned".** `scripts/ci/provision-avd.sh` installs
   `emulator`, `platform-tools` and the system image, creates the AVD, and verifies
   both the binary and `emulator -list-avds`. It is idempotent, and it runs in the
   producer *and* in every consumer: a consumer whose cache restore came back empty
   provisions for itself instead of dying.
5. **The AVD name reaches the emulator through `AVD`**, taken from the producer's
   `avd_name` output, so the module cannot boot an AVD this workflow never created.
6. **The module asks the tool what exists before it boots anything.**
   `emulator -list-avds` is the only authority on which AVDs a machine has, and
   `start_avd` consults it first. A name is a claim; the list is the fact. The
   refusal it produces — "AVD 'X' does not exist on this machine. Available: …" —
   is the difference between an answer and a five-minute boot attempt that ends
   with "the emulator was still not ready".
7. **The consumer enables KVM on its own runner.** The producer enables it for
   itself, and that enabling does not travel with a cache. Without it the
   emulator is found, the AVD is created, and the emulator then dies with
   "x86_64 emulation currently requires hardware acceleration! … This user
   doesn't have permissions to use KVM (/dev/kvm)" — a failure that reads like a
   missing emulator and is neither.
8. **The contract is pinned by a gate, not by a comment.** A repository-wide
   check reports every `${{ env.NAME }}` in a workflow whose name the file does
   not define, because the `env` context holds the variables a workflow defines
   and not the runner's environment. The specific transport is pinned by
   `scripts/tests/test_avd_transport.py`; the class of defect is pinned by
   `scripts/check-workflow-env-context.py`, which is registered as a blocking
   gate in both registries and carries a positive control.

## Rationale

Not "the cache, and trust it": the previous ADR's own consequence section says a
miss is fatal, and a fatal cache miss is a red nightly for a reason that has nothing
to do with the code under test. The failure mode of a cache dependency is that it
looks like a flaky test, which is the most expensive kind of failure to investigate.

Not "install in every shard unconditionally": the install is a multi-hundred-megabyte
download plus an AVD creation, and paying it four times per run when a warm cache
exists is exactly the waste the shared setup job was written to avoid. The script's
fast path — everything already present, do nothing — keeps the warm case at the cost
of one `test -x`.

Not "keep the key spelling in three files": a repeated string is a place to drift,
and this repository has an ADR about two lists that drifted apart. The version suffix
is the same idea: the thing that changes rarely should be written once, and the
thing that changes should announce itself.

Not "trust the cache silently": the previous failure was a cache step that existed,
looked authoritative, and carried nothing. The producer's key now names a version,
the test asserts the paths agree, and the provisioning script fails loudly with the
reason — so a transport that stops working says so, instead of producing four red
shards with a misleading message.

## Consequences

- **A cache miss now costs time instead of the build.** A cold shard downloads the
  system image and creates the AVD. That is minutes, not a red run.
- **The cache is still written, and it is still the fast path.** The `v3` suffix
  means the first run after this change writes a real entry; later runs restore it.
  The `v2` entry measured 2.15 GB.
- **The AVD no longer travels.** It is created on each runner, in half a second,
  and the cache carries only the immutable SDK content. The `v2` entry carried
  the AVD, and the AVD did not survive it.
- **Each consumer enables KVM for itself.** The producer's enabling does not
  travel with a cache, and the two jobs run on different machines.
- **The version suffix is manual.** Changing the set of cached paths means bumping
  it. That is a cost with a reason: an entry cannot be repaired in place, so a silent
  reuse would restore the wrong layout and look like it worked.
- **The test is a contract test, not a proof.** It proves the two sides agree on
  paths, key and AVD name. It cannot prove the cache was populated — only a run can.
- **The consumer now depends on `scripts/ci/provision-avd.sh` being present**, which
  is why both consumers check the repository out before provisioning.
- **A workflow that references an undefined `env` name is now a gate failure.**
  The check is deliberately not GitHub's exact scoping: a step in job A that
  references a variable defined only in job B passes. The failure this gate
  exists for is a name that resolves to nothing *anywhere*, and scoping
  subtleties have never been the cause.
- **The module refuses to start an AVD it cannot see.** A machine without the
  `emulator` binary, or without the named AVD, is reported in one line instead
  of consuming the whole boot budget.

## Links

- `docs/decisions/2026-10-19-avd-cache-is-the-transport-between-runners.md` — the
  decision this one completes, and the one whose "no fallback" consequence named
  this work
- `.github/workflows/_setup-avd.yml` — the producer: resolves the root, builds the
  key, provisions
- `.github/workflows/e2e.yml`, `.github/workflows/android-device-tests.yml` — the two
  consumers
- `scripts/ci/provision-avd.sh` — the single definition of "provisioned"
- `scripts/tests/test_avd_transport.py` — the contract test
- `scripts/check-workflow-env-context.py` — the gate for the class of defect
- run `39253664636` — four red shards, "'emulator' binary not found in PATH"
- run `39236069621` — the diagnostic that showed the binary absent on the shard
  runner while `setup-avd` had executed it
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — why a
  cache step that carries nothing is worse than no cache step
