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
import shutil
import struct
import sys
import tempfile
import unittest
import unittest.mock as mock
import zlib

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'store-capture.py'
spec = importlib.util.spec_from_file_location('store_capture', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# The module has to be in `sys.modules` before it is executed: a dataclass
# looks its own module up there while it is being built.
sys.modules[spec.name] = mod
spec.loader.exec_module(mod)


def png_chunk(kind, body):
    """A PNG chunk: its length, its name, its body and its checksum."""
    crc = zlib.crc32(kind + body) & 0xffffffff
    return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)


def png_bytes(width, height, *, broken_data=False, cut=False, empty_scanlines=False, bad_crc=False,
              ihdr=None, filter_byte=0, before_header=b'', iend_body=b'', trailing=b'',
              second_header=False, extra_chunks=b''):
    """A PNG of the given size, optionally damaged in one of several ways."""
    def chunk(kind, body, break_crc=False):
        crc = zlib.crc32(kind + body) & 0xffffffff
        if break_crc:
            crc ^= 0xffffffff
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

    raw = b''.join(bytes([filter_byte]) + b'\x00' * (width * 3) for _ in range(height))
    image = zlib.compress(raw)
    if broken_data:
        image = image[:-4]
    if empty_scanlines:
        # A stream that decompresses, but carries none of the pixels the
        # header promises.
        image = zlib.compress(b'')
    header = ihdr if ihdr is not None else (width, height, 8, 2, 0, 0, 0)
    head = chunk(b'IHDR', struct.pack('>IIBBBBB', *header))
    data = (
        mod.PNG_SIGNATURE
        + before_header
        + head
        + (head if second_header else b'')
        + chunk(b'IDAT', image, break_crc=bad_crc)
        + extra_chunks
        + chunk(b'IEND', iend_body)
        + trailing
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

    def test_two_captures_with_the_same_order_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text(
                'captures:\n'
                '  - {order: 1, file: a.png, flow: a.yaml}\n'
                '  - {order: 1, file: b.png, flow: b.yaml}\n',
                encoding='utf-8',
            )
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)

    def test_two_captures_with_the_same_file_name_are_refused(self):
        # Two captures writing one file: the second would overwrite the first.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text(
                'captures:\n'
                '  - {order: 1, file: a.png, flow: a.yaml}\n'
                '  - {order: 2, file: a.png, flow: b.yaml}\n',
                encoding='utf-8',
            )
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)

    def test_a_malformed_capture_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text('captures:\n  - {order: 1}\n', encoding='utf-8')
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)

    def test_a_malformed_profile_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text(
                'profile:\n  aspect: "nine"\n'
                'captures:\n  - {order: 1, file: a.png, flow: a.yaml}\n',
                encoding='utf-8',
            )
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)


    def test_a_profile_without_a_size_is_refused(self):
        # The profile is the contract every image is measured against: one
        # without a size does not say what the images have to look like.
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text(
                'profile:\n  aspect: "9:16"\n'
                'captures:\n  - {order: 1, file: a.png, flow: a.yaml}\n',
                encoding='utf-8',
            )
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)

    def test_a_profile_without_an_aspect_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'manifest.yaml'
            path.write_text(
                'profile:\n  width: 1080\n  height: 1920\n'
                'captures:\n  - {order: 1, file: a.png, flow: a.yaml}\n',
                encoding='utf-8',
            )
            with self.assertRaises(mod.CaptureError):
                mod.load_manifest(path)


class DeviceResolutionTest(unittest.TestCase):
    def test_the_daemon_chatter_is_not_a_device(self):
        text = ('List of devices attached\n'
                '* daemon not running; starting now at tcp:5037\n'
                'emulator-5554\tdevice\n')
        self.assertEqual(mod.parse_devices(text), [('emulator-5554', 'device')])

    def test_an_unnamed_device_is_refused(self):
        # One attached device is not a choice: it may be somebody's phone.
        with self.assertRaises(mod.CaptureError):
            mod.resolve_device('', [('emulator-5554', 'device')])

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


