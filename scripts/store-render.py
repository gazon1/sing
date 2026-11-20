#!/usr/bin/env python3
"""Render the store screenshots from the raw captures, and check what came out.

One command: take the raw captures `scripts/store-capture.py` wrote, draw each
of them into the composition `store/templates.yaml` describes, and verify the
result against what Google Play asks of a phone screenshot.

Usage:
    python3 scripts/store-render.py                       # the whole set
    python3 scripts/store-render.py --raw-dir build/store-capture
    python3 scripts/store-render.py --out-dir build/store-assets

WHAT IT PRODUCES
----------------
Everything lands in the output directory, and nothing outside it:

    build/store-assets/01-today.png      the rendered screenshot
    build/store-assets/contact-sheet.png the whole set on one picture
    build/store-assets/manifest.json     the machine-readable description
    build/store-assets/README.md         the same, for a human

The raw captures stay where they are, untouched: they are the source of truth,
and a rendered picture that does not match them is a bug in this script, not a
reason to re-capture.

WHY A SCRIPT AND NOT A DESIGN TOOL
----------------------------------
Six static layouts do not need a rendering stack. They need a canvas, a font
and a rectangle. Pillow draws those, it is a library and not a framework, and
the picture it produces is a function of the raw capture and the template file
and nothing else — which is what "the same set every time" means.

The font is checked in (`store/fonts/Roboto-Regular.ttf`) for the same reason:
a font found on the machine renders differently on another machine.

WHAT IT REFUSES
----------------
A missing raw capture, a headline the font cannot draw, an output that is not
a 24-bit PNG of the promised size, a set that is too short or too long for
Play: each of those is an error and a non-zero exit code, because a store
listing that fails at upload time is a store listing nobody looked at.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageFont

#: The repository root. The script resolves it from its own file, so it works
#: from any working directory.
ROOT = Path(__file__).resolve().parent.parent

#: The compositions: the canvas, the type, the frame and the copy.
TEMPLATES_PATH = ROOT / "store" / "templates.yaml"

#: The capture manifest: which raw captures exist, in which order, and which
#: flow produced each of them.
CAPTURE_MANIFEST_PATH = ROOT / "Maestro" / "capture" / "manifest.yaml"

#: Where the raw captures are, and where the rendered set goes.
RAW_DIR = ROOT / "build" / "store-capture"
OUT_DIR = ROOT / "build" / "store-assets"

#: The marker the renderer writes into its output directory. The directory is
#: wiped before every run, and a directory the renderer did not write is not
#: the renderer's to wipe.
OUT_MARKER = ".store-render-output"

#: What the marker says. The name alone is not a proof of ownership — a
#: directory that holds a file with that name is still somebody else's.
OUT_MARKER_TEXT = "store-render.py output marker\n"

#: Google Play's rules for phone screenshots, as of 2026-11-20
#: (https://support.google.com/googleplay/android-developer/answer/9866151):
#: PNG or JPEG, each side between 320 and 3840 px, and the longest side no
#: more than 2.3 times the shortest. A phone listing carries 2 to 8 of them.
PLAY_MIN_SIDE = 320
PLAY_MAX_SIDE = 3840
PLAY_MAX_RATIO = 2.3
PLAY_MAX_BYTES = 8 * 1024 * 1024
PLAY_MIN_SCREENSHOTS = 2
PLAY_MAX_SCREENSHOTS = 8

#: The PNG magic number.
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _repo_relative(path: Path) -> str:
    """The path as it is written in the repository, not as it is on this machine.

    The manifest is committed next to the pictures it describes, so a path
    that starts with somebody's home directory is noise there — and noise
    that changes on every machine.
    """
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()

#: Exit codes.
EXIT_OK = 0
EXIT_FAILED = 1  # a capture or a validation failed
EXIT_SETUP = 2  # the run could not start: no raw capture, no template


class RenderError(RuntimeError):
    """The run could not start, or could not be trusted.

    A different failure from "the render failed": this one says the inputs are
    not what the run needs — a raw capture that is not there, a template that
    does not parse, a headline the font cannot draw. Those are reported
    before a single picture is drawn.
    """


# ── the templates ────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Canvas:
    """The paper the composition is drawn on."""

    width: int
    height: int
    background: tuple[int, int, int]
    background_end: tuple[int, int, int]
    margin: int


@dataclass(frozen=True)
class Headline:
    """The type above the device."""

    font_path: Path
    size: int
    color: tuple[int, int, int]
    accent: tuple[int, int, int]
    accent_ratio: float
    gap: int
    max_lines: int


@dataclass(frozen=True)
class Device:
    """The frame the raw capture is drawn into."""

    width: int
    bezel: int
    radius: int
    color: tuple[int, int, int]


@dataclass(frozen=True)
class Screenshot:
    """One composition: a raw capture and the headline that goes with it."""

    file: str
    headline: str


@dataclass(frozen=True)
class Template:
    """The whole template file."""

    locale: str
    canvas: Canvas
    headline: Headline
    device: Device
    screenshots: tuple[Screenshot, ...]


def _color(value: object, what: str) -> tuple[int, int, int]:
    """A colour out of `#rrggbb`.

    A colour this checker cannot read is a template it has to refuse: a
    composition in a colour nobody can name is a composition nobody can
    reproduce.
    """
    if not isinstance(value, str) or not value.startswith("#") or len(value) != 7:
        raise RenderError(f"{what} is not a #rrggbb colour: {value!r}")
    try:
        return (
            int(value[1:3], 16),
            int(value[3:5], 16),
            int(value[5:7], 16),
        )
    except ValueError as exc:
        raise RenderError(f"{what} is not a #rrggbb colour: {value!r}") from exc


def _positive_int(value: object, what: str) -> int:
    """A number of pixels: a whole number, and not zero."""
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise RenderError(f"{what} is not a whole number of pixels: {value!r}")
    return value


def load_templates(path: Path = TEMPLATES_PATH) -> Template:
    """Read the compositions out of the template file."""
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise RenderError(f"the template file {path} cannot be read: {exc}") from exc
    except yaml.YAMLError as exc:
        raise RenderError(f"the template file {path} is not valid YAML: {exc}") from exc
    if not isinstance(raw, dict):
        raise RenderError(f"the template file {path} does not hold a mapping")

    canvas_raw = raw.get("canvas") or {}
    headline_raw = raw.get("headline") or {}
    device_raw = raw.get("device") or {}
    shots = raw.get("screenshots")
    if not isinstance(shots, list) or not shots:
        raise RenderError(f"the template file {path} holds no screenshots")

    locale = raw.get("locale")
    if not isinstance(locale, str) or not locale.strip():
        raise RenderError(f"the template file {path} does not name a locale")

    font_name = headline_raw.get("font")
    if not isinstance(font_name, str) or not font_name:
        raise RenderError(f"the template file {path} does not name a font")
    font_path = (path.parent / font_name).resolve()
    if not font_path.is_file():
        raise RenderError(f"the font {font_path} is not there")

    screenshots = []
    for entry in shots:
        if not isinstance(entry, dict):
            raise RenderError(f"a screenshot in {path} is not a mapping")
        name = entry.get("file")
        headline = entry.get("headline")
        if not isinstance(name, str) or not name:
            raise RenderError(f"a screenshot in {path} has no file name")
        if not isinstance(headline, str) or not headline.strip():
            raise RenderError(f"the screenshot {name} has no headline")
        screenshots.append(Screenshot(file=name, headline=headline))

    margin = canvas_raw.get("margin", 0)
    if not isinstance(margin, int) or isinstance(margin, bool) or margin < 0:
        raise RenderError(f"the margin is not a whole number of pixels: {margin!r}")

    return Template(
        locale=locale,
        canvas=Canvas(
            width=_positive_int(canvas_raw.get("width"), "the canvas width"),
            height=_positive_int(canvas_raw.get("height"), "the canvas height"),
            background=_color(canvas_raw.get("background"), "the canvas background"),
            background_end=_color(
                canvas_raw.get("background_end"), "the canvas background_end"
            ),
            margin=margin,
        ),
        headline=Headline(
            font_path=font_path,
            size=_positive_int(headline_raw.get("size"), "the headline size"),
            color=_color(headline_raw.get("color"), "the headline colour"),
            accent=_color(headline_raw.get("accent"), "the accent colour"),
            accent_ratio=float(headline_raw.get("accent_ratio", 0.12)),
            gap=int(headline_raw.get("gap", 48)),
            max_lines=_positive_int(headline_raw.get("max_lines", 3), "max_lines"),
        ),
        device=Device(
            width=_positive_int(device_raw.get("width"), "the device width"),
            bezel=int(device_raw.get("bezel", 0)),
            radius=int(device_raw.get("radius", 0)),
            color=_color(device_raw.get("color"), "the device colour"),
        ),
        screenshots=tuple(screenshots),
    )


# ── the capture manifest ─────────────────────────────────────────────────────


@dataclass(frozen=True)
class Capture:
    """One raw capture: its order, its screen and the flow that produced it."""

    order: int
    file: str
    screen: str
    flow: str
    width: int
    height: int


@dataclass(frozen=True)
class Captures:
    """The capture set: the captures in order, and the profile they share."""

    captures: tuple[Capture, ...]
    profile: dict


def load_captures(path: Path = CAPTURE_MANIFEST_PATH) -> Captures:
    """Read the capture list out of the capture manifest.

    The manifest is the capture command's own record of what a capture is. The
    renderer reads it rather than repeating the list, so a capture that is
    added to the manifest and not to the templates is a gap the renderer can
    report.
    """
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise RenderError(f"the capture manifest {path} cannot be read: {exc}") from exc
    except yaml.YAMLError as exc:
        raise RenderError(f"the capture manifest {path} is not valid YAML: {exc}") from exc
    profile = (raw or {}).get("profile") or {}
    width = _positive_int(profile.get("width"), "the capture profile width")
    height = _positive_int(profile.get("height"), "the capture profile height")
    captures = []
    for entry in (raw or {}).get("captures") or []:
        captures.append(
            Capture(
                order=int(entry["order"]),
                file=str(entry["file"]),
                screen=str(entry.get("screen", "")),
                flow=str(entry.get("flow", "")),
                width=width,
                height=height,
            )
        )
    if not captures:
        raise RenderError(f"the capture manifest {path} holds no captures")
    return Captures(
        captures=tuple(sorted(captures, key=lambda capture: capture.order)),
        profile=dict(profile),
    )


# ── the images ───────────────────────────────────────────────────────────────


def read_png_header(path: Path) -> tuple[int, int, int, int]:
    """The width, height, bit depth and colour type of a PNG.

    Only the header is read: the capture command has already checked that the
    raw capture is a whole, uncorrupted PNG, and this renderer has to know
    two things about it — how big it is, and whether it is a PNG at all.
    """
    try:
        with path.open("rb") as handle:
            head = handle.read(26)
    except OSError as exc:
        raise RenderError(f"{path} cannot be read: {exc}") from exc
    if len(head) < 26 or not head.startswith(PNG_SIGNATURE):
        raise RenderError(f"{path} is not a PNG")
    if head[12:16] != b"IHDR":
        raise RenderError(f"{path} has no image header")
    width, height = struct.unpack(">II", head[16:24])
    bit_depth, color_type = head[24], head[25]
    return width, height, bit_depth, color_type


def missing_glyphs(text: str, font: ImageFont.FreeTypeFont) -> list[str]:
    """The characters of the text the font cannot draw.

    A font without a glyph draws its `.notdef` box for it, and a store
    screenshot with a row of boxes is not a screenshot anybody may publish. The
    check is done on the rendered mask rather than on the font's tables, so it
    holds for whatever the font actually draws.
    """
    # The reference is a private-use character no font carries, so the
    # font draws its `.notdef` for it — the same thing it draws for a
    # character it has no glyph of. The escape is spelled out because the
    # character itself is invisible in the source.
    notdef = bytes(font.getmask("\ue000"))
    missing = []
    for character in dict.fromkeys(text):
        if character.isspace():
            continue
        mask = bytes(font.getmask(character))
        if mask == notdef or not mask:
            missing.append(character)
    return missing


def wrap_text(
    text: str, font: ImageFont.FreeTypeFont, max_width: int
) -> list[str]:
    """The text split into the lines that fit the width.

    Split on spaces: a word that does not fit on a line of its own is a
    template that has to be fixed, and the caller refuses the line when the
    word still does not fit.
    """
    lines: list[str] = []
    current = ""
    for word in text.split():
        candidate = f"{current} {word}".strip()
        if font.getlength(candidate) <= max_width or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def _lerp(
    start: tuple[int, int, int], end: tuple[int, int, int], ratio: float
) -> tuple[int, int, int]:
    """The colour between two colours."""
    return tuple(
        round(a + (b - a) * ratio) for a, b in zip(start, end)
    )  # type: ignore[return-value]


def draw_background(canvas: Canvas) -> Image.Image:
    """The paper: a vertical gradient between the two template colours."""
    image = Image.new("RGB", (canvas.width, canvas.height), canvas.background)
    draw = ImageDraw.Draw(image)
    for y in range(canvas.height):
        ratio = y / max(1, canvas.height - 1)
        draw.line([(0, y), (canvas.width, y)], fill=_lerp(canvas.background, canvas.background_end, ratio))
    return image


def draw_headline(
    image: Image.Image, canvas: Canvas, headline: Headline, text: str, font: ImageFont.FreeTypeFont
) -> int:
    """Draw the headline and its accent bar. Returns the block's bottom edge."""
    missing = missing_glyphs(text, font)
    if missing:
        raise RenderError(
            f"the font {headline.font_path.name} cannot draw "
            f"{', '.join(repr(character) for character in missing)} — "
            "the headline would be a row of empty boxes"
        )
    lines = wrap_text(text, font, canvas.width - 2 * canvas.margin)
    if len(lines) > headline.max_lines:
        raise RenderError(
            f"the headline {text!r} takes {len(lines)} lines and the template "
            f"allows {headline.max_lines}"
        )
    too_wide = [line for line in lines if font.getlength(line) > canvas.width - 2 * canvas.margin]
    if too_wide:
        # A word longer than the line cannot be broken, and the line it sits on
        # would run past the margin — a caption cut off at the edge of a store
        # picture is a caption nobody reads.
        raise RenderError(
            f"the headline {text!r} has a line wider than the "
            f"{canvas.width - 2 * canvas.margin} px the canvas leaves — "
            f"{too_wide[0]!r} does not fit; shorten the word or widen the margin"
        )
    draw = ImageDraw.Draw(image)
    line_height = int(headline.size * 1.25)
    y = canvas.margin
    for line in lines:
        draw.text((canvas.margin, y), line, font=font, fill=headline.color)
        y += line_height
    bar = max(1, round(headline.size * headline.accent_ratio))
    draw.rectangle(
        [canvas.margin, y, canvas.margin + headline.size * 2, y + bar],
        fill=headline.accent,
    )
    return y + bar


