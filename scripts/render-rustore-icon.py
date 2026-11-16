#!/usr/bin/env python3
"""Render the RuStore icon (512x512 PNG) from the adaptive icon sources.

RuStore wants a 512x512 PNG, but the repository only ships launcher bitmaps up to
192x192 plus the two adaptive-icon vector layers. Rendering the vectors keeps the
store icon identical to the adaptive icon on the device, at any resolution.

Usage:
    python3 scripts/render-rustore-icon.py --out build/rustore/icon-512.png

Requires: cairosvg (pip install cairosvg).
"""

import argparse
import io
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

REPO_ROOT = Path(__file__).resolve().parent.parent
RES = REPO_ROOT / "androidApp" / "src" / "main" / "res"


def vector_to_svg(path: Path) -> str:
    """Convert an Android VectorDrawable into an SVG document."""
    root = ET.parse(path).getroot()
    vw = root.get(ANDROID + "viewportWidth", "108")
    vh = root.get(ANDROID + "viewportHeight", "108")
    defs: list[str] = []
    body: list[str] = []
    for index, element in enumerate(root.findall("path")):
        data = element.get(ANDROID + "pathData", "")
        fill = element.get(ANDROID + "fillColor", "#000000")
        gradient = element.find(f".//{AAPT}attr[@name='android:fillColor']/gradient")
        if gradient is not None:
            gradient_id = f"gradient{index}"
            stops = "".join(
                f'<stop offset="{item.get(ANDROID + "offset", "0")}" '
                f'stop-color="{item.get(ANDROID + "color", "#000000")}"/>'
                for item in gradient.findall("item")
            )
            defs.append(
                f'<linearGradient id="{gradient_id}" gradientUnits="userSpaceOnUse" '
                f'x1="{gradient.get(ANDROID + "startX", "0")}" '
                f'y1="{gradient.get(ANDROID + "startY", "0")}" '
                f'x2="{gradient.get(ANDROID + "endX", "0")}" '
                f'y2="{gradient.get(ANDROID + "endY", "0")}">{stops}</linearGradient>'
            )
            fill = f"url(#{gradient_id})"
        elif len(fill) == 9 and fill.startswith("#"):
            fill = "#" + fill[3:] + fill[1:3]  # #AARRGGBB -> #RRGGBBAA
        body.append(f'<path d="{data}" fill="{fill}" fill-rule="nonzero"/>')
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{vw}" height="{vh}" '
        f'viewBox="0 0 {vw} {vh}"><defs>{"".join(defs)}</defs>{"".join(body)}</svg>'
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="output PNG path")
    parser.add_argument("--size", type=int, default=512, help="output size in px")
    parser.add_argument("--corner", type=int, default=64, help="corner radius in px")
    args = parser.parse_args()

    try:
        import cairosvg
    except ImportError:
        print("cairosvg is required: pip install cairosvg", file=sys.stderr)
        return 1
    from PIL import Image, ImageDraw

    def render(svg: str, size: int) -> "Image.Image":
        png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
        return Image.open(io.BytesIO(png)).convert("RGBA")

    # The adaptive icon canvas is 108dp with a 72dp visible area; a standalone
    # store icon shows that visible area, so render wide and crop to it.
    big = args.size * 2
    background = render(
        vector_to_svg(RES / "drawable" / "ic_launcher_background.xml"), big
    )
    foreground = render(
        vector_to_svg(RES / "drawable-v24" / "ic_launcher_foreground.xml"), big
    )
    image = Image.alpha_composite(background, foreground)

    visible = int(round(big * 72 / 108))
    left = (big - visible) // 2
    image = image.crop((left, left, left + visible, left + visible)).resize(
        (args.size, args.size), Image.LANCZOS
    )

    mask = Image.new("L", (args.size, args.size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, args.size - 1, args.size - 1], radius=args.corner, fill=255
    )
    image.putalpha(mask)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, "PNG", optimize=True)
    print(f"{out} {args.size}x{args.size} {out.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
