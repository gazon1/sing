"""Tests for the screen-inspector release boundary.

Issue #3042 forbids a production remote-control capability. Two different
mechanisms carry that boundary now, and each one is a way the other could be
bypassed:

* the Gradle property ``singularity.inspector.host.enabled`` decides whether the
  host is compiled in. Enforced, not defaulted: the packaging tasks of
  ``:desktopApp`` and ``:mcp-server`` refuse to run with it enabled. A guard
  that is attached to the wrong task name, or that silently disappeared in a
  refactor, would leave the property as the only gate — which is a convention.

* ``:mcp-server:jar`` is also an executable artefact (a merged fat JAR with a
  ``Main-Class``), and it is deliberately *not* guarded, because the e2e suite
  spawns it and a host-enabled development build is meant to produce it. That is
  only acceptable while the release workflow publishes no ``mcp-server``
  artefact at all: the JAR is a ``build/libs`` output nobody ships.

Two kinds of assertion follow, and both are needed:

* the **static** ones read the build scripts and the workflow. They are cheap and
  they run everywhere, but structure is not behavior: a guard whose condition
  was rewritten to ``if (false)`` still contains every string they look for.

* the **behavioral** one actually asks Gradle to package with the property
  enabled and requires the build to fail. That is the only assertion that proves
  the refusal, and it is gated behind ``INSPECTOR_RELEASE_BOUNDARY_GRADLE=1``
  for the reason ``check-gate-wiring.py`` gives for its own Gradle controls: a
  casual local run must not cold-boot a daemon and hold the shared cache lock.
  ``hk.pkl`` sets it, so the hook and CI do run it.
"""

import os
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

DESKTOP_BUILD = ROOT / "desktopApp" / "build.gradle.kts"
MCP_SERVER_BUILD = ROOT / "mcp-server" / "build.gradle.kts"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"

#: The behavioral control is skipped unless this is set to `1` — the same
#: trade-off `check-gate-wiring.py` documents for its Gradle-backed controls.
GRADLE_ENV = "INSPECTOR_RELEASE_BOUNDARY_GRADLE"

#: One guarded packaging task per module. The behavioral control runs each one in
#: its own build, because a single build asking for both lets either guard answer
#: the assertion for the other.
GUARDED_TASKS = (":mcp-server:installDist", ":desktopApp:packageDeb")


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


class ReleaseWorkflowPublishesNoMcpServerTest(unittest.TestCase):
    """The unguarded fat JAR is only safe while nobody ships it."""

    def test_release_workflow_does_not_publish_the_mcp_server(self):
        workflow = read(RELEASE_WORKFLOW)
        offending = [
            line.strip()
            for line in workflow.splitlines()
            if re.search(r"mcp-server", line) and not line.lstrip().startswith("#")
        ]
        self.assertEqual(
            [],
            offending,
            "release.yml now references :mcp-server. The screen-inspector fat JAR "
            "(:mcp-server:jar) is deliberately not covered by the packaging guard "
            "because it is a development artefact; publishing it on release would "
            "publish a build that may have the inspector host compiled in. Either "
            "stop publishing it, or extend the guard in mcp-server/build.gradle.kts "
            "to cover the JAR task.",
        )


class PackagingGuardTest(unittest.TestCase):
    """The property must break a release artefact, not merely default to false.

    A presence check is not enough on its own: the guard could be present and still be a
    no-op (a `doFirst` that logs, a condition that is always false). Three things are
    asserted per module, and each one is a way the guard has actually been broken:

    1. the guard is attached to a task action (`doFirst`), not to configuration time —
       a configuration-time check would break every host-enabled checkout, which is why
       it was not written that way;
    2. the action *throws*, because a refusal that only logs is not a refusal;
    3. the action reads a value captured outside it. A `doFirst` that reads a build-script
       property is unserializable for the configuration cache, and this repository makes
       configuration-cache problems fatal.
    """

    def assert_guard_over(self, path: pathlib.Path, task_patterns, property_name):
        text = read(path)
        self.assertIn(
            property_name,
            text,
            f"{path.name} no longer reads the inspector host build property.",
        )
        self.assertIn(
            "doFirst",
            text,
            f"{path.name} has no release guard: the property would only default the "
            f"capability off instead of refusing a release artefact that carries it.",
        )
        guard = guard_action(path)
        self.assertIn(
            "throw GradleException",
            guard,
            f"{path.name}: the release guard does not throw. A guard that logs is not a "
            f"refusal, and the packaging task still produces a host-enabled artefact.",
        )
        self.assertIn(
            host_flag_reference := "hostInArtifacts",
            guard,
            f"{path.name}: the guard action must read a value captured outside it "
            f"({host_flag_reference}), not a build-script property — the latter makes the "
            f"task unserializable for the configuration cache.",
        )
        for pattern in task_patterns:
            self.assertIn(
                pattern,
                text,
                f"{path.name} no longer guards the packaging tasks ({pattern}); a "
                f"release artefact with the inspector host compiled in became possible.",
            )

    def test_desktop_packaging_tasks_are_guarded(self):
        self.assert_guard_over(
            DESKTOP_BUILD,
            task_patterns=["package", "createDistributable"],
            property_name="singularity.inspector.host.enabled",
        )

    def test_mcp_server_distribution_tasks_are_guarded(self):
        self.assert_guard_over(
            MCP_SERVER_BUILD,
            task_patterns=["distZip", "distTar", "installDist"],
            property_name="singularity.inspector.host.enabled",
        )