def draw_device(
    image: Image.Image,
    canvas: Canvas,
    device: Device,
    capture: Image.Image,
    top: int,
) -> None:
    """Draw the frame with the capture inside it, centred in the space below."""
    inner_width = device.width - 2 * device.bezel
    if inner_width <= 0:
        raise RenderError("the device frame is narrower than its own bezel")
    inner_height = round(capture.height * inner_width / capture.width)
    frame_height = inner_height + 2 * device.bezel

    x = (canvas.width - device.width) // 2
    free = canvas.height - canvas.margin - top - frame_height
    if free < 0:
        # The frame has to fit between the headline and the bottom margin: a
        # frame that runs past the canvas is a picture with a half device on it.
        raise RenderError(
            f"the device frame does not fit: {frame_height} px are needed below "
            f"the headline, but only {canvas.height - canvas.margin - top} px are left"
        )
    y = top + free // 2

    scaled = capture.resize((inner_width, inner_height), Image.LANCZOS)
    frame = Image.new("RGB", (device.width, frame_height), device.color)
    # The frame is a rounded rectangle, so the capture's corners are rounded
    # with it: the inner radius is the outer one less the bezel, which keeps
    # the bezel the same width all the way round.
    inner_radius = max(0, device.radius - device.bezel)
    if inner_radius > 0:
        inner_mask = Image.new("L", (inner_width, inner_height), 0)
        ImageDraw.Draw(inner_mask).rounded_rectangle(
            [0, 0, inner_width - 1, inner_height - 1], radius=inner_radius, fill=255
        )
        frame.paste(scaled, (device.bezel, device.bezel), inner_mask)
    else:
        frame.paste(scaled, (device.bezel, device.bezel))
    if device.radius > 0:
        outer_mask = Image.new("L", (device.width, frame_height), 0)
        ImageDraw.Draw(outer_mask).rounded_rectangle(
            [0, 0, device.width - 1, frame_height - 1], radius=device.radius, fill=255
        )
        image.paste(frame, (x, y), outer_mask)
    else:
        image.paste(frame, (x, y))


