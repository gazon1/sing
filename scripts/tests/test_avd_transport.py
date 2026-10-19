"""Tests for the AVD transport between the setup job and the jobs that use it.

The producer (`_setup-avd.yml`) installs the emulator and creates the AVD; the
consumers (`e2e.yml`, `android-device-tests.yml`) run on other runners, where
none of that exists. The cache is the transport, and it was broken in a way that
no test could see:

* the cache paths were written `${{ env.ANDROID_HOME }}/…`, and the `env`
  context does not carry the runner's own environment variables — so the list
  expanded to `/system-images`, `/emulator` and `/platform-tools` at the
  filesystem root, and the cache saved and restored nothing;
* the key was re-assembled independently on each side, so the two sides could
  disagree about which entry they were talking about;
* the entry that was left behind (1659 bytes) was a *hit* on the next run, and
  a cache hit means "do not save" — so the empty entry was permanent.

The symptom was four red Maestro shards on run 39253664636, each dying with
"'emulator' binary not found in PATH" on a runner where the producer had
installed that exact binary ninety seconds earlier.

These tests read the workflow files and assert the contract, so the same class
of drift fails here instead of in a nightly run.
"""
from __future__ import annotations

import os
import pathlib
import re
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

PRODUCER = WORKFLOWS / "_setup-avd.yml"
CONSUMERS = (WORKFLOWS / "e2e.yml", WORKFLOWS / "android-device-tests.yml")
PROVISIONER = ROOT / "scripts" / "ci" / "provision-avd.sh"


def _load(path: pathlib.Path) -> dict:
    return yaml.safe_load(path.read_text())


def _on(workflow: dict) -> dict:
    """The workflow's `on:` block.

    PyYAML resolves the bare key `on` to the boolean True, so the key is looked
    up by value rather than by name.
    """
    for key, value in workflow.items():
        if key is True or key == "on":
            return value
    raise AssertionError("workflow has no `on:` block")


def _steps(workflow: dict, job: str) -> list[dict]:
    return workflow["jobs"][job]["steps"]


def _cache_step(steps: list[dict]) -> dict:
    """The one step that talks to actions/cache, producer or consumer."""
    found = [s for s in steps if "actions/cache" in str(s.get("uses", ""))]
    assert len(found) == 1, f"expected exactly one cache step, found {len(found)}"
    return found[0]


def _consumer_job(workflow: dict) -> str:
    """The job that restores the cache."""
    for name, spec in workflow["jobs"].items():
        if not isinstance(spec, dict):
            continue
        steps = spec.get("steps") or []
        if any("actions/cache/restore" in str(s.get("uses", "")) for s in steps):
            return name
    raise AssertionError("no job restores the AVD cache")


def _paths(cache_step: dict) -> list[str]:
    raw = cache_step["with"]["path"]
    return [line.strip() for line in raw.strip().splitlines() if line.strip()]


def _normalise(paths: list[str]) -> list[str]:
    """Replace whichever SDK-root expression a side uses with one token.

    The producer reads `steps.ctx.outputs.sdk_root` and the consumers read
    `needs.setup-avd.outputs.sdk_root`; they name the same directory. What the
    test cares about is that both sides cache the same four locations.
    """
    out = []
    for p in paths:
        if "system-images" in p:
            out.append("<sdk>/system-images")
        elif p.endswith("/emulator"):
            out.append("<sdk>/emulator")
        elif p.endswith("/platform-tools"):
            out.append("<sdk>/platform-tools")
        else:
            out.append(p)
    return out


class CachePathsAgreeTest(unittest.TestCase):
    """The producer and the consumers must cache the same locations.

    Two. The AVD is not among them: its `userdata.img` is a sparse, mutable
    image, and it did not survive the cache — the first consumer of the entry
    that carried it found the emulator and the system image already installed
    and still had to create the AVD itself, in half a second. Caching a mutable
    image between runners was never working; what it did was make the entry
    look like it carried the AVD.

    `platform-tools` is not among them either: the runner image ships it, and
    the image's copy is root-owned, so restoring over it made tar fail with
    "Cannot utime: Operation not permitted" — a cache that cannot be restored
    cleanly reports a failure nobody can act on.
    """

    def setUp(self):
        self.expected = [
            "<sdk>/system-images",
            "<sdk>/emulator",
        ]

    def test_producer_caches_the_four_locations(self):
        producer = _load(PRODUCER)
        paths = _normalise(_paths(_cache_step(_steps(producer, "setup-avd"))))
        self.assertEqual(self.expected, paths)

    def test_every_consumer_caches_the_same_locations(self):
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                workflow = _load(consumer)
                job = _consumer_job(workflow)
                paths = _normalise(_paths(_cache_step(_steps(workflow, job))))
                self.assertEqual(self.expected, paths)

    def test_no_cache_path_uses_the_env_context(self):
        """`env.ANDROID_HOME` is empty on a runner; that is what broke the paths."""
        for path in (PRODUCER, *CONSUMERS):
            with self.subTest(path=path.name):
                workflow = _load(path)
                job = (
                    "setup-avd"
                    if path == PRODUCER
                    else _consumer_job(workflow)
                )
                for cached in _paths(_cache_step(_steps(workflow, job))):
                    self.assertNotIn("env.ANDROID_HOME", cached)


