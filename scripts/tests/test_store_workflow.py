"""Tests for the capture workflow of `.github/workflows/store-screenshots.yml`.

The workflow runs on a device, and everything it reads there is text the
device made up. The notification key is the sharpest case: `cmd notification
list` prints a key whose tag is whatever the posting app wrote, and the
workflow hands that key to the remote shell of the device. A key that reaches
the shell as source is a command, not a key — so this module pins that the
key is quoted before it goes, and that the quoting actually holds.

The quoting is checked by running it, not by reading it: the assignment from
the workflow is executed with a key that carries a quote, and the remote
command is then parsed by a shell, the way the device parses it. A key that
survives as one argument — and leaves nothing behind — is a key that cannot
run a command.
"""

from __future__ import annotations

import pathlib
import subprocess
import tempfile
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
WORKFLOW = ROOT / ".github/workflows/store-screenshots.yml"
SNOOZE_STEP = "Silence the device's own notifications"


def workflow() -> dict:
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8")) or {}


def snooze_script() -> str:
    """The shell of the step that silences the device's own notifications."""
    for job in workflow()["jobs"].values():
        for step in job.get("steps") or []:
            if step.get("name") == SNOOZE_STEP:
                return step["run"]
    raise AssertionError(f"the workflow has no step named {SNOOZE_STEP!r}")


class NotificationKeyTest(unittest.TestCase):
    """The notification key is text, and it goes to the shell as text."""

    def setUp(self):
        self.script = snooze_script()

    def test_the_key_is_quoted_before_it_reaches_the_device(self):
        # The step puts the key in single quotes — which are the remote
        # shell's, not the workflow's. A quote inside the key closes them.
        self.assertNotIn("'$key'", self.script)
        self.assertIn("'$quoted'", self.script)

    def quoted(self, key: str) -> str:
        """The key as the workflow quotes it, by running its own line."""
        assignment = next(
            (
                line.strip()
                for line in self.script.splitlines()
                if line.strip().startswith("quoted=")
            ),
            None,
        )
        self.assertIsNotNone(assignment, "the step does not quote the key at all")
        result = subprocess.run(
            ["bash", "-c", f'key="$1"\n{assignment}\nprintf "%s" "$quoted"', "_", key],
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout

    def test_a_quote_in_the_key_stays_inside_the_argument(self):
        # The key is text from the device: a quote in it must not end the
        # argument and start a command of somebody else's.
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / "pwned"
            key = f"1000|com.example|42|it'; touch {marker}; echo '|1000"
            quoted = self.quoted(key)
            remote = f"cmd notification snooze --for 3600000 '{quoted}'"
            result = subprocess.run(
                ["sh", "-c", f'set -- {remote}; printf "%s" "$6"'],
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.stdout, key)
            self.assertFalse(marker.exists(), "the key ran a command")


if __name__ == "__main__":
    unittest.main()