def render_one(
    template: Template, screenshot: Screenshot, raw: Path, capture: Capture
) -> Image.Image:
    """Draw one composition."""
    width, height, _bit_depth, _color_type = read_png_header(raw)
    if (width, height) != (capture.width, capture.height):
        raise RenderError(
            f"{raw.name} is {width}x{height}, but the capture profile promises "
            f"{capture.width}x{capture.height}"
        )
    font = ImageFont.truetype(str(template.headline.font_path), template.headline.size)
    image = draw_background(template.canvas)
    bottom = draw_headline(
        image, template.canvas, template.headline, screenshot.headline, font
    )
    with Image.open(raw) as source:
        capture_image = source.convert("RGB")
    draw_device(
        image, template.canvas, template.device, capture_image, bottom + template.headline.gap
    )
    return image


def validate_play(path: Path) -> list[str]:
    """What is wrong with the image, if anything is.

    An empty list means the file is what Google Play accepts of a phone
    screenshot: a PNG without an alpha channel, each side between 320 and
    3840 px, the longest side no more than 2.3 times the shortest, and small
    enough to upload.
    """
    problems: list[str] = []
    try:
        data = path.read_bytes()
    except OSError as exc:
        return [f"cannot be read: {exc}"]
    if not data.startswith(PNG_SIGNATURE):
        return ["is not a PNG"]
    if len(data) < 26 or data[12:16] != b"IHDR":
        return ["has no image header"]
    width, height = struct.unpack(">II", data[16:24])
    bit_depth, color_type = data[24], data[25]
    if color_type != 2 or bit_depth != 8:
        # Play asks for 24-bit PNG: three channels, eight bits each, and no
        # alpha channel. An RGBA file is not that.
        problems.append(
            f"is a {bit_depth}-bit colour type {color_type} PNG, not a 24-bit one"
        )
    for side, name in ((width, "width"), (height, "height")):
        if side < PLAY_MIN_SIDE or side > PLAY_MAX_SIDE:
            problems.append(
                f"has a {name} of {side} px, outside the {PLAY_MIN_SIDE}-{PLAY_MAX_SIDE} px Play allows"
            )
    longest, shortest = max(width, height), min(width, height)
    if longest > shortest * PLAY_MAX_RATIO:
        problems.append(
            f"is {width}x{height}, whose longest side is more than {PLAY_MAX_RATIO} times the shortest"
        )
    if len(data) > PLAY_MAX_BYTES:
        problems.append(f"is {len(data) // 1024} KB, over the 8 MB Play allows")
    return problems


