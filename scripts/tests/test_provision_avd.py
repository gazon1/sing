"""Tests for scripts/ci/provision-avd.sh.

The script is the guarantee behind the Maestro shards: the cache that carries
the emulator from the producer job to the consumer jobs is an optimisation, and
this script is what makes a runner that missed that cache able to run the flows
anyway. It runs on a CI runner, where a wrong path or a wrong exit code turns
into a red shard, so its contract is pinned here.

The fakes stand in for the Android SDK: a fake `sdkmanager` unpacks "packages"
into the same layout the real one does, and a fake `avdmanager` creates an AVD
directory with a `userdata.img`. No test touches a real SDK.
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "scripts" / "ci" / "provision-avd.sh"

SYSTEM_IMAGE = "system-images;android-36;google_apis;x86_64"
AVD_NAME = "singularity-api36"

FAKE_SDKMANAGER = """\
#!/bin/sh
if [ "$1" = "--licenses" ]; then head -c 100 >/dev/null; exit 0; fi
root=$(cd "$(dirname "$0")/../../.." && pwd)
for pkg in "$@"; do
  case "$pkg" in
    emulator)
      mkdir -p "$root/emulator"
      printf '#!/bin/sh\\n[ "$1" = "-list-avds" ] && echo "$FAKE_AVD"\\nexit 0\\n' \
        > "$root/emulator/emulator"
      chmod +x "$root/emulator/emulator"
      ;;
    platform-tools) mkdir -p "$root/platform-tools" ;;
    system-images*)
      d="$root/$(echo "$pkg" | tr ';' '/')"
      mkdir -p "$d"
      touch "$d/package.xml"
      ;;
  esac
done
"""

FAKE_AVDMANAGER = """\
#!/bin/sh
name=""
while [ $# -gt 0 ]; do
  case "$1" in --name) name="$2"; shift ;; esac
  shift
done
mkdir -p "$FAKE_AVD_HOME/$name.avd" && touch "$FAKE_AVD_HOME/$name.avd/userdata.img"
case "${FAKE_CONFIG:-default}" in
  none) ;;
  no-gpu) printf 'hw.lcd.width=1080\\n' > "$FAKE_AVD_HOME/$name.avd/config.ini" ;;
  *) printf 'hw.lcd.width=1080\\nhw.gpu.enabled=no\\nhw.gpu.mode=auto\\n' \\
      > "$FAKE_AVD_HOME/$name.avd/config.ini" ;;
