"""Tests for `scripts/agent-account.fish` — the dedicated account agents run as.

The script creates an account, an ACL layout and one sudoers rule, so a test
that "just runs it" needs root. These tests hold the parts that would be wrong
before any of that executes:

* **A worktree inside the developer's home is refused unless it is asked for.**
  That worktree needs `--x` on `$HOME`, which is the one cost the design exists
  to avoid: the home's world-readable dotfiles become readable by the agent. A
  script that quietly accepted the path would put that cost on a reader who
  never saw it, and nothing in its output would say so.

* **The plan is the execution.** `--dry-run` prints the commands off one code
  path, so the plan cannot drift from the run.

* **`run` builds the agent's environment and hands the command through.**
  Preserving the caller's environment would hand the agent every token the
  developer's shell carries; taking the command for an option would eat it.

* **A check that cannot verify a claim reports a NOTE, not a PASS.**

The fixture is a throwaway layout, not a mock: the real script, stub binaries on
`PATH` for the tools it shells out to, and a `sudo` that records its argv. The
script runs under the fish on `PATH`, found the same way its shebang finds it —
`#!/usr/bin/env -S fish --no-config` — so a test that passes here and fails on a
machine without fish is a test that failed, not a test that skipped.
"""

import functools
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'agent-account.fish'

DEV = 'devel'
AGENT_UID = '2001'

#: Stand-in for `getent`: answers the records the script reads and nothing else
#: — a third lookup exits 2, as the real one does. `{home}` is the fixture home,
#: because the script resolves the developer's home from the passwd database and
#: a stub that named `/home/devel` would describe a machine the test is not on.
STUB_GETENT = """#!/usr/bin/env bash
case "$1 ${{2:-}}" in
  'passwd devel') echo 'devel:x:1000:1000:Devel:{home}:/bin/bash' ;;
  'passwd agent') {agent_record} ;;
  'group sing-dev') echo 'sing-dev:x:2001:devel,agent' ;;
  'group wheel') echo 'wheel:x:10:devel' ;;
  'group docker') echo 'docker:x:969:devel' ;;
  'group kvm') echo 'kvm:x:36:devel' ;;
  *) exit 2 ;;
esac
"""

