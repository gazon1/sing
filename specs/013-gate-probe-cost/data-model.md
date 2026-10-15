# Data Model: Bounded Gate Liveness Probing

Entities introduced or changed by this feature. No persistence; everything is in-process for the duration of one run.

## Probe

One attempt to run one gate and classify its outcome.

| Field | Type | Meaning |
|---|---|---|
| `name` | str | Display name, as declared in the registry |
| `cmd` | str | The command as written in the registry |
| `budget` | int | Per-probe wall-clock seconds |
| `output_cap` | int | Maximum bytes retained from the probe's combined output |

**Validation**: `budget` and `output_cap` MUST be positive. `output_cap` MUST exceed the largest output a healthy gate is known to produce, asserted by test so the cap is never the reason a healthy gate is misread.

## Verdict

The classification of a probe. The existing five are retained verbatim; one is added.

| Verdict | Meaning | Fatal to the run? |
|---|---|---|
| `ok` | Ran, exit 0 | No |
| `finding` | Ran, non-zero without a traceback — the gate working | No |
| `slow` | Ran, exceeded the **per-probe** budget | No |
| `dead` | Ran and crashed instead of reporting | **Yes** |
| `unrunnable` | Could not start, or names a missing file | **Yes** |
| `unreached` | **New.** Never started: the aggregate budget expired first | No, but MUST be named |

**Rule**: `slow` and `unreached` MUST NOT share a code path or a printed form. `slow` means "ran and took too long"; `unreached` means "never ran". Merging them is the verdict-merging this gate's own history identifies as the cause of a false red CI job — and it would also convert "we did not check this" into a number that reads as checked.

## Budget

| Field | Type | Meaning |
|---|---|---|
| `per_probe_seconds` | int | Bound on one probe |
| `aggregate_seconds` | int | Bound on the whole run |
| `started_at` | monotonic instant | Run start, for remaining-budget computation |

**Validation**: `aggregate_seconds` MUST be greater than `per_probe_seconds`, and MUST exceed the measured serial sum of the probe set by a stated margin. If the aggregate budget is ever below the serial sum, the gate silently stops probing — which is the failure FR-006 exists to make visible.

## BatchQuery

A single repository query issued once and resolved against many records, replacing one spawn per record.

| Field | Type | Meaning |
|---|---|---|
| `kind` | enum | `commit-walk` or `blob-batch` |
| `records` | ordered set | Paths being resolved |
| `spawns` | int | Process spawns consumed — the metric SC-005 asserts on |

**Rule**: `spawns` MUST NOT grow with the number of records or with the number of commits per record. A batch that silently truncates its answer set is worse than a slow one, because it reports a clean verdict derived from partial data (FR-002).

## SkipSet

The gates excluded from probing as expensive, inherited from the registry rather than restated.

| Field | Type | Meaning |
|---|---|---|
| `entries` | ordered list of (name, reason) | Skipped gates and why |
| `source` | registry | Where the decision was made |

**Rule**: reported in full on every run, **including when empty** (FR-008). An empty skip set stated explicitly and an unstated one must not look the same.

## UnreachedSet

Gates the aggregate budget prevented from probing. Populated only when the budget expires; empty otherwise.

Reported individually and in the summary count. A member of this set is the most consequential thing the gate did not do, which is why it is named rather than counted.

## Relationships

```text
Budget ──bounds──> Probe ──yields──> Verdict
   │                 │
   │                 └──classified by──> SkipSet (gates never probed)
   └──expires───────> UnreachedSet (gates never started)

BatchQuery ──resolves──> records in the immutability gate's corpus
```

## Invariants

1. Every gate is accounted for in exactly one of: probed (with a verdict), skipped (with a reason), or unreached (only when the budget expired).
2. A gate is never simultaneously skipped and unreached — a gate excluded by the skip rules cannot be one the budget failed to reach.
3. `unreached` is non-empty only when the aggregate budget expired.
4. Process-spawn count for the corpus is independent of commits-per-record.