def guard_action(path: pathlib.Path) -> str:
    """The text of the `doFirst { … }` action in a build script, braces balanced.

    The word also appears in the prose comments around the guard, so the match is the
    action syntax (`doFirst` followed by its block) and never a mention in a comment.
    """
    text = read(path)
    for match in re.finditer(r"doFirst\s*\{", text):
        line_start = text.rfind("\n", 0, match.start()) + 1
        if text[line_start : match.start()].lstrip().startswith("//"):
            continue
        brace = match.end() - 1
        depth = 0
        for index in range(brace, len(text)):
            if text[index] == "{":
                depth += 1
            elif text[index] == "}":
                depth -= 1
                if depth == 0:
                    return text[brace : index + 1]
    return ""


class GuardActionReaderTest(unittest.TestCase):
    """`guard_action` is the test's own reader — it must not pass by returning ''."""

    def test_reads_the_real_action(self):
        for path in (DESKTOP_BUILD, MCP_SERVER_BUILD):
            action = guard_action(path)
            self.assertTrue(action.startswith("{"), f"{path.name}: no doFirst action found")
            self.assertGreater(len(action), 100, f"{path.name}: the action looks truncated")


class PackagingGuardBehaviorTest(unittest.TestCase):
    """The positive control: a host-enabled packaging build must fail.

    Everything else in this file reads text. This test asks Gradle, because a
    guard is only worth what it refuses — ``if (false)`` leaves every string the
    static assertions look for in place and refuses nothing.

    Skipped unless ``INSPECTOR_RELEASE_BOUNDARY_GRADLE=1``; see the module
    docstring for why, and ``hk.pkl`` for where it is set.
    """

    @unittest.skipUnless(
        os.environ.get(GRADLE_ENV) == "1",
        f"set {GRADLE_ENV}=1 to run the Gradle-backed release-boundary control",
    )
    def test_host_enabled_packaging_is_refused(self):
        # One invocation per module, deliberately. Asking for both tasks in one build makes
        # either guard satisfy the assertion, so neutering the *other* one still passed
        # this control — which is exactly the vacuous control it exists to prevent.
        for task in GUARDED_TASKS:
            self.assert_refused(task)

    def assert_refused(self, task: str):
        # No `--dry-run`: Gradle skips task actions in a dry run, so the guard's
        # `doFirst` would never execute and the control would pass while measuring
        # nothing. The guard throws in `doFirst`, i.e. before the task does any work,
        # so the refusal costs a task-graph configuration, not a package build.
        proc = subprocess.run(
            ["./gw", "-q", task, "-Psingularity.inspector.host.enabled=true"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=900,
            check=False,
        )
        output = proc.stdout + proc.stderr
        self.assertNotEqual(
            0,
            proc.returncode,
            f":{task} with the inspector host enabled SUCCEEDED. That module's release "
            "guard is not refusing, so a distributable artefact can be built with the "
            f"host compiled in. Output:\n{output[-4000:]}",
        )
        self.assertIn(
            "release artefact",
            output,
            f":{task} failed, but not with the release guard's message. Output:\n{output[-4000:]}",
        )

    @unittest.skipUnless(
        os.environ.get(GRADLE_ENV) == "1",
        f"set {GRADLE_ENV}=1 to run the Gradle-backed release-boundary control",
    )
    def test_default_packaging_is_not_refused(self):
        # The other half of the control, and it has to *run* the tasks. A guard changed to
        # refuse unconditionally satisfies `test_host_enabled_packaging_is_refused` while
        # breaking every ordinary package build; `--dry-run` cannot see that, because a dry
        # run skips task actions and the guard lives in `doFirst`. So: real task execution,
        # and the exit code is the assertion.
        for task in GUARDED_TASKS:
            proc = subprocess.run(
                ["./gw", "-q", task],
                cwd=ROOT,
                capture_output=True,
                text=True,
                timeout=1800,
                check=False,
            )
            output = proc.stdout + proc.stderr
            self.assertNotIn(
                "release artefact",
                output,
                f":{task} was refused by the release guard without the host enabled. The "
                f"guard must only fire when the property is passed. Output:\n{output[-4000:]}",
            )
            self.assertEqual(
                0,
                proc.returncode,
                f":{task} FAILED on a default build. The release guard must not break the "
                f"ordinary packaging path. Output:\n{output[-4000:]}",
            )


if __name__ == "__main__":
    unittest.main()