# ── the contact sheet ────────────────────────────────────────────────────────


def contact_sheet(
    template: Template,
    entries: list[tuple[Screenshot, Image.Image]],
    out: Path,
) -> None:
    """The whole set on one picture, to review before an upload.

    The sheet is a preview, not a store asset: it is not in the manifest, and
    it is not one of the screenshots Play is given.
    """
    columns = 3
    thumb_width = 300
    gap = 24
    label_height = 56
    font = ImageFont.truetype(str(template.headline.font_path), 26)
    rows = (len(entries) + columns - 1) // columns
    thumb_height = round(
        thumb_width * template.canvas.height / template.canvas.width
    )
    width = columns * thumb_width + (columns + 1) * gap
    height = rows * (thumb_height + label_height) + (rows + 1) * gap
    sheet = Image.new("RGB", (width, height), (11, 18, 32))
    draw = ImageDraw.Draw(sheet)
    for index, (screenshot, picture) in enumerate(entries):
        row, column = divmod(index, columns)
        x = gap + column * (thumb_width + gap)
        y = gap + row * (thumb_height + label_height + gap)
        sheet.paste(picture.resize((thumb_width, thumb_height), Image.LANCZOS), (x, y))
        draw.text(
            (x, y + thumb_height + 8),
            screenshot.headline,
            font=font,
            fill=(226, 232, 240),
        )
    sheet.save(out, format="PNG")


