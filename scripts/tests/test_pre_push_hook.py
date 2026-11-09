"""Tests for `.githooks/pre-push` — the verdict must be about the pushed tree.

The defect this exists for: the hook's detekt step ran against the CURRENT
worktree and called its verdict "did auto-correct change any file". A push of a
tree nobody had in front of them was therefore judged by a tree that was not
being pushed, and a violation the author had already fixed locally was
reported as a violation of the tree that was going out — or, the other way
round, a violation that WAS in the pushed tree went out unjudged.

These tests drive the real hook through a real `git push` into a local bare
remote, in a temporary repository, with a stand-in for the Gradle wrapper that
reports a planted violation the way detekt reports one:

  - a clean push passes;
  - a violation in the pushed tree fails the push;
  - a violation that is only in the worktree does NOT fail the push (it is not
    in the tree being pushed);
  - a violation in the pushed tree fails the push even when the worktree
    already holds the fix (the pushed tree still carries it);
  - the gate suite's own tests pass inside the pushed tree even when the hook
    runs with the git environment git hands it.

The last two are the ones that matter: together they say the verdict is about
the tree that is being pushed, in both directions.

The last case is the one that was broken for months. Git hands a hook the
environment it runs in, and GIT_DIR in it points at the worktree the push was
made from — in a linked worktree, GIT_DIR is that worktree's own git dir.
The hook runs the gate suite inside a throwaway worktree, and without
dropping that environment git read the ORIGINAL worktree's index there: a
test that shells out to git failed with "git add: exit status 128", and the
suite reported a failure that had nothing to do with the code. Nothing
noticed, because the only thing that tested the hook was the hook.

Nothing here touches the developer's repository: the repository, the remote
and the hook copy are all temporary, and the Gradle wrapper is a stand-in, so
the tests take well under a second and need no build.
"""

import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / 'scripts' / 'src'))

from sgate.commands._registry import PRE_PUSH_SCRIPTS  # noqa: E402
from sgate.gatekit.gitenv import (  # noqa: E402
    INHERITED_FROM_HOOK,
    SHELL_HELPER,
    tree_env as _clean_git_env,
)

HOOK = REPO_ROOT / '.githooks' / 'pre-push'

#: The verdict script the hook calls, and the module graph it reads to decide
#: which modules to judge. Both travel with the repository — the hook calls the
#: script, and the script computes the modules from the graph.
VERDICT_SCRIPT = REPO_ROOT / 'scripts' / 'pre-push-verdict.py'
CHANGED_MODULES = REPO_ROOT / 'scripts' / 'changed-modules.py'
# The runner the hook calls for the gate suite's own tests. The fixture tree
# carries it, because the hook calls it by name: a fixture that does not carry
# the file the hook runs proves nothing about the hook.
GATE_TEST_RUNNER = REPO_ROOT / 'scripts' / 'run-gate-tests.py'
PACKAGE_BINDING = REPO_ROOT / 'scripts' / 'package-binding.py'

#: The tool that prints the CI verdict. The hook calls it before the push
#: goes out, so the verdict is a line in the output of the push and not a
#: thing a reader has to remember to ask for.
CI_VERDICT_SCRIPT = REPO_ROOT / 'scripts' / 'ci-verdict.py'

#: The tool the hook compares two answers with. It is not a gate — it judges
#: nothing about the tree — so the fixture carries the real file, the way it
#: carries the verdict: a stubbed ratchet would say "nothing is new" about
#: every push, and the tests would prove nothing.
#:
#: The path comes from the record of the hook (`config/hooks/pre-push.json`),
#: so the fixture cannot name a tool the record does not name.
HOOK_RECORD = REPO_ROOT / 'config' / 'hooks' / 'pre-push.json'
RATCHET_TOOL = REPO_ROOT / json.loads(
    HOOK_RECORD.read_text(encoding='utf-8'),
)['hook_scripts']['ratchet_tool']

#: The canonical answer to "which git variables must a command inside a tree
#: not inherit". The verdict script loads it by path, the way the checkout
#: carries it.
GITENV_SCRIPT = REPO_ROOT / 'scripts' / 'src' / 'sgate' / 'gatekit' / 'gitenv.py'
#: The helper that drops the git environment a hook exports. The hook
#: sources it; the fixture tree carries it, the same way it carries the
#: answer the helper reads.
GIT_ENV_HELPER = REPO_ROOT / 'scripts' / 'git-env.sh'
#: The declaration of the gates the hook runs. The hook reads it by path, so
#: the fixture tree carries it — the same way it carries the answer the
#: verdict script takes.
PRE_PUSH_DECLARATION = REPO_ROOT / 'scripts' / 'src' / 'sgate' / 'gatekit' / 'pre_push.py'

#: The one way to reach that answer when the package is out of reach. The
#: verdict script imports it, and the fixture tree has to carry it for the
#: same reason it carries the answer itself.
GITENV_LOADER = REPO_ROOT / 'scripts' / 'gitenv_loader.py'

#: A stand-in for the GitHub CLI: it reports one run of the CI workflow, the
#: way `gh run list` reports it. The hook asks about the commit it pushes, and
#: the answer has to name the workflow, the run and the conclusion.
#:
#: It writes down what it was asked, so the test can check the question and
#: not only the answer: a stand-in that answers every question alike proves
#: that the hook printed a line, not that it asked about the commit it pushes.
FAKE_GH = """#!/usr/bin/env bash
if [ -n "${FAKE_GH_LOG:-}" ]; then
  printf '%s\\n' "$*" >> "$FAKE_GH_LOG"
fi
cat <<JSON
[{"databaseId": 39888579980, "conclusion": "${FAKE_GH_CONCLUSION:-success}", "status": "completed", "url": "https://github.com/gazon1/sing/actions/runs/39888579980", "workflowName": "CI"}]
JSON
"""

# The marker detekt would report on. The stand-in Gradle wrapper looks for it
# in the Kotlin files of the tree it is run in.
VIOLATION = 'PLANTED_VIOLATION'

# A stand-in for the Gradle wrapper: it is what detekt would report for the
# tree it is run in, and it is committed, so a temporary worktree holds it too.
FAKE_GRADLEW = """#!/usr/bin/env bash
# A stand-in for the Gradle wrapper. detekt's verdict for the tree it runs in.
set -uo pipefail
if grep -rl --include='*.kt' '{marker}' . >/dev/null 2>&1; then
    echo "Example.kt:1:1: ClassSignature: the class signature must be on one line" >&2
    exit 1
fi
exit 0
""".format(marker=VIOLATION)

#: A gate stub that passes. The real gates read the repository; the hook only
#: needs them to succeed, and the point of these tests is the detekt verdict.
PASSING_PYTHON_GATE = """#!/usr/bin/env python3
import sys
sys.exit(0)
"""

#: A test of the gate suite that passes.
GREEN_GATE_TEST = """import unittest


class GreenGateTest(unittest.TestCase):
    def test_it_passes(self):
        self.assertTrue(True)
"""

#: A test of the gate suite that fails — unless the tree carries the marker.
#:
#: The marker is what makes the case possible: a module that is red in the
#: tree without the push having touched it. The module is green when it is
#: committed, the push that carries it goes out, and the marker goes away in
#: a later commit — so the module is red in the tree and the push that made
#: it red never touched the module.
RED_GATE_TEST = """import pathlib
import unittest


class RedGateTest(unittest.TestCase):
    def test_it_fails(self):
        marker = pathlib.Path(__file__).resolve().parents[2] / 'red-marker.txt'
        self.assertTrue(marker.exists(), 'the marker is gone')
"""

#: The file whose absence makes the red module red.
RED_GATE_MARKER = 'red-marker.txt'

#: The gate the ratchet has to tell apart: a finding the base tree holds as
#: well is pre-existing, and a finding only the pushed tree holds is the
#: push's own. It fails when the tree it runs in holds a marker file, and it
#: says where the finding is, the way the real gates do.
#:
#: It stands in for the version-catalog gate: the step that always runs, so
#: the case does not depend on which paths the push touched.
MARKER_GATE_SCRIPT = 'scripts/build-version-catalog-gate.py'
MARKER_GATE = """#!/usr/bin/env python3
import os
import sys

if os.path.exists('bad.txt'):
    print('bad.txt:1:1: hardcoded version literal')
    sys.exit(1)
sys.exit(0)
"""