esac
echo "created $name"
"""


class ProvisionAvdTest(unittest.TestCase):
    def setUp(self):
        import tempfile

        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.base = pathlib.Path(self._tmp.name)
        self.sdk = self.base / "sdk"
        self.avd_home = self.base / "avd"
        self.avd_home.mkdir(parents=True)

    def _write_fake_tools(self) -> None:
        bin_dir = self.sdk / "cmdline-tools" / "latest" / "bin"
        bin_dir.mkdir(parents=True)
        for name, body in (
            ("sdkmanager", FAKE_SDKMANAGER),
            ("avdmanager", FAKE_AVDMANAGER),
        ):
            path = bin_dir / name
            path.write_text(textwrap.dedent(body))
            path.chmod(0o755)

    def _provision(self, **overrides) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update(
            {
                "AVD_NAME": AVD_NAME,
                "SYSTEM_IMAGE": SYSTEM_IMAGE,
                "SDK_ROOT": str(self.sdk),
                "ANDROID_AVD_HOME": str(self.avd_home),
                "FAKE_AVD": AVD_NAME,
                "FAKE_AVD_HOME": str(self.avd_home),
                "GITHUB_PATH": "",
            }
        )
        env.update(overrides)
        return subprocess.run(
            ["bash", str(SCRIPT)],
            env=env,
            capture_output=True,
            text=True,
            timeout=120,
        )

    def test_the_script_is_executable(self):
        self.assertTrue(os.access(SCRIPT, os.X_OK), "provision-avd.sh must be executable")

    def test_missing_inputs_exit_two(self):
        result = self._provision(AVD_NAME="")
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn("AVD_NAME", result.stderr)

    def test_a_missing_sdk_exits_one(self):
        result = self._provision(SDK_ROOT=str(self.base / "nope"))
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("no Android SDK", result.stderr)

    def test_it_installs_the_emulator_and_creates_the_avd(self):
        """The cold path: nothing is present, so the script provisions it."""
        self._write_fake_tools()
        result = self._provision()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.sdk / "emulator" / "emulator").is_file())
        self.assertTrue((self.sdk / "platform-tools").is_dir())
        self.assertTrue(
            (self.sdk / "system-images" / "android-36" / "google_apis" / "x86_64").is_dir(),
            "the system image must land in the path sdkmanager actually uses",
        )
        self.assertTrue(
            (self.avd_home / f"{AVD_NAME}.avd" / "userdata.img").is_file()
        )

    def test_an_already_provisioned_machine_is_left_alone(self):
        """The warm path: a restored cache must not trigger a reinstall."""
        self._write_fake_tools()
        first = self._provision()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        marker = self.sdk / "platform-tools" / ".provisioned-once"
        marker.write_text("")

        second = self._provision()
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        self.assertIn("already present", second.stdout)
        # The fast path must not have re-run sdkmanager, which would have
        # rewritten the marker.
        self.assertEqual("", marker.read_text())

    def test_the_capture_avd_renders_through_the_host_gpu(self):
        """`GPU_MODE=host` must make the AVD ask for the host GPU.

        Software rendering is a startup crash on this host — the render thread
        dies before the window opens — and the `pixel_2` profile the 9:16 capture
        device uses is written with the GPU off, so a capture AVD created by this
        script would not boot.
        """
        self._write_fake_tools()
        result = self._provision(GPU_MODE="host")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        config = (self.avd_home / f"{AVD_NAME}.avd" / "config.ini").read_text()
        self.assertIn("hw.gpu.enabled=yes", config)
        self.assertIn("hw.gpu.mode=host", config)

    def test_the_gpu_keys_are_added_when_the_profile_has_none(self):
        """A profile without the keys gets them — a substitution alone would not.

        The keys are what the emulator reads; a profile that has neither is a
        profile whose GPU mode nobody chose, and the script reporting success
        would make that invisible.
        """
        self._write_fake_tools()
        result = self._provision(GPU_MODE="host", FAKE_CONFIG="no-gpu")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        config = (self.avd_home / f"{AVD_NAME}.avd" / "config.ini").read_text()
        self.assertIn("hw.gpu.enabled=yes", config)
        self.assertIn("hw.gpu.mode=host", config)
        self.assertIn("hw.lcd.width=1080", config)

    def test_the_gpu_settings_survive_a_second_run(self):
        """Running it twice must leave one setting, not two."""
        self._write_fake_tools()
        first = self._provision(GPU_MODE="host")
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        second = self._provision(GPU_MODE="host")
        self.assertEqual(0, second.returncode, second.stdout + second.stderr)
        config = (self.avd_home / f"{AVD_NAME}.avd" / "config.ini").read_text()
        self.assertEqual(1, config.count("hw.gpu.enabled=yes"))
        self.assertEqual(1, config.count("hw.gpu.mode=host"))

    def test_a_plain_ci_avd_keeps_its_own_gpu_mode(self):
        """Without `GPU_MODE` the profile is left exactly as it was.

        The GPU policy of this repository is split on purpose (ADR
        `2026-10-18-emulator-gpu-strategy-split`): the Maestro surface launches
        with `-gpu off` and the instrumentation surface with
        `-gpu swiftshader_indirect`. A script that rewrote every AVD's GPU
        settings would make the file disagree with both.
        """
        self._write_fake_tools()
        result = self._provision()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        config = (self.avd_home / f"{AVD_NAME}.avd" / "config.ini").read_text()
        self.assertIn("hw.gpu.enabled=no", config)
        self.assertIn("hw.gpu.mode=auto", config)

    def test_a_missing_avd_config_fails_loudly(self):
        """Asking for the host GPU without a config to write it must fail."""
        self._write_fake_tools()
        result = self._provision(GPU_MODE="host", FAKE_CONFIG="none")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("no AVD config", result.stderr)

    def test_an_unknown_gpu_mode_fails_loudly(self):
        """A typo in `GPU_MODE` must not be read as "no GPU mode"."""
        self._write_fake_tools()
        result = self._provision(GPU_MODE="swiftshader")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("GPU_MODE", result.stderr)

    def test_it_fails_when_the_avd_is_not_listed(self):
        """A machine with the binary but the wrong AVD must fail loudly.

        The emulator module boots the AVD named by `AVD`; a machine that has an
        emulator but not that AVD fails like a boot problem, which is exactly
        the misdirection this check removes.
        """
        self._write_fake_tools()
        result = self._provision(FAKE_AVD="some_other_avd")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("does not list", result.stderr)


if __name__ == "__main__":
    unittest.main()