class CacheKeyIsSingleSourcedTest(unittest.TestCase):
    """The key is the producer's output, not a string both sides re-spell."""

    @staticmethod
    def _key(ctx: dict) -> str:
        match = re.search(r'cache_key=(avd-\S+)"', ctx["run"])
        assert match, f"no cache_key in the ctx step: {ctx['run']!r}"
        return match.group(1)

    @staticmethod
    def _prefix(ctx: dict) -> str:
        match = re.search(r'cache_key_prefix=(\S+)"', ctx["run"])
        assert match, f"no cache_key_prefix in the ctx step: {ctx['run']!r}"
        return match.group(1)

    def test_producer_publishes_a_full_key_and_a_prefix(self):
        producer = _load(PRODUCER)
        outputs = producer["jobs"]["setup-avd"]["outputs"]
        self.assertIn("cache_key", outputs)
        self.assertIn("cache_key_prefix", outputs)
        self.assertIn("cache_key", _on(producer)["workflow_call"]["outputs"])

    def test_consumers_restore_the_producer_key_verbatim(self):
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                workflow = _load(consumer)
                job = _consumer_job(workflow)
                with_ = _cache_step(_steps(workflow, job))["with"]
                self.assertEqual(
                    "${{ needs.setup-avd.outputs.cache_key }}", with_["key"]
                )
                self.assertIn(
                    "${{ needs.setup-avd.outputs.cache_key_prefix }}",
                    with_["restore-keys"],
                )

    def test_the_key_carries_a_version(self):
        """A version suffix is how a stale, wrongly-shaped entry gets retired."""
        producer = _load(PRODUCER)
        ctx = next(
            s for s in _steps(producer, "setup-avd")
            if s.get("id") == "ctx"
        )
        self.assertRegex(self._key(ctx), r"^avd-.+-v\d+$")

    def test_the_restore_prefix_carries_the_version_too(self):
        """A restore-key is a prefix match, so an unversioned prefix is a trap.

        A prefix of `…-v2` also matches `…-v20`, and a prefix without the
        version matches the entries written under the old layout — which is
        precisely the entry that carried nothing. The prefix must not be able
        to reach back across a layout change, so it has to be the key plus a
        separator.
        """
        producer = _load(PRODUCER)
        ctx = next(
            s for s in _steps(producer, "setup-avd")
            if s.get("id") == "ctx"
        )
        key = self._key(ctx)
        prefix = self._prefix(ctx)
        self.assertEqual(key + "-", prefix)
        # The trailing separator is what stops `…-v2` from matching `…-v20`.
        self.assertTrue(prefix.endswith("-"), prefix)

    def test_no_caller_passes_an_undefined_input(self):
        """Every input a caller passes must exist on the producer.

        A dead input is a false API: it reads as a knob, changes nothing, and
        the workflow it was meant to influence keeps doing whatever it did.
        """
        producer = _load(PRODUCER)
        inputs = _on(producer)["workflow_call"].get("inputs", {})
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                workflow = _load(consumer)
                for job, spec in workflow["jobs"].items():
                    if not isinstance(spec, dict) or "uses" not in spec:
                        continue
                    for name in (spec.get("with") or {}):
                        self.assertIn(
                            name,
                            inputs,
                            f"{job} passes '{name}', which the producer does not declare",
                        )


class SdkRootIsNotReDerivedTest(unittest.TestCase):
    """The SDK root is the producer's output, so both sides name one directory."""

    def test_consumers_take_the_sdk_root_from_the_producer(self):
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                workflow = _load(consumer)
                job = _consumer_job(workflow)
                for path in _paths(_cache_step(_steps(workflow, job))):
                    if path.endswith(("system-images", "emulator", "platform-tools")):
                        self.assertIn(
                            "${{ needs.setup-avd.outputs.sdk_root }}", path
                        )


class ProvisioningIsAvailableTest(unittest.TestCase):
    """The cache is an optimisation; provisioning is the guarantee."""

    def test_the_provisioning_script_exists_and_is_executable(self):
        self.assertTrue(PROVISIONER.is_file())
        self.assertTrue(
            os.access(PROVISIONER, os.X_OK),
            "scripts/ci/provision-avd.sh must be executable — the workflows call it directly",
        )

    def test_producer_and_consumers_all_run_the_same_script(self):
        for path in (PRODUCER, *CONSUMERS):
            with self.subTest(path=path.name):
                self.assertIn("scripts/ci/provision-avd.sh", path.read_text())


class AvdNameReachesTheEmulatorTest(unittest.TestCase):
    """The emulator module boots the AVD named by `AVD`; the default is wrong.

    `emulator._types.Config` defaults to "Medium_Phone", an AVD this workflow
    never creates. A job that boots the right binary against the wrong AVD
    fails like a boot problem, not like a misnamed one.
    """

    def test_every_consumer_passes_the_avd_name_to_the_emulator(self):
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                workflow = _load(consumer)
                job = _consumer_job(workflow)
                found = [
                    s for s in _steps(workflow, job)
                    if "AVD" in (s.get("env") or {})
                ]
                self.assertTrue(found, f"{consumer.name} never sets AVD")
                for step in found:
                    self.assertEqual(
                        "${{ needs.setup-avd.outputs.avd_name }}",
                        step["env"]["AVD"],
                    )


if __name__ == "__main__":
    unittest.main()
