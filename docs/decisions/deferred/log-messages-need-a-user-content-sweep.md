---
title: "Log Messages Need A User Content Sweep"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — sweep done 2026-10-10**

**Tracked as:** #443

**Found in:** MR-3 retrospective. The redaction decorator catches credential
shapes; it does not catch task titles, note bodies, or AI prompt fragments.

**Sweep result (2026-10-10):** Full record at `docs/decisions/2026-10-10-log-interpolation-sweep.md`.
33 log call sites audited. All interpolations classified as typed ids (ULIDs/UUIDs,
system-generated) or technical metadata (LSN, protocol version, counts, enum names).
Two noted cases carry a theoretical user-content path via exception messages from
decode failures and server error strings — documented in the sweep record with the
conclusion that no actionable leak was found.

**Verdict:** No user content interpolation found that requires fixing. The sweep record
allows the next audit to be a diff.

---
