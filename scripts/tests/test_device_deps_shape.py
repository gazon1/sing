"""Tests for the shape of `DeviceDeps` — the seam tests substitute fakes into.

`DeviceDeps` is the bundle every device-layer call goes through. It is a
dataclass with an explicit list of fields, and the fields carry the
defaults that let a bundle say only what it cares about:

  - `prepare_device` defaults to a NO-OP. The preparation is an action on a
    real device, and a bundle that only asserts a flow's behaviour has no
    device to wake;
  - the other defaulted fields carry their real implementation, so a bundle
    that does not name them still gets the real thing.

Two things must not drift:

  - a field must not lose its default, because that turns "I do not care"
    into "please call this on a real device" — or into "this is broken";
  - a real implementation must stay real. Substituting a stub for one of
    them in the *bundle* is how a contract test stops testing the contract.

The field set is compared against `_REAL_IMPLS` in
`scripts/tests/test_device_contracts.py`, which is the authority on "which
implementation belongs to which field".
"""

import dataclasses
import unittest

from emulator.maestro._types import (
    DeviceDeps,
    _app_installed,
    _device_api_level,
    _junit_reports_device_failure,
    _prepare_device,
)


class DeviceDepsShapeTest(unittest.TestCase):

    def test_it_is_a_dataclass(self):
        self.assertTrue(
            dataclasses.is_dataclass(DeviceDeps),
            "DeviceDeps must be a dataclass, so the field list is the contract",
        )

    def test_every_field_is_declared_explicitly(self):
        """The fields are written out, not inherited from a base class."""
        declared = {f.name for f in dataclasses.fields(DeviceDeps)}
        self.assertEqual(
            declared,
            set(DeviceDeps.__dataclass_fields__),
            "the declared fields and the dataclass fields disagree",
        )
        self.assertIn("prepare_device", declared)
        self.assertIn("acquire_device", declared)
        self.assertIn("adb_on", declared)

    def test_prepare_device_defaults_to_a_no_op(self):
        """A bundle that does not care about a device gets nothing done to it."""
        self.assertIs(
            DeviceDeps.__dataclass_fields__["prepare_device"].default,
            _prepare_device,
        )
        # And the no-op really is a no-op: it must not raise on a serial.
        self.assertIsNone(_prepare_device("emulator-5554"))

    def test_the_other_defaults_are_the_real_implementations(self):
        defaults = {
            name: field.default
            for name, field in DeviceDeps.__dataclass_fields__.items()
            if field.default is not dataclasses.MISSING
        }
        self.assertEqual(
            _junit_reports_device_failure,
            defaults["junit_reports_device_failure"],
        )
        self.assertEqual(_app_installed, defaults["app_installed"])
        self.assertEqual(_device_api_level, defaults["device_api_level"])

    def test_the_fields_without_defaults_are_the_required_ones(self):
        """A required field has no default — the bundle must supply it."""
        required = {
            name
            for name, field in DeviceDeps.__dataclass_fields__.items()
            if field.default is dataclasses.MISSING
        }
        self.assertIn("try_run", required)
        self.assertIn("adb_on", required)
        self.assertIn("acquire_device", required)


if __name__ == '__main__':
    unittest.main()
