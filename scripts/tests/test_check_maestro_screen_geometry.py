"""Tests for check-maestro-screen-geometry.py.

The gate exists because a flow that names a pixel is a claim about one display.
Measured on a physical phone (2026-10-28, realme RMX3938, SDK 35, 720×1600
@ 320dpi): the flows that scrolled the settings rail by pixel never reached the
trailing tabs, and the flows that opened the menu sheet and tapped its last
item tapped the system navigation bar instead of the item. On the CI emulator
(1080×2400) those flows are green — a statement about the emulator that reads
like a statement about the app.

The properties the tests hold:

  - a swipe in pixels is a finding — the gesture only exists on that display;
  - a swipe as a share of the screen is fine — the same gesture, scaled;
  - a swipe addressed to the element that scrolls is fine — the display is not
    part of the claim at all;
  - a tap on a pixel is a finding;
  - the gate's own tree is clean, so a green gate means the tree is clean and
    not that the gate is looking in the wrong place.
"""

import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-maestro-screen-geometry.py'
spec = importlib.util.spec_from_file_location('check_maestro_screen_geometry', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

HEADER = 'appId: com.singularity.todo\nname: probe\n---\n'


def flow(*commands: str) -> str:
    """A flow file with the given command lines."""
    return HEADER + ''.join(f'- {command}\n' for command in commands)


class PixelCoordinatesTest(unittest.TestCase):
    def _findings(self, body: str):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            path = root / 'probe.yaml'
            path.write_text(body, encoding='utf-8')
            return mod.check_flow(path, root)

    def test_a_swipe_in_pixels_is_a_finding(self):
        findings = self._findings(flow('swipe:\n    start: 105, 2000\n    end: 105, 1600'))
        self.assertEqual(2, len(findings), 'both ends of the swipe name a pixel')
        self.assertTrue(all(f.command == 'swipe' for f in findings))

    def test_a_swipe_in_pixels_without_a_space_is_a_finding(self):
        findings = self._findings(flow('swipe:\n    start: 105,2000'))
        self.assertEqual(1, len(findings))

    def test_a_swipe_as_a_share_of_the_screen_is_fine(self):
        findings = self._findings(flow('swipe:\n    start: "50%, 88%"\n    end: "50%, 62%"'))
        self.assertEqual([], findings, 'a share of the screen is the same gesture everywhere')

    def test_a_swipe_on_the_element_that_scrolls_is_fine(self):
        findings = self._findings(
            flow('swipe:\n    direction: UP\n    element:\n      id: settings_nav_rail_scroll')
        )
        self.assertEqual([], findings, 'the display is not part of the claim')

    def test_a_tap_on_a_pixel_is_a_finding(self):
        findings = self._findings(flow('tapOn:\n    point: "10, 20"'))
        self.assertEqual(1, len(findings))
        self.assertEqual('tapOn', findings[0].command)

    def test_a_tap_on_an_element_is_fine(self):
        findings = self._findings(flow('tapOn:\n    id: tasks_fab'))
        self.assertEqual([], findings)

    def test_a_flow_without_geometry_is_clean(self):
        findings = self._findings(flow('tapOn:\n    id: tasks_fab', 'hideKeyboard'))
        self.assertEqual([], findings)


class ExceptionsRegistryTest(unittest.TestCase):
    """The registry is where a reason is written down."""

    def _check(self, body: str, exceptions):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            path = root / 'probe.yaml'
            path.write_text(body, encoding='utf-8')
            return mod.check_flows(root, exceptions)

    def test_a_flow_without_an_exception_is_a_finding(self):
        findings = self._check(flow('swipe:\n    start: 105, 2000'), {})
        self.assertEqual(1, len(findings))

    def test_a_listed_flow_is_not_a_finding(self):
        findings = self._check(
            flow('swipe:\n    start: 105, 2000'),
            {'probe.yaml': 'the rail does not scroll under a Maestro swipe'},
        )
        self.assertEqual([], findings)

    def test_a_listed_flow_without_a_reason_is_a_finding(self):
        findings = self._check(flow('swipe:\n    start: 105, 2000'), {'probe.yaml': ''})
        self.assertEqual(1, len(findings))
        self.assertEqual('exception', findings[0].command)

    def test_a_stale_exception_is_a_finding(self):
        findings = self._check(flow('tapOn:\n    id: tasks_fab'), {'probe.yaml': 'a reason'})
        self.assertEqual(1, len(findings))
        self.assertEqual('exception', findings[0].command)

    def test_the_repository_registry_covers_every_pixel(self):
        exceptions = mod.read_exceptions(mod.EXCEPTIONS_FILE)
        self.assertTrue(exceptions, 'the registry exists and is not empty')
        for flow_path, reason in exceptions.items():
            self.assertTrue(reason, f'{flow_path} must say why')


class RealTreeTest(unittest.TestCase):
    def test_the_repository_flows_are_clean(self):
        """The gate must pass on the tree it guards.

        A gate that fails on the tree it was written for is either broken or
        the tree is wrong — and the first thing to know is which.
        """
        findings = mod.check_flows(mod.DEFAULT_FLOWS_DIR, mod.read_exceptions(mod.EXCEPTIONS_FILE))
        self.assertEqual(
            [],
            [f._asdict() for f in findings],
            'no flow may name a pixel: the gesture has to mean the same on every display',
        )


if __name__ == '__main__':
    unittest.main()