#: A gate that falls over without saying anything.
#:
#: The ratchet compares what the gate said, and it has to tell "the gate found
#: a finding" from "the gate broke": a run that exits nonzero and prints
#: nothing found nothing at all, so the two trees cannot be compared and the
#: push stops. It stands in for the version-catalog gate — the step that
#: always runs — so the case does not depend on which paths the push touched.
SILENT_GATE_SCRIPT = MARKER_GATE_SCRIPT
SILENT_GATE = """#!/usr/bin/env python3
import sys

sys.exit(1)
"""

SOURCE = 'package com.example\n\nclass Example\n' + VIOLATION + '\n'
CLEAN_SOURCE = 'package com.example\n\nclass Example\n'

#: The marker a formatted source does not carry. The formatting gate of the
#: fixture is a stand-in that judges the files it is given — the way the real
#: one does — and nothing else.
FORMATTING_DEFECT = 'FORMATTING_DEFECT'

#: The environment variable the stand-in writes the files it was given to.
KTLINT_LOG_ENV = 'KTLINT_LOG'

#: A stand-in for the formatting script.
#:
#: The real script needs the ktlint binary, and the question these cases ask
#: is not what ktlint says about a file but WHICH files the gate is given: a
#: gate that judged the whole tree would be given a file with a defect that
#: the push does not touch, and the push would not go out. The stand-in
#: judges the files it is given, and writes them down so the test can ask.
KTLINT_STANDIN = '''#!/usr/bin/env python3
"""A stand-in for the formatting gate: it judges the files it was given."""
import os
import pathlib
import sys

files = []
args = sys.argv[1:]
while args:
    arg = args.pop(0)
    if arg == '--root':
        args.pop(0)
        continue
    files.append(arg)

log = os.environ.get('{log_env}')
if log:
    pathlib.Path(log).write_text('\\n'.join(files) + '\\n', encoding='utf-8')

for path in files:
    candidate = pathlib.Path(path)
    if not candidate.is_file():
        continue
    if '{defect}' in candidate.read_text(encoding='utf-8'):
        print(path + ':1:1: standard:no-consecutive-comments: not formatted', file=sys.stderr)
        raise SystemExit(1)

print('ktlint-lint: checking ' + str(len(files)) + ' file(s).')
raise SystemExit(0)
'''.format(log_env=KTLINT_LOG_ENV, defect=FORMATTING_DEFECT)

# The environment variable the probe writes what it saw to. The hook inherits
# the environment of the push, so the probe inside the pushed tree can tell
# the test outside what the git environment looked like from in there.
MARKER_ENV = 'PRE_PUSH_GIT_ENV_MARKER'

#: The environment variable the hook sets when it runs the suite.
#:
#: `PrePushHookTest` drives the hook through a real push, and the hook runs the
#: suite — so a suite that runs inside a hook runs itself: the hook's step
#: pushes, the push runs the hook, the hook runs the suite, and the suite
#: pushes again. On 2026-10-23 that recursion is what made the hook's own step
#: fail: the probe test asserted that the probe had run, and the probe ran one
#: tree deeper than the assertion was about.
#:
#: Inside a hook the class is therefore skipped. It runs everywhere else — in
#: CI, and by anybody who runs the suite by hand — so the coverage stays where
#: it belongs and the hook's step is one level deep.
INSIDE_HOOK_ENV = 'GATE_SUITE_INSIDE_HOOK'

#: True when this module runs inside the hook.
inside_hook = bool(os.environ.get(INSIDE_HOOK_ENV))

# A gate suite test that writes down the environment the suite ran in.
#
# It is committed into the pushed tree, so the hook's gate-suite step runs it
# inside that tree. It answers one question: did the suite run inside a hook?
SUITE_ENV_PROBE = '''"""A gate suite test that writes down the environment the suite ran in."""

import os
import pathlib
import unittest

PROBE_ENV = '{marker_env}'
INSIDE_HOOK_ENV = '{inside_hook_env}'


class SuiteEnvironmentTest(unittest.TestCase):
    """The suite writes down the environment it ran in."""

    def test_it_writes_what_it_saw(self):
        marker = os.environ.get(PROBE_ENV)
        if marker:
            pathlib.Path(marker).write_text(
                'inside_hook=' + os.environ.get(INSIDE_HOOK_ENV, '') + '\\n',
                encoding='utf-8',
            )
        self.assertTrue(True)
'''.format(marker_env=MARKER_ENV, inside_hook_env=INSIDE_HOOK_ENV)

# A gate suite test that shells out to git — the shape the real suite has.
#
# It is committed into the pushed tree, so the hook's gate-suite step runs it
# inside that tree. It asserts the one thing that broke: git, run inside the
# tree under test, must talk about THAT tree. Under a foreign GIT_DIR it talks
# about the worktree the push was made from, and the assertion fails.
#
# The marker is written BEFORE the assertion, so a failing probe still says
# what it saw: that is what tells the test outside whether the tree under test
# was the tree git talked about.
GIT_ENV_PROBE = '''"""A gate suite test that shells out to git — the shape the suite has.

The tree this test runs in is the tree being pushed. Git, run inside it, must
talk about THIS tree and not about the worktree the push was made from.
"""

import os
import pathlib
import subprocess
import unittest

MARKER_ENV = '{marker_env}'


class GitEnvironmentTest(unittest.TestCase):
    """git inside the tree under test must talk about that tree."""

    def _git_dir_of_this_tree(self) -> str:
        root = pathlib.Path(__file__).resolve().parents[2]
        dot_git = root / '.git'
        if dot_git.is_dir():
            return str(dot_git.resolve())
        # A linked worktree: a `.git` FILE that says where the git dir is.
        pointer = dot_git.read_text(encoding='utf-8').strip()
        _, _, path = pointer.partition('gitdir:')
        return str((root / path.strip()).resolve())

    def test_git_inside_this_tree_sees_this_tree(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        observed = subprocess.run(
            ['git', 'rev-parse', '--absolute-git-dir'],
            cwd=root,
            capture_output=True,
            text=True,
        )
        marker = os.environ.get(MARKER_ENV)
        if marker:
            pathlib.Path(marker).write_text(
                'observed=' + observed.stdout.strip() + '\\n'
                'GIT_DIR=' + os.environ.get('GIT_DIR', '') + '\\n',
                encoding='utf-8',
            )
        self.assertEqual(0, observed.returncode, observed.stderr)
        self.assertEqual(
            self._git_dir_of_this_tree(),
            str(pathlib.Path(observed.stdout.strip()).resolve()),
            'git inside the tree under test talks about a DIFFERENT tree — the '
            'git environment of the hook leaked into the tree being pushed',
        )
'''.format(marker_env=MARKER_ENV)


