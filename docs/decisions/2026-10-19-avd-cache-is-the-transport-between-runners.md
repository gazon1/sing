---
title: "GitHub runners do not share a filesystem, so the cache is the transport"
date: 2026-10-19
status: open
tags: ["ci", "android", "emulator", "e2e"]
---

## Context

`_setup-avd.yml` says it "sets up the AVD once and shares it across all matrix shards". It did not, and could not: it runs in its own job, on its own runner, and hands the caller three strings.

```yaml
needs: [plan, build-apk, setup-avd]
...
sdk_root="${{ needs.setup-avd.outputs.sdk_root }}"
echo "ANDROID_AVD_HOME=${{ needs.setup-avd.outputs.android_avd_home }}" >> "$GITHUB_ENV"
```

`needs` transfers **values**, not state. `/home/runner/.config/.android/avd` is a real path — on the job that produced it. On the job that consumes it, the emulator package and the AVD are simply absent, and both vanish again when the job ends.

Four defects were stacked behind each other here, each invisible until the one in front of it was removed:

| layer | symptom |
|---|---|
| reusable workflow invoked from a step's `uses:` | `Can't find 'action.yml'` — job died before running a line |
| `ANDROID_AVD_HOME` assigned, never `export`ed | `emulator -list-avds` reported nothing while the `.ini` sat on disk |
| emulator installed only as an `sdkmanager` side effect | missing binary on the *consumer*, though `setup-avd` had run it 90s earlier |
| cache saved `~/.android/avd` while `avdmanager` wrote `~/.config/.android/avd` | cache archived an empty directory; the "already exists" branch could never be taken |

The fourth is the one worth remembering. The cache was already there, half-built, doing nothing: it held the AVD directory nobody used and not the emulator everybody needed. The design looked correct because a cache step existed; nothing measured whether it carried anything.

## Idea

Either the setup moves into every shard, or the cache becomes an explicit transport with a contract both sides agree on.

## Decision

The cache is the transport, and the contract is: **one path list, one key, assembled from the producer's own outputs.**

- `ANDROID_AVD_HOME` is pinned to `$HOME/.android/avd` and `export`ed *before* `avdmanager` runs. The location stops being something discovered afterwards with `find` and becomes a fact of the workflow, so producer and consumer cannot disagree about it.
- Producer caches four paths — the AVD home, `system-images`, `emulator`, `platform-tools`. The two consumers restore the same four under the same key, spelled from `needs.setup-avd.outputs.{avd_name,system_image,cache_key}` rather than re-typed, so the key cannot drift between the writer and the reader.
- Consumers use `actions/cache/restore`, never `actions/cache`. Keys are immutable; four shards racing to save one key would each log a misleading "another job is creating this cache" and none would win.
- `sdkmanager "emulator" "platform-tools" "$SYSTEM_IMAGE"`. The emulator arrived as a transitive dependency of the system image, which is why it was present in the setup job and missing from the other one. A requirement resting on someone else's side effect is a coincidence.

## Rationale

Not "install in every shard": four shards would each pay the install and recreate the AVD, discarding the one thing that made this workflow worth having, and the cache was already there to carry it.

Not "keep discovering the path with `find`": it happened to work on the runner image that produced it, and produced a directory the cache never saved. Pinning it makes the miss loud — the AVD would land where we said, or not at all.

The key is assembled from the producer's outputs rather than repeated at each call site because the failure mode of a repeated string is that one of the copies changes and nobody notices; a cache that silently restores the wrong prefix is worse than one that misses, because it looks like it worked.

## Consequences

- **A cache miss on the consumer is fatal, and there is no fallback.** `e2e.yml` does not install the emulator itself; only `_setup-avd.yml` does. If the key is evicted, or changed — a system-image bump rewrites it — the Maestro shard starts on a runner with no emulator and fails with `'emulator' binary not found in PATH`, the exact symptom this ADR exists to explain. A `cache-hit == 'false'` branch that installs on demand would close it. **That is the obvious next piece of work and it is not in this change.**
- The cache grows by roughly the emulator package, on top of the system image it already held. Cold runs cost the same as before — one install, in one job — and are not made cheaper by this.
- Warm runs stop paying for `avdmanager` and `sdkmanager` entirely.
- Two registries now have to stay in step: the path list in three files and the key. They are commented as such, and `scripts/check-gate-registries.py` proves that idea generalises — the same "two lists drift" shape is already live between `static-gates.sh` and `_registry.GATES` (29 against 39).
- `ANDROID_AVD_HOME` now points at `~/.android/avd` on runners whose image defaults to `~/.config/.android/avd`. Any step that hardcoded the default would now disagree with the workflow.

## Links

- `.github/workflows/_setup-avd.yml` — the producer and the four cached paths
- `.github/workflows/e2e.yml`, `android-device-tests.yml` — the two consumers
- run `39236069621` — the diagnostic that showed `/usr/local/lib/android/sdk/emulator` absent on the shard runner while `setup-avd` had executed it
- run `39233512741` — `emulator -list-avds does not list singularity-api36`
- `docs/decisions/2026-10-19-sgate-regex-repl-empty-is-a-deletion.md` — the same shape one layer down: a value crossing a boundary that nothing checked
- `docs/decisions/2026-10-06-a-gate-that-lies-is-worse-than-no-gate.md` — why a cache step that carries nothing is worse than no cache step
