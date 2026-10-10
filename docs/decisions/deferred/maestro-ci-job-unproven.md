---
title: "Maestro Ci Job Unproven"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** [#87](https://github.com/gazon1/sing/issues/87)

**Found in:** 2026-10-04, while adding the `maestro-smoke` CI job.

The job is the first thing in this repository that ever *executes* a Maestro
flow in CI. It has never run — GitHub Actions emulators are a different
environment from the host's AVD, including for the gfxstream crash that
`run-maestro.sh` works around locally.

Specifically unproven:
- `reactivecircus/android-emulator-runner` + `Maestro/scripts/wait-for-boot.sh`
  boots and the APK installs;
- the `smoke` set passes in that environment at all — some flows may depend on
  host behaviour;
- wall-clock cost, which is why `agenda` and `regression` were left out.

**Do this first:** run the job once on a branch and read the log before trusting
it. If the smoke set turns out to be slow or flaky, the `timeout-minutes: 45` and
`MAESTRO_MAX_RETRIES=1` are the first knobs to turn.

**Partly answered on the host, 2026-10-04.** The `smoke` set was run locally for
the first time, so "does it pass at all" is no longer open — it did not, and the
run found six real defects (see `maestro-flows-share-one-app-instance-so-failures-cascade`,
`a-flow-can-be-unrunnable-and-every-check-still-pass`,
`overflow-menu-rows-were-tagged-with-a-nobody-reads-scheme`,
`a-testtag-built-from-a-localised-label-changes-with-device-locale`,
`flows-select-by-localised-text-and-the-device-is-russian`). Four are fixed and
verified on the device.

**What this changes for the CI job:** the smoke set is *not* ready to be a
blocking gate yet, and the reason is now known rather than unknown. `smoke` runs
on a **Russian-locale** emulator here, which surfaced a class of defect no
English-locale run would have found; CI's emulator will have its own locale, and
until every flow selects by id rather than by translated text, "passes in CI" and
"passes on the host" will disagree in ways neither run can explain.

So the order matters: fix the remaining locale-dependent selectors first, then
push the branch and read the log. Doing it the other way round spends the first
CI run as a debugging session.

Still unproven, and unchanged by any of the above:
- `reactivecircus/android-emulator-runner` + `Maestro/scripts/wait-for-boot.sh`
  actually booting and installing;
- wall-clock cost of the `smoke` set;
- whether `agenda` and `regression` fit in the same budget.

---