#: Stand-in for `sudo`: records its argv, then runs the command it was given the
#: way `sudo --user` would. The record is what the tests read: the point is the
#: argv, not the transition, and a real transition needs a real account.
STUB_SUDO = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$SUDO_RECORD"
shift 2 || exit 1                       # --non-interactive --user=agent
[ "${1:-}" = "--" ] && shift
exec "$@"
"""

#: Stand-in for the root-only tools: record that they were consulted, exit 0.
STUB_NOOP = """#!/usr/bin/env bash
printf '%s %s\\n' "$(basename "$0")" "$*" >> "$TOOL_RECORD"
exit 0
"""

SYSTEM_PATH = '/usr/bin:/bin'


@functools.cache
def fish_interpreter() -> str:
    """The real fish binary. Absence is a failure, not a skip.

    A skipped test is a silent green: it counts as a testcase and reports
    nothing (scripts/check-test-runs.py calls it exactly that). This script is
    fish, so a machine without fish cannot run it at all — and that has to be
    red, not quiet.

    Resolved through `status fish-path`, the same way the script's own `as_root`
    resolves it, because `which('fish')` answers with a *shim*: mise puts one on
    PATH, and the shim needs mise's environment to work — while the test runs the
    script with a hermetic PATH (stubs + system dirs). Measured: the shim there
    fails with "fish is not a valid shim", and six tests went red for a reason
    that had nothing to do with them.
    """
    shim = shutil.which('fish')
    if shim is None:
        raise RuntimeError(
            'fish is not on PATH, so scripts/agent-account.fish cannot run at all. '
            'Install it: `mise install`, or your distribution package.'
        )
    resolved = subprocess.run(
        [shim, '--no-config', '-c', 'echo (status fish-path)'],
        capture_output=True,
        text=True,
        check=False,
    )
    real = resolved.stdout.strip()
    return real if resolved.returncode == 0 and real else shim


class AgentAccountTest(unittest.TestCase):
    #: Whether the fixture pretends the account already exists. A first run
    #: creates it; every later run finds it, and the plan must say so.
    agent_exists = True

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='agent-account-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / 'home'
        self.home.mkdir()
        self.stubs = self.tmp / 'stubs'
        self.stubs.mkdir()
        self.sudo_record = self.tmp / 'sudo.argv'
        self.tool_record = self.tmp / 'tools.argv'

        agent_record = (
            f'echo agent:x:{AGENT_UID}:{AGENT_UID}::/home/agent:/bin/bash'
            if self.agent_exists
            else 'exit 2'
        )
        self._write_stub('getent', STUB_GETENT.format(home=self.home, agent_record=agent_record))
        self._write_stub('sudo', STUB_SUDO)
        for tool in ('useradd', 'groupadd', 'usermod', 'setfacl', 'getfacl', 'visudo'):
            self._write_stub(tool, STUB_NOOP)

        # The SDK and the toolchain the script grants read access to: a plan is
        # only complete when those paths exist to be granted.
        (self.home / 'Android' / 'Sdk').mkdir(parents=True)
        (self.home / '.local' / 'share' / 'mise').mkdir(parents=True)
        (self.home / '.local' / 'bin').mkdir(parents=True)

    def _write_stub(self, name: str, body: str) -> None:
        stub = self.stubs / name
        stub.write_text(body, encoding='utf-8')
        stub.chmod(0o755)

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.update(
            HOME=str(self.home),
            PATH=f'{self.stubs}:{SYSTEM_PATH}',
            SUDO_USER=DEV,
            SUDO_RECORD=str(self.sudo_record),
            TOOL_RECORD=str(self.tool_record),
            # A token in the caller's environment: `run` must not carry it over.
            AGENT_TEST_SECRET='do-not-leak',
        )
        return subprocess.run(
            [fish_interpreter(), str(SCRIPT), *args],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def _plan(self, *args: str) -> subprocess.CompletedProcess:
        return self._run('apply', '--dry-run', *args)

    def _worktree(self) -> pathlib.Path:
        worktree = self.tmp / 'worktrees' / 'wt'
        worktree.mkdir(parents=True)
        return worktree


class FirstRunTest(AgentAccountTest):
    """The plan for a host that has no account yet."""

    agent_exists = False

    def test_the_plan_creates_the_account_and_no_privileged_group(self):
        """`docker` and `wheel` are root-equivalent; the account holds neither."""
        plan = self._plan(str(self._worktree()))
        self.assertEqual(0, plan.returncode, plan.stdout + plan.stderr)

        self.assertIn('useradd --create-home --user-group --shell /bin/bash agent', plan.stdout)
        for group in ('wheel', 'docker', 'kvm'):
            self.assertNotIn(group, plan.stdout, f'{group} must not appear in the plan')

        # The one sudoers rule: the DEVELOPER re-executes `env` AS the agent. A
        # rule granting `agent` anything is the escalation #3239 forbids.
        sudoers = [line for line in plan.stdout.splitlines() if 'sudoers.d' in line]
        self.assertEqual(1, len(sudoers), plan.stdout)
        self.assertIn('ALL=(agent)', sudoers[0])
        self.assertNotIn('ALL=(root)', sudoers[0])

        # The agent's own state, private to it.
        self.assertIn('--owner=agent --group=agent --mode=0700 /home/agent/.gradle', plan.stdout)


class PlanTest(AgentAccountTest):
    """The plan for a host that already has the account — the idempotent run."""

    def test_a_worktree_inside_the_home_needs_an_explicit_opt_in(self):
        """The traverse grant is the one cost, so silence is not consent."""
        inside = self.home / 'work' / 'wt'
        inside.mkdir(parents=True)

        refused = self._plan(str(inside))
        self.assertEqual(2, refused.returncode, refused.stdout + refused.stderr)
        self.assertIn('--allow-in-home', refused.stderr)
        self.assertIn(str(inside), refused.stderr)

        accepted = self._plan('--allow-in-home', str(inside))
        self.assertEqual(0, accepted.returncode, accepted.stdout + accepted.stderr)
        self.assertIn('warning', accepted.stderr)
        self.assertIn(f'group:sing-dev:rwX {inside}', accepted.stdout)

    def test_the_home_is_granted_traversal_and_never_a_read(self):
        """`--x` opens the path; a read grant on $HOME would open every dotfile."""
        plan = self._plan(str(self._worktree()))
        self.assertEqual(0, plan.returncode, plan.stdout + plan.stderr)

        home_lines = [line for line in plan.stdout.splitlines() if line.endswith(f' {self.home}')]
        self.assertEqual(
            [f'setfacl --modify user:agent:--x {self.home}'],
            home_lines,
            'the home is granted traversal exactly once, and never r or rX',
        )

        # Read access to what the build needs, and ownership of the worktree —
        # including the files git writes next, which is what the default entry is.
        self.assertIn(f'user:agent:rX {self.home}/Android/Sdk', plan.stdout)
        self.assertIn(f'default:user:agent:rX {self.home}/Android/Sdk', plan.stdout)
        worktree = self.tmp / 'worktrees' / 'wt'
        self.assertIn(f'group:sing-dev:rwX {worktree}', plan.stdout)
        self.assertIn(f'default:group:sing-dev:rwX {worktree}', plan.stdout)

    def test_a_dry_run_executes_nothing(self):
        """The plan is printed, not applied — and it names every command."""
        plan = self._plan(str(self._worktree()))
        self.assertEqual(0, plan.returncode, plan.stdout + plan.stderr)
        self.assertFalse(self.tool_record.exists(), 'a dry run consulted a real tool')
        # The account is already there, so this run reconciles instead of
        # creating — and it still names the grants it would write.
        self.assertIn(f'usermod --append --groups sing-dev {DEV}', plan.stdout)
        self.assertIn(f'setfacl --modify user:agent:--x {self.home}', plan.stdout)


class RunTest(AgentAccountTest):
    def test_run_hands_the_command_over_and_drops_the_callers_environment(self):
        """The agent gets a built environment; `env -i` is the mechanism."""
        result = self._run('run', '--', 'echo', 'hello')

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('hello\n', result.stdout)

        argv = self.sudo_record.read_text(encoding='utf-8').splitlines()
        self.assertEqual(1, len(argv), argv)
        self.assertIn('--non-interactive', argv[0])
        self.assertIn('--user=agent', argv[0])
        # `-i` starts from an empty environment; without it every token in the
        # caller's shell rides into the agent.
        self.assertIn('/usr/bin/env -i', argv[0])
        self.assertIn('GRADLE_USER_HOME=/home/agent/.gradle', argv[0])
        self.assertNotIn('AGENT_TEST_SECRET', argv[0])
        self.assertTrue(argv[0].endswith('echo hello'), argv[0])


class ShebangTest(unittest.TestCase):
    """Two properties of the shebang, both measured on a real script, not assumed.

    Neither is style. `#!/usr/bin/env fish` is how the file starts today, and it
    is what the root half cannot rely on: `sudo` runs with its own `secure_path`,
    where a developer-installed fish does not exist. And without `--no-config`
    the reader's fish config runs first, so a function in it shadows the binaries
    the script calls as root — measured: a config defining `function setfacl`
    made the run print "HIJACKED setfacl" where the real tool belonged.
    """

    def test_the_shebang_does_not_inherit_the_readers_fish_config(self):
        """The script runs tools as root; the caller's shell must not win."""
        first = SCRIPT.read_text(encoding='utf-8').splitlines()[0]
        self.assertTrue(first.startswith('#!'), first)
        self.assertIn('--no-config', first, f'a fish config can shadow the tools this script runs: {first}')
        # `env` only accepts flags through `-S`, and the file is exec'd directly.
        self.assertIn('-S', first, f'the multi-word shebang needs env -S: {first}')

    def test_no_verb_depends_on_the_shebang_to_reach_root(self):
        """`as_root` names the interpreter, because sudo's PATH has no fish."""
        body = SCRIPT.read_text(encoding='utf-8')
        function = body.split('function as_root', 1)[1].split('\nend', 1)[0]
        self.assertIn('(status fish-path)', function, function)


class CheckTest(AgentAccountTest):
    def test_check_fails_on_an_unprovisioned_host(self):
        """A check that cannot find what it verifies must not report success."""
        result = self._run('check', str(self._worktree()))

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('FAIL', result.stdout)
        # The absence of a privileged grant is verified even with no account.
        self.assertIn('agent is not in docker', result.stdout)
        # A claim it could not verify is a NOTE, never a PASS.
        self.assertIn('unverified', result.stdout)


if __name__ == '__main__':
    unittest.main()
