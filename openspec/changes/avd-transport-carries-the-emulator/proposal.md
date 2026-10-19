# avd-transport-carries-the-emulator

Issue: — · Related: `docs/decisions/2026-10-19-avd-cache-is-the-transport-between-runners.md`,
`docs/decisions/2026-10-19-avd-transport-is-an-optimisation-provisioning-is-the-guarantee.md`

## What

Make the AVD reach the job that boots it, and make the job that boots it able to
produce the AVD on its own when the transport delivers nothing.

Concretely: the SDK root and the cache key are resolved once in the producer and
published as outputs; both consumers restore those outputs verbatim; a shared
script (`scripts/ci/provision-avd.sh`) defines what "provisioned" means and runs
in the producer *and* in every consumer; each consumer enables KVM on its own
runner; a gate reports any `${{ env.X }}` in a workflow whose name the file does
not define; and the emulator module asks `emulator -list-avds` what exists
before it tries to boot anything.

## Why

Measured at run `39253664636` (commit `d809ed4d`):

```
e2e (full, shard 1..4)   ERROR | 'emulator' binary not found in PATH.
                          ensure-emulator could not produce a ready device.
```

All four Maestro shards failed within a second of each other, on runners where
the `setup-avd` job had executed that exact binary ninety seconds earlier. The
transport between the two jobs carried nothing, and three defects sat behind
that one message:

1. **The cache paths named nothing.** They were written
   `${{ env.ANDROID_HOME }}/…`, and the `env` context does not hold the
   runner's environment — it holds the variables the *workflow* defines. The
   list expanded to `/system-images`, `/emulator` and `/platform-tools` at the
   filesystem root, so the producer saved an empty tree and the consumer
   restored an empty tree.
2. **The empty entry was permanent.** The surviving cache entry was 1659 bytes.
   A cache *hit* means "do not save", so every later run accepted that entry and
   never rewrote it. The producer installed the emulator correctly on its own
   runner and still could not replace the entry.
3. **The AVD name never reached the emulator.** The module boots the AVD named
   by `AVD` and defaults to `Medium_Phone`, an AVD this workflow never creates.
   The shard job never set `AVD`, so the right binary would have met the wrong
   AVD — a failure that reads like a boot problem rather than a misnamed one.

The same defect was visible in PR #740 *before* the merge: the PR's own e2e
smoke shard failed with the identical message. `main` carries no branch
protection, so nothing blocked the merge.

## What the first repair uncovered

The transport repair was verified by a real run (run `39259981571`), and it
worked: the cache restored 2.15 GB, `sdkmanager` had nothing left to download,
and the emulator binary was found. Two further defects stood behind it, in the
order the run revealed them:

1. **The AVD did not travel.** The shard found the emulator and the system
   image installed and still had to create the AVD — `userdata.img` is a
   sparse, mutable image, and it did not survive the cache. Creating the AVD
   took half a second. So the cached AVD was weight without transport, and the
   cache now carries only the immutable SDK content.
2. **KVM was enabled on the wrong machine.** The producer enables KVM for
   itself; that enabling does not travel with a cache, and the two jobs run on
   different runners. The shard's emulator therefore died with "x86_64
   emulation currently requires hardware acceleration! … This user doesn't have
   permissions to use KVM (/dev/kvm)" — a failure that reads like a missing
   emulator and is neither.
3. **`platform-tools` could not be restored.** The runner image ships it,
   root-owned, and tar could not set its mode or times: "Cannot utime:
   Operation not permitted". The files extracted and adb worked, but a cache
   that cannot be restored cleanly reports a failure nobody can act on. The
   image provides it; the cache does not need to.

None of the three was visible while the transport was broken: the shard died
before it reached any of them. The second run got as far as a booted
emulator — `Device: emulator-5554` — and then found the APK the workflow
downloads as a *directory* while the script required a file.

## The two real options

1. **Repair the transport and let the cache remain the only source.** Fix the
   path expressions, build the key in one place, version it so the corrupt
   entry is retired, and pass `AVD` to the emulator. Cost: a cache eviction, a
   system-image bump, or any future change to the cached layout reproduces the
   same failure — and it reproduces as four red shards whose log points at the
   emulator rather than at the transport.

2. **Repair the transport and make provisioning the guarantee.** The cache
   becomes the fast path: a warm cache restores the emulator, the system image
   and the AVD, and the provisioning script does nothing. A cold cache makes
   the shard install the emulator and create the AVD for itself, which costs
   minutes instead of the build. The producer and the consumers run the same
   script, so the two sides cannot disagree about what "provisioned" means.

Recommendation: 2. A cache is an optimisation; depending on it is a dependency on
an eviction policy that belongs to somebody else, and the failure mode of that
dependency is a red nightly that looks like a flaky test — the most expensive
kind of failure to investigate. Option 1 leaves the trap armed.

## What not to do

- **Do not fix the paths and stop there.** That is option 1, and it leaves the
  next eviction to reproduce this exactly.
- **Do not drop the cache.** Without it, four shards each download the system
  image on every run. The cache is worth keeping as the fast path; it is not
  worth trusting as the only path.
- **Do not re-spell the key on the consumer side.** Producer and consumer
  spelling the key independently is what let a version drift into a
  never-written entry in the first place.
- **Do not restore an old-layout entry as a fallback.** A `restore-keys` prefix
  that does not carry the version can match an entry written under the previous
  layout — the precise trap this change closes. The prefix carries the version;
  a miss falls through to provisioning.
- **Do not treat "the cache was warm" as the property under test.** No check
  can prove the cache was populated; only a run can. The property that matters
  is "this runner has the emulator and the AVD the workflow names", and that is
  what the provisioning script verifies.

## Consequences

- A cold shard now downloads the emulator and the system image instead of
  failing. That is minutes, not a build.
- The first run after this change writes a real cache entry; later runs restore
  it. The entry is large, and the repository's cache budget is finite.
- The cache key carries a version. Changing the set of cached paths means
  bumping it, because a cache entry cannot be repaired in place.
- A workflow that references an undefined `env` name is now a gate failure
  rather than a silent empty string.
- The emulator module refuses to start an AVD it cannot see, and says which
  AVDs the machine has.