# ── the manifest ─────────────────────────────────────────────────────────────


def write_manifest(
    out: Path,
    template: Template,
    entries: list[dict],
    profile: dict,
) -> None:
    """The machine-readable description of the set."""
    manifest = {
        "locale": template.locale,
        "output_dir": _repo_relative(out),
        "canvas": {
            "width": template.canvas.width,
            "height": template.canvas.height,
            "background": template.canvas.background,
            "background_end": template.canvas.background_end,
        },
        "device_profile": profile,
        "screenshots": entries,
    }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_readme(out: Path, template: Template, entries: list[dict]) -> None:
    """The same description, for a person reading it before an upload."""
    lines = [
        "# The rendered store screenshots",
        "",
        "What this folder holds, and where every picture came from.",
        "",
        "| # | File | Output | Screen | Source flow | Locale | Size |",
        "|---|------|--------|--------|-------------|--------|------|",
    ]
    for entry in entries:
        lines.append(
            f"| {entry['order']} | `{entry['file']}` | `{entry['output_path']}` | "
            f"{entry['screen']} | `{entry['source_flow']}` | {entry['locale']} | "
            f"{entry['width']}x{entry['height']} |"
        )
    lines += [
        "",
        f"Locale: **{template.locale}** — the store copy in `store/templates.yaml` is in it.",
        "",
        f"Rendered output: `{_repo_relative(out)}/`.",
        "",
        "The raw captures are the source of truth and live outside this folder;",
        "these pictures are rendered from them by `scripts/store-render.py`.",
        "",
        "`contact-sheet.png` is the preview of the whole set. It is not a store",
        "asset — it is what a person looks at before uploading.",
        "",
        "Regenerate them with `just sr` (see `store/README.md`).",
        "",
    ]
    (out / "README.md").write_text("\n".join(lines), encoding="utf-8")


