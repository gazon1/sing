# Contract: Probe Verdict Vocabulary

The liveness gate's observable contract. Anything reading its output — CI, `check.sh`, a developer at a terminal — depends on these forms.

## Exit codes

Unchanged by this feature.

| Code | Condition |
|---|---|
| 0 | No gate `dead`, no gate `unrunnable` |
| 1 | At least one gate `dead` or `unrunnable` |

`unreached` is **not** fatal, matching `slow`. A run that did not reach every gate has not found a broken gate, and must not claim the opposite either — which is what naming each unreached gate is for.

## Summary line

The gate's final line MUST account for every gate in exactly one category. Format changes only by the addition of the `unreached` count, appended to the existing fields so existing readers keep working.

```
check-gate-liveness.py: <probed> gate(s) probed, <n> died, <n> unrunnable,
<n> slow, <n> unreached, <n> reported findings, <n> shell-function gates,
<n> skipped as slow
```

## Per-gate lines

| Verdict | Line form | Level |
|---|---|---|
| `dead` | `ERROR: gate "<name>" died instead of reporting: <detail>` | error |
| `unrunnable` | `ERROR: gate "<name>" cannot run: <detail>` | error |
| `slow` | `note: <name> — no verdict after <n>s; slow is not dead, so this is not a failure` | note |
| `unreached` | `note: <name> — not probed: the <n>s aggregate budget expired first. Not checked, not passed.` | note |
| `finding` | `note: <name> — reported a finding (non-zero, no crash); that is the gate working` | note |
| shell-function | `note: <name> — runs a shell function from the registry; not probeable out of context, see static-gates.sh` | note |

### The `unreached` line

Its wording is load-bearing. "Not checked, not passed" is required because the two common misreadings of a timeout are "the gate is slow" and "the gate is fine", and both are wrong. A reader who sees only a count cannot tell an exhausted budget from an exhausted probe.

## Skip set

Emitted on every run.

| Case | Line form |
|---|---|
| Non-empty | `<n> skipped as heavy, each named with its reason` followed by one line per entry |
| Empty | `0 skipped as heavy` |

An explicit `0` is required (FR-008). Silence is ambiguous between "nothing was skipped" and "the skip logic did not run" — the exact ambiguity that made the 2026-10-15 incident invisible.

## Backwards compatibility

The only added fields are the `unreached` count and the per-gate `unreached` line. No existing line is removed, reordered, or reworded. Consumers parsing the summary line by field position are unaffected by an appended field.

## What this contract deliberately does not change

The set of gates, their modes (`blocking` / `advisory`), and the meaning of `dead` versus `finding`. This feature makes the gate bounded and correct about what it did not check; it does not change what the gate decides.