class IdentityTest(unittest.TestCase):
    """The provenance rules: what the APK is, and what the device carries."""

    def verify(self, *, identity, installed, sha, built_here):
        with mock.patch.object(mod, 'apk_identity', return_value=identity), \
                mock.patch.object(mod, 'installed_version', return_value=installed), \
                mock.patch.object(mod, 'git_sha', return_value=sha):
            return mod.verify_identity(
                'emulator-5554', pathlib.Path('/tmp/app-debug.apk'), built_here
            )

    def test_the_badging_output_is_read(self):
        output = ("package: name='com.singularity.todo' versionCode='1' "
                  "versionName='0.1.0+gabc'\n")
        self.assertEqual(
            mod._parse_badging(output), ('com.singularity.todo', '0.1.0+gabc')
        )

    def test_an_apk_that_cannot_be_read_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            self.verify(identity=None, installed='0.1.0', sha='abc', built_here=True)

    def test_another_application_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            self.verify(
                identity=('com.other.app', '0.1.0'), installed='0.1.0',
                sha='abc', built_here=True,
            )

    def test_an_app_that_is_not_installed_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            self.verify(
                identity=('com.singularity.todo', '0.1.0'), installed=None,
                sha='abc', built_here=True,
            )

    def test_a_stale_install_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            self.verify(
                identity=('com.singularity.todo', '0.1.0'), installed='0.0.9',
                sha='abc', built_here=True,
            )

    def test_this_checkouts_build_is_accepted(self):
        package, version = self.verify(
            identity=('com.singularity.todo', '0.1.0+gabc'),
            installed='0.1.0+gabc', sha='abc', built_here=True,
        )
        self.assertEqual((package, version), ('com.singularity.todo', '0.1.0+gabc'))

    def test_a_build_from_another_commit_is_refused(self):
        with self.assertRaises(mod.CaptureError):
            self.verify(
                identity=('com.singularity.todo', '0.1.0+gabc'),
                installed='0.1.0+gabc', sha='def', built_here=True,
            )

    def test_a_foreign_apk_is_not_held_to_this_checkouts_sha(self):
        package, _ = self.verify(
            identity=('com.singularity.todo', '0.1.0'), installed='0.1.0',
            sha='def', built_here=False,
        )
        self.assertEqual(package, 'com.singularity.todo')


