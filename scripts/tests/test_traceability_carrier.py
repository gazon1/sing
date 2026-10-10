#!/usr/bin/env python3
"""Tests for `traceability carrier` — the reachability-probe generator.

A generator that writes files has a failure mode a checker does not: it can
produce output that is *valid and wrong*. Every test here is about a way that
happened or nearly happened while this was built, and the two that bite hardest
are the ones that would have landed a broken tree rather than an error.

The one that did: the first version decided a carrier already existed by
checking whether the file it was about to write was already there. It is not
there. `TASK-REC-01`'s carrier is hand-written and named
`TaskRecurrenceScenarioTest`, while the generated name is
`TaskRec01ScenarioTest` — nothing in common. So the check passed, the generator
wrote a *second* carrier for one (scenario, target) next to the real one, and
`validate` rejects two tests claiming one scenario. The generator would have
produced a tree that does not validate, and it did so silently.

The fix is to ask the link set rather than the filesystem, because the link set
is the thing that means "this scenario is carried".
"""

import pathlib
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
# The traceability package is a source-tree package, not an installed one, so
# the in-process tests below have to put it on the path themselves. The CLI
# tests pass it through PYTHONPATH instead.
sys.path.insert(0, str(ROOT / "infra" / "kiwi"))


def _run(*args):
    import os

    env = {**os.environ, "PYTHONPATH": str(ROOT / "infra" / "kiwi")}
    return subprocess.run(
        [sys.executable, "-m", "traceability", *args],
        capture_output=True,
        text=True,
        cwd=ROOT,
        env=env,
        check=False,
    )


class PlanTest(unittest.TestCase):
    """The computed plan — pure, so it can be asserted without touching disk."""

    def setUp(self):
        from traceability import SCENARIOS_DIR
        from traceability.carrier import plan_carrier
        from traceability.spec import Target, load_specs

        self.specs = load_specs(SCENARIOS_DIR)
        self._plan = plan_carrier
        self.root = ROOT
        self.desktop = Target.DESKTOP
        self.android = Target.ANDROID

    def test_display_name_puts_the_id_first(self):
        # The scanner rejects an id anywhere else, and the failure mode of
        # getting it wrong is an empty matrix rather than an error.
        plan = self._plan(self.specs["TASK-CHECK-01"], self.desktop, self.root)
        self.assertTrue(
            plan.display_name.startswith("TASK-CHECK-01 "),
            f"id must be the first token, got {plan.display_name!r}",
        )

    def test_the_numeric_segment_survives_into_the_class_name(self):
        # `TaskTimeScenarioTest` would be the natural title-case and the wrong
        # answer: a future `TASK-TIME-02` would land on the same class name in a
        # second file, which is the collision that made the number worth keeping.
        a = self._plan(self.specs["TASK-TIME-01"], self.desktop, self.root)
        self.assertEqual(a.class_name, "TaskTime01ScenarioTest")
        self.assertEqual(a.path.name, "TaskTime01ScenarioTest.kt")

    def test_the_android_tier_refuses_to_produce_a_compose_test(self):
        from traceability.carrier import CarrierError, render_probe

        plan = self._plan(self.specs["AUTH-SIGNIN-01"], self.android, self.root)
        with self.assertRaises(CarrierError) as ctx:
            render_probe(plan)
        # The message has to name the alternative, or the agent writes the
        # Kotlin test anyway because the error only said no.
        self.assertIn("scenario:AUTH-SIGNIN-01", str(ctx.exception))

    def test_the_probe_uses_the_plural_selector(self):
        from traceability.carrier import render_probe

        text = render_probe(self._plan(self.specs["AUTH-SIGNIN-01"], self.desktop, self.root))
        # Comments are stripped first, and that is the point: the template
        # *names* `onRoot()` in the comment explaining why not to use it, so a
        # whole-text search would fail on the explanation and pass on the usage.
        # A test that cannot tell the two is worse than no test, because it
        # reports a problem that is not there.
        code = "\n".join(
            line for line in text.splitlines()
            if not line.lstrip().startswith(("//", "*", "/*"))
        )
        self.assertIn("awaitTag", code)
        self.assertNotIn("onNodeWithTag", code)
        self.assertNotIn("onRoot()", code)

    def test_the_probe_carries_the_slow_tag(self):
        from traceability.carrier import render_probe

        text = render_probe(self._plan(self.specs["AUTH-SIGNIN-01"], self.desktop, self.root))
        self.assertIn('@Tag("slow")', text)

    def test_the_probe_header_warns_against_platform_narrowing(self):
        # The generator exists because a failed probe was read as a fact about
        # the platform. If the warning is dropped from the template, the
        # generator goes back to producing the wrong conclusion with less
        # effort than writing it by hand.
        from traceability.carrier import render_probe

        text = render_probe(self._plan(self.specs["AUTH-SIGNIN-01"], self.desktop, self.root))
        self.assertIn("TASK-TIME-01", text)
        self.assertIn("commonMain", text)


