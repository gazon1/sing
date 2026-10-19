# Tasks — avd-transport-carries-the-emulator

- [x] Resolve the SDK root and build the cache key in one step of the producer,
      and publish both as job outputs. The consumers read them verbatim; no side
      re-spells a path or a key.
- [x] Version the cache key (`…-v2`) so the 1659-byte entry written under the
      broken paths is retired. Cache keys are immutable, so an entry written
      under an old layout cannot be repaired in place.
- [x] Make the `restore-keys` prefix carry the version, so a fallback restore
      cannot reach back across a layout change.
- [x] Remove the `cache_key` input from the reusable workflow: no caller passed
      it, and the key is built in one place. An input that is ignored reads as a
      knob and changes nothing.
- [x] Pass `AVD` to the emulator from the producer's `avd_name` output. The
      module's default (`Medium_Phone`) is an AVD this workflow never creates.
- [x] Extract `scripts/ci/provision-avd.sh`: one definition of "provisioned",
      idempotent, failing loudly, run by the producer and by every consumer.
      A cache miss now costs time instead of the build.
- [x] Verify the provisioning script in both directions — a cold machine
      installs and creates the AVD, a warm machine is left alone, a machine with
      the wrong AVD fails with the reason.
- [x] Ask `emulator -list-avds` what exists before booting anything, and report
      a missing AVD in one line with the ones that do exist.
- [x] Add `scripts/check-workflow-env-context.py`: every `${{ env.NAME }}` in a
      workflow must name something the file defines. Registered in both gate
      registries, with a positive control that renames a definition and leaves
      the reference behind.
- [x] Pin the transport contract with `scripts/tests/test_avd_transport.py`:
      the same four cached locations on both sides, the producer's key, no
      `env`-context path, and the AVD name passed through.
- [x] Record the decision in an ADR, and close the "no fallback" bullet the
      previous ADR left open.
- [x] Confirm on a real run that the transport works: run `39259981571` restored
      2.15 GB from the cache, `sdkmanager` had nothing to download, and the
      emulator binary was found.
- [x] Drop the AVD from the cached paths. Its `userdata.img` did not survive
      the cache; the consumer created the AVD itself in half a second. The key
      moves to `v3`, because the cached layout changed.
- [x] Enable KVM on the consumer's runner. The producer's enabling does not
      travel with a cache, and the two jobs run on different machines.
- [ ] Confirm on a real run that the shard boots the AVD and the Maestro flows
      pass. The last run reached the emulator and died on KVM permissions;
      the KVM fix is in, and the run that proves it is the next one. No local
      run can stand in for this — there is no Android SDK on the development
      host.
- [ ] Decide what to do with the four dead settings the static gate reports
      (`Notifications.sound`, `Notifications.vibration`,
      `WorkSchedule.weekendSat`, `WorkSchedule.weekendSun`). That gate is
      blocking and red on `main`, and it is a product decision — wire the
      values, hide the controls, or record why in the baseline. It is not this
      change's debt and it is not a CI question.
- [ ] Give `main` a branch protection with `CI gate` as the required check.
      The transport bug was visible in PR #740 before the merge and nothing
      blocked it.
