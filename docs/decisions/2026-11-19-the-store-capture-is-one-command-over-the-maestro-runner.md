---
title: "The store capture is one command that drives the existing Maestro runner"
date: 2026-11-19
status: accepted
tags: [android, maestro, release, testing]
---

# The store capture is one command that drives the existing Maestro runner

## Context

The store listing needs six raw screenshots. The pieces that produce them
existed already: the capture flows under `Maestro/flows/capture/` (#3004), the
manifest that names them and puts them in order, the seeded dataset behind
them, and the capture device — a 9:16 AVD whose own screen is 1080 × 1920 px
(ADR `2026-11-17-the-capture-device-is-a-9-16-avd.md`).

What did not exist was the command. The flows were run by hand, one at a time,
and nothing checked that the images that came out were the images the
storyboard promises. A screenshot taken by hand is a screenshot nobody can
tell the story of: it may be 1080 × 2400, it may be a lock screen, it may be
an English locale, and it looks exactly as plausible as a good one.

Two things make this more than a shell script around `maestro test`.

The first is the identity of the build. The device keeps whatever was
installed on it last, and a capture from a stale install is a picture of an
older app. It looks like a good capture, which is the problem: nothing about
the image says which build it came from.

The second is the trap this repository already knows about. The `emulator`
package — the Maestro runner among others — is installed as an editable
install, and the install belongs to whichever checkout ran
`pip install -e scripts/src/` last. The runner resolves its root from the
*installed* package's file, so a command that reaches the runner through the
installed console script runs another checkout's flows against this checkout's
report. `./rm` exists to answer exactly that question, and it refuses to run
when the package does not resolve to this tree.

## Idea

One command that installs the APK it can identify, drives the repository's own
Maestro runner once per capture flow, photographs the screen right after each
flow, and checks every PNG against the profile the manifest documents.

## Decision

**The command is `scripts/store-capture.py`, driven by `just sc`.** A script in
the tree, not a console script in the editable package: the package's install
moves between checkouts, the script does not. It drives this checkout's runner
through `./rm`, which is what refuses a foreign tree.

**The existing runner is driven, not replaced.** One invocation per capture
flow — `--flow <flow> --skip-install --keep-screen-on` — so the device
recovery, the fixture wipe, the session reset, the provenance check and the
failure artifacts stay in one place. The runner's own semantics and its suite
selection are not touched, and the E2E runner is not touched at all.

**The screenshot is `adb exec-out screencap -p`.** Not Maestro's own
`takeScreenshot` output: that directory's layout is a property of the Maestro
version, and adb is deterministic and gives byte-exact PNGs. The screen is
taken immediately after the flow that produced it, which is the only order in
which the picture belongs to that flow — and `--keep-screen-on` is what keeps
the runner from putting the device to sleep before the picture is taken.

**The identity check is layered.** The version name inside the APK must equal
the version name on the device, and an APK built here must carry this
checkout's short SHA. The first rule refuses a stale install; the second
refuses an APK that was built from another commit.

**The device is pinned — always.** The capture device has to be named, and a
name that is not attached is an error. One attached device is not a choice: it
is just as likely to be somebody's phone as the capture AVD, and the capture
installs an APK on it and changes its settings. A capture that silently fell
back to another device would be a capture of a screen nobody asked for. The
command does not boot an emulator and does not uninstall the app — a signature
clash is reported, not resolved by throwing away the data on somebody's phone.

**The image check reads the image, not only its header.** The PNG is parsed
chunk by chunk: every chunk's checksum is verified, the image data has to
decompress into exactly the scanlines the header's own dimensions, bit depth
and colour type promise, and the result has to be the size and the aspect the
manifest documents. A file that carries the right header and no pixels is not
a screenshot, and a file with a broken chunk is not a PNG.

A header this checker cannot measure is refused rather than skipped: an
interlaced image, an unknown colour type, a colour type with a bit depth that
is not a legal pair, an unknown compression or filter method — in each case
the pixels behind the header would never be counted, so the image would pass
without anybody looking at it.

The scanlines themselves are looked at as well: every one of them starts with
a filter byte, and PNG knows only five of them. A sixth value is not a
picture anybody can decode, so it is refused.

The chunk order is checked as well: the header comes first and only once, the
image data follows it as one run of chunks — an empty image-data chunk counts
as part of the run, so it cannot hide a split one — the end marker carries
nothing and nothing follows it, and a critical chunk nobody knows — a chunk
whose name starts with a capital letter — is refused, because a decoder has to
reject a file it cannot read. The compressed data itself is one zlib stream and
nothing else, so bytes after it are refused too.

The palette is checked as well. An indexed image is a list of indices into a
palette, so a palette is required for one, there is at most one of it, it has
to be a whole number of three-byte entries, it has to be in the file before
the pixels that index into it, and a colour type that has no colours to index
must not carry one at all.

**The profile is applied to the app's own settings, not only to the system.**
The theme and the font scale are the app's settings: they live in the app's
data, so `cmd uimode night no` and `settings put system font_scale` do not
reach them, and a device where somebody once chose the dark theme would keep
the dark theme after an install. The command resets the app's settings file
before the first flow, so the app comes up with its defaults — the system
theme, which the command puts into light mode, and a font scale of 1.0. A
build that does not allow that reset is refused: the capture would be
published in the app's own old look, and the image check cannot see the
difference, because it looks at the shape of the picture and not at what is
on it.

**The profile lives in the manifest, next to the captures it constrains.**
`Maestro/capture/manifest.yaml` carries the width, the height, the aspect and
the format; the image check reads them from there. The storyboard, the manifest
and the checker cannot drift apart, and the numbers are not written twice.

**The output directory is a dedicated generated directory, wiped at the start
of every run.** A PNG left by an earlier run must never be able to stand in
for a capture that did not happen.

**CI is unchanged.** The command is local, it needs a device, and nothing in
the CI surface calls it.

## Rationale

The screenshot is the artefact, and it is published under the store's name. A
capture that is wrong in a way nobody can see is worse than no capture, because
the mistake is made once and discovered after the upload.

Every check in the command answers a question that a human eye cannot answer
about a PNG: is this the build we meant, is this image whole, is it the size
and shape the store accepts. The image check is cheap — the PNG is parsed with
the standard library, chunk by chunk — and it is the difference between "the
command ran" and "the command produced the six images the storyboard
describes".

The manifest is the source of truth for both halves of that: the order and the
names of the captures, and the profile they are measured against. A second
place with the same numbers is a second place to be wrong.

Driving the existing runner rather than writing a second device runner is the
whole point of the reuse constraint. The runner already knows how to bring a
device back, how to wipe a fixture, how to reset a session, how to retry a
flow that failed because the device died and not retry one that failed its
assertion, and where the failure artifacts go. A capture command that did any
of that itself would be a second copy of all of it, and the two copies would
drift.

## Consequences

- `just sc` takes the whole set; `just sc 01-today` takes one capture, for
  iteration. `SERIAL` (or the recipe's second argument) pins the device, and a
  run without it is refused.
- The raw PNGs, the Maestro logs and the JUnit reports land in
  `build/store-capture/`. Nothing is committed: a raw capture is a generated
  artefact, and the rendered marketing assets live elsewhere.
- The command exits non-zero when a flow fails or an image does not pass the
  check, so it can be used in a script without being read.
- The capture profile — the app locale, the light theme, the font scale, the
  status bar and the screen timeout — is a contract, not a wish. The locale,
  the light theme and the font scale have to hold, or the capture is refused:
  a screenshot of the app in the wrong theme or the wrong language is not the
  screenshot the storyboard documents, and no check on the picture's shape
  would notice. Each of them is read back from the device after it is set, so
  a command that says nothing and changes nothing is caught. Two properties
  are best-effort and only cost a warning: the screen timeout, which changes
  no pixel, and hiding the battery percentage, which the status bar may refuse
  to give up. The theme and the font scale are the app's own settings, so they
  are reset through the app's data rather than through the system's settings.
- The status bar is part of the picture, so it is checked too: the battery
  percentage is hidden, the shade is collapsed, and a device with a pending
  notification refuses the capture — a notification in a store screenshot is
  somebody's message that nobody may publish. The system's own notifications
  carry nothing personal and are allowed. The check is closed: a device that
  cannot be asked about its notifications, whose shade cannot be collapsed, or
  whose notification record has no readable package refuses the capture as
  well, because an unread status bar is not a clean one. The notifications are
  looked at again before every screenshot, because a notification that arrives
  while a flow plays is a notification in the picture.
- The app's own settings are reset by stopping the app and dropping its
  settings file, and both steps have to work: a running app keeps its loaded
  preferences in memory and would put the old theme back on the screen after
  the file is gone. The file is then looked for again, and only a listing that
  says the file is not there proves that it is gone — a listing that failed for
  any other reason proves nothing and refuses the capture.
- `scripts/tests/test_store_capture.py` covers the pure parts: the manifest
  and its order, the selection of one capture, the device resolution (unnamed,
  missing, unusable), the identity rules (the wrong application, a stale
  install, a build from another commit, a foreign APK) and the image
  validation (empty, wrong format, truncated, corrupt, empty pixels, broken
  checksum, wrong size, wrong aspect, the headers this checker cannot measure
  — interlaced, an unknown colour type, an illegal colour type and bit depth
  pair, an unknown compression or filter method — an illegal scanline filter,
  a chunk before the header, a second header, data in or after the end marker,
  an unknown critical chunk, image data that is not one run of chunks, a split
  run hidden behind an empty image-data chunk, a palette that is missing,
  malformed or late, a second palette, data
  after the compressed image, and the profile: a reset the device refuses, an
  app that cannot be stopped, a property that does not take, a settings file
  that survives the reset, a settings file that cannot be read back, a
  notification that cannot be inspected, a notification record without a
  readable package, a shade that cannot be collapsed). The order of the capture
  is covered as well: the notifications are looked at again after the flow and
  before the screenshot.
- The six flows are still `manual` and `capture`-tagged, so the nightly full
  suite does not pay for them.

## Links

- `scripts/store-capture.py` — the command
- `Maestro/capture/manifest.yaml` — the captures, their order and the profile
- `Maestro/capture/STORYBOARD.md` — what the six images are, and why
- `Maestro/README.md` — how the command is run
- `docs/decisions/2026-11-17-the-capture-device-is-a-9-16-avd.md` — the device
- `docs/decisions/2026-11-17-the-demo-dataset-is-a-seeded-definition.md` — the
  dataset the capture seeds
- <https://github.com/gazon1/sing/issues/3005> — the issue this answers