# ── the run ──────────────────────────────────────────────────────────────────


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    """The command line."""
    parser = argparse.ArgumentParser(
        prog="store-render.py",
        description="Render the store screenshots from the raw captures.",
    )
    parser.add_argument(
        "--raw-dir",
        default=os.environ.get("STORE_RAW_DIR", ""),
        help=f"where the raw captures are (default: {RAW_DIR.relative_to(ROOT)})",
    )
    parser.add_argument(
        "--out-dir",
        default=os.environ.get("STORE_OUT_DIR", ""),
        help=f"where the rendered set goes (default: {OUT_DIR.relative_to(ROOT)})",
    )
    return parser.parse_args(argv)


def refuse_unsafe_out_dir(out_dir: Path, raw_dir: Path) -> None:
    """Refuse an output directory that would swallow the raw captures.

    The output directory is wiped before every run, so it has to be a place of
    its own. An output directory that is the raw directory, that holds it, or
    that holds the repository takes the captures — or the repository — with it.
    """
    if out_dir == raw_dir:
        raise RenderError(
            f"the output directory {out_dir} is the raw capture directory — "
            "the rendered set goes somewhere else"
        )
    if raw_dir.is_relative_to(out_dir):
        raise RenderError(
            f"the output directory {out_dir} holds the raw captures at {raw_dir} — "
            "the rendered set goes somewhere else"
        )
    if out_dir.is_relative_to(raw_dir):
        raise RenderError(
            f"the output directory {out_dir} is inside the raw capture directory — "
            "the rendered set goes somewhere else"
        )
    if out_dir == ROOT or ROOT.is_relative_to(out_dir):
        raise RenderError(
            f"the output directory {out_dir} holds the repository — the rendered "
            "set goes somewhere else"
        )


