"""Tests for store-capture.py — the local store-screenshot command.

The command decides two things that a store screenshot depends on and that
nothing else in the repository checks:

  * *what* is captured — the manifest's captures, in the manifest's order;
  * *whether the capture is real* — the image is a PNG, it is complete, and it
    is the size and shape the storyboard promises.

The first is a question about the files in the tree, and the second is a
question about bytes. Both are pure, so both are tested here; the device
work around them is what the command is for, not what these tests are for.
"""

import importlib.util
import pathlib
import struct
import sys
import tempfile
import unittest
import zlib

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'store-capture.py'
spec = importlib.util.spec_from_file_location('store_capture', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# The module has to be in `sys.modules` before it is executed: a dataclass
# looks its own module up there while it is being built.
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def png_bytes(width, height, *, broken_data=False, cut=False):
    """A PNG of the given size, optionally damaged in one of two ways."""
    def chunk(kind, body):
        crc = zlib.crc32(kind + body) & 0xffffffff
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

    raw = b''.join(b'\x00' + b'\x00' * (width * 3) for _ in range(height))
    image = zlib.compress(raw)
    if broken_data:
        image = image[:-4]
    data = (
        mod.PNG_SIGNATURE
        + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
        + chunk(b'IDAT', image)
        + chunk(b'IEND', b'')
    )
    if cut:
        data = data[:-24]
    return data


class ManifestTest(unittest.TestCase):
    def test_the_real_manifest_lists_the_captures_in_order(self):
        manifest = mod.load_manifest()
        self.assertEqual([c.order for c in manifest.captures], [1, 2, 3, 4, 5, 6])
        self.assertEqual(manifest.captures[0].file, '01-today.png')
        self.assertEqual(manifest.captures[-1].file, '06-notes.png')
        for capture in manifest.captures:
            self.assertTrue((mod.ROOT / capture.flow).is_file(), capture.flow)

    def test_the_manifest_carries_the_profile_the_images_are_measured_against(self):
        profile = mod.load_manifest().profile
        self.assertEqual((profile.width, profile.height), (1080, 1920))
        self.assertEqual(profile.aspect, (9, 16))
        self.assertEqual(profile.format, 'png')

    def test_one_capture_can_be_named(self):
        captures = mod.load_manifest().captures
        self.assertEqual([c.file for c in mod.select_captures(captures, '02-task-detail')],
                         ['02-task-detail.png'])
        self.assertEqual([c.file for c in mod.select_captures(captures, '02-task-detail.png')],
                         ['02-task-detail.png'])

    def test_an_unknown_capture_is_refused(self):
        captures = mod.load_manifest().captures
        with self.assertRaises(mod.CaptureError):
            mod.select_captures(captures, '99-nothing')

    def test_a_manifest_without_captures_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text('captures: []\n', encoding='utf-8')
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)


class DeviceResolutionTest(unittest.TestCase):
    def test_the_daemon_chatter_is_not_a_device(self):
        text = ('List of devices attached\n'
                '* daemon not running; starting now at tcp:5037\n'
                'emulator-5554\tdevice\n')
        self.assertEqual(mod.parse_devices(text), [('emulator-5554', 'device')])

    def test_one_attached_device_is_the_device(self):
        self.assertEqual(
            mod.resolve_device('', [('emulator-5554', 'device')]),
            'emulator-5554',
        )

    def test_a_pinned_device_is_the_device(self):
        devices = [('emulator-5554', 'device'), ('0O65321F', 'device')]
        self.assertEqual(mod.resolve_device('0O65321F', devices), '0O65321F')

    def test_a_missing_device_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            mod.resolve_device('emulator-5554', [('0O65321F', 'device')])

    def test_an_ambiguous_device_is_refused(self):
        devices = [('emulator-5554', 'device'), ('0O65321F', 'device')]
        with self.assertRaises(mod.CaptureError):
            mod.resolve_device('', devices)

    def test_no_device_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            mod.resolve_device('', [])

    def test_an_offline_device_is_not_usable(self):
        with self.assertRaises(mod.CaptureError):
            mod.resolve_device('emulator-5554', [('emulator-5554', 'offline')])


class ImageValidationTest(unittest.TestCase):
    profile = mod.Profile(width=1080, height=1920, aspect=(9, 16), format='png')

    def check(self, data):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'shot.png'
            path.write_bytes(data)
            return mod.validate_png(path, self.profile)

    def test_a_good_image_has_no_problems(self):
        self.assertEqual(self.check(png_bytes(1080, 1920)), [])

    def test_an_empty_file_is_refused(self):
        self.assertTrue(self.check(b''))

    def test_a_file_that_is_not_a_png_is_refused(self):
        self.assertIn('not a PNG', ' '.join(self.check(b'JFIF' + b'\x00' * 100)))

    def test_a_truncated_file_is_refused(self):
        self.assertTrue(self.check(png_bytes(1080, 1920, cut=True)))

    def test_corrupt_image_data_is_refused(self):
        self.assertTrue(self.check(png_bytes(1080, 1920, broken_data=True)))

    def test_the_wrong_size_is_refused(self):
        problems = self.check(png_bytes(1080, 1440))
        self.assertTrue(any('1080x1920' in problem for problem in problems))

    def test_the_wrong_aspect_is_refused(self):
        problems = self.check(png_bytes(1080, 1440))
        self.assertTrue(any('9:16' in problem for problem in problems))

    def test_a_missing_file_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            problems = mod.validate_png(pathlib.Path(tmp) / 'gone.png', self.profile)
        self.assertTrue(problems)


if __name__ == '__main__':
    unittest.main()
