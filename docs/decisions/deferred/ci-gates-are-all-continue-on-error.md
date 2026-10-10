---
title: "Ci Gates Are All Continue On Error"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#53 closed with the evidence.** `grep -n "continue-on-error" ci.yml` returns exactly two hits — the Maestro tag lint (:249) and the flake-analysis annotation (:166) — both with a documented reason. The version-catalog, assembleDebug and mcp-server gates are all blocking, and mcp-server additionally runs `check-test-runs.py --require mcp-server:test`.

**Status: RESOLVED (2026-10-04).** All four remaining advisory gates are blocking:
`Run detekt`, `Assemble Android debug`, `Build version catalog gate`, and the whole
`mcp-server` job. See ADR `2026-10-04-measurement-integrity`.

The `mcp-server` job was the one that mattered: it holds the profile-bootstrap
identity tests, so the P0 data-corruption fix shipped with the tests that cover it
unable to fail a build.
**Tracked as:** #53

**Symptom:** every gate step in the `build` job carried
`continue-on-error: true` — `Build version catalog gate`, `Run detekt`,
`Assemble Android debug`, `Find unwired surfaces`. Only `jvmTest`,
`desktopApp:test` and `Check Maestro test tags` could fail the workflow.
So "CI is green" said nothing about detekt, unwired surfaces, or version
literals.

`Check Maestro test tags` stays `continue-on-error: true` on purpose, and the
comment says why: it is superseded by `MaestroFlowTagsTest` in `:shared:jvmTest`,
which is blocking and covers the same tag registry. A non-blocking step that is
documented as a convenience for local use is not a hole; one that duplicates a
blocking gate and is *believed* to be the gate is.

---