def owns_out_dir(out_dir: Path) -> bool:
    """Whether the output directory is one this renderer wrote.

    The marker has to carry more than its name: a directory that holds a
    file called `.store-render-output` is not the renderer's directory.
    """
    marker = out_dir / OUT_MARKER
    if not marker.is_file():
        return False
    try:
        return marker.read_text(encoding="utf-8") == OUT_MARKER_TEXT
    except (OSError, UnicodeDecodeError):
        return False


def wipe_out_dir(out_dir: Path, known: set[str]) -> None:
    """Empty the output directory — but only one this renderer wrote.

    The output directory is wiped before every run, so it has to be a place
    of its own: the marker has to be one this renderer wrote, and every
    file in it has to be a file this renderer writes. Anything else belongs
    to somebody else: it is refused, not deleted.
    """
    if not out_dir.exists():
        return
    if not out_dir.is_dir():
        raise RenderError(f"the output path {out_dir} is not a directory")
    entries = sorted(entry.name for entry in out_dir.iterdir())
    if not entries:
        shutil.rmtree(out_dir)
        return
    if not owns_out_dir(out_dir):
        raise RenderError(
            f"the output directory {out_dir} is not one this renderer wrote "
            "— refusing to delete it. Remove it yourself if it is yours."
        )
    unexpected = [name for name in entries if name not in known]
    if unexpected:
        raise RenderError(
            f"the output directory {out_dir} holds files this renderer did "
            f"not write ({', '.join(unexpected)}) — refusing to delete them. "
            "Remove them yourself if they are yours."
        )
    shutil.rmtree(out_dir)


