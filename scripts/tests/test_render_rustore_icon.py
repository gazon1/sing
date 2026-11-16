"""Tests for render_rustore_icon.py.

The renderer turns Android VectorDrawable layers into SVG for the RuStore icon.
Two things it can get wrong are invisible in a diff and obvious in the store:

  - Android colors are ARGB (#AARRGGBB), SVG 1.1 has no 8-digit hex. Copying
    the hex through makes SVG read "#440000" as opaque dark red, so the soft
    shadow under the logo becomes a solid block;
  - VectorDrawable strokes are dropped silently, so the background grid never
    appears in the store icon even though it is on the device.

The tests run against the real resource files, because the bug lives in the
pairing between the renderer and the vectors it reads, not in the renderer
alone. No cairosvg needed: only the SVG document is inspected.
"""

import importlib.util
import pathlib
import unittest
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent / "render_rustore_icon.py"
)


def load_module():
    spec = importlib.util.spec_from_file_location("render_rustore_icon", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SplitArgbTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_eight_digit_hex_loses_alpha_into_opacity(self):
        self.assertEqual(self.module.split_argb("#44000000"), ("#000000", "0.267"))

    def test_transparent_black_has_zero_opacity(self):
        self.assertEqual(self.module.split_argb("#00000000"), ("#000000", "0"))

    def test_opaque_alpha_becomes_one(self):
        self.assertEqual(self.module.split_argb("#FF3DDC84"), ("#3DDC84", "1"))

    def test_six_digit_hex_has_no_opacity(self):
        self.assertEqual(self.module.split_argb("#3DDC84"), ("#3DDC84", None))

    def test_named_color_passes_through(self):
        self.assertEqual(self.module.split_argb("white"), ("white", None))


class VectorToSvgTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.res = MODULE_PATH.parent.parent / "androidApp" / "src" / "main" / "res"

    def test_gradient_stops_carry_separate_opacity(self):
        svg = self.module.vector_to_svg(
            self.res / "drawable-v24" / "ic_launcher_foreground.xml"
        )
        self.assertIn('stop-color="#000000"', svg)
        self.assertIn('stop-opacity="0.267"', svg)
        self.assertNotIn("#44000000", svg)
        self.assertNotIn("#00000000", svg)

    def test_stroke_is_rendered_with_its_own_opacity(self):
        svg = self.module.vector_to_svg(
            self.res / "drawable" / "ic_launcher_background.xml"
        )
        self.assertIn('stroke="#FFFFFF"', svg)
        self.assertIn('stroke-opacity="0.2"', svg)
        self.assertIn('stroke-width="0.8"', svg)

    def test_every_stroked_path_keeps_its_stroke(self):
        """The grid is thirty-two lines, and one stroke is not a grid.

        Counting is the point: a renderer that emitted a single stroke would
        pass an `assertIn` check, and the store icon would lose its grid
        without a single test going red.
        """
        source = self.res / "drawable" / "ic_launcher_background.xml"
        stroked = [
            element
            for element in ET.parse(source).getroot().findall("path")
            if element.get(ANDROID + "strokeColor") is not None
        ]
        svg = self.module.vector_to_svg(source)
        self.assertEqual(len(stroked), 32)
        self.assertEqual(svg.count('stroke="'), len(stroked))
        self.assertEqual(svg.count('stroke-width="'), len(stroked))

    def test_no_eight_digit_hex_survives(self):
        layers = (
            self.res / "drawable" / "ic_launcher_background.xml",
            self.res / "drawable-v24" / "ic_launcher_foreground.xml",
        )
        for layer in layers:
            svg = self.module.vector_to_svg(layer)
            self.assertNotRegex(svg, r"#[0-9A-Fa-f]{8}")


if __name__ == "__main__":
    unittest.main()
