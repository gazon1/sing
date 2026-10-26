"""Tests for scripts/check-mobile-isolation.py.

A gate that does not run is not a gate, and a gate that cannot fail is worse than
none: it turns "not checked" into "checked and green". So these tests do three
things — the script exists and is a Click command, it is green on the real tree,
and it is **red** on trees that carry what the mobile build may not carry.
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "scripts/check-mobile-isolation.py"


def run_gate(root: pathlib.Path) -> subprocess.CompletedProcess:
    """The gate, pointed at a tree."""
    return subprocess.run(
        [sys.executable, str(MODULE_PATH), "--check", "--root", str(root)],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_a_clean_tree(self):
        """A gate that is red on a clean tree proves nothing about a red tree."""
        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class MobileIsolationTest(unittest.TestCase):
    """The gate's verdict on trees built for the purpose."""

    def _tree(self, files: dict[str, str]) -> pathlib.Path:
        """A tree with the given files, relative paths to contents."""
        root = pathlib.Path(tempfile.mkdtemp(prefix="mobile-isolation-"))
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return root

    def test_a_clean_mobile_tree_passes(self):
        root = self._tree(
            {
                "shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt":
                    "package com.singularity.todo.core.llm\n",
                "shared/src/androidMain/kotlin/com/singularity/todo/core/log/AndroidLog.kt":
                    "package com.singularity.todo.core.log\n",
            },
        )

        result = run_gate(root)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_the_protocol_client_in_common_main_fails(self):
        root = self._tree(
            {
                "shared/src/commonMain/kotlin/com/singularity/todo/core/llm/TextGenPort.kt":
                    "package com.singularity.todo.core.llm\n\n"
                    "import com.singularity.todo.feature.ai.acp.AcpAgentInfo\n",
            },
        )

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("external-agent", result.stderr + result.stdout)

    def test_the_protocol_library_in_android_main_fails(self):
        root = self._tree(
            {
                "shared/src/androidMain/kotlin/com/singularity/todo/core/log/AndroidLog.kt":
                    "package com.singularity.todo.core.log\n\n"
                    "import com.agentclientprotocol.model.SessionUpdate\n",
            },
        )

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_the_process_package_in_android_main_fails(self):
        root = self._tree(
            {
                "shared/src/androidMain/kotlin/com/singularity/todo/core/process/AndroidProcess.kt":
                    "package com.singularity.todo.core.process\n",
            },
        )

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_an_empty_tree_fails_rather_than_passing_vacuously(self):
        root = pathlib.Path(tempfile.mkdtemp(prefix="mobile-isolation-empty-"))

        result = run_gate(root)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
