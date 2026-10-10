---
title: "Maestro Smoke Cannot Run In This Environment"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04 (#46 already closed). The symptom was environmental and disproven on re-probe; what it surfaced was a flow bug, tracked as #50, and the fact that the smoke job had never run, tracked as #87. Keeping this entry open would have re-asserted a host claim that was already shown false.

**Status: CLOSED as disproven (2026-10-04).** Re-probed with the emulator up:
the flow ran to completion and failed on a real assertion, with no
`DeviceServerDiedException`. The environment recovers; the blocker was transient.
The failure it surfaced is a bug in the flow, tracked as #50. Kept below because
the original symptom can return, and the record of what it was is worth more
than a deleted paragraph.

**Found in:** B5 verification of `navigation-open-policy`, 2026-10-04.

Every Maestro flow fails with `DeviceServerDiedException` on `deviceInfo` (~130ms),
including the untouched control flow `04-delete.yaml`, so it is not the branch under
test. The emulator is alive (`adb shell echo ok` responds) and
`scripts/ensure-emulator.sh` finds the AVD already running, so the usual cold-start
path is not involved.

**Already ruled out:** not a tag problem (`MaestroFlowTagsTest` passes 106/106), not
an APK problem (`:androidApp:assembleDebug` is green), not a device problem.

**Try next:** this is a host/driver problem, so it is not a refactor. See ADR
`2026-09-28-emulator-gfxstream-colorbuffer-segv` for the gfxstream history — the
standing instruction is not to pass `-gpu` flags, because the default host GPU path
fails periodically and has no cure. The next useful step is a fresh boot with
`adb emu kill` + `ensure-emulator.sh` and a re-run of the control flow alone; if that
still fails, the fix belongs to the emulator image, not to this repository.

---