class ProfileApplicationTest(unittest.TestCase):
    """The profile is a contract: the device has to end up in it."""

    def device(self, overrides=None):
        """A device that answers the profile the way the capture expects."""
        overrides = overrides or {}
        answers = {
            ('shell', 'run-as', mod.APP_ID, 'rm'): (0, ''),
            ('shell', 'run-as', mod.APP_ID, 'ls'): (1, 'No such file or directory'),
            ('shell', 'cmd', 'locale', 'set-app-locales'): (0, ''),
            ('shell', 'cmd', 'locale', 'get-app-locales'): (
                0, 'Locales for com.singularity.todo for user 0 are [ru-RU]'
            ),
            ('shell', 'cmd', 'uimode', 'night', 'no'): (0, ''),
            ('shell', 'cmd', 'uimode', 'night'): (0, 'Night mode: no'),
            ('shell', 'settings', 'put', 'system', 'font_scale'): (0, ''),
            ('shell', 'settings', 'get', 'system', 'font_scale'): (0, '1.0'),
            ('shell', 'settings', 'put', 'system', 'screen_off_timeout'): (0, ''),
            ('shell', 'dumpsys', 'notification'): (0, 'Current Notification Manager state:\n'),
        }

        def fake_adb(*args, serial='', timeout=60):
            key = tuple(args)
            for prefix, answer in overrides.items():
                if key[:len(prefix)] == prefix:
                    return answer
            for prefix, answer in answers.items():
                if key[:len(prefix)] == prefix:
                    return answer
            return 0, ''

        return fake_adb

    def test_the_apps_own_settings_are_reset(self):
        # The theme and the font scale are the app's settings: without this
        # step a device that was once in the dark theme stays in it.
        calls = []
        device = self.device()

        def fake_adb(*args, serial='', timeout=60):
            calls.append((args, serial))
            return device(*args, serial=serial, timeout=timeout)

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            warnings = mod.apply_profile('emulator-5554')

        self.assertEqual(warnings, [])
        self.assertTrue(
            any('run-as' in ' '.join(args) for args, _ in calls),
            [args for args, _ in calls],
        )

    def test_a_device_that_refuses_the_reset_is_refused(self):
        # The capture would be published in the app's own old look, and the
        # image check cannot see the difference.
        device = self.device(
            {('shell', 'run-as', mod.APP_ID, 'rm'): (1, 'run-as: Package not debuggable')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_settings_file_that_survives_the_reset_is_refused(self):
        device = self.device(
            {('shell', 'run-as', mod.APP_ID, 'ls'): (0, 'files/user_settings.preferences_pb')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_settings_file_that_cannot_be_read_back_is_refused(self):
        # A listing that failed for another reason than "there is no such
        # file" proves nothing about the file being gone.
        device = self.device(
            {('shell', 'run-as', mod.APP_ID, 'ls'): (1, 'run-as: Permission denied')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_an_app_that_cannot_be_stopped_is_refused(self):
        # A running app keeps its loaded settings in memory: removing the
        # file under it would leave the old theme on the screen.
        device = self.device(
            {('shell', 'am', 'force-stop'): (1, 'Error: could not stop')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_theme_that_does_not_take_is_refused(self):
        # The device says it set the light theme and still reports the dark
        # one — the captures would be the wrong theme.
        device = self.device(
            {('shell', 'cmd', 'uimode', 'night'): (0, 'Night mode: yes')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_locale_that_does_not_take_is_refused(self):
        device = self.device(
            {
                ('shell', 'cmd', 'locale', 'get-app-locales'): (
                    0, 'Locales for com.singularity.todo for user 0 are [en-US]'
                )
            }
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_locale_that_is_only_a_fallback_is_refused(self):
        # Android keeps an ordered list of locales: the first one is the
        # language the app is shown in. A device that reports English first
        # and Russian as a fallback would capture the app in English.
        device = self.device(
            {
                ('shell', 'cmd', 'locale', 'get-app-locales'): (
                    0, 'Locales for com.singularity.todo for user 0 are [en-US, ru-RU]'
                )
            }
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_a_font_scale_that_does_not_take_is_refused(self):
        device = self.device(
            {('shell', 'settings', 'get', 'system', 'font_scale'): (0, '1.3')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            with self.assertRaises(mod.CaptureError):
                mod.apply_profile('emulator-5554')

    def test_the_screen_timeout_only_costs_the_screen(self):
        # The timeout changes no pixel, so a device that refuses it is a
        # warning and not a refusal.
        device = self.device(
            {('shell', 'settings', 'put', 'system', 'screen_off_timeout'): (1, 'denied')}
        )
        with mock.patch.object(mod, 'adb', side_effect=device):
            warnings = mod.apply_profile('emulator-5554')

        self.assertTrue(any('screen timeout' in warning for warning in warnings), warnings)


class StatusBarTest(unittest.TestCase):
    """The status bar shows the clock only."""

    def test_the_notifications_of_the_device_are_read(self):
        dump = (
            '  Notification List:\n'
            '    NotificationRecord(0x1: pkg=android user=UserHandle{0} id=19 ...)\n'
            '    NotificationRecord(0x2: pkg=com.example.chat user=UserHandle{0} id=7 ...)\n'
        )
        self.assertEqual(mod.notification_packages(dump), ['android', 'com.example.chat'])

    def test_a_device_without_notifications_is_clean(self):
        self.assertEqual(mod.notification_packages('Current Notification Manager state:\n'), [])

    def test_a_pending_notification_refuses_the_capture(self):
        def fake_adb(*args, serial='', timeout=60):
            if 'dumpsys' in args:
                return 0, ('    NotificationRecord(0x2: pkg=com.example.chat '
                           'user=UserHandle{0} id=7 ...)\n')
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            with self.assertRaises(mod.CaptureError) as caught:
                mod.clean_status_bar('emulator-5554')

        self.assertIn('com.example.chat', str(caught.exception))

    def test_the_system_notifications_do_not_refuse_the_capture(self):
        def fake_adb(*args, serial='', timeout=60):
            if 'dumpsys' in args:
                return 0, '    NotificationRecord(0x1: pkg=android user=UserHandle{0} id=19 ...)\n'
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            self.assertEqual(mod.clean_status_bar('emulator-5554'), [])

    def test_a_dump_that_holds_no_list_is_refused(self):
        # A dump in a format this checker does not know is not an empty
        # notification list: it is a status bar nobody has read.
        with self.assertRaises(ValueError):
            mod.notification_packages('something entirely different\n')

    def test_the_battery_percentage_is_hidden(self):
        calls = []

        def fake_adb(*args, serial='', timeout=60):
            calls.append(args)
            if 'dumpsys' in args:
                return 0, 'Current Notification Manager state:\n'
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            mod.clean_status_bar('emulator-5554')

        self.assertTrue(
            any('status_bar_show_battery_percent' in ' '.join(args) for args in calls),
            calls,
        )

    def test_a_notification_dump_that_cannot_be_read_refuses_the_capture(self):
        # An inspection that did not run proves nothing: an unread status bar
        # is not a clean one.
        def fake_adb(*args, serial='', timeout=60):
            if 'dumpsys' in args:
                return 1, 'Error: dumpsys failed'
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            with self.assertRaises(mod.CaptureError) as caught:
                mod.clean_status_bar('emulator-5554')

        self.assertIn('notifications', str(caught.exception))

    def test_a_notification_without_a_readable_package_is_refused(self):
        # The dump says a notification is there and does not say whose it is.
        # Nobody can clear that, so the capture is refused.
        dump = '    NotificationRecord(0x3: user=UserHandle{0} id=21 ...)\n'
        with self.assertRaises(ValueError):
            mod.notification_packages(dump)

        def fake_adb(*args, serial='', timeout=60):
            if 'dumpsys' in args:
                return 0, dump
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            with self.assertRaises(mod.CaptureError):
                mod.clean_status_bar('emulator-5554')

    def test_a_shade_that_cannot_be_collapsed_refuses_the_capture(self):
        def fake_adb(*args, serial='', timeout=60):
            if 'statusbar' in args:
                return 1, 'Error: no status bar'
            return 0, ''

        with mock.patch.object(mod, 'adb', side_effect=fake_adb):
            with self.assertRaises(mod.CaptureError) as caught:
                mod.clean_status_bar('emulator-5554')

        self.assertIn('shade', str(caught.exception))


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

    def test_an_image_without_pixels_is_refused(self):
        # The header promises 1080x1920, and the stream decompresses — but it
        # carries none of the scanlines it promised.
        problems = self.check(png_bytes(1080, 1920, empty_scanlines=True))
        self.assertTrue(any('image data' in problem for problem in problems), problems)

    def test_a_broken_checksum_is_refused(self):
        problems = self.check(png_bytes(1080, 1920, bad_crc=True))
        self.assertTrue(any('checksum' in problem for problem in problems), problems)

    def test_an_interlaced_image_is_refused(self):
        # An interlaced image has a scanline layout this checker cannot
        # count, so the pixels behind the header are never looked at — it
        # has to be refused rather than waved through.
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(1080, 1920, 8, 2, 0, 0, 1))
        )
        self.assertTrue(any('interlaced' in problem for problem in problems), problems)

    def test_an_unknown_colour_type_is_refused(self):
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(1080, 1920, 8, 9, 0, 0, 0))
        )
        self.assertTrue(any('colour type' in problem for problem in problems), problems)

    def test_an_illegal_colour_type_and_bit_depth_pair_is_refused(self):
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(1080, 1920, 1, 2, 0, 0, 0))
        )
        self.assertTrue(any('legal pair' in problem for problem in problems), problems)

    def test_an_unknown_compression_method_is_refused(self):
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(1080, 1920, 8, 2, 3, 0, 0))
        )
        self.assertTrue(any('compression method' in problem for problem in problems), problems)

    def test_an_unknown_filter_method_is_refused(self):
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(1080, 1920, 8, 2, 0, 7, 0))
        )
        self.assertTrue(any('filter method' in problem for problem in problems), problems)

    def test_an_image_without_a_size_is_refused(self):
        problems = self.check(
            png_bytes(1080, 1920, empty_scanlines=True, ihdr=(0, 0, 8, 2, 0, 0, 0))
        )
        self.assertTrue(any('not an image' in problem for problem in problems), problems)

    def test_an_illegal_filter_byte_is_refused(self):
        # PNG knows five filters; a sixth one is not a picture anybody can
        # decode, even with the right size and the right number of bytes.
        problems = self.check(png_bytes(1080, 1920, filter_byte=5))
        self.assertTrue(any('filter' in problem for problem in problems), problems)

    def test_a_header_that_is_too_long_is_refused(self):
        # The image header is exactly thirteen bytes; a longer chunk that
        # pretends to be one is not a header.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        header = struct.pack('>IIBBBBB', 1080, 1920, 8, 2, 0, 0, 0) + b'\x00'
        data = (
            mod.PNG_SIGNATURE
            + chunk(b'IHDR', header)
            + chunk(b'IDAT', zlib.compress(b''))
            + chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('malformed' in problem for problem in problems), problems)

    def test_a_chunk_before_the_header_is_refused(self):
        # The header comes first: a file that starts with anything else does
        # not know what it is.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        problems = self.check(png_bytes(1080, 1920, before_header=chunk(b'PLTE', b'\x00\x00\x00')))
        self.assertTrue(any('before its image header' in problem for problem in problems), problems)

    def test_a_second_header_is_refused(self):
        problems = self.check(png_bytes(1080, 1920, second_header=True))
        self.assertTrue(any('more than one image header' in problem for problem in problems), problems)

    def test_image_data_before_the_header_is_refused(self):
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        problems = self.check(png_bytes(1080, 1920, before_header=chunk(b'IDAT', b'\x00')))
        self.assertTrue(any('image data before' in problem for problem in problems), problems)

    def test_data_in_the_end_marker_is_refused(self):
        problems = self.check(png_bytes(1080, 1920, iend_body=b'junk'))
        self.assertTrue(any('end marker' in problem for problem in problems), problems)

    def test_data_after_the_end_marker_is_refused(self):
        problems = self.check(png_bytes(1080, 1920, trailing=b'junk'))
        self.assertTrue(any('after its end marker' in problem for problem in problems), problems)

    def test_an_unknown_critical_chunk_is_refused(self):
        # A chunk with a capital-letter name is critical: a decoder that does
        # not know it has to refuse the file.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        problems = self.check(png_bytes(1080, 1920, extra_chunks=chunk(b'ABCD', b'\x00')))
        self.assertTrue(any('unknown critical chunk' in problem for problem in problems), problems)

    def test_an_unknown_ancillary_chunk_is_allowed(self):
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        self.assertEqual(self.check(png_bytes(1080, 1920, extra_chunks=chunk(b'vpAg', b'\x00'))), [])

    def test_image_data_that_is_not_one_run_is_refused(self):
        # The image data of a PNG is one run of chunks: a chunk between two of
        # them is a file nobody can decode.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        raw = b''.join(b'\x00' + b'\x00' * (1080 * 3) for _ in range(1920))
        image = zlib.compress(raw)
        half = len(image) // 2
        data = (
            mod.PNG_SIGNATURE
            + chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', image[:half])
            + chunk(b'tEXt', b'Comment\x00hello')
            + chunk(b'IDAT', image[half:])
            + chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('image data after another chunk' in problem for problem in problems), problems)

    def test_an_empty_image_data_chunk_does_not_hide_a_split_run(self):
        # An empty IDAT is legal, and a file that hides one between two
        # chunks of real data must still be caught: the run of image data
        # is broken by the text chunk in the middle.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        raw = b''.join(b'\x00' + b'\x00' * (1080 * 3) for _ in range(1920))
        image = zlib.compress(raw)
        data = (
            mod.PNG_SIGNATURE
            + chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', b'')
            + chunk(b'tEXt', b'Comment\x00hello')
            + chunk(b'IDAT', image)
            + chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('image data after another chunk' in problem for problem in problems), problems)

    def test_an_indexed_image_without_a_palette_is_refused(self):
        # The pixels of an indexed image are indices into a palette; without
        # one there is no picture to show.
        raw = b''.join(b'\x00' + b'\x00' * (1080 * 1) for _ in range(1920))
        data = (
            mod.PNG_SIGNATURE
            + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 3, 0, 0, 0))
            + png_chunk(b'IDAT', zlib.compress(raw))
            + png_chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('palette' in problem for problem in problems), problems)

    def test_an_indexed_image_with_a_palette_is_accepted(self):
        raw = b''.join(b'\x00' + b'\x00' * (1080 * 1) for _ in range(1920))
        data = (
            mod.PNG_SIGNATURE
            + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 3, 0, 0, 0))
            + png_chunk(b'PLTE', b'\x00\x00\x00\xff\xff\xff')
            + png_chunk(b'IDAT', zlib.compress(raw))
            + png_chunk(b'IEND', b'')
        )
        self.assertEqual(self.check(data), [])

    def test_a_malformed_palette_is_refused(self):
        # A palette is a whole number of three-byte entries.
        raw = b''.join(b'\x00' + b'\x00' * (1080 * 1) for _ in range(1920))
        data = (
            mod.PNG_SIGNATURE
            + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 3, 0, 0, 0))
            + png_chunk(b'PLTE', b'\x00\x00\x00\xff')
            + png_chunk(b'IDAT', zlib.compress(raw))
            + png_chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('palette' in problem for problem in problems), problems)

    def test_a_palette_after_the_image_data_is_refused(self):
        raw = b''.join(b'\x00' + b'\x00' * (1080 * 1) for _ in range(1920))
        data = (
            mod.PNG_SIGNATURE
            + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 3, 0, 0, 0))
            + png_chunk(b'IDAT', zlib.compress(raw))
            + png_chunk(b'PLTE', b'\x00\x00\x00\xff\xff\xff')
            + png_chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('palette' in problem for problem in problems), problems)

    def test_a_second_palette_is_refused(self):
        # A PNG carries at most one palette.
        raw = b''.join(b'\x00' + b'\x00' * (1080 * 1) for _ in range(1920))
        data = (
            mod.PNG_SIGNATURE
            + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 3, 0, 0, 0))
            + png_chunk(b'PLTE', b'\x00\x00\x00\xff\xff\xff')
            + png_chunk(b'PLTE', b'\x00\xff\x00\xff\x00\xff')
            + png_chunk(b'IDAT', zlib.compress(raw))
            + png_chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('more than one palette' in problem for problem in problems), problems)

    def test_a_chunk_with_a_malformed_name_is_refused(self):
        # A chunk is named by four letters, and the third of them carries the
        # reserved bit. A name that is not that is not a chunk name.
        problems = self.check(png_bytes(1080, 1920, extra_chunks=png_chunk(b'\x01bad', b'\x00')))
        self.assertTrue(any('malformed name' in problem for problem in problems), problems)

    def test_a_chunk_with_a_lowercase_reserved_bit_is_refused(self):
        problems = self.check(png_bytes(1080, 1920, extra_chunks=png_chunk(b'abcd', b'\x00')))
        self.assertTrue(any('malformed name' in problem for problem in problems), problems)

    def test_data_after_the_compressed_image_is_refused(self):
        # The compressed data of a PNG is one zlib stream and nothing else.
        def chunk(kind, body):
            crc = zlib.crc32(kind + body) & 0xffffffff
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', crc)

        raw = b''.join(b'\x00' + b'\x00' * (1080 * 3) for _ in range(1920))
        image = zlib.compress(raw) + b'junk'
        data = (
            mod.PNG_SIGNATURE
            + chunk(b'IHDR', struct.pack('>IIBBBBB', 1080, 1920, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', image)
            + chunk(b'IEND', b'')
        )
        problems = self.check(data)
        self.assertTrue(any('after its compressed image data' in problem for problem in problems), problems)

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


class CaptureOrderTest(unittest.TestCase):
    """The status bar is checked right before the picture is taken."""

    def run_capture(self, calls):
        output = mod.ROOT / 'build' / 'store-capture-test'
        shutil.rmtree(output, ignore_errors=True)
        output.mkdir(parents=True, exist_ok=True)
        try:

            def fake_screenshot(serial, path):
                calls.append('screenshot')
                path.write_bytes(png_bytes(1080, 1920))

            def fake_check(serial):
                calls.append('check')

            with mock.patch.object(mod, 'prepare_output_dir', return_value=output), \
                    mock.patch.object(mod, 'resolve_device', return_value='emulator-5554'), \
                    mock.patch.object(mod, 'attached_devices', return_value=[]), \
                    mock.patch.object(mod, 'build_capture_apk', return_value=pathlib.Path('app.apk')), \
                    mock.patch.object(mod, 'install_apk'), \
                    mock.patch.object(mod, 'verify_identity', return_value=('pkg', '1.0')), \
                    mock.patch.object(mod, 'apply_profile', return_value=[]), \
                    mock.patch.object(mod, 'run_flow', side_effect=lambda *a, **k: calls.append('flow') or True), \
                    mock.patch.object(mod, 'assert_clean_status_bar', side_effect=fake_check), \
                    mock.patch.object(mod, 'capture_screenshot', side_effect=fake_screenshot):
                code = mod.main(['--flow', '01-today', '--serial', 'emulator-5554'])
        finally:
            shutil.rmtree(output, ignore_errors=True)
        return code

    def test_the_notifications_are_checked_again_before_the_screenshot(self):
        # A notification that arrives while the flow plays would be in the
        # picture, so the status bar is looked at again after the flow and
        # before the screen is taken.
        calls = []
        self.assertEqual(0, self.run_capture(calls))
        self.assertEqual(calls, ['flow', 'check', 'screenshot'])


if __name__ == '__main__':
    unittest.main()
