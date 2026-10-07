#!/usr/bin/env python3
"""Tests for check-kiwi-inventory-ratchet.py.

The gate's whole value is that a number two branches both edit now lives in one file with
its reason beside it. So the tests here are about the *rule* — a stale measurement, an
exceeded ceiling, a floor file missing a metric — and not about today's numbers, which
change every time anyone adds a test.
"""

import importlib.util
import json
import pathlib
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _load():
    path = _ROOT / "scripts" / "check-kiwi-inventory-ratchet.py"
    spec = importlib.util.spec_from_file_location("check_kiwi_inventory_ratchet", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = _load()


def _config(**floors):
    return {
        "floors": [
            {"metric": m, "max": v, "measured": w, "label": m, "note": "n"}
            for m, (v, w) in floors.items()
        ]
    }


class CheckTest(unittest.TestCase):
    def test_green_when_every_metric_is_under_its_ceiling(self):
        exceeded, stale = gate.check(_config(inventory=(370, 360), skipped=(20, 5)), {"inventory": 360, "skipped": 5})
        self.assertEqual([], exceeded)
        self.assertEqual([], stale)

    def test_growth_past_the_ceiling_is_reported(self):
        exceeded, _ = gate.check(_config(inventory=(350, 350), skipped=(20, 5)), {"inventory": 360, "skipped": 5})
        self.assertEqual([("inventory", 350, 360)], exceeded)

    def test_a_stale_measurement_is_reported_even_when_under_the_ceiling(self):
        # The state that matters: the number is still legal, so nothing about the ceiling
        # says anything — but the file is describing a commit other than this one, and
        # nothing else in the repository would say so.
        exceeded, stale = gate.check(_config(inventory=(370, 342), skipped=(20, 5)), {"inventory": 360, "skipped": 5})
        self.assertEqual([], exceeded)
        self.assertEqual(1, len(stale))
        self.assertIn("342", stale[0])

    def test_a_floor_without_a_measured_field_is_not_stale(self):
        # Optional on purpose: an absent `measured` means "nobody has recorded it yet",
        # which is different from "recorded and wrong".
        cfg = _config(inventory=(370, 360), skipped=(20, 5))
        for floor in cfg["floors"]:
            del floor["measured"]
        _, stale = gate.check(cfg, {"inventory": 360, "skipped": 5})
        self.assertEqual([], stale)

    def test_a_metric_with_no_floor_cannot_pass(self):
        with self.assertRaises(ValueError) as ctx:
            gate.check(_config(inventory=(370, 360)), {"inventory": 360, "skipped": 5})
        self.assertIn("skipped", str(ctx.exception))

    def test_the_shipped_floor_file_measures_the_tree(self):
        # The one assertion that can go stale, which is exactly why the gate reports it:
        # someone adds a test class, forgets the floor file, and the file drifts away
        # from the repository without anything failing.
        config = json.loads(
            (_ROOT / "config" / "docs" / "kiwi-inventory-ratchet.json").read_text(encoding="utf-8")
        )
        measured = gate.measure()
        _, stale = gate.check(config, measured)
        self.assertEqual(
            [],
            stale,
            f"the floor file records measurements that no longer match the tree: {stale}",
        )


class MainTest(unittest.TestCase):
    def test_a_missing_floor_file_is_a_failure_not_a_pass(self):
        with tempfile.TemporaryDirectory() as d:
            missing = str(pathlib.Path(d) / "nope.json")
            self.assertEqual(1, gate.main(["--config", missing]))

    def test_accept_growth_turns_the_failure_into_a_warning(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "floor.json"
            # The recorded measurement is left correct, so the only thing that can fail
            # here is the ceiling — otherwise the staleness check answers first and the
            # test proves nothing about the flag.
            config = _config(skipped=(20, 5))
            config["floors"].append(
                {"metric": "inventory", "max": 1, "measured": 360, "label": "inventory", "note": "n"}
            )
            path.write_text(json.dumps(config))
            self.assertEqual(1, gate.main(["--config", str(path)]))
            self.assertEqual(0, gate.main(["--config", str(path), "--accept-growth"]))


if __name__ == "__main__":
    unittest.main()