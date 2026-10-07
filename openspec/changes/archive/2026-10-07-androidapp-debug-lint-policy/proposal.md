# androidapp-debug-lint-policy

Issue: #99 · Backlog entry: `androidapp-debug-source-set-unlinted`

## What

Decide whether `src/debug` source sets are governed by the same detekt rules as
production code, and record the answer in a form that applies to every future
debug-only file rather than to this one.

No production behaviour changes. This is verification posture only.

## Why

`androidApp/build.gradle.kts` sets `detekt.source` to `src/main/kotlin` and
`src/androidTest/kotlin`. `src/debug` is not listed, so `DebugSeedActivity.kt`
has never been linted — and it is the only source set the module skips.

Linting it today produces 16 findings, all in `DebugSeedActivity.kt`:

| Rule | Count | Why it fires |
|---|---|---|
| `NoRunBlocking` | 1 | a one-shot seeder blocks a background thread |
| `NoDirectClockSystem` | 4 | it stamps seed timestamps |
| `TooGenericExceptionCaught` | 1 | a seeder that must not crash the app |
| `BlankLineBetweenWhenConditions` | 5 | formatting |
| `ClassSignature` | 2 | formatting |
| other formatting | 3 | auto-correctable |

The four rule findings are correct for production code and wrong for a debug
seeder: the tool's entire purpose is to block, stamp real time, and swallow
failures so the developer gets a populated database. The formatting findings are
real and should be fixed whatever the policy is.

What makes this a decision rather than a chore is that it generalises. The next
`src/debug` file inherits whatever is decided here. Baselining all 16 would
accept the four suppressions and the twelve real formatting findings in the same
gesture, and the four would then be indistinguishable from the twelve forever.

The same source set also had a second defect, now fixed: the list named
`src/androidAndroidTest/kotlin`, a source set that does not exist. detekt
ignored it without complaint, which is the normal outcome of a typo in a
configuration list.

## How

Three defensible policies, in increasing order of coverage:

1. **Exempt by source set.** Debug-only tooling is not production code and is
   not held to production rules. Cheapest, and honest about what `src/debug`
   is. Cost: a debug source set can accumulate a real defect and nothing will
   say so.
2. **Lint with a baseline carrying the intentional suppressions.** The
   formatting findings are enforced from day one; the four suppressions are
   visible as four named entries rather than as sixteen anonymous ones. This is
   the recommended policy and the cheapest one that still catches a mistake.
3. **Lint with a narrower rule set.** A `src/debug` config inheriting the
   production rules minus the four that are wrong for tooling. Most precise,
   most machinery, and it needs a precedent for what "wrong for tooling" means —
   which policy 2 makes concrete and case-by-case.

The proposal recommends policy 2, and records the argument rather than assuming
it: a debug source set that is not linted at all is a place where a real defect
can hide indefinitely, and a debug source set that is linted with everything
suppressed is the same place with a nicer-looking badge.