def main(argv: list[str] | None = None) -> int:
    """Render the set. Returns the exit code."""
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, ValueError):  # pragma: no cover — a closed stream
        pass
    args = parse_args(argv)
    raw_dir = Path(args.raw_dir).expanduser().resolve() if args.raw_dir else RAW_DIR
    out_dir = Path(args.out_dir).expanduser().resolve() if args.out_dir else OUT_DIR

    try:
        template = load_templates()
        manifest = load_captures()
        refuse_unsafe_out_dir(out_dir, raw_dir)
        captures = manifest.captures
        by_file = {capture.file: capture for capture in captures}
        compositions = {shot.file: shot for shot in template.screenshots}
        missing = [
            capture.file for capture in captures
            if capture.file not in compositions
        ]
        if missing:
            raise RenderError(
                f"the templates hold no composition for {', '.join(missing)} — "
                "every raw capture needs one"
            )
        unknown = [
            shot.file for shot in template.screenshots if shot.file not in by_file
        ]
        if unknown:
            raise RenderError(
                f"the templates compose {', '.join(unknown)}, which the capture "
                "manifest does not know"
            )
        for capture in captures:
            raw = raw_dir / capture.file
            if not raw.is_file():
                raise RenderError(
                    f"the raw capture {raw} is not there — run the capture command first"
                )
        known_outputs = {
            OUT_MARKER,
            "manifest.json",
            "README.md",
            "contact-sheet.png",
        } | {shot.file for shot in template.screenshots}
        wipe_out_dir(out_dir, known_outputs)
        out_dir.mkdir(parents=True)
        (out_dir / OUT_MARKER).write_text(OUT_MARKER_TEXT, encoding="utf-8")
    except RenderError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_SETUP

    entries: list[dict] = []
    pictures: list[tuple[Screenshot, Image.Image]] = []
    failures: list[str] = []
    for capture in captures:
        shot = compositions[capture.file]
        raw = raw_dir / shot.file
        try:
            picture = render_one(template, shot, raw, capture)
        except RenderError as exc:
            failures.append(f"{shot.file}: {exc}")
            print(f"FAILED: {shot.file}: {exc}")
            continue
        out = out_dir / shot.file
        picture.save(out, format="PNG")
        problems = validate_play(out)
        if problems:
            failures.append(f"{shot.file}: {'; '.join(problems)}")
            print(f"FAILED: {shot.file} {'; '.join(problems)}")
            continue
        entries.append(
            {
                "order": capture.order,
                "file": shot.file,
                "screen": capture.screen,
                "source_flow": capture.flow,
                "source_capture": _repo_relative(raw),
                "output_path": _repo_relative(out),
                "headline": shot.headline,
                "locale": template.locale,
                "width": template.canvas.width,
                "height": template.canvas.height,
                "bytes": out.stat().st_size,
            }
        )
        pictures.append((shot, picture))
        print(f"ok: {shot.file} ({out.stat().st_size // 1024} KB)")

    if not failures and len(entries) < PLAY_MIN_SCREENSHOTS:
        failures.append(
            f"the set holds {len(entries)} screenshots and Play wants at least "
            f"{PLAY_MIN_SCREENSHOTS}"
        )
    if len(entries) > PLAY_MAX_SCREENSHOTS:
        failures.append(
            f"the set holds {len(entries)} screenshots and Play takes at most "
            f"{PLAY_MAX_SCREENSHOTS}"
        )

    if failures:
        for failure in failures:
            print(f"failed: {failure}")
        print(f"output: {out_dir.relative_to(ROOT) if out_dir.is_relative_to(ROOT) else out_dir}")
        return EXIT_FAILED

    profile = {
        "device": manifest.profile.get("device"),
        "density": manifest.profile.get("density"),
        "capture_width": manifest.profile.get("width"),
        "capture_height": manifest.profile.get("height"),
        "capture_aspect": manifest.profile.get("aspect"),
        "capture_format": manifest.profile.get("format"),
    }
    write_manifest(out_dir, template, entries, profile)
    write_readme(out_dir, template, entries)
    contact_sheet(template, pictures, out_dir / "contact-sheet.png")

    print("\n=== store assets ===")
    for entry in entries:
        print(f"  {entry['order']}. {entry['file']:<22} {entry['headline']}")
    print(f"{len(entries)} screenshots in {out_dir.relative_to(ROOT) if out_dir.is_relative_to(ROOT) else out_dir}")
    print("preview: contact-sheet.png")
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
