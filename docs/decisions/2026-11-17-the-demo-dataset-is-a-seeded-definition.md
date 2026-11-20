---
title: "The demo dataset is a seeded definition, and the seed contract grew the three keys it needed"
date: 2026-11-17
status: accepted
tags: [testing, maestro, android, seed]
---

# The demo dataset is a seeded definition, and the seed contract grew the three keys it needed

## Context

A Google Play listing needs screenshots. A screenshot needs a screen with
something on it, and a screen with something on it needs data. Until now
this repository had no answer to "what data?", so every capture run would
have had to invent one — by hand, on a device, by a person tapping through
onboarding. That is not reproducible: the second capture of the store listing
would not be the first one, and nobody could say what changed between them.

The repository already has a way to put a fixed state into the app: the
`todo-debug://seed` deep link, used by every Maestro flow that needs a row.
It writes one entity per link, from a query string. What it could not write
was the state a *screenshot* needs:

- a task with a **priority** — the Today screen shows priority, and a set
  without one cannot show it;
- a task **inside a project** — a task that belongs to nothing makes the
  project screenshots impossible;
- a note with a **body** — a note that is only a link is not the thing the
  Notes screenshot is about.

The obvious alternative — a second fixture API, or a JSON file the app
imports — is a second way to say the same thing. The seed link is already
the fixture contract of this repository, and it is already debug-only.

Two things were already true of the seed contract and had to stay true:
the link that seeds a row cannot know an id the app generated, and a fixture
that cannot be honoured must fail loudly rather than write something else.

## Idea

Extend the seed contract with the three missing keys, and express the demo
dataset as a *definition* — a flow that wipes the profile's content and
seeds the same rows, in the same order, with relative dates — that the
capture set reads from.

## Decision

**The seed contract grows three keys, and no second API appears.**
`priority=` on a task, `inProject=<project name>` on a task, and `desc=` on
a note becomes the note's body. `SeedLinkKeys` owns the vocabulary, the
debug seed activity reads it, `SeedWriter` writes it.

**A task names its project by name.** `inProject=Website launch`, not
`inProject=<id>`. The link that seeds the task runs before the app has
given the project its id, so an id is not knowable from the link; a name
is. This is the same reason `parent=` names a project by id — the parent's
id *is* knowable there, because the fixture seeds the parent with a fixed
id first.

**A key that cannot be honoured fails the seed.** An unknown priority, or a
project that is not there, throws: the task is not written, and the seed
activity's failure screen comes up with the reason on it. This mirrors the
existing rule for `parent=`. The alternative — writing the task outside the
project — is worse than a red seed: the capture would show a task that
belongs to nothing, and the screenshot would be wrong in a way that is
visible to a stranger.

**The dataset is a definition, not an append.** The flow resets a
profile's content first and then seeds every row. A capture after a dozen
failed runs produces the same profile as the first one. The reset is not
concurrency-safe, so a capture run owns its device.

**The dataset lives in `Maestro/capture/`, not in `Maestro/helpers/`.** A
helper in this repository is a subflow that at least one test flow reaches;
the dataset is reached by no test flow on purpose — it switches the active
profile to `Play demo`, and a test flow that ran it would leave every later
flow in the wrong profile. It belongs to the capture set, so it sits next to
the storyboard that names its rows.

**The reset lands in a profile of the dataset's own.** `reset=content` deletes
every row of the *active* profile, so the dataset does not run it against
whichever profile the device happens to hold: it switches to a dedicated
`Play demo` profile first, and only then resets. The switch happens before the
reset, and the scoped identity is retargeted in the same step, so the reset
cannot land in the previous profile. This is the repository's existing
multi-profile mechanism used as an isolation boundary — no second isolation
system is introduced — and the demo dataset is therefore destructive only
inside a profile that holds demo data.

**A link always reaches the seed activity.** The debug activity is declared
`singleTask`, and it takes a link that arrives at a live instance in
`onNewIntent`. Measured on the device: a seed link whose data equals the app
task's base intent is not launched at all. Android hands it to the task's top
activity instead — the running `MainActivity`, which knows nothing about seeds
— and answers "Activity not started, intent has been delivered to currently
running top-most instance". The link is then lost without a word: no seed
runs, no failure is shown, and the flow that sent it goes on. The base intent
is the link that opened the task, so the link that opens a run is the link the
*next* run loses — the dataset would have been a definition only on a freshly
installed app. The alternative — a link that is unique per run — would put a
run id into the contract, and the contract is what makes the dataset readable.

**The dates are relative.** `today`, `+1d` … `+5d`, never a fixed date. A
fixed date in a store screenshot is a screenshot that goes stale: in a
month every task in it is overdue, and the set advertises a problem instead
of the product.

**The storyboard and the dataset change together.** `Maestro/capture/`
holds the storyboard (why each image is there) and a manifest (the file
names and the order); `Maestro/capture/demo-dataset.yaml` holds the rows.
The dataset and the story name the same tasks, projects and notes, so a
change to one is a change to the other.

## Rationale

The seed link was already the fixture contract; a second API would have been
a second contract, with its own failure modes and its own documentation, for
the same job. Three keys on the existing vocabulary are cheaper than a new
surface and keep every flow that seeds a task working unchanged.

Naming the project rather than its id is what makes the extension usable
from a link at all — and a name is what a person reading the dataset wants
to see anyway.

Failing loudly on an unhonourable key is the rule the seed contract already
follows for `parent=`, and it is the rule a *published* artefact needs
most: a store screenshot cannot fail, and a screenshot that was quietly
seeded wrong is a screenshot nobody notices until it is live.

Relative dates keep the published set true forever; a fixed date is a
guarantee that the listing shows stale data.

## Consequences

- `Maestro/helpers/seed.yaml` documents the three keys; `SeedLinkKeys` is
  the single owner of the vocabulary.
- The seed writer resolves a project by name before writing a task, and a
  bulk seed resolves each distinct name once, before any row is written: an
  unknown name fails the whole bulk instead of writing tasks that belong to
  nothing.
- A note's body is written whether or not the task it links to exists; a
  missing target leaves the note without the link rather than without the
  text. Both paths render the body through the app's own content mapper, so a
  note reads the same with and without a link.
- The seed activity is `singleTask` and handles `onNewIntent`, so a link
  cannot be delivered to an activity that will not act on it. The capture
  runner (#3005) can still confirm that a seed ran by the logcat line the
  activity writes.
- The capture flows, the local capture command and the Play compositions
  are separate issues (#3004, #3005, #3006). Nothing here runs yet: this
  issue delivers the dataset and the story.
- The capture device is a 9:16 phone AVD — 1080 × 1920 px. The listing is built
  around 9:16 (Google Play's cap on the longest side of a phone screenshot is
  2.3 times the shortest, as of 2026-11-20), so the repository's
  default 1080 × 2400 AVD is not a capture device, and a `wm size` override
  cannot turn it into one: the override leaves the captured image at its
  physical size and moves the screen out from under the Maestro flows'
  percentage coordinates. The profile is written down in the storyboard.
- The generated PNGs are not committed. They are a build output of the
  capture command, not a source artefact.

## Links

- `Maestro/capture/demo-dataset.yaml` — the dataset
- `Maestro/capture/STORYBOARD.md` — the story and the capture profile
- `docs/decisions/2026-11-17-the-capture-device-is-a-9-16-avd.md` — the device
  the profile is captured on
- `Maestro/capture/manifest.yaml` — the file names and the order
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedLinkKeys.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedWriter.kt`
- `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt`
- <https://support.google.com/googleplay/android-developer/answer/9866151> — the
  preview-asset rules the capture profile answers to
