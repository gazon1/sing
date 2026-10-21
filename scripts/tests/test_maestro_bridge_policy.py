#!/usr/bin/env python3
"""Unit tests for the bridge's policy: what it allows, and how it matches.

Run with: python3 -m unittest discover -s scripts/tests

The policy is the whole of the bridge's exposure: it runs device commands on
behalf of a Maestro script, and the set it may run is a file. These tests
cover three things:

* the shipped policy says what it means to say — the shapes the flows need;
* the rule — a command has to match one of them WHOLE. A prefix is not a
  match, and the positive controls below are the cases a prefix check would
  wave through: `run-as … ; id`, `setprop … true extra`, `am start …`;
* the policy is a policy, not a constant: a new shape is an edit to the file,
  and a policy that cannot be read, cannot be parsed or names another app
  allows *nothing*.
"""
from __future__ import annotations

import json
import pathlib
import tempfile
import unittest
import urllib.error
import urllib.parse
import urllib.request

from emulator.maestro._bridge import Bridge, CommandResult, is_allowed
from emulator.maestro._policy import (
    MATCH_WHOLE,
    PolicyError,
    empty_policy,
    load_policy,
    parse_policy,
)
from emulator.maestro._types import APP_ID

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
POLICY_FILE = ROOT / "config" / "maestro" / "bridge-policy.yaml"


def _get(url: str) -> tuple[int, dict]:
    """Fetch a URL and return its status and the decoded JSON body."""
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


def _quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


class ShippedPolicyTest(unittest.TestCase):
    """The policy in the repository is the one the bridge runs with."""

    def test_the_policy_file_is_in_the_repository(self) -> None:
        self.assertTrue(
            POLICY_FILE.is_file(),
            f"the bridge policy is missing: {POLICY_FILE}",
        )

    def test_it_loads(self) -> None:
        policy = load_policy(POLICY_FILE)
        self.assertEqual(
            ("run-as-script", "setprop", "getprop"),
            policy.ids(),
        )

    def test_it_names_this_app(self) -> None:
        self.assertEqual(APP_ID, load_policy(POLICY_FILE).app_id)

    def test_it_asks_for_a_whole_match(self) -> None:
        """The mode is declared, so a policy cannot quietly ask for a prefix."""
        document = POLICY_FILE.read_text(encoding="utf-8")
        self.assertIn(f"match: {MATCH_WHOLE}", document)

    def test_every_shape_says_why_it_is_allowed(self) -> None:
        """A shape without a reason is a shape nobody checked."""
        for entry in load_policy(POLICY_FILE).entries:
            self.assertTrue(entry.why.strip(), f"{entry.id} has no reason")


class WholeCommandNotPrefixTest(unittest.TestCase):
    """The rule: the WHOLE command has to match. A prefix is not a match.

    These are the positive controls. Each of them starts with a command the
    policy allows and carries something else, and each has to be refused: a
    prefix check accepts all of them, and `run-as … ; id` is the one that runs
    the second half as the *shell* user rather than the app's.
    """

    def setUp(self) -> None:
        self.policy = load_policy(POLICY_FILE)

    def test_the_shapes_the_flows_need_are_allowed(self) -> None:
        for command in (
            f"run-as {APP_ID} sh -c 'ls shared_prefs'",
            f"run-as {APP_ID} sh -c 'echo not-a-keyset > shared_prefs/x.xml'",
            "setprop debug.stub_auth.signin_failure true",
            "getprop debug.stub_auth.signin_failure",
        ):
            with self.subTest(command=command):
                self.assertTrue(self.policy.allows(command))

    def test_a_run_as_script_with_another_argument_is_refused(self) -> None:
        self.assertFalse(
            self.policy.allows(f"run-as {APP_ID} sh -c 'ls' extra"),
            "the same prefix with different arguments is a different command",
        )

    def test_a_second_command_after_an_allowed_one_is_refused(self) -> None:
        self.assertFalse(self.policy.allows(f"run-as {APP_ID} sh -c 'ls' ; id"))

    def test_a_setprop_with_another_argument_is_refused(self) -> None:
        self.assertFalse(
            self.policy.allows("setprop debug.stub_auth.signin_failure true extra")
        )

    def test_a_getprop_with_another_argument_is_refused(self) -> None:
        self.assertFalse(
            self.policy.allows("getprop debug.stub_auth.signin_failure extra")
        )

    def test_a_command_that_is_not_in_the_policy_is_refused(self) -> None:
        """`am start` is not in the policy, so no argument list of it is either."""
        self.assertFalse(
            self.policy.allows(
                "am start -a android.intent.action.VIEW "
                "-d todo-debug://seed?reset=content"
            )
        )

    def test_a_command_carrying_a_newline_is_refused(self) -> None:
        """A newline is how a second command is smuggled in."""
        self.assertFalse(self.policy.allows("setprop a b\nid"))

    def test_an_empty_command_is_refused(self) -> None:
        self.assertFalse(self.policy.allows(""))

    def test_surrounding_whitespace_is_the_only_thing_normalized(self) -> None:
        """Trailing whitespace is not a second command; an inner space is."""
        self.assertTrue(self.policy.allows("  getprop debug.stub_auth.x  "))
        self.assertFalse(self.policy.allows("getprop  debug.stub_auth.x"))


