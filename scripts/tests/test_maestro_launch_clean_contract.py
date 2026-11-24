"""Regression contract for the shared launch-clean spotlight dismissal.

Review 7 (PR #3145, #3113) flagged missing regression coverage for the
launch-clean spotlight fix. Review 8 flagged the startup-race window where the
spotlight can appear after an initial dismissal but before nav readiness.
Review 9 flagged that the race-mitigation assertion was too weak.

This is a structural contract test: it pins helper ordering/race mitigation and
the skip-spotlight no-op contract. It is not a device/CI rerun and does not
prove runtime behavior on a real emulator.
"""

from __future__ import annotations

import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
LAUNCH_CLEAN = ROOT / "Maestro/helpers/launch-clean.yaml"
SKIP_SPOTLIGHT = ROOT / "Maestro/helpers/skip-spotlight.yaml"


def _load_docs(path: pathlib.Path) -> list[object]:
    with path.open(encoding="utf-8") as handle:
        return list(yaml.safe_load_all(handle))


def _commands(path: pathlib.Path) -> list[object]:
    docs = _load_docs(path)
    if len(docs) < 2 or not isinstance(docs[1], list):
        raise AssertionError(f"{path} must contain a header doc and a command list")
    return docs[1]


def _is_run_flow(command: object, name: str) -> bool:
    return isinstance(command, dict) and command.get("runFlow") == name


def _is_nav_readiness_wait(command: object) -> bool:
    if not isinstance(command, dict):
        return False
    wait = command.get("extendedWaitUntil")
    if not isinstance(wait, dict):
        return False
    visible = wait.get("visible")
    return isinstance(visible, dict) and visible.get("id") == "nav_tab_today"


class LaunchCleanSpotlightContractTest(unittest.TestCase):
    def test_skip_spotlight_covers_before_during_and_after_nav_readiness(self):
        commands = _commands(LAUNCH_CLEAN)
        skip_indices = [
            index for index, command in enumerate(commands)
            if _is_run_flow(command, "skip-spotlight.yaml")
        ]
        wait_indices = [
            index for index, command in enumerate(commands)
            if _is_nav_readiness_wait(command)
        ]

        self.assertEqual(len(wait_indices), 1, "launch-clean should wait for nav_tab_today once")
        self.assertGreaterEqual(
            len(skip_indices),
            2,
            "launch-clean should dismiss spotlight before and after nav readiness",
        )
        self.assertLess(
            skip_indices[0],
            wait_indices[0],
            "spotlight must be dismissed before nav_tab_today readiness wait; "
            "a first-run scrim can block the readiness check itself",
        )
        self.assertGreater(
            skip_indices[-1],
            wait_indices[0],
            "post-readiness dismissal must remain after nav_tab_today readiness wait; "
            "the tour can appear after shell render",
        )

        race_mitigation = commands[skip_indices[0] + 1:wait_indices[0]]
        self.assertTrue(race_mitigation, "expected a race-mitigation step before nav readiness wait")

        # The race guard must be conditional on nav not being ready, wait briefly
        # for a late spotlight, and dismiss it. A bare nested skip-spotlight call
        # is not enough: that would miss the async-appearance window.
        guarded = [
            command for command in race_mitigation
            if isinstance(command, dict)
            and isinstance(command.get("runFlow"), dict)
            and command["runFlow"].get("when", {}).get("notVisible", {}).get("id") == "nav_tab_today"
        ]
        self.assertTrue(
            guarded,
            "race mitigation must be guarded by notVisible nav_tab_today so it only "
            "runs when shell readiness is still blocked",
        )
        guarded_commands: list[object] = []
        for command in guarded:
            nested = command["runFlow"].get("commands", [])
            if isinstance(nested, list):
                guarded_commands.extend(nested)

        spotlight_wait_indices = [
            index for index, command in enumerate(guarded_commands)
            if isinstance(command, dict)
            and isinstance(command.get("extendedWaitUntil"), dict)
            and command["extendedWaitUntil"].get("visible", {}).get("id") == "onboarding_spotlight_card"
        ]
        self.assertTrue(
            spotlight_wait_indices,
            "race mitigation must wait briefly for onboarding_spotlight_card to appear",
        )
        self.assertTrue(
            any(
                guarded_commands[index]["extendedWaitUntil"].get("timeout") == 5000
                for index in spotlight_wait_indices
            ),
            "spotlight race wait should be bounded to 5000ms",
        )
        self.assertTrue(
            any(
                guarded_commands[index]["extendedWaitUntil"].get("optional") is True
                for index in spotlight_wait_indices
            ),
            "spotlight race wait must be optional so absence does not fail launch-clean",
        )

        dismissal_indices = [
            index for index, command in enumerate(guarded_commands)
            if _is_run_flow(command, "skip-spotlight.yaml")
        ]
        self.assertTrue(
            dismissal_indices,
            "race mitigation must dismiss spotlight after the optional appearance wait",
        )
        self.assertTrue(
            min(spotlight_wait_indices) < min(dismissal_indices),
            "spotlight appearance wait must occur before skip-spotlight dismissal in guarded race mitigation",
        )

    def test_nav_readiness_wait_keeps_shell_timeout(self):
        commands = _commands(LAUNCH_CLEAN)
        waits = [command for command in commands if _is_nav_readiness_wait(command)]
        self.assertEqual(len(waits), 1)
        self.assertEqual(waits[0]["extendedWaitUntil"]["timeout"], 30000)

    def test_launch_clean_preserves_session_and_notification_contract(self):
        docs = _load_docs(LAUNCH_CLEAN)
        launch = docs[1][0]
        self.assertIn("launchApp", launch)
        config = launch["launchApp"]
        self.assertEqual(config.get("clearState"), False)
        self.assertEqual(config.get("permissions", {}).get("notifications"), "allow")


class SkipSpotlightContractTest(unittest.TestCase):
    def test_skip_spotlight_is_conditional_noop(self):
        commands = _commands(SKIP_SPOTLIGHT)
        self.assertEqual(len(commands), 2, "skip-spotlight should be conditional tap + disappearance wait")

        conditional = commands[0]
        self.assertIn("runFlow", conditional)
        run_flow = conditional["runFlow"]
        self.assertEqual(run_flow.get("when", {}).get("visible", {}).get("id"), "onboarding_spotlight_card")
        nested = run_flow.get("commands", [])
        self.assertEqual(len(nested), 1)
        self.assertEqual(nested[0].get("tapOn", {}).get("id"), "onboarding_spotlight_skip")

        wait = commands[1]
        self.assertIn("extendedWaitUntil", wait)
        self.assertEqual(wait["extendedWaitUntil"].get("notVisible", {}).get("id"), "onboarding_spotlight_card")
        self.assertEqual(wait["extendedWaitUntil"].get("timeout"), 8000)


if __name__ == "__main__":
    unittest.main()