def _git(*args: str, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ['git', *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
        env=_clean_git_env(),
    )



@unittest.skipIf(
    inside_hook,
    'the suite is running inside a hook, and this test drives one',
)
class PrePushDeclarationTest(unittest.TestCase):
    """The declaration, read by path — the way the hook reads it."""

    @staticmethod
    def _declaration():
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            'pre_push_declaration', PRE_PUSH_DECLARATION,
        )
        module = importlib.util.module_from_spec(spec)
        # The declaration holds a dataclass, and a dataclass looks its own
        # module up in `sys.modules` — a module that is not registered there
        # has no `__dict__` to look into.
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        return module

    def test_the_tree_rules_are_skipped_when_the_gate_cli_cannot_run(self):
        """A machine without click is not stopped by a rule it cannot execute.

        The base hook skipped the tree rules when click was not installed, and
        CI runs them either way. A rule that cannot run must not become a
        reason a push cannot happen — so the rule is skipped, and the hook
        says that it was skipped.

        The rules are the ones the declaration names as tree rules, not every
        step that happens to run a script out of the tree: a step that runs
        without the gate CLI — the formatting check, which needs no click —
        is not a rule the missing CLI can stop, and asking it to carry the
        guard would make a machine without click skip a check that would have
        run.
        """
        declaration = self._declaration()
        tree_rule_scripts = {
            f'scripts/{rule}.py' for rule in declaration.PRE_PUSH_TREE_RULES
        }
        tree_rules = [
            step for step in declaration.PRE_PUSH_STEPS
            if step.script in tree_rule_scripts and 'if-script' in step.when
        ]
        self.assertTrue(tree_rules, 'the declaration has no tree rules')
        for step in tree_rules:
            self.assertIn('if-import:click', step.when, step.label)

    def test_every_step_answers_for_itself_and_nothing_else(self):
        """The declaration knows which gates it expects an answer for."""
        declaration = self._declaration()
        labels = declaration.pre_push_step_labels()
        self.assertEqual(len(labels), len(set(labels)), 'two steps answer under one label')
        self.assertEqual(
            list(labels),
            [step.label for step in declaration.PRE_PUSH_STEPS],
        )

    def test_a_gate_the_hook_never_answered_for_is_named(self):
        """The answer the hook gives is the answer the declaration expects.

        A hook that answered for eleven of twelve gates has a gap, and the
        gap has to name the gate that is missing — otherwise the reader has
        to count.
        """
        declaration = self._declaration()
        labels = declaration.pre_push_step_labels()
        missing = declaration.undecided_labels(','.join(labels[:-1]))
        self.assertEqual((labels[-1],), missing)
        self.assertEqual((), declaration.undecided_labels(','.join(labels)))