class CliTest(unittest.TestCase):
    """The four refusals, run as a subprocess because the exit code is the contract.

    Every case here goes through `--dry-run` or expects a refusal, and that is
    not tidiness. The first version of the deprecated case ran the *real* command
    with no flag, back when the CLI had no deprecation check, and it wrote
    `CalFilt01ScenarioTest.kt` into `desktopApp/src/jvmTest` — a test that
    generates a source file in the repository it is testing. It sat there long
    enough to be picked up as a real carrier by `scan_all`, which then made the
    per-scenario rule demand a result for a retired scenario and failed a test
    three files away. A test that writes needs a flag that stops it writing, and
    the one that stops it writing has to be part of what the test asserts.
    """

    def test_unknown_scenario_is_an_error(self):
        result = _run("carrier", "NOPE-99", "--dry-run")
        self.assertEqual(result.returncode, 1)
        self.assertIn("не найден", result.stdout)

    def test_a_deprecated_scenario_is_refused(self):
        # CAL-FILT-01 keeps the targets it used to have — that is how its row
        # still shows which platforms it covered — so a target check alone waves
        # it through, and a probe written for it would put a `●` on a row whose
        # glyph is `⊘`.
        result = _run("carrier", "CAL-FILT-01", "--target", "desktop", "--dry-run")
        self.assertEqual(result.returncode, 1)
        self.assertIn("выведен из эксплуатации", result.stdout)

    def test_an_existing_carrier_is_refused_even_under_another_filename(self):
        # The regression this file exists for. TASK-REC-01's carrier is
        # TaskRecurrenceScenarioTest.kt; a filename check on the generated
        # path finds nothing and writes a duplicate.
        result = _run("carrier", "TASK-REC-01", "--dry-run")
        self.assertEqual(result.returncode, 1)
        self.assertIn("уже несёт", result.stdout)
        self.assertNotIn("записан", result.stdout)

    def test_dry_run_writes_nothing(self):
        # AUTH-ATTACH-01 claims desktop but has no desktop carrier (auth flows are
        # not yet committed). Used to test the dry-run path for a valid desktop
        # carrier request that has not yet been fulfilled.
        result = _run("carrier", "AUTH-ATTACH-01", "--target", "desktop", "--dry-run")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("dry run", result.stdout)
        self.assertFalse(
            (ROOT / "desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/attachment"
                   / "AuthAttach01ScenarioTest.kt").exists()
        )

    def test_no_refusal_path_leaves_a_file_behind(self):
        # The property the four cases above each protect one instance of, stated
        # once so that adding a fifth refusal without `--dry-run` is caught here
        # rather than in whichever unrelated test finds the stray file first.
        for scenario in ("NOPE-99", "CAL-FILT-01", "TASK-REC-01"):
            before = set(ROOT.glob("desktopApp/src/jvmTest/kotlin/**/*ScenarioTest.kt"))
            _run("carrier", scenario, "--dry-run")
            after = set(ROOT.glob("desktopApp/src/jvmTest/kotlin/**/*ScenarioTest.kt"))
            self.assertEqual(before, after, f"{scenario} left a file behind")


if __name__ == "__main__":
    unittest.main()
