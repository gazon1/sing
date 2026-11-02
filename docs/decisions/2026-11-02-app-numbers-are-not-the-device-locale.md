---
title: The app's numbers and date labels are the app's own, not the device's
date: 2026-11-02
status: accepted
tags: [i18n, formatting, dates, architecture]
---

# The app's numbers and date labels are the app's own, not the device's

## Context

`String.format` without a `Locale` formats in the **device's** locale, and `SimpleDateFormat`
without a `Locale` does the same. The rule against them was already written down — in the
header of `core/ui/Formatters.kt`, in the words of the shared decimal formatter — but it was
a comment in one file, and eleven other places used `String.format`:

* `core/database/LocalTimeFormats.kt` — the wire format for every time in the app: the
  `due_time` / `start_time` / `end_time` columns, the backup files, the AI tool inputs. On an
  Arabic device it wrote `"١٤:٣٠:٠٠"`, and `Integer.parseInt` cannot read that back, so the
  value was lost on the next read.
* five screen formatters — the agenda's work-block label, the backup stamp, the calendar grid,
  the pomodoro timer, the work-schedule times — each of which followed the device's decimal
  separator or digits.

The same file also said what the shared formatters must do: pad by hand, so the question of a
locale does not arise. `formatDecimal` (added for #1701) already did; the integers did not.

A second, smaller shape of the same problem: `formatRussianDueDate` took a nullable date and
returned a nullable string, so every caller had to invent a fallback for a case that could not
happen — and two of them fell back to the raw ISO date, the very shape the label replaced.

## Idea

The numbers the app writes and the date labels it shows are the app's own, and the helpers
that produce them say so in their signature: a formatter that can return "no value" makes
every caller write a fallback, and the fallbacks are where the raw ISO date kept coming back.

## Decision

- **Numbers are padded by hand.** `formatPadded(value: Int, digits: Int = 2)` in
  `core/ui/Formatters.kt` is the one way to write a padded integer, and the call sites route
  through it. `formatDecimal` already worked this way.
- **The wire format keeps its own padding.** `core/database/LocalTimeFormats.kt` pads by hand
  in place rather than importing `core.ui`: a data package does not take a dependency on a UI
  package to write two digits. The duplication is deliberate and small.
- **`formatRussianDueDate` takes a non-null date** and returns a non-null label. A caller whose
  date really is optional asks for `formatRussianDueDateOrNull`. No caller may invent a
  fallback for a date it already knows is there.
- **A domain package may import a formatter from `core.ui`.** The arch rule that keeps a
  feature's domain out of presentation and data layers is about `feature.*.presentation.*` and
  `feature.*.data.*`; `core.ui` is neither, and `feature/agenda/domain` already imports
  `core.ui.components`. The reverse is not true, and is not to become true: `core.database` does
  not import `core.ui`.
- **A locale-sensitive number is a bug, not a localisation.** The app's date labels are Russian
  while the rest of the app is English (#1700) and the month names come from the device
  (#1388, #1634). Re-localising them is a separate job; until then, the digits and the
  punctuation are the app's own on every device.

## Rationale

The alternative — formatting in the device's locale — makes the same value read differently on
two phones, and for the wire format it makes the value unreadable. The rule was already the
house rule; what was missing was that it could be followed without thinking, and that a
formatter's signature would not invite the raw ISO date back in.

The nullable signature of `formatRussianDueDate` was the second half of the same disease: a
`String?` return asks the caller for a plan B, and the plan B that two callers wrote was the
raw ISO date.

## Consequences

- The Arabic-device rows that were written before this change stay broken; the repair and the
  read path are tracked in #1822 and #1823.
- Nothing keeps `String.format` out of the code — the rule is still a comment. A detekt rule
  is filed as #1824.
- The remaining `String.format` in the tooling (`adr-cli`, the UI-test helpers) follows the
  device locale too; it never reaches a screen, and it is filed as #1825.
- The raw ISO date still leaks in two places — the task card and the recurrence preview, plus
  the `formatDueChip` fallback for a date that is neither today nor tomorrow — and those are
  tracked by #1820.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/Formatters.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/RussianDateFormatter.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/database/LocalTimeFormats.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/ui/LocaleIndependenceTest.kt`
- #1820, #1821, #1822, #1823, #1824, #1825
