"""Tests for scripts/gradle-slot.sh — the pool that waits for memory.

The pool counts builds and reads the machine's free memory. The reading is a
GUARD: a build that starts without room for its daemon and its forks dies
halfway, when the forks cannot get their heap — which is later and harder to
read than a build that never started.

Two things went wrong with that guard, and both are silent:

* a memory the machine would not report was read as ZERO, and zero is below
  every threshold — so on such a machine the pool blocked every build and
  exited 97 with "the build did not start";
* the threshold was absolute, so a machine with less memory than the threshold
  could never satisfy it and waited for memory that could never be free.

And one thing was wrong with the message: the timeout always named the SLOTS,
even when the memory was what blocked the build. A reader sent looking for a
busy lock does not find one.
"""

import fcntl
import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "scripts" / "gradle-slot.sh"

#: The kernel's units are kB: 1 GiB is 1048576 of them.
GIB = 1024 * 1024


def _meminfo(total_gib: float, available_gib: float) -> str:
    return (
        f"MemTotal:       {int(total_gib * GIB)} kB\n"
        f"MemAvailable:   {int(available_gib * GIB)} kB\n"
    )


class MemoryReadingTest(unittest.TestCase):
    """What the pool does when it knows the memory, and when it does not."""

    def _run(self, meminfo: str | None, **env_extra: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["SINGULARITY_GRADLE_SLOT_WAIT_SECS"] = "2"
        env["SINGULARITY_GRADLE_MIN_FREE_GIB"] = "16"
        with tempfile.TemporaryDirectory() as slots:
            env["SINGULARITY_GRADLE_SLOTS_DIR"] = slots
            if meminfo is None:
                env["SINGULARITY_MEMINFO_FILE"] = str(
                    pathlib.Path(slots) / "there-is-no-meminfo-here"
                )
            else:
                path = pathlib.Path(slots) / "meminfo"
                path.write_text(meminfo, encoding="utf-8")
                env["SINGULARITY_MEMINFO_FILE"] = str(path)
            env.update(env_extra)
            return subprocess.run(
                ["bash", str(SCRIPT), "true"],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )

    def test_an_unknown_memory_does_not_block_the_build(self):
        """A reading the machine does not give is not a reading of zero.

        The first version turned every failure into `0`, and `0` is below every
        threshold: the pool then blocked every build on such a machine and
        exited 97 — a gate that cannot see the constraint must not enforce it.
        """
        result = self._run(None)
        self.assertEqual(
            result.returncode,
            0,
            "a machine whose memory cannot be read must not have its builds "
            f"blocked; stderr was: {result.stderr}",
        )

    def test_too_little_memory_blocks_the_build(self):
        """The guard still guards: a machine with 3 GiB free does not get a
        build that needs 16."""
        result = self._run(_meminfo(total_gib=64, available_gib=3))
        self.assertEqual(result.returncode, 97)

    def test_the_timeout_names_the_memory_when_the_memory_blocked(self):
        """The message has to name the reason, or the reader goes looking for
        a busy lock that is not there."""
        result = self._run(_meminfo(total_gib=64, available_gib=3))
        self.assertIn("memory", result.stderr)
        self.assertNotIn("slots were busy", result.stderr)

    def test_the_threshold_is_capped_by_what_the_machine_has(self):
        """A machine with 8 GiB cannot have 16 GiB free, and asking it to is
        a wait for memory that can never be free."""
        result = self._run(_meminfo(total_gib=8, available_gib=4))
        self.assertEqual(
            result.returncode,
            0,
            "the threshold must be capped by what the machine can give; "
            f"stderr was: {result.stderr}",
        )

    def test_a_machine_with_room_keeps_the_recorded_threshold(self):
        """The cap is for the machine that cannot give the threshold — not for
        every machine.

        A cap of a quarter of the machine's memory would have let a 32 GiB box
        start a build with 9 GiB free, although the recorded threshold is
        16 GiB — and a build that dies halfway for want of memory is what this
        gate exists to prevent.
        """
        result = self._run(_meminfo(total_gib=32, available_gib=9))
        self.assertEqual(
            result.returncode,
            97,
            "a machine with 32 GiB in total must still be asked for the "
            "recorded 16 GiB; stderr was: "
            f"{result.stderr}",
        )

    def test_enough_memory_starts_the_build(self):
        result = self._run(_meminfo(total_gib=64, available_gib=40))
        self.assertEqual(result.returncode, 0)


class TimeoutMessageTest(unittest.TestCase):
    """The timeout names the slots when the SLOTS are what blocked the build."""

    def test_a_busy_pool_names_the_slots(self):
        with tempfile.TemporaryDirectory() as slots:
            env = dict(os.environ)
            env["SINGULARITY_GRADLE_SLOTS_DIR"] = slots
            env["SINGULARITY_GRADLE_SLOT_COUNT"] = "2"
            env["SINGULARITY_GRADLE_SLOT_WAIT_SECS"] = "1"
            env["SINGULARITY_MEMINFO_FILE"] = str(pathlib.Path(slots) / "meminfo")
            pathlib.Path(slots, "meminfo").write_text(
                _meminfo(total_gib=64, available_gib=40), encoding="utf-8"
            )
            held = []
            try:
                for index in (1, 2):
                    handle = pathlib.Path(slots, f"slot-{index}.lock").open("w")
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    held.append(handle)
                result = subprocess.run(
                    ["bash", str(SCRIPT), "true"],
                    env=env,
                    capture_output=True,
                    text=True,
                    check=False,
                )
            finally:
                for handle in held:
                    fcntl.flock(handle, fcntl.LOCK_UN)
                    handle.close()
        self.assertEqual(result.returncode, 97)
        self.assertIn("slots were busy", result.stderr)
        self.assertNotIn("memory", result.stderr)


if __name__ == "__main__":
    unittest.main()