class PrePushHookTest(unittest.TestCase):
    """The hook, driven through a real push."""

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='pre-push-hook-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = self.tmp / 'repo'
        self.remote = self.tmp / 'remote.git'
        self.repo.mkdir()
        _git('init', '-q', '-b', 'main', cwd=self.repo)
        _git('config', 'user.email', 'hook@example.com', cwd=self.repo)
        _git('config', 'user.name', 'Hook Test', cwd=self.repo)
        _git('config', 'commit.gpgsign', 'false', cwd=self.repo)
        # The hook under test travels with the repository.
        _git('init', '-q', '--bare', str(self.remote), cwd=self.tmp)
        # The architecture check the hook runs last: the fixture's tree does
        # not carry the tool, and a hook that cannot find it says so and
        # stops the push. On a machine that has the tool, the step runs the
        # real one against the fixture's tree — and the answer is then the
        # machine's, not the fixture's. The fixture installs a stand-in, so
        # the answer is the fixture's on every machine.
        self.stub_bin = self.tmp / 'stub-bin'
        self.stub_bin.mkdir()
        self.easement_log = self.tmp / 'easement.log'
        easement = self.stub_bin / 'easement'
        easement.write_text(
            '#!/usr/bin/env bash\n'
            f'printf "%s\\n" "$*" >> "{self.easement_log}"\n'
            'exit 0\n',
            encoding='utf-8',
        )
        easement.chmod(0o755)
        # The semaphore the hook runs the detekt verdict under. The hook asks
        # for it by name and falls back to running the verdict directly when
        # it is not there — but a machine that HAS it may have a shim that
        # cannot resolve a version (mise says "No version is set for shim"),
        # and the verdict then fails for a reason that has nothing to do with
        # the code. The stand-in does what the hook needs of the semaphore:
        # it runs the command after the separator.
        heavy = self.stub_bin / 'heavy'
        heavy.write_text(
            '#!/usr/bin/env bash\n'
            'while [ "$#" -gt 0 ]; do\n'
            '    if [ "$1" = "--" ]; then shift; break; fi\n'
            '    shift\n'
            'done\n'
            'exec "$@"\n',
            encoding='utf-8',
        )
        heavy.chmod(0o755)
        # The stand-in is on the PATH of everything the fixture runs, so
        # every push the fixture makes finds it — the push that carries the
        # hook, and the push the hook's own steps make.
        self._original_path = os.environ.get('PATH', '')
        os.environ['PATH'] = f'{self.stub_bin}{os.pathsep}{self._original_path}'
        self.addCleanup(os.environ.__setitem__, 'PATH', self._original_path)

    def _write(self, relative: str, content: str, *, executable: bool = False) -> pathlib.Path:
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        if executable:
            path.chmod(0o755)
        return path

    def _install_hook(self) -> None:
        self._write('.githooks/pre-push', HOOK.read_text(encoding='utf-8'), executable=True)
        _git('config', 'core.hooksPath', '.githooks', cwd=self.repo)

    def _install_stubs(self, *, with_runner: bool = True) -> None:
        """The scripts the hook calls, and a Gradle wrapper that reports findings.

        The list of scripts comes from the registry — the same declaration the
        hook reads its rules from. It used to be written out here, and the two
        lists drifted: the hook called five scripts and this fixture stubbed
        three, so every script added to the hook was a failing test that only
        the full run found.
        """
        self._write('gradlew', FAKE_GRADLEW, executable=True)
        # The verdict runs `./gw` — the wrapper that gives a worktree its own
        # GRADLE_USER_HOME — so the stand-in is installed under that name too.
        self._write('gw', FAKE_GRADLEW, executable=True)
        for script in PRE_PUSH_SCRIPTS:
            if not with_runner and script == 'scripts/run-gate-tests.py':
                # A tree that predates the runner has no runner at all — not
                # even a stub of one.
                continue
            self._write(script, PASSING_PYTHON_GATE)
        # The verdict and the module graph it reads are part of the repository:
        # the hook calls the script, and the script computes the modules to
        # judge from the graph. They are the real files, not stubs — the
        # verdict is what these tests are about.
        self._write('scripts/pre-push-verdict.py', VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._write('scripts/changed-modules.py', CHANGED_MODULES.read_text(encoding='utf-8'))
        # The gate suite's own runner is called by the hook by name, so the
        # fixture tree carries it — the same way it carries the scripts the
        # hook reads. A tree that predates the runner has none, and the hook
        # falls back to the plain `unittest` for it.
        if with_runner:
            self._write('scripts/run-gate-tests.py', GATE_TEST_RUNNER.read_text(encoding='utf-8'))
            # The runner binds the package through a module it loads BY PATH:
            # it cannot import that module, because the package it repairs may
            # be the thing that is missing. A tree without the module has a
            # runner that cannot bind anything.
            self._write('scripts/package-binding.py', PACKAGE_BINDING.read_text(encoding='utf-8'))

        # The answer the verdict script takes is in the checkout too: the hook
        # runs the script from the checkout, and the checkout carries the file
        # the script loads. A checkout without it is not a checkout.
        self._write('scripts/src/sgate/gatekit/gitenv.py', GITENV_SCRIPT.read_text(encoding='utf-8'))
        # The helper that drops the git environment, and the file it reads
        # the names from: the hook sources it, so the fixture carries it.
        self._write('scripts/git-env.sh', GIT_ENV_HELPER.read_text(encoding='utf-8'))
        # The declaration of the gates the hook runs: the hook reads it by
        # path, in a tree that carries nothing else of the gate package.
        self._write(
            'scripts/src/sgate/gatekit/pre_push.py',
            PRE_PUSH_DECLARATION.read_text(encoding='utf-8'),
        )
        # And the way the script reaches that answer: the loader that reads
        # the file by path, because the package that also holds it cannot be
        # imported by the ambient interpreter the hook runs.
        self._write('scripts/gitenv_loader.py', GITENV_LOADER.read_text(encoding='utf-8'))
        # And the tool the hook compares two answers with. It is not a gate,
        # so it is not stubbed: the fixture carries the real file, the way it
        # carries the verdict. A stubbed ratchet would answer "nothing is new"
        # about every push, and the tests would prove nothing.
        self._write(
            RATCHET_TOOL.relative_to(REPO_ROOT),
            RATCHET_TOOL.read_text(encoding='utf-8'),
        )

    def _commit_source(self, content: str, message: str) -> None:
        self._write('shared/src/main/kotlin/Example.kt', content)
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', message, cwd=self.repo)

    def _commit(self, message: str) -> None:
        """Commit what the fixture holds — the source and the gate travel with it."""
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', message, cwd=self.repo)

    def _push(self, *extra: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess:
        # The remote is added once: a case that pushes twice — the first push
        # to put a commit on the remote, the second to measure a branch
        # against it — would fail on the second `git remote add`.
        if 'origin' not in _git('remote', cwd=self.repo).stdout.split():
            _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        push_env = _clean_git_env()
        if env:
            push_env.update(env)
        return subprocess.run(
            ['git', 'push', *extra, 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=push_env,
        )

    def _linked_worktree(self) -> pathlib.Path:
        """A linked worktree of the fixture repository — where the defect lived."""
        worktree = self.tmp / 'worktree'
        _git('worktree', 'add', '--detach', str(worktree), 'HEAD', cwd=self.repo)
        return worktree

    def _the_environment_git_hands_a_hook(self, worktree: pathlib.Path) -> str:
        """What git exports to a hook in a linked worktree.

        The premise of the case below: in a linked worktree, git exports
        GIT_DIR to the hook, and it points at that worktree's own git dir — a
        tree that is NOT the tree the hook checks out. A probe hook writes it
        down; it runs with a hooks path of its own, so the hook under test is
        the only hook that ever gates a push in this fixture.
        """
        hooks = worktree / '.probehooks'
        hooks.mkdir()
        hook = hooks / 'pre-push'
        hook.write_text(
            '#!/usr/bin/env bash\nprintf "%s" "${GIT_DIR:-}" > "$PROBE_MARKER"\n',
            encoding='utf-8',
        )
        hook.chmod(0o755)
        marker = self.tmp / 'probe-marker.txt'
        env = _clean_git_env()
        env['PROBE_MARKER'] = str(marker)
        subprocess.run(
            ['git', '-c', 'core.hooksPath=.probehooks', 'push', 'origin', 'HEAD:refs/heads/probe'],
            cwd=worktree,
            capture_output=True,
            text=True,
            env=env,
            check=True,
        )
        return marker.read_text(encoding='utf-8').strip()

    def _push_from_worktree(self, worktree: pathlib.Path, marker: pathlib.Path) -> subprocess.CompletedProcess:
        """Push from a linked worktree, with the marker in the environment.

        The hook inherits the environment of the push, so the probe inside the
        pushed tree sees the environment the hook ran with.
        """
        env = _clean_git_env()
        env[MARKER_ENV] = str(marker)
        return subprocess.run(
            ['git', 'push', 'origin', 'HEAD:refs/heads/main'],
            cwd=worktree,
            capture_output=True,
            text=True,
            env=env,
        )

    # ── the cases ────────────────────────────────────────────────────────

    def test_a_clean_push_passes(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('=== Pre-push OK ===', result.stdout + result.stderr)

    def test_the_architecture_check_still_runs(self):
        """The stand-in does not stand in for the step — it stands in for the tool.

        The hook runs the architecture check last, against the tree that is
        being pushed. The fixture's tree does not carry the tool, so the
        fixture installs one — and a stand-in that made the step disappear
        would be a step nobody runs any more, so the hook's own answer is
        checked: the step ran, and it ran against the pushed tree.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('easement: OK', output)
        ran = self.easement_log.read_text(encoding='utf-8') if self.easement_log.exists() else ''
        self.assertIn('check', ran, 'the architecture check did not run:\n' + output)

    def test_a_hook_that_answers_for_no_gate_fails_the_push(self):
        """A hook that ran no gate and said OK checked a tree it never looked at.

        The gates come from the table, so the hook runs them by label, and a
        loop that skips them all looks exactly like a hook that read the
        table and did nothing with it. The declaration knows how many gates
        there are, so the hook tells it which ones it answered for — and a
        hook that answered for none fails the push.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        hook = self.repo / '.githooks' / 'pre-push'
        hook.write_text(
            hook.read_text(encoding='utf-8').replace(
                '            [ -n "$step_label" ] || continue\n',
                '            [ -n "$step_label" ] || continue\n            continue\n',
            ),
            encoding='utf-8',
        )

        result = self._push()

        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('did not answer for every gate', result.stdout + result.stderr)

    def test_the_suite_runs_inside_a_hook(self):
        # The gate-suite step of the hook runs the suite in the tree being
        # pushed — so the suite runs inside a hook, and the marker says so.
        # The suite's own test — the one that drives the hook through a real
        # push — skips itself when it sees the marker: a suite that drives the
        # hook from inside the hook is a suite that runs itself, and on
        # 2026-10-23 that recursion is what made this step fail.
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_probe.py', SUITE_ENV_PROBE)
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        marker = self.tmp / 'suite-env.txt'
        result = self._push(env={MARKER_ENV: str(marker)})

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(marker.exists(), 'the suite did not run in the pushed tree')
        self.assertIn('inside_hook=1', marker.read_text(encoding='utf-8'))

    def test_a_tree_without_the_runner_still_runs_the_suite(self):
        # A branch that predates the runner has no runner, and a push of an
        # old branch is not blocked by a file that branch does not have.
        self._install_hook()
        self._install_stubs(with_runner=False)
        self._write(
            'scripts/tests/test_generated.py',
            'import unittest\n\n\nclass GeneratedTest(unittest.TestCase):\n'
            '    def test_it_passes(self):\n        self.assertTrue(True)\n',
        )
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('gate suite tests: OK', output)
        self.assertIn('the tree has no runner', output)

    def _a_tree_with_a_gate_suite(self) -> None:
        """A tree whose gate suite holds a passing module and a failing one.

        The failing module fails because a marker is gone — so it is green
        when it is committed and red afterwards, and the commit that made it
        red does not have to touch the module. The remote holds the tree
        before that commit, so the push that follows carries the red module
        without carrying the commit that made it red.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_gate_green.py', GREEN_GATE_TEST)
        self._write('scripts/tests/test_gate_red.py', RED_GATE_TEST)
        self._write(RED_GATE_MARKER, 'the marker is here\n')
        self._commit('a gate suite with a passing module and a failing one')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

    def _the_failing_module_is_red(self) -> None:
        """The premise: the module the push does not touch is red.

        A case about a module that is not red proves nothing, so the test
        says so: the module is run here, in the tree the push carries, and it
        fails.
        """
        red = subprocess.run(
            [sys.executable, '-m', 'unittest', 'scripts.tests.test_gate_red'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=_clean_git_env(),
        )
        self.assertNotEqual(0, red.returncode, 'the premise: the module is not red')

    def test_a_failing_module_the_push_does_not_touch_does_not_block_the_push(self):
        """The gate suite's step judges the modules the push touches.

        The tree carries a module of the gate suite that is red, and this push
        touches another module of it. The red module is upstream's debt: it
        is red in CI, where the whole suite runs, and it is not this push's
        to carry.
        """
        self._a_tree_with_a_gate_suite()
        (self.repo / RED_GATE_MARKER).unlink()
        self._write('scripts/tests/test_gate_green.py', GREEN_GATE_TEST + '\n# this push touches this module\n')
        self._commit('the marker is gone and the passing module changed')
        self._the_failing_module_is_red()

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('test_gate_green', output)
        self.assertNotIn(
            'test_gate_red',
            output,
            'the gate suite step judged a module the push does not touch:\n' + output,
        )

    def test_a_failing_module_the_push_touches_blocks_the_push(self):
        """The narrowing does not make the step deaf.

        A module the push touches and a failure in it are a finding the push
        does not go out with — the step judges what the change reaches, and
        that includes the change itself.
        """
        self._a_tree_with_a_gate_suite()
        (self.repo / RED_GATE_MARKER).unlink()
        self._write('scripts/tests/test_gate_red.py', RED_GATE_TEST + '\n# this push touches this module\n')
        self._commit('the marker is gone and the failing module changed')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn('test_gate_red', output)
        self.assertIn('gate suite tests: FAILED', output)

    def test_a_module_the_push_deletes_does_not_block_the_push(self):
        """A module the push deletes is not a module the push breaks.

        The step judges the modules of the suite the push carries, and the
        tree is what the push carries: a module this push deletes is not in
        the tree any more. A name the tree does not hold is a name the
        runner turns into a failed test — and the push would be blocked by a
        module that is not there.

        The push carries the commit that created the module as well: the
        name of a deleted module is in the history of the push, and a step
        that reads the history and not the tree judges a module that is
        gone.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_gate_green.py', GREEN_GATE_TEST)
        self._write('scripts/tests/test_gate_other.py', GREEN_GATE_TEST)
        self._commit('a gate suite with two passing modules')

        (self.repo / 'scripts/tests/test_gate_green.py').unlink()
        self._commit('one of the modules is gone')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertNotIn(
            '_FailedTest',
            output,
            'the step judged a module the tree does not hold:\n' + output,
        )
        self.assertIn('test_gate_other', output)

    def test_a_module_the_push_renames_is_judged_under_its_new_name(self):
        """A rename is a deletion and an addition, and the addition is the one.

        The old name is not in the tree any more, so it is not a module to
        judge; the new name is, and it is judged under the name the tree
        gives it.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_gate_green.py', GREEN_GATE_TEST)
        self._commit('a gate suite with one passing module')

        _git('mv', 'scripts/tests/test_gate_green.py', 'scripts/tests/test_gate_renamed.py', cwd=self.repo)
        self._commit('the module has a new name')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('test_gate_renamed', output)
        self.assertNotIn(
            '_FailedTest',
            output,
            'the step judged a module the tree does not hold:\n' + output,
        )

    def test_a_helper_the_push_changes_reaches_the_modules_that_import_it(self):
        """A helper of the suite is not a test, but the change reaches its importers.

        The scope of the step is the change the push carries: a helper under
        scripts/tests/ is part of the suite, and the modules that import it
        are judged by it.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/helper_mod.py', 'def helper():\n    return 1\n')
        self._write(
            'scripts/tests/test_uses_helper.py',
            'import unittest\n\nfrom helper_mod import helper\n\n\n'
            'class UsesHelperTest(unittest.TestCase):\n'
            '    def test_it_passes(self):\n'
            '        self.assertEqual(1, helper())\n',
        )
        self._commit('a helper and a test that uses it')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        self._write('scripts/tests/helper_mod.py', 'def helper():\n    return 1\n\n\ndef other():\n    return 2\n')
        self._commit('the helper changed')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn(
            'test_uses_helper',
            output,
            'the step did not reach the module the changed helper serves:\n' + output,
        )

    def test_a_helper_the_push_changes_reaches_a_test_behind_another_helper(self):
        """The walk follows the chain of helpers to the test it serves.

        A helper that imports another helper is not a test module — but the
        test module it serves is reached by the change just the same, two
        steps away. A walk that stops at the first helper judges nothing,
        and the push goes out with a broken helper.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/helper_a.py', 'def helper():\n    return 1\n')
        self._write(
            'scripts/tests/helper_b.py',
            'from helper_a import helper as helper_a\n\n\ndef helper():\n    return helper_a() + 1\n',
        )
        self._write(
            'scripts/tests/test_uses_b.py',
            'import unittest\n\nfrom helper_b import helper\n\n\n'
            'class UsesBTest(unittest.TestCase):\n'
            '    def test_it_passes(self):\n'
            '        self.assertEqual(2, helper())\n',
        )
        self._commit('a helper, a helper that uses it, and a test that uses that one')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        # The change breaks the helper two steps away from the test.
        self._write('scripts/tests/helper_a.py', 'def helper():\n    return 41\n')
        self._commit('the helper changed')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(
            0,
            result.returncode,
            'the walk stopped at the helper and judged nothing:\n' + output,
        )
        self.assertIn(
            'test_uses_b',
            output,
            'the step did not reach the test behind the changed helper:\n' + output,
        )
        self.assertIn('gate suite tests: FAILED', output)

    def test_a_module_the_push_touches_that_holds_no_test_cases_does_not_block_the_push(self):
        """A module without tests cannot fail.

        The runner answers with NO TESTS RAN when the modules it was given
        hold no test cases at all. That is the absence of a measurement, not
        a failure of the tree — and a push that adds a placeholder module
        must not be blocked by it.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_gate_green.py', GREEN_GATE_TEST)
        self._write('scripts/tests/test_placeholder.py', '"""A module with no test cases yet."""\n')
        self._commit('a gate suite with a passing module and a placeholder')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        # The push touches the placeholder alone: the runner has nothing to
        # measure, and nothing to fail.
        self._write(
            'scripts/tests/test_placeholder.py',
            '"""A module with no test cases yet."""\n\n# touched by this push\n',
        )
        self._commit('the placeholder changed')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertNotIn(
            'gate suite tests: FAILED',
            output,
            'the push was blocked by a module that holds no test cases:\n' + output,
        )

    def test_the_hook_and_the_runner_agree_on_the_code_that_means_no_test_ran(self):
        """The hook reads the runner's answer, and the answer is a number.

        The runner says "nothing was measured" with an exit code, and the
        hook reads that code as "skipped" rather than as a failure. The
        number lives in the runner — so a runner that changes it without the
        hook changing would turn every empty module back into a blocked push.
        """
        hook_code = re.search(
            r'^NO_TESTS_RAN=(\d+)$', HOOK.read_text(encoding='utf-8'), re.MULTILINE,
        )
        runner_code = re.search(
            r'^NO_TESTS_RAN = (\d+)$', GATE_TEST_RUNNER.read_text(encoding='utf-8'), re.MULTILINE,
        )
        self.assertIsNotNone(hook_code, 'the hook does not name the code it reads')
        self.assertIsNotNone(runner_code, 'the runner does not name the code it answers with')
        self.assertEqual(runner_code.group(1), hook_code.group(1))

    def test_the_push_prints_the_ci_verdict_of_the_commit_it_pushes(self):
        """The CI verdict is a line of the push, not a thing to remember to ask for.

        The tool that prints it is `scripts/ci-verdict.py`, and the hook calls
        it about the commit that is going out. The answer names the workflow,
        the run and the conclusion — a run id on its own is not a verdict, and
        a repository has several workflows.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # A stand-in for the GitHub CLI, so the answer does not depend on a
        # network and on what the real CI happens to have concluded.
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # The push goes to the local remote, and the *fetch* url of that remote
        # is the GitHub repository the hook asks about — a fork, so that the
        # derivation of the repository is part of the question.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:example/fork.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', str(self.remote), cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        pushed = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertIn('ci.yml  run 39888579980  success', output)
        self.assertEqual(0, result.returncode, output)
        # The question, and not only the answer: the hook asked about the
        # commit it pushes, and about the repository it pushes to.
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn(f'--commit {pushed}', asked)
        self.assertIn('--repo example/fork', asked)

    def test_a_failing_ci_verdict_does_not_block_the_push(self):
        """The verdict is a line of the push. A red line is not a red push.

        The CI verdict is printed for the reader, before the push goes out —
        and the push goes out anyway, because CI has not seen this commit yet
        in the usual case, and a push that depends on CI is a push that
        depends on the network. A verdict that says "failure" must therefore
        be printed and not obeyed.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:example/fork.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', str(self.remote), cwd=self.repo)
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        # The stand-in answers the way a red run answers.
        env['FAKE_GH_CONCLUSION'] = 'failure'

        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertIn('ci.yml  run 39888579980  failure', output)
        self.assertIn('=== Pre-push OK ===', output)
        self.assertEqual(0, result.returncode, output)

    def test_the_verdict_is_about_the_remote_the_push_reaches(self):
        """A push to a fork asks about the fork, not about `origin`.

        Git names the remote it pushes to as the first argument of the hook,
        and a clone has more than one remote often enough: `origin` is a
        convention, not a rule. The verdict that names `origin` when the push
        went to `fork` is a verdict about another repository.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # Two remotes: `origin` is the GitHub repository the clone came from,
        # `fork` is the one this push goes to.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:example/upstream.git', cwd=self.repo)
        _git('remote', 'add', 'fork', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'fork', 'git@github.com:example/the-fork.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'fork', str(self.remote), cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)

        result = subprocess.run(
            ['git', 'push', 'fork', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo example/the-fork', asked)
        self.assertNotIn('--repo example/upstream', asked)

    def test_the_verdict_is_about_the_url_the_push_reaches(self):
        """A push to a url asks about that url, not about `origin`.

        `git push <url>` names no remote at all: git hands the url over as the
        remote name, and the configuration has no entry for a url. The
        repository the push reaches is the url.

        The url is a `file://` one whose path holds `github.com`, so the push
        reaches a local repository and the test asks nothing of the network —
        and the hook reads the repository out of the url the way it reads it
        out of a remote's configuration.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:example/upstream.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', str(self.remote), cwd=self.repo)
        # The repository the push reaches, at a path that says GitHub.
        reached = self.tmp / 'github.com' / 'example' / 'the-fork.git'
        reached.parent.mkdir(parents=True)
        _git('init', '-q', '--bare', str(reached), cwd=self.tmp)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)

        result = subprocess.run(
            ['git', 'push', f'file://{reached}', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo example/the-fork', asked)
        self.assertNotIn('--repo example/upstream', asked)

    def test_the_verdict_is_about_the_repository_the_push_reaches(self):
        """The push url is the repository that receives the push.

        The fetch url and the push url are two answers to two questions, and
        the question here is the second one: CI runs in the repository the
        push reaches. The urls are read from the configuration, so a
        developer who pushes to a mirror is asked about the repository they
        mean and not about the mirror the rewrite points at.

        The hook is run the way git runs it — the ref lines on stdin, the
        remote and its url as arguments — and the push itself is not made:
        the question the hook asks is not a question about the network.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # Two GitHub repositories: the one the push is read from and the one
        # the push goes to.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:upstream/sing.git', cwd=self.repo)
        _git('remote', 'set-url', '--push', 'origin', 'git@github.com:fork/mine.git', cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        sha = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            [str(self.repo / '.githooks' / 'pre-push'), 'origin', 'git@github.com:fork/mine.git'],
            cwd=self.repo,
            input=f'refs/heads/main {sha} refs/heads/main {sha}\n',
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo fork/mine', asked)

    def test_the_verdict_is_about_the_repository_the_push_names_and_not_the_mirror_it_is_rewritten_to(self):
        """A mirror the push is rewritten to is not the repository CI runs in.

        The urls are read from the configuration, so a rewrite the developer
        has set up does not change the answer: the repository the push names
        is the repository whose runs are asked about.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        # The push names a GitHub repository, and a rule rewrites what it
        # names to a mirror that is not one.
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        _git('remote', 'set-url', 'origin', 'git@github.com:owner/repo.git', cwd=self.repo)
        _git('config', 'url.https://mirror.example.com/.insteadOf', 'git@github.com:', cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)
        sha = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()

        result = subprocess.run(
            [str(self.repo / '.githooks' / 'pre-push'), 'origin', 'git@github.com:owner/repo.git'],
            cwd=self.repo,
            input=f'refs/heads/main {sha} refs/heads/main {sha}\n',
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertIn('--repo owner/repo', asked)

    def test_a_push_to_a_remote_that_is_not_github_asks_nobody(self):
        """The step must not ask about a repository the remote is not.

        The tool's own default names the upstream, so a step that ran without
        naming the repository would ask about somebody else's runs — the very
        mistake the derivation exists to avoid.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ci-verdict.py', CI_VERDICT_SCRIPT.read_text(encoding='utf-8'))
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        gh = bin_dir / 'gh'
        gh.write_text(FAKE_GH, encoding='utf-8')
        gh.chmod(0o755)
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        gh_log = self.tmp / 'gh.log'
        env = _clean_git_env()
        env['PATH'] = f'{bin_dir}{os.pathsep}{env.get("PATH", "")}'
        env['FAKE_GH_LOG'] = str(gh_log)

        result = subprocess.run(
            ['git', 'push', 'origin', 'main'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            env=env,
        )

        output = result.stdout + result.stderr
        self.assertIn('ci verdict: SKIPPED (origin is not a GitHub repository)', output)
        self.assertEqual(0, result.returncode, output)
        asked = gh_log.read_text(encoding='utf-8') if gh_log.exists() else ''
        self.assertEqual('', asked, 'nobody was asked about a repository the remote is not')

    def test_a_violation_in_the_pushed_tree_fails_the_push(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(SOURCE, 'a file with a finding')

        result = self._push()

        self.assertNotEqual(0, result.returncode, 'the push went out with a finding in it')
        output = result.stdout + result.stderr
        self.assertIn('detekt', output)
        self.assertIn('FAILED', output)

    def test_a_push_that_moves_no_kotlin_is_not_a_clean_verdict(self):
        """Nothing was judged, so nothing was found — the summary must not claim clean."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The trunk the change is measured against.
        head = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()
        _git('update-ref', 'refs/remotes/origin/main', head, cwd=self.repo)
        # A change that moves neither Kotlin nor rules.
        self._write('docs/note.md', 'a note\n')
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'a note', cwd=self.repo)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('nothing to judge', output)
        self.assertIn('detekt verdict: not taken', output)
        # The reason is the verdict's own, not one the hook invented.
        self.assertIn('no detekt run is needed', output)

    def test_the_summary_carries_the_verdicts_own_reason(self):
        """A change that moves Kotlin into a module with no detekt task.

        The verdict has TWO reasons for taking none, and they are different
        claims: a change that moves no Kotlin at all, and a change that moves
        Kotlin into a module whose verdict is its test suite — it applies the
        plain kotlin("jvm") plugin, so it has no detekt task of its own.

        The hook used to substitute the first reason for the second: it told
        the reader that a change which moves Kotlin moves no Kotlin. The
        summary must carry the reason the verdict gave.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The trunk the change is measured against.
        head = _git('rev-parse', 'HEAD', cwd=self.repo).stdout.strip()
        _git('update-ref', 'refs/remotes/origin/main', head, cwd=self.repo)
        # A change in the module whose verdict is its test suite.
        self._write(
            'tools/unwritten-properties/src/main/kotlin/Example.kt',
            CLEAN_SOURCE,
        )
        _git('add', '-A', cwd=self.repo)
        _git('commit', '-q', '-m', 'a change in a module without a detekt task', cwd=self.repo)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('detekt verdict: not taken', output)
        self.assertIn(
            'no detekt task exists for it',
            output,
            'the summary did not carry the verdict\'s own reason:\n' + output,
        )
        self.assertNotIn(
            'this change moves no Kotlin and no rules',
            output,
            'the hook substituted its own reason for the verdict\'s:\n' + output,
        )

    def test_the_coverage_claim_is_true(self):
        """CI judges a hand-written list of modules, not every module.

        The claim the hook used to make — "CI judges every module" — is false
        as a statement about the mechanism: CI's detekt list is written out by
        hand, while the hook's set comes from the module graph. A module the
        graph knows and CI's list does not name is judged here and by nobody
        else.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('CI judges the modules its own list names', output)
        self.assertNotIn('CI judges every module', output)

    def test_the_verdict_is_taken_in_the_worktree_when_it_holds_the_pushed_tree(self):
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertIn('byte-identical', output, 'a clean worktree is the pushed tree')

    def test_a_violation_only_in_the_worktree_does_not_fail_the_push(self):
        """The worktree is not the tree being pushed, so it cannot be the verdict."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The author breaks the file locally and pushes the clean commit.
        self._write('shared/src/main/kotlin/Example.kt', SOURCE)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')

    def test_a_violation_in_the_pushed_tree_fails_even_when_the_worktree_is_clean(self):
        """The fix in the worktree does not make the pushed tree clean."""
        self._install_hook()
        self._install_stubs()
        self._commit_source(SOURCE, 'a file with a finding')
        # The author fixes it locally — after committing, before pushing.
        self._write('shared/src/main/kotlin/Example.kt', CLEAN_SOURCE)

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, 'the push went out with a finding in it')
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')

    def test_a_changed_editorconfig_sends_the_verdict_to_the_pushed_tree(self):
        """ktlint reads .editorconfig from inside detekt, so it is part of the verdict."""
        self._install_hook()
        self._install_stubs()
        self._write('.editorconfig', 'root = true\n')
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        # The editorconfig changes locally: the rules detekt applies change with it.
        self._write('.editorconfig', 'root = true\nmax_line_length = 120\n')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('temporary worktree', output, 'the verdict was taken about the pushed tree')

    def test_the_gate_suite_step_passes_with_the_git_environment_git_hands_a_hook(self):
        """The suite runs inside the pushed tree, under the hook's environment.

        Git exports GIT_DIR to a hook in a linked worktree, and it points at
        that worktree — the one the push was made from. The hook runs the gate
        suite in a throwaway worktree, so the environment has to be dropped
        there: otherwise git reads the original worktree's index and the suite
        fails for a reason that has nothing to do with the code.

        The assertion is about the hook's own behaviour — the gate suite step
        passes — and the premise (a foreign GIT_DIR) is checked too, so the
        case cannot pass by having no environment to drop.
        """
        self._install_hook()
        self._install_stubs()
        self._write('scripts/tests/test_git_environment.py', GIT_ENV_PROBE)
        # The probe travels with the tree being pushed: the commit that carries
        # the source carries the gate suite test as well.
        self._commit_source(CLEAN_SOURCE, 'a clean file and a gate suite test')
        _git('remote', 'add', 'origin', str(self.remote), cwd=self.repo)
        worktree = self._linked_worktree()

        # The premise: git really does hand a hook in a linked worktree a
        # GIT_DIR, and it points at that worktree — the one the push is made
        # from, and not the tree the hook checks out.
        foreign = self._the_environment_git_hands_a_hook(worktree)
        self.assertTrue(
            foreign,
            'git handed the hook no GIT_DIR at all — the case would prove nothing',
        )
        self.assertEqual(
            foreign,
            subprocess.run(
                ['git', 'rev-parse', '--absolute-git-dir'],
                cwd=worktree,
                capture_output=True,
                text=True,
                env=_clean_git_env(),
            ).stdout.strip(),
            'the GIT_DIR of the hook is not the git dir of the worktree the push '
            'was made from',
        )

        marker = self.tmp / 'git-env-marker.txt'
        result = self._push_from_worktree(worktree, marker)
        output = result.stdout + result.stderr

        # The probe inside the pushed tree ran — so the gate suite step was
        # exercised, and it saw a tree of its own.
        self.assertTrue(
            marker.exists(),
            'the probe inside the pushed tree did not run — the gate suite step '
            'was not exercised:\n' + output,
        )
        seen = marker.read_text(encoding='utf-8')
        self.assertNotIn(
            'observed=' + foreign,
            seen,
            'git inside the pushed tree talked about the worktree the push was '
            'made from:\n' + seen,
        )

        # The behaviour: the suite passed inside the pushed tree anyway.
        self.assertIn(
            'gate suite tests: OK',
            output,
            'the gate suite failed inside the pushed tree under the git '
            'environment of the hook:\n' + output,
        )
        self.assertEqual(0, result.returncode, output)

    # ── the ratchet ──────────────────────────────────────────────────────

    def test_a_finding_the_base_tree_has_as_well_does_not_block_the_push(self):
        """The repository's own findings are the repository's CI run to report.

        The hook judges the tree that is being pushed, and the trunk's trees
        are not green: a finding the branch did not bring is the trunk's
        finding, and a hook that blocks on it blocks every push, forever, on
        somebody else's debt.

        So the gate is run again in the base tree — the commit the branch
        forked from — and what the base has as well is not this push's.
        """
        self._install_hook()
        self._install_stubs()
        self._write(MARKER_GATE_SCRIPT, MARKER_GATE)
        self._write('bad.txt', 'a finding the trunk already carries\n')
        self._commit_source(CLEAN_SOURCE, 'a clean file with a pre-existing finding')
        # The remote holds this commit, so the next push is measured against
        # it — that commit is the base of the branch that follows.
        first = self._push('--no-verify')
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self._commit_source(CLEAN_SOURCE + '\n// a change of its own\n', 'a change')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('PRE-EXISTING', output)

    def test_a_finding_the_push_brings_blocks_the_push(self):
        """What the base does not have, the push brought.

        The ratchet is not a way of waving a finding through: a finding that
        is only in the tree the push carries is this push's, and the push
        stops.
        """
        self._install_hook()
        self._install_stubs()
        self._write(MARKER_GATE_SCRIPT, MARKER_GATE)
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        first = self._push('--no-verify')
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self._write('bad.txt', 'a finding this push brings\n')
        self._commit_source(CLEAN_SOURCE + '\n// a change of its own\n', 'a change')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn('version-catalog gate: FAILED', output)
        self.assertIn('what this push adds:', output)

    def test_a_gate_that_broke_without_saying_anything_blocks_the_push(self):
        """A gate that fell over is not a gate that found nothing.

        The ratchet compares what the gate said. A run that exits nonzero
        without printing anything said nothing — it broke — and two broken
        runs used to compare as equal (both outputs empty), so the push was
        accepted on the strength of a gate that validated neither tree.

        The comparison cannot be made, and a comparison that could not be
        made stops the push.
        """
        self._install_hook()
        self._install_stubs()
        self._write(SILENT_GATE_SCRIPT, SILENT_GATE)
        self._commit_source(CLEAN_SOURCE, 'a clean file')
        first = self._push('--no-verify')
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        self._commit_source(CLEAN_SOURCE + '\n// a change of its own\n', 'a change')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn('version-catalog gate: FAILED', output)
        self.assertNotIn('PRE-EXISTING', output)

    def test_easement_is_skipped_when_the_tree_does_not_declare_it(self):
        """The tree's own declaration decides whether it has this gate.

        The tool is installed through mise, and mise resolves a shim from the
        tree's own mise.toml — so a tree that does not declare easement has
        no easement, and a push of such a tree is not blocked by a tool it
        does not have. It used to be decided by whether the binary could be
        found, and the answer depended on the machine: the same tree passed
        on one machine and failed on another.
        """
        self._install_hook()
        self._install_stubs()
        self._commit_source(CLEAN_SOURCE, 'a clean file')

        result = self._push()

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        self.assertIn('easement: SKIPPED', output)


    # ── the formatting gate ───────────────────────────────────────────────

    def _a_remote_that_holds_the_formatting_gate(self) -> pathlib.Path:
        """A remote whose tree carries the formatting gate and a clean source."""
        self._install_hook()
        self._install_stubs()
        self._write('scripts/ktlint-lint.py', KTLINT_STANDIN)
        self._write('shared/src/main/kotlin/Clean.kt', CLEAN_SOURCE)
        self._commit('a clean source and the formatting gate')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        return self.tmp / 'ktlint-files.txt'

    def test_a_file_born_in_a_merge_is_judged_by_the_formatting_gate(self):
        """A merge carries files that no commit of the range shows.

        `git log --name-only` says nothing about a merge commit: the diff of
        a merge is computed against its parents, and the plain log does not
        show it. A file that a merge introduced is therefore invisible to it
        — and a gate that judges only what the plain log names does not
        judge that file at all. The push of such a merge carries the file,
        so the gate has to judge it.
        """
        log = self._a_remote_that_holds_the_formatting_gate()

        # Two branches add the same file, and the merge resolves the
        # conflict by hand. The resolution also creates a file that neither
        # parent has: that file is born in the merge commit.
        _git('checkout', '-q', '-b', 'side', cwd=self.repo)
        self._write('shared/src/main/kotlin/Contested.kt', 'package com.example\n\nclass ContestedSide\n')
        self._commit('the side branch adds the contested file')
        _git('checkout', '-q', 'main', cwd=self.repo)
        self._write('shared/src/main/kotlin/Contested.kt', 'package com.example\n\nclass ContestedMain\n')
        self._commit('the mainline adds the contested file')
        # The merge conflicts: that is the point — the resolution is a file
        # the merge alone carries.
        subprocess.run(
            ['git', 'merge', 'side'],
            cwd=self.repo,
            capture_output=True,
            text=True,
            check=False,
            env=_clean_git_env(),
        )
        self._write('shared/src/main/kotlin/Contested.kt', 'package com.example\n\nclass ContestedResolved\n')
        self._write(
            'shared/src/main/kotlin/BornInMerge.kt',
            'package com.example\n\nclass BornInMerge\n' + FORMATTING_DEFECT + '\n',
        )
        self._commit('the merge resolves the conflict and adds a file of its own')

        result = self._push(env={KTLINT_LOG_ENV: str(log)})

        output = result.stdout + result.stderr
        judged = log.read_text(encoding='utf-8').split() if log.exists() else []
        self.assertIn(
            'shared/src/main/kotlin/BornInMerge.kt',
            judged,
            'the formatting gate did not judge the file the merge introduced:\n' + output,
        )
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn('ktlint gate: FAILED', output)

    def test_a_source_with_a_non_ascii_name_is_judged_by_the_formatting_gate(self):
        """A path with non-ASCII letters is a path.

        Git escapes such a path by default (`"f/Caf\\303\\251.kt"`), and the
        escaped form matches no pattern the gates use: the file is in the
        push and no gate sees it.
        """
        log = self._a_remote_that_holds_the_formatting_gate()

        self._write('shared/src/main/kotlin/Café.kt', CLEAN_SOURCE)
        self._commit('a source whose name is not ASCII')

        result = self._push(env={KTLINT_LOG_ENV: str(log)})

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        judged = log.read_text(encoding='utf-8').split() if log.exists() else []
        self.assertIn(
            'shared/src/main/kotlin/Café.kt',
            judged,
            'the formatting gate did not judge the source with the non-ASCII name:\n' + output,
        )

    def _a_remote_that_holds_a_formatting_defect(self) -> pathlib.Path:
        """A remote whose tree holds a source with a formatting defect.

        The defect is in every tree the later pushes carry, and in none of the
        commits those pushes carry: it is the defect the tree came with — the
        upstream defect that used to block every push of the branch.
        """
        self._install_hook()
        self._install_stubs()
        self._write(
            'shared/src/main/kotlin/Legacy.kt',
            'package com.example\n\n' + FORMATTING_DEFECT + '\n',
        )
        self._commit('a source with a formatting defect')
        first = self._push()
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)

        # The gate of the pushes that follow is the stand-in, and they touch
        # one clean source.
        self._write('scripts/ktlint-lint.py', KTLINT_STANDIN)
        self._write('shared/src/main/kotlin/Clean.kt', CLEAN_SOURCE)
        self._commit('a clean source and the formatting gate')
        return self.tmp / 'ktlint-files.txt'

    def test_a_formatting_defect_the_push_does_not_touch_does_not_block_the_push(self):
        """A push is not blocked by a formatting defect it does not carry.

        The formatting gate judges the Kotlin sources the push touches. The
        tree this push carries holds a source with a defect that the push
        does not touch, and the push goes out.

        The gate is a stand-in that judges the files it is given and writes
        them down: a gate that judged the whole tree would be given the file
        with the defect, and the push would not go out.
        """
        log = self._a_remote_that_holds_a_formatting_defect()

        result = self._push(env={KTLINT_LOG_ENV: str(log)})

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode, output)
        judged = log.read_text(encoding='utf-8').split() if log.exists() else []
        self.assertEqual(
            ['shared/src/main/kotlin/Clean.kt'],
            sorted(judged),
            'the formatting gate judged ' + repr(judged) + ' — it has to judge the '
            'sources the push touches, and only them:\n' + output,
        )

    def test_a_formatting_defect_the_push_touches_still_blocks_the_push(self):
        """The narrowing does not make the gate deaf.

        A source the push touches and a defect in it are a finding the push
        does not go out with — the gate judges what the change reaches, and
        that includes the change itself.
        """
        log = self._a_remote_that_holds_a_formatting_defect()
        self._write(
            'shared/src/main/kotlin/New.kt',
            'package com.example\n\n' + FORMATTING_DEFECT + '\n',
        )
        self._commit('a source with a formatting defect')

        result = self._push(env={KTLINT_LOG_ENV: str(log)})

        output = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode, output)
        self.assertIn('ktlint gate: FAILED', output)
        judged = log.read_text(encoding='utf-8').split() if log.exists() else []
        self.assertIn('shared/src/main/kotlin/New.kt', judged)
        self.assertNotIn(
            'shared/src/main/kotlin/Legacy.kt',
            judged,
            'the formatting gate was given a source the push does not touch',
        )


class HookGitEnvironmentTest(unittest.TestCase):
    """The hook drops the git environment, and it drops all of it.

    The hook runs its steps inside the tree being pushed, and git hands a hook
    the environment of the worktree the push was made from. Every step that
    runs there has to drop that environment — and the list of names it has to
    drop lives in one place, `sgate.gatekit.gitenv`, because a step that drops
    three of the four names breaks in a way nobody sees: the tree is read
    through the wrong index, and the command reports about a tree nobody
    pushed.
    """

    def test_the_hook_drops_the_environment_through_the_sanctioned_helper(self):
        """One place drops the environment, and it is the helper.

        The hook used to write the four names down five times, and a copy of a
        list is a list that can drift: a step that dropped three of the four
        names breaks in a way nobody sees — the tree is read through the wrong
        index, and the command reports about a tree nobody pushed.
        """
        source = HOOK.read_text(encoding='utf-8')
        self.assertIn(
            SHELL_HELPER,
            source,
            'the hook does not call the sanctioned helper — the environment is '
            'dropped somewhere else, and nobody can see where',
        )
        self.assertNotIn(
            'env -u GIT_DIR',
            source,
            'the hook writes the names down itself; the list lives in one place',
        )

    def test_the_helper_reads_the_names_from_the_canonical_answer(self):
        """The helper carries no copy of the list.

        The canon is `sgate.gatekit.gitenv`, and the shell cannot import it —
        so the helper reads the answer from it and the file carries nothing.
        A second copy of the list is a list that can drift, and the drift is
        invisible until a step breaks on a tree that was fine.
        """
        helper = GIT_ENV_HELPER.read_text(encoding='utf-8')
        for name in INHERITED_FROM_HOOK:
            self.assertNotIn(
                name,
                helper,
                f'the helper carries its own copy of {name}; the list lives in '
                f'sgate.gatekit.gitenv',
            )
        self.assertIn(
            'gitenv.py --shell-unset-args',
            helper,
            'the helper does not read the names from the canon',
        )

    def test_the_helper_drops_every_name_the_canonical_answer_names(self):
        """What the helper drops is what the canon names — checked by running it."""
        result = subprocess.run(
            ['bash', '-c', f'. "{GIT_ENV_HELPER}" && echo "$GATEKIT_GIT_UNSET_ARGS"'],
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        args = result.stdout.split()
        dropped = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == '-u']
        self.assertEqual(
            sorted(dropped),
            sorted(INHERITED_FROM_HOOK),
            f'the helper does not drop exactly the names the canon names: {result.stdout.strip()}',
        )


@unittest.skipIf(
    inside_hook,
    'the suite is running inside a hook, and this test runs the suite inside a hook',
)
class InsideHookTest(unittest.TestCase):
    """The suite that runs inside a hook does not run the hook's own test.

    The reason is the recursion: the hook's gate-suite step runs the suite in
    the tree being pushed, and one of the suite's tests pushes through the
    hook — so the suite pushes, the push runs the hook, and the hook runs the
    suite again, one tree deeper. On 2026-10-23 that recursion is what made
    the hook's own step fail: the probe test asserted that the probe had run,
    and the probe ran one tree deeper than the assertion was about.
    """

    def test_the_hook_test_class_is_skipped_inside_a_hook(self):
        result = subprocess.run(
            [sys.executable, '-m', 'unittest', '-v', 'scripts.tests.test_pre_push_hook'],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            env={**os.environ, INSIDE_HOOK_ENV: '1'},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        # The reason, and not the word "skipped": the class that holds this
        # test is skipped too, and its own skip would satisfy a weaker
        # assertion. The reason named here belongs to the class that drives
        # the hook — the one whose absence of a skip is the recursion.
        self.assertIn(
            'the suite is running inside a hook, and this test drives one',
            result.stdout + result.stderr,
            'the suite ran the hook\'s own test inside a hook:\n'
            + result.stdout + result.stderr,
        )


if __name__ == '__main__':
    unittest.main()
