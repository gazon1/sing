---
title: "The store screenshots are rendered from the raw captures by a checked-in template and a checked-in font"
date: 2026-11-20
status: accepted
tags: [android, release, store, tooling]
---

# The store screenshots are rendered from the raw captures by a checked-in template and a checked-in font

## Context

The capture command produces the six raw PNGs the store listing is made of
(ADR `2026-11-19-the-store-capture-is-one-command-over-the-maestro-runner.md`).
They are the truth: photographs of this app, on this device, in this state.

They are also, on their own, not what a store listing shows. A listing shows a
picture per screen with a caption, a frame, and a set that hangs together —
and a raw capture is a full-bleed screenshot with no caption and no
composition. Somebody was going to take those six PNGs and lay them out.

The obvious way to do that is a design tool: a Figma file, a template, a
person dragging boxes. That is also the way that stops being reproducible. The
composition lives in a tool's document, not in the repository; the next person
cannot tell which version produced the published pictures; a changed headline
is a manual edit with no diff; and the person who does it has to have the
tool, the account and the file.

The issue that asks for this (#3006) asks for something narrower and stricter:
render the validated raw captures into a Play-ready set from **version-controlled
templates**, validate the export against Play's current requirements, produce
a manifest, a README and a contact-sheet preview, and keep the local command
usable without CI.

## Idea

One renderer that reads the raw captures and a checked-in template file and
writes the store set. The template file holds the geometry and the copy; the
font is checked in; the renderer validates what it writes and refuses what
Google Play would refuse.

## Decision

**The renderer is `scripts/store-render.py`, driven by `just sr`.** A script in
the tree, like the capture command: the editable install of `scripts/src/`
belongs to whichever checkout installed it last, and a renderer that drew
another checkout's templates would be a renderer of something else.

**The raw captures are the source of truth, and they are not touched.** The
renderer reads `build/store-capture/*.png` and writes `build/store-assets/`.
It never edits a raw capture, and a rendered picture never becomes an input.
The two directories are separate on purpose: a picture that was rendered once
and re-rendered from its own output is a picture that drifts. The renderer
wipes its output directory before every run, so it refuses an output directory
that is the raw directory, that holds it, or that holds the repository: the
one place where "the raw captures are never touched" could be broken by a
mistyped path is the path of the output directory.

**The compositions and the copy live in `store/templates.yaml`, and nowhere
else.** The canvas, the colours, the type, the frame and the headline of every
picture are in that file; the script carries no copy. A renderer that carried
its own headlines would have to be edited to change a caption, and the caption
would then live in a place nobody reads when they look for the copy.

The copy is Russian, because that is the language of the listing this set is
made for and the language of the app in the pictures (the capture locale is
`ru-RU`). It says only what the screen behind it shows — no ratings, no
awards, no claim the app does not make.

**The font is checked in: `store/fonts/Roboto-Regular.ttf`** (Roboto Regular,
Apache-2.0, from `googlefonts/roboto-2`, licence next to it). Two reasons, and
both are load-bearing.

The first is determinism. A font found on the machine is a font that renders
differently on another machine, and "the same raw capture, the same store
picture" has to hold for one set to be comparable with the last one.

The second is the language. The font Pillow ships has no Cyrillic: a Russian
headline drawn with it is a row of empty boxes. The renderer checks the
coverage of the glyphs it draws and **refuses a headline the font cannot
draw** rather than shipping the boxes — the check is done on the rendered
mask, not on the font's tables, so it holds for whatever the font actually
draws.

**Pillow is the whole drawing stack.** A canvas, a font and a rectangle are
what six static layouts need. No design tool, no browser, no rendering engine,
no renderer to install: the export is a function of the raw capture, the
template file and the checked-in font, and nothing else.

**Every rendered picture is validated before it is written to the set, and the
set itself is validated.** Play's rules for phone screenshots (as of
2026-11-20, <https://support.google.com/googleplay/android-developer/answer/9866151>)
are 2 to 8 screenshots, PNG or JPEG up to 8 MB, every side between 320 px and
3840 px, the longest side no more than 2.3 times the shortest, and a 24-bit
PNG without an alpha channel. The renderer measures the file it just wrote —
the size, the shape, the colour depth, the weight — and refuses the picture
if it fails, and refuses the set if it is too short or too long. A set that
reaches the upload as an artifact is a set that was checked.

**The rendered set describes itself.** `manifest.json` carries the locale, the
canvas, the device profile the captures came from, the output directory and one
entry per picture — its order, its file, the screen it shows, the flow that
produced it, the raw capture it was rendered from, the path it was written to,
the headline and the size. `README.md` is the same thing for a person.
`contact-sheet.png` is the whole set on one picture: the preview a person looks
at before an upload. It is a preview, not a store asset, and it is not in the
manifest.

**The device profile is read from the capture manifest, not written twice.**
`Maestro/capture/manifest.yaml` is the capture command's own record of what a
capture is; the renderer reads the list of captures from it, so a capture that
is added to the manifest and not to the templates is a gap the renderer
reports, and a composition without a capture is refused.

**The rendered pictures are not committed.** They are an output, they change
with every capture, and a committed PNG is a PNG nobody can regenerate. The
repository carries the template and the font; the set is an artifact.

**CI is a manual workflow and changes no gate.** `.github/workflows/store-screenshots.yml`
does the whole thing — build the APK, boot the capture device, capture, render
— and uploads the set as an artifact. It runs when somebody asks for it
(Actions → Store screenshots → Run workflow) and never on a pull request: the
emulator costs minutes and gigabytes, and nothing in it is a check. No
required check changes, and no pull request runs an emulator because of it.

## Rationale

The store listing is published under the store's name, once, and the pictures
in it are the app's face. A composition that cannot be reproduced from the
repository is a composition that will be re-done differently next time, and
nobody will be able to say which version produced what.

Putting the geometry and the copy in a YAML file in the repository makes the
whole set a diff. A changed headline is one line. A changed colour is one
line. A changed layout is a handful of numbers, and the next render is
provably the same picture from the same raw capture.

The validation is the other half. A store upload fails at the worst possible
moment — after the copy is written, after the listing is scheduled — and it
fails with a message about dimensions, which says nothing about which of the
six pictures was wrong. Checking each picture as it is written moves that
failure to the machine that can fix it.

The font decision is small and easy to get wrong in both directions. Taking the
font from the machine means the set is not reproducible and, with Pillow's
bundled font, not even legible in the language the listing is written in.
Vendoring one file costs 500 kB and makes both true at once.

Refusing what Play would refuse is not a substitute for looking at the
pictures — a picture of the wrong screen passes every file-level check — but
it is what a program can do, and the content rules that only a person can
check (no service provider, no notification) stay in the storyboard, where
the capture device's contract is written down.

## Consequences

- `just sc` captures; `just sr` renders. The renderer needs the raw captures
  to be there and says so when they are not.
- `just sr` writes `build/store-assets/`: the six PNGs, `manifest.json`,
  `README.md` and `contact-sheet.png`. It exits non-zero when a picture or the
  set fails the check, so it can be used in a script without being read.
- The renderer needs Pillow and PyYAML. Both come with the repository's own
  tooling (`pip install -e scripts/src/`); Pillow is declared in
  `scripts/src/pyproject.toml` so CI's venv has it, and pinned to the version
  the set was verified with — Pillow's own releases do not promise the same
  pixels, and the set's promise is the same picture from the same raw capture.
- The renderer wipes its output directory before every run. It only wipes a
  directory it wrote itself — it leaves a marker file in it — so a directory
  that holds files of somebody else's is refused instead of deleted.
- `scripts/tests/test_store_render.py` covers the pure parts: the templates
  and the captures covering each other, the font being checked in, the font
  drawing every headline (and refusing one it cannot), a headline that does not
  fit the canvas (too wide, too many lines), the Play rules (accepted, too
  small, with an alpha channel, 16-bit, over 8 MB, too long a side, not a
  PNG), the size of the set (one refused, two accepted, nine refused), the
  whole run on synthetic captures, the raw captures coming out of the run
  unchanged, the output directory not being allowed to swallow them, the
  manifest it writes, and the same raw capture rendering the same picture
  twice.
- The storyboard's own claim about Play's ratio rule was wrong and is fixed:
  the limit is 2.3 times the shortest side, not twice, so a 1080 × 2400
  capture is not refused by the ratio any more. The capture device is 9:16
  because the listing is built around 9:16, not because the other one would
  be rejected.
- A fresh Google Play Store emulator posts a keyboard tutorial of its own,
  and the capture command refuses a device with a notification on it. The CI
  workflow snoozes the device's own learning notifications before the
  capture — the status bar in a published screenshot has to show the clock
  only.

## Links

- `scripts/store-render.py` — the renderer
- `store/templates.yaml` — the compositions and the copy
- `store/fonts/` — the checked-in font and its licence
- `store/README.md` — how to preview, regenerate and replace the set
- `Maestro/capture/manifest.yaml` — the captures and the device profile
- `Maestro/capture/STORYBOARD.md` — what the six images are, and why
- `.github/workflows/store-screenshots.yml` — the manual CI run
- `docs/decisions/2026-11-19-the-store-capture-is-one-command-over-the-maestro-runner.md`
  — the capture the renderer works from
- <https://github.com/gazon1/sing/issues/3006> — the issue this answers
