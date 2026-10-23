"""Tests for check-agents-size.py — the cheat sheet stays a cheat sheet.

`AGENTS.md` was 517 lines, and every session pays for every one of them. The
rules that needed more room moved to `docs/architecture/`, and this gate keeps
the file from growing back: a change that needs more room needs a document
and a pointer, not a longer cheat sheet.

The tests hold the gate to its own claims:

  - a file within the limit passes;
  - a file over the limit fails;
  - a missing file fails (there is no cheat sheet to keep short);
  - the real AGENTS.md is within the limit.
"""

import importlib.util
import pathlib
import tempfile
import unittest

from click.testing import CliRunner

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / 'scripts' / 'check-agents-size.py'
spec = importlib.util.spec_from_file_location('check_agents_size', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class AgentsSizeTest(unittest.TestCase):
    """The limit, on files built for it."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='check-agents-size-'))
        self.addCleanup(self._remove)

    def _remove(self) -> None:
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write(self, lines: int) -> pathlib.Path:
        path = self.tmp / 'AGENTS.md'
        path.write_text('\n'.join(f'line {i}' for i in range(lines)), encoding='utf-8')
        return path

    def _run(self, *args: str):
        return CliRunner().invoke(mod.cli, list(args))

    def test_a_file_within_the_limit_passes(self):
        path = self._write(mod.MAX_LINES)
        result = self._run('--file', str(path))
        self.assertEqual(0, result.exit_code, result.output)

    def test_a_file_over_the_limit_fails(self):
        path = self._write(mod.MAX_LINES + 1)
        result = self._run('--file', str(path))
        self.assertEqual(mod.VALIDATION_FAILURE, result.exit_code, result.output)
        self.assertIn('limit', result.output)

    def test_a_missing_file_is_not_a_passing_file(self):
        result = self._run('--file', str(self.tmp / 'nothing.md'))
        self.assertEqual(mod.VALIDATION_FAILURE, result.exit_code, result.output)


class RealRepositoryTest(unittest.TestCase):
    """The real AGENTS.md is within the limit."""

    def test_the_real_agents_md_is_within_the_limit(self):
        self.assertLessEqual(
            mod.line_count(mod.AGENTS),
            mod.MAX_LINES,
            'AGENTS.md grew past the limit — the prose belongs in docs/architecture/',
        )


if __name__ == '__main__':
    unittest.main()
