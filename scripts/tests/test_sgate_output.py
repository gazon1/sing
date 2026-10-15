#!/usr/bin/env python3
"""Unit tests for sgate.output — structured output helpers."""
from __future__ import annotations

import io
import os
import sys
import unittest
import unittest.mock


class TestIsCI(unittest.TestCase):
    """Tests for is_ci()."""

    def test_is_ci_false_when_not_set(self):
        """CI env var absent → is_ci() returns False."""
        # Make a fresh copy of the module so env changes don't bleed
        import importlib

        import sgate.output
        importlib.reload(sgate.output)
        env = {k: v for k, v in os.environ.items() if k != "CI"}
        with unittest.mock.patch.dict(os.environ, env, clear=True):
            self.assertFalse(sgate.output.is_ci())

    def test_is_ci_true_when_set(self):
        """CI=github_actions → is_ci() returns True."""
        import sgate.output
        with unittest.mock.patch.dict(os.environ, {"CI": "github_actions"}):
            self.assertTrue(sgate.output.is_ci())

    def test_is_ci_false_when_empty_string(self):
        """CI='' (empty string) → is_ci() returns False (empty is falsy)."""
        import sgate.output
        with unittest.mock.patch.dict(os.environ, {"CI": ""}):
            self.assertFalse(sgate.output.is_ci())


class TestGateFailHumanOutput(unittest.TestCase):
    """Tests for gate_fail() human-readable output to stdout."""

    def test_gate_fail_writes_to_stdout(self):
        """gate_fail() writes a violation line to stdout."""
        import sgate.output

        with unittest.mock.patch("sys.stdout", new_callable=io.StringIO) as mock_out:
            sgate.output.gate_fail(
                gate_name="test-gate",
                file="/path/to/file.py",
                line=42,
                expected="a value",
                actual="b value",
                rule="wrong-value",
                message=None,
            )
            output = mock_out.getvalue()

        self.assertIn("/path/to/file.py:42", output)
        self.assertIn("wrong-value", output)
        self.assertIn("expected a value", output)
        self.assertIn("got b value", output)

    def test_gate_fail_without_file_line(self):
        """gate_fail() with file=None still writes a line."""
        import sgate.output

        with unittest.mock.patch("sys.stdout", new_callable=io.StringIO) as mock_out:
            sgate.output.gate_fail(
                gate_name="test-gate",
                file=None,
                line=None,
                expected=None,
                actual=None,
                rule="some-rule",
                message="something went wrong",
            )
            output = mock_out.getvalue()

        self.assertIn("some-rule", output)
        self.assertIn("something went wrong", output)

    def test_gate_fail_uses_message_when_provided(self):
        """When message is provided, it is used instead of expected/actual."""
        import sgate.output

        with unittest.mock.patch("sys.stdout", new_callable=io.StringIO) as mock_out:
            sgate.output.gate_fail(
                gate_name="test-gate",
                file="/f",
                line=1,
                expected="ignored",
                actual="ignored",
                rule=None,
                message="custom message",
            )
            output = mock_out.getvalue()

        self.assertIn("custom message", output)
        self.assertNotIn("expected", output)


class TestGateFailCIAction(unittest.TestCase):
    """Tests for gate_fail() GitHub Actions annotation in CI mode."""

    def _capture_github_output(self, **env_vars):
        """Run gate_fail in CI mode, capture stdout."""
        import sgate.output

        stdout_buf = io.StringIO()
        with unittest.mock.patch.dict(os.environ, {"CI": "true", **env_vars}):
            with unittest.mock.patch("sys.stdout", stdout_buf):
                sgate.output.gate_fail(
                    gate_name="my-gate",
                    file="/repo/config.yml",
                    line=7,
                    expected="unique key",
                    actual="duplicate key",
                    rule="duplicate-key",
                    message=None,
                )
        return stdout_buf.getvalue()

    def test_github_error_annotation_emitted(self):
        """In CI mode, gate_fail() emits ::error with file and line."""
        output = self._capture_github_output()
        self.assertIn("::error", output)
        self.assertIn("file=/repo/config.yml", output)
        self.assertIn("line=7", output)

    def test_github_error_annotation_includes_gate_name(self):
        """::error body includes the gate name in brackets."""
        output = self._capture_github_output()
        self.assertIn("[my-gate]", output)


class TestGateWarnCIAction(unittest.TestCase):
    """Tests for gate_warn()."""

    def test_gate_warn_writes_to_stdout(self):
        """gate_warn() writes a warning line to stdout."""
        import sgate.output

        with unittest.mock.patch("sys.stdout", new_callable=io.StringIO) as mock_out:
            sgate.output.gate_warn("something suspicious", file="/f", line=3)
        output = mock_out.getvalue()
        self.assertIn("/f:3", output)
        self.assertIn("warning", output)

    def test_gate_warn_emits_github_warning_in_ci(self):
        """In CI mode, gate_warn() emits ::warning."""
        import sgate.output

        stdout_buf = io.StringIO()
        with unittest.mock.patch.dict(os.environ, {"CI": "true"}):
            with unittest.mock.patch("sys.stdout", stdout_buf):
                sgate.output.gate_warn("deprecated pattern", file="/x", line=5)
        output = stdout_buf.getvalue()
        self.assertIn("::warning", output)
        self.assertIn("file=/x", output)
        self.assertIn("line=5", output)


if __name__ == "__main__":
    unittest.main()
