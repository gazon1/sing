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

So this file asserts exactly the two facts the boundary rests on. If the release
workflow ever starts publishing the fat JAR, the reasoning above stops holding
and this test says so instead of letting a host-enabled JAR ride out on a
release.
"""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

DESKTOP_BUILD = ROOT / "desktopApp" / "build.gradle.kts"
MCP_SERVER_BUILD = ROOT / "mcp-server" / "build.gradle.kts"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


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


if __name__ == "__main__":
    unittest.main()