class TheBridgeRunsThePolicyTest(unittest.TestCase):
    """The bridge refuses what the policy refuses, and says where it lives."""

    def setUp(self) -> None:
        self.calls: list[tuple[str, str]] = []

        def runner(serial: str, command: str) -> CommandResult:
            self.calls.append((serial, command))
            return CommandResult(code=0, stdout="", stderr="")

        self.bridge = Bridge("emulator-5554", run_device_command=runner)
        self.base = self.bridge.start()
        self.addCleanup(self.bridge.stop)

    def test_an_allowed_command_runs(self) -> None:
        command = f"run-as {APP_ID} sh -c 'ls'"
        status, _ = _get(f"{self.base}/shell?cmd={_quote(command)}")
        self.assertEqual(200, status)
        self.assertEqual([("emulator-5554", command)], self.calls)

    def test_a_command_outside_the_policy_is_refused_and_named(self) -> None:
        status, body = _get(
            f"{self.base}/shell?cmd={_quote(f'run-as {APP_ID} sh -c ls ; id')}"
        )
        self.assertEqual(400, status)
        self.assertIn("refused", body["error"])
        self.assertIn("whole", body["error"])
        self.assertEqual([], self.calls)

    def test_the_refusal_says_where_the_policy_is(self) -> None:
        """A reader of the failure has to be able to find the file to edit."""
        status, body = _get(f"{self.base}/shell?cmd={_quote('id')}")
        self.assertEqual(400, status)
        self.assertIn(str(POLICY_FILE), body["error"])


class TheWhitelistIsThePolicyFileTest(unittest.TestCase):
    """`is_allowed` reads the policy file, not a constant."""

    def test_the_module_level_check_reads_the_policy(self) -> None:
        self.assertTrue(is_allowed(f"run-as {APP_ID} sh -c 'ls'"))
        self.assertFalse(is_allowed(f"run-as {APP_ID} sh -c 'ls' ; id"))

    def test_a_new_shape_needs_no_python_change(self) -> None:
        """The point of the file: a new need is an edit to the policy.

        `settings put` is the example the flows will need; it is *not* in the
        shipped policy (nothing needs it today), and adding it here — in a
        temporary policy file — must be enough to have the bridge run it.
        """
        document = f"""
version: 1
match: {MATCH_WHOLE}
app_id: {APP_ID}
commands:
  - id: settings-put
    pattern: "settings put [a-z]+ [A-Za-z0-9._-]+ [A-Za-z0-9._-]+"
    why: "a test policy that allows one settings write"
"""
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "policy.yaml"
            path.write_text(document, encoding="utf-8")
            policy = load_policy(path)

        self.assertTrue(policy.allows("settings put secure show_ime_with_hard_keyboard 0"))
        # The same prefix with different arguments is still a different command.
        self.assertFalse(policy.allows("settings put secure show_ime_with_hard_keyboard"))
        self.assertFalse(
            policy.allows("settings put secure show_ime_with_hard_keyboard 0 extra")
        )

    def test_a_pattern_that_swallows_the_rest_needs_no_permission(self) -> None:
        """The greedy-pattern guard is part of "the whole command"."""
        document = f"""
version: 1
match: {MATCH_WHOLE}
app_id: {APP_ID}
commands:
  - id: greedy
    pattern: "settings put .*"
    why: "a pattern that names no end"
"""
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "policy.yaml"
            path.write_text(document, encoding="utf-8")
            with self.assertRaises(PolicyError):
                load_policy(path)


class FailClosedTest(unittest.TestCase):
    """A policy that cannot be trusted allows nothing."""

    def test_a_missing_policy_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            missing = pathlib.Path(tmp) / "nope.yaml"
            with self.assertRaises(PolicyError):
                load_policy(missing)

    def test_a_policy_for_another_app_is_refused(self) -> None:
        document = {
            "version": 1,
            "match": MATCH_WHOLE,
            "app_id": "com.other.app",
            "commands": [{"id": "x", "pattern": "id", "why": "y"}],
        }
        with self.assertRaises(PolicyError):
            parse_policy(document, POLICY_FILE)

    def test_a_policy_that_asks_for_a_prefix_match_is_refused(self) -> None:
        document = {
            "version": 1,
            "match": "prefix",
            "app_id": APP_ID,
            "commands": [{"id": "x", "pattern": "id", "why": "y"}],
        }
        with self.assertRaises(PolicyError):
            parse_policy(document, POLICY_FILE)

    def test_a_policy_with_an_unknown_version_is_refused(self) -> None:
        document = {
            "version": 99,
            "match": MATCH_WHOLE,
            "app_id": APP_ID,
            "commands": [{"id": "x", "pattern": "id", "why": "y"}],
        }
        with self.assertRaises(PolicyError):
            parse_policy(document, POLICY_FILE)

    def test_a_policy_with_a_broken_pattern_is_refused(self) -> None:
        document = {
            "version": 1,
            "match": MATCH_WHOLE,
            "app_id": APP_ID,
            "commands": [{"id": "x", "pattern": "id (", "why": "y"}],
        }
        with self.assertRaises(PolicyError):
            parse_policy(document, POLICY_FILE)

    def test_a_pattern_that_swallows_the_rest_of_the_command_is_refused(self) -> None:
        """`.*` is a prefix match in disguise.

        `run-as com.singularity.todo .*` would allow `run-as com.singularity.todo ; id`
        — the whole-command rule cannot save a pattern that names no end.
        """
        document = {
            "version": 1,
            "match": MATCH_WHOLE,
            "app_id": APP_ID,
            "commands": [
                {"id": "x", "pattern": f"run-as {APP_ID} .*", "why": "y"},
            ],
        }
        with self.assertRaises(PolicyError):
            parse_policy(document, POLICY_FILE)

    def test_an_empty_policy_allows_nothing(self) -> None:
        policy = empty_policy(POLICY_FILE)
        self.assertFalse(policy.allows(f"run-as {APP_ID} sh -c 'ls'"))
        self.assertFalse(policy.allows("id"))


if __name__ == "__main__":
    unittest.main()
