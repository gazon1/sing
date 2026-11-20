"""Tests for store-render.py — the store-screenshot renderer.

The renderer decides three things that nothing else in the repository checks:

  * *which* captures are rendered — the templates and the capture manifest have
    to cover each other, because a capture without a composition is a capture
    nobody sees and a composition without a capture is a hole in the store;
  * *whether the copy can be drawn* — a headline the checked-in font has no
    glyph for is a row of empty boxes, and it has to be refused rather than
    rendered;
  * *whether the result is acceptable to Play* — the size, the shape, the
    colour depth and the weight of every rendered picture.

All three are pure: the device work belongs to the capture command, and the
rendering is a function of a raw capture and the template file and nothing
else. So they are tested here, on synthetic captures.
"""

import contextlib
import dataclasses
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

from PIL import Image, ImageFont

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'store-render.py'
spec = importlib.util.spec_from_file_location('store_render', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# The module has to be in `sys.modules` before it is executed: a dataclass
# looks its own module up there while it is being built.
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def write_png(path, size, mode='RGB'):
    """A raw capture of the given size: a flat picture of the right shape."""
    image = Image.new(mode, size, (32, 64, 96) if mode == 'RGB' else (32, 64, 96, 255))
    image.save(path, format='PNG')
    return path


class TemplateTest(unittest.TestCase):
    """The templates and the captures have to agree with each other."""

    def test_every_capture_has_a_composition_and_every_composition_a_capture(self):
        template = mod.load_templates()
        captures = mod.load_captures().captures
        capture_files = {capture.file for capture in captures}
        shot_files = {shot.file for shot in template.screenshots}
        self.assertEqual(capture_files, shot_files)
        self.assertEqual(len(shot_files), len(template.screenshots))

    def test_the_templates_say_which_locale_they_are_written_in(self):
        template = mod.load_templates()
        self.assertEqual(template.locale, 'ru-RU')

    def test_the_font_is_checked_in(self):
        # A font found on the machine renders differently on another machine,
        # so the font is part of the repository, not part of the environment.
        template = mod.load_templates()
        self.assertTrue(template.headline.font_path.is_file())
        self.assertTrue(
            template.headline.font_path.is_relative_to(mod.ROOT),
            template.headline.font_path,
        )

    def test_the_checked_in_font_draws_every_headline(self):
        # The copy is in Russian, so this is the check that the vendored font
        # really carries Cyrillic — a font without it draws empty boxes.
        template = mod.load_templates()
        font = ImageFont.truetype(str(template.headline.font_path), template.headline.size)
        for shot in template.screenshots:
            self.assertEqual(mod.missing_glyphs(shot.headline, font), [], shot.headline)

    def test_a_headline_the_font_cannot_draw_is_refused(self):
        template = mod.load_templates()
        font = ImageFont.truetype(str(template.headline.font_path), template.headline.size)
        missing = mod.missing_glyphs('任务列表', font)
        self.assertTrue(missing, 'the vendored font has no CJK glyphs, as expected')


class HeadlineTest(unittest.TestCase):
    """The copy has to fit the picture it is drawn on."""

    def setUp(self):
        self.template = mod.load_templates()
        self.font = ImageFont.truetype(
            str(self.template.headline.font_path), self.template.headline.size
        )
        self.image = Image.new(
            'RGB', (self.template.canvas.width, self.template.canvas.height)
        )

    def test_a_word_wider_than_the_line_is_refused(self):
        # A word cannot be broken, and a caption that runs past the margin is
        # a caption cut off at the edge of a store picture.
        with self.assertRaises(mod.RenderError):
            mod.draw_headline(
                self.image,
                self.template.canvas,
                self.template.headline,
                'А' * 300,
                self.font,
            )

    def test_a_headline_with_too_many_lines_is_refused(self):
        with self.assertRaises(mod.RenderError):
            mod.draw_headline(
                self.image,
                self.template.canvas,
                self.template.headline,
                ' '.join(['слово'] * 20),
                self.font,
            )

    def test_a_headline_that_fits_is_drawn(self):
        bottom = mod.draw_headline(
            self.image,
            self.template.canvas,
            self.template.headline,
            self.template.screenshots[0].headline,
            self.font,
        )
        self.assertGreater(bottom, 0)


class PlayRuleTest(unittest.TestCase):
    """What Play accepts of a phone screenshot."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = pathlib.Path(self.dir.name)

    def test_a_picture_play_accepts_has_no_problems(self):
        path = write_png(self.root / 'good.png', (1080, 1920))
        self.assertEqual(mod.validate_play(path), [])

    def test_a_picture_that_is_too_small_is_refused(self):
        path = write_png(self.root / 'small.png', (200, 200))
        self.assertTrue(mod.validate_play(path))

    def test_a_picture_with_an_alpha_channel_is_refused(self):
        # Play asks for a 24-bit PNG: three channels, eight bits each.
        path = write_png(self.root / 'alpha.png', (1080, 1920), mode='RGBA')
        self.assertTrue(mod.validate_play(path))

    def test_a_sixteen_bit_picture_is_refused(self):
        path = self.root / 'sixteen.png'
        Image.new('I;16', (1080, 1920)).save(path, format='PNG')
        problems = mod.validate_play(path)
        self.assertTrue(any('16-bit' in problem for problem in problems), problems)

    def test_a_picture_of_more_than_eight_megabytes_is_refused(self):
        # Play takes 8 MB per screenshot, and the check is on the file's weight.
        path = write_png(self.root / 'heavy.png', (1080, 1920))
        with path.open('ab') as handle:
            handle.write(b'\0' * (mod.PLAY_MAX_BYTES + 1))
        problems = mod.validate_play(path)
        self.assertTrue(any('8 MB' in problem for problem in problems), problems)

    def test_a_picture_whose_longest_side_is_too_long_is_refused(self):
        path = write_png(self.root / 'tall.png', (400, 1000))
        self.assertTrue(mod.validate_play(path))

    def test_a_file_that_is_not_a_png_is_refused(self):
        path = self.root / 'not.png'
        path.write_bytes(b'this is not a picture')
        self.assertTrue(mod.validate_play(path))


class RenderTest(unittest.TestCase):
    """The whole run, on synthetic raw captures."""

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.root = pathlib.Path(self.dir.name)
        self.raw = self.root / 'raw'
        self.raw.mkdir()
        self.out = self.root / 'out'
        for capture in mod.load_captures().captures:
            write_png(self.raw / capture.file, (capture.width, capture.height))

    def render(self):
        """Run the renderer, without its chatter in the test output."""
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return mod.main(['--raw-dir', str(self.raw), '--out-dir', str(self.out)])

    def world_of(self, count):
        """A template and a capture list of the given size, and the raw files.

        Play's rules are about the size of the set, and the set is the number
        of compositions — so a rule about a set of one or of nine needs a world
        of one or of nine.
        """
        template = mod.load_templates()
        captures = mod.load_captures()
        shots = list(template.screenshots)[:count]
        caps = list(captures.captures)[:count]
        while len(shots) < count:
            index = len(shots) + 1
            name = f'{index:02d}-extra.png'
            shots.append(mod.Screenshot(file=name, headline='Ещё один экран'))
            caps.append(
                mod.Capture(
                    order=index,
                    file=name,
                    screen='Extra',
                    flow='extra',
                    width=caps[0].width,
                    height=caps[0].height,
                )
            )
            write_png(self.raw / name, (caps[0].width, caps[0].height))
        return (
            dataclasses.replace(template, screenshots=tuple(shots)),
            dataclasses.replace(captures, captures=tuple(caps)),
        )

    def render_world(self, count):
        """Run the renderer over a world of the given size."""
        template, captures = self.world_of(count)
        with mock.patch.object(mod, 'load_templates', return_value=template), mock.patch.object(
            mod, 'load_captures', return_value=captures
        ):
            return self.render()

    def test_the_run_renders_a_set_play_accepts(self):
        self.assertEqual(self.render(), 0)
        template = mod.load_templates()
        for shot in template.screenshots:
            self.assertTrue((self.out / shot.file).is_file(), shot.file)
            self.assertEqual(mod.validate_play(self.out / shot.file), [], shot.file)
        self.assertTrue((self.out / 'contact-sheet.png').is_file())

    def test_the_manifest_describes_the_set(self):
        self.render()
        manifest = json.loads((self.out / 'manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['locale'], 'ru-RU')
        self.assertEqual(len(manifest['screenshots']), 6)
        self.assertEqual(manifest['device_profile']['device'], 'capture_9x16')
        for entry in manifest['screenshots']:
            self.assertTrue(entry['source_capture'].endswith(entry['file']), entry)

    def test_a_capture_inside_the_repository_is_recorded_as_a_repository_path(self):
        # The manifest is read next to the pictures it describes: a path that
        # starts with somebody's home directory is noise there.
        self.assertEqual(
            mod._repo_relative(mod.ROOT / 'build' / 'store-capture' / '01-today.png'),
            'build/store-capture/01-today.png',
        )

    def test_the_same_raw_capture_renders_the_same_picture(self):
        # "The same set every time" is the whole point of rendering from
        # templates instead of editing pictures by hand.
        self.render()
        first = (self.out / '01-today.png').read_bytes()
        self.render()
        self.assertEqual(first, (self.out / '01-today.png').read_bytes())

    def test_a_missing_raw_capture_stops_the_run(self):
        (self.raw / '01-today.png').unlink()
        self.assertNotEqual(self.render(), mod.EXIT_OK)

    def test_the_raw_captures_are_not_touched(self):
        # The raw captures are the source of truth: a render that changed them
        # would leave nothing to render the next set from.
        before = {path.name: path.read_bytes() for path in sorted(self.raw.glob('*.png'))}
        self.render()
        after = {path.name: path.read_bytes() for path in sorted(self.raw.glob('*.png'))}
        self.assertEqual(before, after)

    def test_the_output_directory_may_not_be_the_raw_capture_directory(self):
        # The renderer wipes its output directory before it runs, so the output
        # directory may not be a place the raw captures live in.
        before = {path.name: path.read_bytes() for path in sorted(self.raw.glob('*.png'))}
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = mod.main(['--raw-dir', str(self.raw), '--out-dir', str(self.raw)])
        self.assertNotEqual(code, mod.EXIT_OK)
        after = {path.name: path.read_bytes() for path in sorted(self.raw.glob('*.png'))}
        self.assertEqual(before, after)

    def test_the_output_directory_may_not_hold_the_raw_captures(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = mod.main(['--raw-dir', str(self.raw), '--out-dir', str(self.root)])
        self.assertNotEqual(code, mod.EXIT_OK)
        self.assertTrue((self.raw / '01-today.png').is_file())

    def test_the_output_directory_of_somebody_else_is_not_wiped(self):
        # The renderer wipes its output directory before it runs. A directory
        # that holds files the renderer did not write is refused, not deleted.
        self.out.mkdir()
        keep = self.out / 'notes.txt'
        keep.write_text('not ours', encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = mod.main(['--raw-dir', str(self.raw), '--out-dir', str(self.out)])
        self.assertNotEqual(code, mod.EXIT_OK)
        self.assertTrue(keep.is_file())
        self.assertEqual(keep.read_text(encoding='utf-8'), 'not ours')

    def test_a_set_of_one_screenshot_is_refused(self):
        # Play wants 2 to 8 screenshots in a phone listing.
        self.assertNotEqual(self.render_world(1), mod.EXIT_OK)

    def test_a_set_of_nine_screenshots_is_refused(self):
        self.assertNotEqual(self.render_world(9), mod.EXIT_OK)

    def test_a_set_of_two_screenshots_is_accepted(self):
        self.assertEqual(self.render_world(2), mod.EXIT_OK)

    def test_the_manifest_records_where_the_pictures_are(self):
        self.render()
        manifest = json.loads((self.out / 'manifest.json').read_text(encoding='utf-8'))
        self.assertTrue(manifest['output_dir'])
        for entry in manifest['screenshots']:
            self.assertTrue(entry['output_path'].endswith(entry['file']), entry)

    def test_the_capture_manifest_decides_the_order_of_the_set(self):
        # The manifest says what is captured, in which order. The set follows
        # it — not the order the templates happen to list the compositions in.
        template = mod.load_templates()
        captures = mod.load_captures()
        reordered = dataclasses.replace(captures, captures=tuple(reversed(captures.captures)))
        with mock.patch.object(mod, 'load_templates', return_value=template), mock.patch.object(
            mod, 'load_captures', return_value=reordered
        ):
            self.assertEqual(self.render(), 0)
        manifest = json.loads((self.out / 'manifest.json').read_text(encoding='utf-8'))
        self.assertEqual(
            [entry['file'] for entry in manifest['screenshots']],
            [capture.file for capture in reordered.captures],
        )


if __name__ == '__main__':
    unittest.main()
