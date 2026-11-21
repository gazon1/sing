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
    """The property must break a release artefact, not merely default to false."""

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


if __name__ == "__main__":
    unittest.main()
