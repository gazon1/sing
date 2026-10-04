"""Unit tests for infra/kiwi/prune.py — run retention selection.

Run with: python3 -m unittest discover -s scripts/tests

The deletion itself is not exercised: it is irreversible, and a test that
deletes from a real stand would make the test suite depend on a developer's
local state. What is tested is the *selection* — which runs fall outside the
window — because that is the part with a bug that would silently destroy
history, and it is testable without touching a database.
"""

import importlib.util
import pathlib
import unittest

KIWI_DIR = pathlib.Path(__file__).resolve().parents[2] / "infra" / "kiwi"
_spec = importlib.util.spec_from_file_location(
    "kiwi_prune", KIWI_DIR / "prune.py"
)
_module = importlib.util.module_from_spec(_spec)
_module.__name__ = "kiwi_prune"
_module.__file__ = str(KIWI_DIR / "prune.py")
sys_modules = __import__("sys").modules
sys_modules["kiwi_prune"] = _module
_spec.loader.exec_module(_module)

prune = _module


class FakeClient:
    """Enough of KiwiClient for plan_prune. Records deletions, never performs."""

    def __init__(self, runs_by_plan: dict[str, list[int]]):
        self._runs = runs_by_plan
        self.deleted: list[dict] = []

    def get_product(self, name):
        return {"id": 1, "name": name}

    def call(self, method, query=None):
        assert method == "TestPlan.filter", method
        return [
            {"id": i + 1, "name": name}
            for i, name in enumerate(self._runs)
        ]

    def get_runs(self, plan_id):
        names = list(self._runs)
        return [{"id": rid, "summary": f"run {rid}"} for rid in self._runs[names[plan_id - 1]]]

    def remove_query(self, query):
        self.deleted.append(query)


class PlanPruneTest(unittest.TestCase):
    def test_keeps_the_newest_n_per_plan(self):
        client = FakeClient({"shared": [1, 2, 3, 4, 5], "desktop": [10, 11, 12]})
        doomed = prune.plan_prune(client, "P", keep=2)
        # Newest means highest id, so with keep=2 the survivors are 4,5 / 11,12.
        self.assertEqual(
            sorted(r["id"] for r in doomed["shared"]), [1, 2, 3]
        )
        self.assertEqual(
            sorted(r["id"] for r in doomed["desktop"]), [10]
        )

    def test_nothing_is_doomed_when_under_the_window(self):
        client = FakeClient({"shared": [1, 2]})
        self.assertEqual(prune.plan_prune(client, "P", keep=5), {})

    def test_plans_are_windowed_independently(self):
        # A plan that rarely runs must not be emptied because another plan is
        # busy: that is the whole reason for a per-plan window rather than an
        # age-based cutoff.
        client = FakeClient({"busy": [1, 2, 3, 4, 5, 6], "rare": [7]})
        doomed = prune.plan_prune(client, "P", keep=2)
        self.assertNotIn("rare", doomed)
        self.assertEqual(len(doomed["busy"]), 4)

    def test_doomed_lists_carry_no_overlap_with_the_window(self):
        # 20 runs, window 19: only the oldest falls out. An off-by-one here
        # would delete one run too many per rotation, compounding silently
        # until the history is gone.
        client = FakeClient({"p": list(range(1, 21))})
        doomed = prune.plan_prune(client, "P", keep=19)
        ids = sorted(r["id"] for r in doomed["p"])
        self.assertEqual(ids, [1])
        self.assertEqual(max(ids), min(range(2, 21)) - 1)


if __name__ == "__main__":
    unittest.main()
