# The store screenshots

The rendered set the Google Play listing shows: six pictures, a manifest that
describes them and a contact sheet to look at before an upload.

Nothing here is hand-made. Every picture is rendered from a **raw capture** of
this app by [`scripts/store-render.py`](../scripts/store-render.py), and the raw
capture is the source of truth: the renderer never invents a screen, a rating
or an award.

```
Maestro/capture/manifest.yaml   what is captured, in which order
        │
        ▼  scripts/store-capture.py  (just sc)      the raw PNGs — the truth
build/store-capture/*.png
        │
        ▼  scripts/store-render.py     (just sr)    the compositions
store/templates.yaml + store/fonts/ ──────────► build/store-assets/
```

## The three files here

| File | What it is |
|---|---|
| `templates.yaml` | The compositions and the store copy: the canvas, the type, the frame and the headline of every picture. The only place the copy lives. The order of the set is the capture manifest's. |
| `fonts/Roboto-Regular.ttf` | The font the headlines are drawn with. Checked in on purpose — see [The font](#the-font). |
| `fonts/LICENSE.txt` | The font's licence (Apache-2.0). |

The rendered pictures are **not** in the repository: they are an output, they
change with every capture, and a committed PNG is a PNG nobody can regenerate.
The workflow uploads them as an artifact.

## Preview, regenerate, replace

**Preview.** The set is looked at as a whole before it is uploaded. The
renderer writes `build/store-assets/contact-sheet.png` — all six pictures on one
sheet — and `build/store-assets/README.md`, a table of what each picture is,
where it came from and where it was written. The `manifest.json` next to it
says the same in a machine-readable way, with the output directory and the
path of every picture. Look at the contact sheet first: it is the store
listing, small.

**Regenerate.** Two commands, in this order:

```bash
just sc    # capture the raw screenshots from the capture device
just sr    # render the store screenshots from them
```

The renderer needs Pillow and PyYAML, which come with the repository's own
tooling: `pip install -e scripts/src/`. Pillow is pinned, because the renderer
promises the same picture from the same raw capture every time.

`just sr` alone is enough when the raw captures are already there (after
`just sc`, or after a capture run in CI). It writes into `build/store-assets/`
and refuses to write a picture that Google Play would not accept: the size,
the shape, the colour depth and the weight of every rendered PNG are checked,
and a headline the font cannot draw is an error rather than a row of empty
boxes. A headline that does not fit the canvas — a word wider than the line,
or more lines than the template allows — is refused too, rather than drawn
past the margin.

The output directory is the renderer's own: it is wiped before every run, so
the renderer wipes only a directory it wrote itself, and only while that
directory holds nothing but the files the renderer writes.

**Replace.** To publish a new set, capture again and render again — the same
two commands. To change what a picture *says*, edit the headline in
`store/templates.yaml` and re-run `just sr`; no picture is edited by hand. To
change the layout — the canvas, the colours, the frame — the numbers are in
the same file.

## The font

`fonts/Roboto-Regular.ttf` — Roboto Regular, Apache-2.0, from the
[googlefonts/roboto-2](https://github.com/googlefonts/roboto-2) repository
(`src/hinted/Roboto-Regular.ttf`).

It is checked in rather than taken from the machine for two reasons. The first
is determinism: a font found on the machine renders differently on another
machine, and "the same raw capture, the same store picture" has to hold for
one set to be comparable with the last one. The second is the language: the
store copy is Russian, and the font Pillow ships has no Cyrillic — a Russian
headline drawn with it is a row of empty boxes. The renderer refuses a
headline the font cannot draw instead of shipping the boxes.

## The rules the renderer holds the set to

Google Play's rules for phone screenshots (as of 2026-11-20,
<https://support.google.com/googleplay/android-developer/answer/9866151>):

- 2 to 8 screenshots for a phone listing;
- PNG or JPEG, up to 8 MB each;
- every side between 320 px and 3840 px;
- the longest side no more than 2.3 times the shortest;
- a 24-bit PNG without an alpha channel.

The renderer checks the ones it can check in a file — the size, the shape, the
colour depth and the weight — and holds the set to the rest: it refuses to
render fewer than two or more than eight pictures. The two rules only a person
can check are the content ones: no service provider and no notification in the
shot. Those belong to the capture device, and
[`Maestro/capture/STORYBOARD.md`](../Maestro/capture/STORYBOARD.md) is where
that contract is written down.

## In CI

The workflow [`.github/workflows/store-screenshots.yml`](../.github/workflows/store-screenshots.yml)
does the whole thing — build, boot the capture device, capture, render — and
uploads the set as the artifact `store-screenshots`. It is manual
(Actions → Store screenshots → Run workflow): it changes no gate, and no pull
request runs an emulator because of it.

One step in it is worth knowing about: a fresh Play image posts a keyboard
tutorial on its first boot, and the capture refuses a device that holds a
notification from anybody but the system — a notification in a store screenshot
is somebody's message that nobody may publish. The workflow snoozes the
device's own notifications before the capture starts, so a fresh runner does
not have to be coaxed into a clean status bar by hand.
