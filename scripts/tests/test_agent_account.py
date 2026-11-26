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
        """A real git worktree, because provision resolves its common git dir.

        A plain directory would let the plan pass while the thing that matters —
        the object store a linked worktree writes to — is never resolved. A real
        `git init` costs milliseconds and exercises the same `git rev-parse`.
        """
        worktree = self.tmp / 'worktrees' / 'wt'
        worktree.mkdir(parents=True)
        subprocess.run(
            ['git', 'init', '--quiet', str(worktree)],
            capture_output=True,
            check=True,
        )
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
        subprocess.run(['git', 'init', '--quiet', str(inside)], capture_output=True, check=True)

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


class RegressionTest(PlanTest):
    """The four defects the first review found, each held by a plan-level test.

    Every one of them was a real bug that the eight tests above did not see,
    because they checked a verb's output rather than the resource it forgot.
    """

    def test_the_common_git_dir_is_granted_not_only_the_worktree(self):
        """A linked worktree keeps its objects in ONE shared store."""
        worktree = self._worktree()
        plan = self._plan(str(worktree))
        self.assertEqual(0, plan.returncode, plan.stdout + plan.stderr)

        common = subprocess.run(
            ['git', '-C', str(worktree), 'rev-parse', '--git-common-dir'],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        resolved = os.path.realpath(os.path.join(str(worktree), common))
        self.assertIn(
            f'group:sing-dev:rwX --modify default:group:sing-dev:rwX {resolved}',
            plan.stdout,
            'provision must grant the same store `check` verifies, or the agent '
            'can read its sources and cannot commit',
        )

    def test_rollback_removes_only_its_own_acl_entries(self):
        """`--remove-default` takes every default entry, including a foreign one."""
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertNotIn(
            '--recursive --remove-default ',
            rollback,
            'a bare --remove-default wipes default entries this script never wrote',
        )
        # The form that names one subject and leaves the others alone.
        self.assertIn('-x "d:u:$AGENT_USER"', rollback)
        self.assertIn('-x "d:g:$AGENT_GROUP"', rollback)

    def test_an_existing_account_is_reconciled_out_of_privileged_groups(self):
        """`check` reporting it is not `apply` fixing it."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1].split('# The group is the collaboration')[0]
        self.assertIn('for group in wheel docker kvm', provision)
        self.assertIn('remove_group $group', provision)
        # The helper distinguishes the two ways a group can be held: the
        # supplementary list (`gpasswd`) and the primary gid (`usermod --gid`),
        # because `getent group` cannot see the second one at all.
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function remove_group', 1)[1].split('\nend', 1)[0]
        self.assertIn('gpasswd --delete', helper)
        self.assertIn('usermod --gid', helper)

    def test_a_root_that_is_not_a_worktree_is_refused_before_anything_is_written(self):
        """A half-applied host is worse than a refused run.

        `provision` used to resolve `git rev-parse` only in the worktree loop —
        after useradd and the sudoers rule had run. A plain directory left an
        account created and the host half-provisioned. Measured.
        """
        not_a_repo = self.tmp / 'plain'
        not_a_repo.mkdir()

        plan = self._plan(str(not_a_repo))
        self.assertEqual(2, plan.returncode, plan.stdout + plan.stderr)
        for write in ('useradd', 'groupadd', 'sudoers.d', 'setfacl'):
            self.assertNotIn(
                write, plan.stdout,
                f'{write} is printed before the root was refused — the run would be half-applied',
            )

    def test_rollback_tolerates_a_worktree_that_was_removed(self):
        """The grants outlive the worktree; cleanup must still work."""
        result = self._run('rollback', '--dry-run', str(self.tmp / 'gone'))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_the_agent_path_reaches_the_toolchain_it_documents(self):
        """java, node and heavy are mise SHIMS, not ~/.local/bin."""
        result = self._run('run', '--', 'echo', 'ok')
        argv = self.sudo_record.read_text(encoding='utf-8')
        self.assertIn('.local/share/mise/shims', argv, 'the build toolchain is unresolved without this')


class RoundTwoRegressionTest(PlanTest):
    """The five defects of the second review round, each held by a test."""

    def test_a_bare_repository_is_not_a_worktree(self):
        """`--git-common-dir` answers on a bare repo (it prints ".")."""
        bare = self.tmp / 'bare.git'
        subprocess.run(['git', 'init', '--quiet', '--bare', str(bare)], check=True)

        plan = self._plan(str(bare))
        self.assertEqual(2, plan.returncode, plan.stdout + plan.stderr)
        self.assertIn('not a git worktree', plan.stderr)
        self.assertNotIn('setfacl', plan.stdout, 'nothing may be granted to a repo with no worktree')

    def test_primary_group_membership_is_visible(self):
        """`getent group` lists supplementary members only.

        An account whose PRIMARY gid is `docker` does not appear there, and a
        primary group is as root-equivalent as a supplementary one. The helper
        therefore asks `id -Gn`, and the removal path distinguishes `gpasswd`
        (supplementary list) from `usermod --gid` (the primary one).
        """
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function in_group', 1)[1].split('\nend', 1)[0]
        self.assertIn('id -Gn', helper, 'getent group cannot see a primary gid')
        self.assertNotIn('getent group', helper, 'the group file is the wrong source for this question')

        # The property itself, measured on a real identity rather than assumed:
        # the fixture's developer is in at least one group `getent group` would
        # report differently from `id -Gn`.
        groups = subprocess.run(['id', '-Gn'], capture_output=True, text=True, check=True).stdout.split()
        self.assertTrue(groups, 'id -Gn must answer for the account running the test')

    def test_an_existing_account_gets_its_password_locked(self):
        """A new account has no password by construction; an existing one may."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1].split('# The group is the collaboration')[0]
        self.assertIn('passwd --status', provision)
        self.assertIn('passwd --lock', provision)

    def test_a_command_scoped_sudo_rule_is_not_reported_as_clean(self):
        """`(emulator) /usr/bin/foo` matches none of NOPASSWD, (ALL or a bare ALL."""
        sudo = self.stubs / 'sudo'
        sudo.write_text(
            '#!/usr/bin/env bash\n'
            "printf '%s\\n' 'User agent may run the following commands on host:' '    (emulator) /usr/bin/foo'\n"
            'exit 0\n',
            encoding='utf-8',
        )
        result = self._run('check', str(self._worktree()))
        self.assertIn('holds a sudo rule', result.stdout)
        self.assertIn('(emulator) /usr/bin/foo', result.stdout)

    def test_provisioning_and_running_agree_on_the_agent_home(self):
        """One resolution, or the state is created where `run` does not look."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1].split('echo "=== agent account')[0]
        self.assertIn('(agent_home)', provision)
        self.assertNotIn('set -l agent_home_dir /home/', provision, 'a hardcoded home drifts from agent_home')


class RoundThreeRegressionTest(PlanTest):
    """The three defects of the third review round."""

    def test_check_refuses_a_bare_repository_too(self):
        """`apply` refused it; `check` certified it. Both ask the same predicate."""
        bare = self.tmp / 'bare-check.git'
        subprocess.run(['git', 'init', '--quiet', '--bare', str(bare)], check=True)

        result = self._run('check', str(bare))
        self.assertIn('is not a git worktree', result.stdout)

    def test_the_sudoers_file_is_this_scripts_own(self):
        """Another administrator's `/etc/sudoers.d` file must not be replaced."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('/etc/sudoers.d/50-agent-account', provision)
        self.assertNotIn('/etc/sudoers.d/50-agent-run', provision)
        # And it refuses a file it does not recognise instead of overwriting it.
        self.assertIn('did not write', provision, 'a file with extra grants must be refused')

    def test_a_bare_repo_is_rejected_before_any_write(self):
        """The predicate is separate from the resolution, so `check` cannot die mid-verdict."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function is_worktree', 1)[1].split('\nend', 1)[0]
        self.assertIn('--is-inside-work-tree', helper)
        # `git_common_dir` exits on a path that is not a worktree, and an exit
        # inside a command substitution ends the WHOLE script. `check` therefore
        # tests the predicate before it resolves anything: a verdict may not stop
        # half-way. (`rollback` may — there, stopping is the referendum on a host
        # whose git state cannot be cleaned.)
        check = body.split('function check', 1)[1]
        self.assertIn('if not is_worktree $root', check)
        self.assertNotIn('(git_common_dir $root 2>/dev/null)', check.split('# The transition itself')[0])


class RoundFourRegressionTest(PlanTest):
    """The five defects of the fourth review round, one of them blocking."""

    def test_an_account_with_uid_zero_is_refused(self):
        """`agent` with uid 0 is root; nothing asked, and `run` then ran as root."""
        getent = self.stubs / 'getent'
        getent.write_text(
            '#!/usr/bin/env bash\n'
            "case \"$1 ${2:-}\" in\n"
            "  'passwd devel') echo 'devel:x:1000:1000:Devel:" + str(self.home) + ":/bin/bash' ;;\n"
            "  'passwd agent') echo 'agent:x:0:0::/home/agent:/bin/bash' ;;\n"
            "  'group sing-dev') echo 'sing-dev:x:2001:devel,agent' ;;\n"
            "  *) exit 2 ;;\n"
            'esac\n',
            encoding='utf-8',
        )
        plan = self._plan(str(self._worktree()))
        self.assertEqual(2, plan.returncode, plan.stdout + plan.stderr)
        self.assertIn('uid 0', plan.stderr)
        self.assertNotIn('useradd', plan.stdout)

    def test_the_home_itself_needs_the_explicit_opt_in(self):
        """The glob `$HOME/*` did not match `$HOME`."""
        plan = self._plan(str(self.home))
        self.assertEqual(2, plan.returncode, plan.stdout + plan.stderr)
        self.assertIn('--allow-in-home', plan.stderr)

    def test_a_sudoers_file_with_extra_grants_is_refused(self):
        """One matching line is not proof the whole file is ours."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('did not write', provision)
        self.assertIn('!= "$rule"', provision)

    def test_a_foreign_member_of_the_group_is_reported(self):
        """`sing-dev` is documented as two identities; it only ever appended."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('foreign_members $dev $AGENT_USER', provision)
        self.assertIn('already holds', provision)

    def test_a_failed_step_stops_the_run(self):
        """A failing `usermod` used to be followed by `=== done ===`."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function run_cmd', 1)[1].split('\nend', 1)[0]
        self.assertIn('die', helper, 'run_cmd must not swallow a failed step')
        self.assertIn('$status', helper)


class RoundFiveRegressionTest(PlanTest):
    """The three defects of the fifth review round."""

    def test_a_primary_gid_member_of_the_group_is_foreign_too(self):
        """`getent group` cannot see a user whose PRIMARY gid is `sing-dev`."""
        body = SCRIPT.read_text(encoding='utf-8')
        scan = body.split('function foreign_members', 1)[1].split('\nend', 1)[0]
        self.assertIn('getent passwd', scan, 'the primary gid lives in passwd, not in the group file')
        self.assertIn('$fields[4]', scan)
        # ONE predicate: `apply` refusing a host is worth less than `check`
        # reporting it, and a member added after provisioning used to be
        # invisible to the acceptance verdict.
        check = body.split('function check\n', 1)[1]
        self.assertIn('foreign_members $dev $AGENT_USER', check)

    def test_grants_are_recorded_so_rollback_survives_a_removed_worktree(self):
        """A linked worktree's store lives outside it; the path cannot be re-derived."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_acl $target tree', provision)
        rollback = body.split('function rollback', 1)[1]
        # Every recorded target is walked, not only the roots passed in: undoing
        # wt1 left wt2 writable, and the group whose gid those entries named was
        # deleted right afterwards.
        self.assertIn('set recorded (recorded_targets)', rollback)
        self.assertIn('for target in $recorded', rollback)

    def test_check_reads_the_effective_permission_not_the_raw_entry(self):
        """A named entry under a restrictive mask is read-only, not writable."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function effective_bits', 1)[1].split('\nend', 1)[0]
        self.assertIn('#effective:', helper)
        # The comparison is bit-wise, in BOTH directions. Grepping for the word
        # `r` called a mask of `-wx` (read denied) healthy, because the check
        # expected `rX` — and a mask of `r--` on a `rwx` entry also passed,
        # because it went through a different branch.
        coverage = body.split('function bits_cover', 1)[1].split('\nend', 1)[0]
        for bit in ('r', 'w', 'x'):
            self.assertIn(f"case {bit}", coverage)
        self.assertIn('case X', coverage)


class RoundSixRegressionTest(PlanTest):
    """The sixth round: rollback must not delete what `apply` only adopted."""

    def test_ownership_is_recorded_before_anything_is_created(self):
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        # Both branches record, so a pre-existing identity is known to be adopted.
        self.assertIn('record_ownership user created', provision)
        self.assertIn('record_ownership user adopted', provision)
        self.assertIn('record_ownership group created', provision)
        self.assertIn('record_ownership group adopted', provision)

    def test_rollback_deletes_only_what_it_owned(self):
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('owned_by_script user', rollback)
        self.assertIn('owned_by_script group', rollback)
        self.assertIn('adopted it rather than created it', rollback)

    def test_rollback_rechecks_the_sudoers_content(self):
        """Provisioning refuses a foreign file; rollback must refuse it too."""
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('NOPASSWD: /usr/bin/env', rollback, 'rollback must know what it wrote')
        self.assertIn('kept $sudoers', rollback)


class RoundSevenRegressionTest(PlanTest):
    """The seventh round: rollback undid more than apply wrote, and less."""

    def test_a_pre_existing_acl_is_restored_not_deleted(self):
        """`--modify` overwrites an entry that existed; `-x` deleted it."""
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('restore_acl', rollback)
        # Deletion is reserved for the case where no snapshot was taken.
        helper = body.split('function restore_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('setfacl --restore=$file', helper, 'the dump is the inverse, not a hand-written record')
        self.assertIn('test -e $file; or return 1', helper, 'no snapshot means the old path')

    def test_the_group_owner_is_checked_independently_of_the_account(self):
        """A host may carry `sing-dev` and no `agent`."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        branch = provision.split('if test -z "$account"', 1)[1].split('else', 1)[0]
        self.assertIn('getent group $AGENT_GROUP', branch)
        self.assertIn('record_ownership group adopted', branch)


class RoundEightRegressionTest(PlanTest):
    """The eighth round: the record could not express what `--recursive` writes.

    Every finding here is one the previous record format *could not* hold, so
    each test checks the property on a real tree rather than the code that
    reads it — a hand-written record format passes its own tests.
    """

    def test_the_snapshot_is_taken_before_the_write_and_covers_the_tree(self):
        """A cap of 200 directories is not a snapshot of a tree granted at every file."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('--recursive', record, 'the write is recursive, so the snapshot must be')
        code = '\n'.join(line for line in body.splitlines() if not line.lstrip().startswith('#'))
        self.assertNotIn('head -200', code, 'a capped walk is not a snapshot')
        # TWO PASSES: every snapshot precedes every write. A nested repository
        # lies inside the outer tree's dump, so an interleaved loop snapshots it
        # after the outer grant already wrote there — recording `sing-dev:rwX`
        # as the "previous" state that rollback then restores.
        provision = body.split('function provision', 1)[1]
        first_write = min(
            provision.index(marker) for marker in (
                'run_cmd setfacl --modify "user:$AGENT_USER:--x"',
                'run_cmd setfacl --recursive --modify "user:$AGENT_USER:rX"',
                'run_cmd setfacl --recursive --modify "group:$AGENT_GROUP:rwX"',
            )
        )
        last_snapshot = max(
            provision.index(marker) for marker in ('record_acl $writable node', 'record_acl $target tree')
        )
        self.assertLess(
            last_snapshot, first_write,
            'every target must be snapshotted before the first write, or a nested one records our own grant',
        )

    def test_the_home_and_the_toolchain_are_snapshotted_too(self):
        """The home is granted non-recursively and the toolchain recursively."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_acl $writable node', provision, 'the traverse grant needs a snapshot')
        self.assertIn('record_acl $target tree', provision, 'the SDK, mise and the worktrees are recursive')
        # The toolchain reaches `granted` through the same loop as the worktrees.
        self.assertIn('set -a granted $dev_home_dir/$rel', provision)
        self.assertIn('set -a granted $target', provision)

    def test_a_snapshot_is_the_tools_own_dump(self):
        """The inverse must be `setfacl --restore`, or every delimiter is a guess."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('getfacl $flags $target > $file', record)
        self.assertIn('--physical', record, 'a dump that follows symlinks is not a dump of this tree')
        # The old format could not round-trip a path containing `;` or `=`.
        self.assertNotIn("string split ';'", body)
        self.assertNotIn("string split '='", body)

    def test_a_path_maps_to_a_file_name_injectively(self):
        """`;` and `=` are legal in paths, so the dump's name cannot be the path."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function acl_dump_file', 1)[1].split('\nend', 1)[0]
        self.assertIn('--style=var', helper)
        # Escaping is only useful if it round-trips: the third test below runs it.
        escaped = subprocess.run(
            [fish_interpreter(), '--no-config', '-c',
             'echo (string unescape --style=var -- (string escape --style=var -- "/tmp/a;b=c d"))'],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        self.assertEqual('/tmp/a;b=c d', escaped)

    def test_a_second_apply_does_not_overwrite_the_first_snapshot(self):
        """Otherwise rollback faithfully restores what apply itself wrote."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('if not test -e $file', record)

    def test_a_group_membership_apply_did_not_add_is_not_removed(self):
        """`usermod --append` is not reversible by deleting the membership."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_membership $dev $AGENT_GROUP', provision)
        self.assertIn('record_membership $AGENT_USER $AGENT_GROUP', provision)
        # Recorded before the append that makes it true for everyone.
        self.assertLess(
            provision.index('record_membership $dev $AGENT_GROUP'),
            provision.index('run_cmd usermod --append --groups $AGENT_GROUP $dev'),
        )
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('not was_member $AGENT_USER $AGENT_GROUP', rollback)
        self.assertIn('not was_member $DEV_NAME $AGENT_GROUP', rollback)

    def test_a_privileged_group_taken_away_comes_back(self):
        """`apply` has to remove `docker`; rollback must not also erase the fact."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_removed_group $group', provision)
        self.assertLess(
            provision.index('record_removed_group $group'),
            provision.index('remove_group $group'),
        )
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('previously_removed_groups', rollback)
        self.assertIn('usermod --append --groups $group $AGENT_USER', rollback)

    def test_the_records_are_tab_separated_with_real_tabs(self):
        """`$subject\\t$group` in a word is a literal backslash-t, not a separator."""
        state = self.tmp / 'state'
        state.write_text('', encoding='utf-8')
        # `string join \\t` in the writer, `printf '...\\t...'` in the reader: the
        # two must agree on a real tab, or the record is unreadable by whoever
        # wrote it — which is exactly what happened.
        script = (
            f'printf "%s\\n" (string join \\t -- member devel sing-dev) >> {state}'
            '; '
            f'grep -qF -- (printf "member\\tdevel\\tsing-dev") {state}; or echo MISS'
        )
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual('', result.stdout.strip(), 'the record must be greppable by its own writer')


class AclRoundTripTest(AgentAccountTest):
    """The snapshot really restores a host that had ACLs BEFORE `apply` ran.

    The eighth review asked for exactly this: rollback tested against a host
    carrying pre-existing ACLs, not against a code-reading assertion. These
    tests run the script's own `record_acl` / `restore_acl` — extracted, with
    the real `setfacl` and `getfacl`, on a throwaway tree — and compare the
    `getfacl` dump byte for byte.

    The tree carries every thing the record has to hold: a named entry that
    `--modify` will OVERWRITE, a `mask::` that limits it, a `default:` block
    that `-x` takes wholesale, and a path containing `;` and `=` — the two
    characters the old line format used as delimiters.
    """

    #: The functions under test, lifted verbatim from the script so the fixture
    #: exercises the shipped code and not a copy of it.
    SUBJECT = 'nobody'

    def _functions(self) -> str:
        body = SCRIPT.read_text(encoding='utf-8')
        parts = []
        for name in ('acl_dump_file', 'record_acl', 'restore_acl'):
            # The signature line carries `--argument-names`, so the marker is the
            # newline before it and the name is kept: a function without its
            # name is a syntax error, not a smaller copy of the code.
            tail = body.split(f'\nfunction {name}', 1)[1]
            signature, _, _ = tail.partition('\nend')
            parts.append(f'function {name}{signature}\nend')
        return '\n'.join(parts)

    #: What the lifted functions call, defined here so the fixture needs no root
    #: and writes nothing outside its temp directory. `run_cmd` keeps the
    #: script's shape — fail loudly instead of continuing — but the record lives
    #: in the fixture: `install --owner=root` is `Operation not permitted`
    #: unprivileged (measured), and the real script is root-only by design.
    PRELUDE = """
set -g DRY_RUN 0
function die
    echo "fixture: $argv" >&2
    exit 2
end
function run_cmd
    if test $DRY_RUN -eq 1
        string escape -- $argv | string join ' '
        return 0
    end
    switch $argv[1]
        case install
            mkdir --parents --mode=0700 (acl_dir)
        case chmod
            command chmod $argv[2..-1]
        case '*'
            $argv
    end
    or die "step failed: $argv"
end
function acl_dir
    echo $ACL_DIR
end
function targets_file
    echo $TARGETS
end
"""

    def _tree(self) -> pathlib.Path:
        """A worktree-shaped tree with grants that are NOT this script's."""
        root = self.tmp / 'tree;with=chars'
        (root / 'src' / 'nested').mkdir(parents=True)
        (root / 'src' / 'file.txt').write_text('x', encoding='utf-8')
        # A named entry `apply` will overwrite, plus the mask that limits it.
        self._setfacl(['-m', f'u:{self.SUBJECT}:r-x', str(root / 'src')])
        # A default block `-x` would take wholesale, and a foreign group entry.
        self._setfacl(['-m', f'd:u:{self.SUBJECT}:r-x', str(root / 'src')])
        self._setfacl(['-m', 'd:g:nobody:r-x', str(root / 'src')])
        self._setfacl(['-m', 'u:nobody:rwx', str(root)])
        return root

    def _setfacl(self, args: list[str]) -> None:
        result = subprocess.run(['setfacl', *args], capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, f'setfacl {args}: {result.stdout}{result.stderr}')

    def _dump(self, root: pathlib.Path) -> str:
        return subprocess.run(
            ['getfacl', '--recursive', '--physical', '--absolute-names', str(root)],
            capture_output=True, text=True, check=True,
        ).stdout

    def _fish(self, script: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
            env={**os.environ, 'ACL_DIR': str(self.tmp / 'acl'), 'TARGETS': str(self.tmp / 'targets')},
        )

    def _harness(self, root: pathlib.Path, name: str, kind: str = '') -> subprocess.CompletedProcess:
        """Run the script's own function with the record beside the fixture.

        `--argument-names target kind`, so the path is the FIRST argument — the
        fixture passes what the shipped call sites pass, in their order.
        """
        call = f'{name} "{root}"' + (f' {kind}' if kind else '')
        return self._fish(self.PRELUDE + self._functions() + f'\n{call}\n')

    def test_a_host_with_preexisting_acls_is_restored_exactly(self):
        """`apply` overwrites a named entry and adds a default block; rollback undoes it."""
        root = self._tree()
        before = self._dump(root)

        # What `apply` does: record first, then the recursive write.
        recorded = self._harness(root, 'record_acl', 'tree')
        self.assertEqual(0, recorded.returncode, recorded.stdout + recorded.stderr)
        self._setfacl(['--recursive', '-m', f'u:{self.SUBJECT}:rwX', '-m', f'd:u:{self.SUBJECT}:rwX', str(root)])
        self.assertNotIn(before, self._dump(root), 'the fixture must actually change the tree')

        restored = self._harness(root, 'restore_acl')
        self.assertEqual(0, restored.returncode, restored.stdout + restored.stderr)
        self.assertEqual(
            before, self._dump(root),
            'rollback must put back the named entry, the mask and the default block',
        )

    def test_the_restored_tree_keeps_the_foreign_grant_it_had(self):
        """A grant `apply` did not write must survive a full round trip."""
        root = self._tree()
        self._harness(root, 'record_acl', 'tree')
        self._setfacl(['--recursive', '-m', f'u:{self.SUBJECT}:rwX', str(root)])
        self._harness(root, 'restore_acl')

        after = subprocess.run(
            ['getfacl', '--omit-header', str(root)],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertIn('user:nobody:rwx', after)

    def test_the_snapshot_file_name_survives_a_path_with_delimiters(self):
        """`;` and `=` are legal in paths, so the dump name cannot be the path."""
        root = self._tree()
        self.assertIn(';', str(root))
        recorded = self._harness(root, 'record_acl', 'tree')
        self.assertEqual(0, recorded.returncode, recorded.stdout + recorded.stderr)

        dumps = list((self.tmp / 'acl').iterdir())
        self.assertEqual(1, len(dumps), dumps)
        self.assertNotIn(';', dumps[0].name, 'the file name must not carry the raw delimiters')


class RoundNineRegressionTest(PlanTest):
    """The ninth round: rollback fidelity and the two ways to escape the boundary.

    Role A found no BLOCKING defect and six real ones. Each test here fails when
    its fix is reverted — checked by reverting, not assumed.
    """

    def test_an_adopted_home_inside_the_developers_home_is_refused(self):
        """A stale passwd record made `apply` chmod and populate the developer's home."""
        getent = self.stubs / 'getent'
        getent.write_text(
            '#!/usr/bin/env bash\n'
            "case \"$1 ${2:-}\" in\n"
            f"  'passwd devel') echo 'devel:x:1000:1000:Devel:{self.home}:/bin/bash' ;;\n"
            f"  'passwd agent') echo 'agent:x:{AGENT_UID}:{AGENT_UID}::'\"{self.home}\"'/inside:/bin/bash' ;;\n"
            "  'group sing-dev') echo 'sing-dev:x:2001:devel,agent' ;;\n"
            "  'group wheel') echo 'wheel:x:10:devel' ;;\n"
            "  'group docker') echo 'docker:x:969:devel' ;;\n"
            "  'group kvm') echo 'kvm:x:36:devel' ;;\n"
            '  *) exit 2 ;;\n'
            'esac\n',
            encoding='utf-8',
        )
        plan = self._plan(str(self._worktree()))
        self.assertEqual(2, plan.returncode, plan.stdout + plan.stderr)
        self.assertIn('inside the developer', plan.stderr)
        self.assertNotIn('setfacl', plan.stdout, 'nothing may be granted before the home is refused')

    def test_the_root_as_an_agent_home_is_refused(self):
        """`chmod 700 /` on an adopted account whose home is the root."""
        body = SCRIPT.read_text(encoding='utf-8')
        guard = body.split('function home_problem', 1)[1].split('\nend', 1)[0]
        self.assertIn('"$resolved" = /', guard)
        # Quoted: an unquoted `/*` is expanded before `string match` sees it.
        self.assertIn("string match -q -- '/*'", guard)
        # ONE predicate, or `apply` rejects a host `check` certifies.
        self.assertIn('home_problem $agent_home_dir', body)
        check = body.split('function check\n', 1)[1]
        self.assertIn('home_problem $agent_home_dir', check)

    def test_an_adopted_accounts_sudo_rule_stops_the_run(self):
        """Group reconciliation does not touch a sudoers rule, and that is the escalation."""
        provision = SCRIPT.read_text(encoding='utf-8').split('function provision', 1)[1]
        self.assertIn('--list --other-user=$AGENT_USER', provision)
        self.assertIn('holds a sudo rule', provision)
        # Reported, never silently "fixed": removing somebody else's rule is not this script's call.
        self.assertNotIn('visudo --remove', provision)

    def test_a_created_group_is_rolled_back_even_without_the_account(self):
        """A failed `apply` created the group, then could not create the account."""
        rollback = SCRIPT.read_text(encoding='utf-8').split('function rollback', 1)[1]
        guard = rollback.split('set -l home', 1)[0]
        self.assertIn('has_record', guard, 'the record, not the passwd database, decides what is undone')
        self.assertIn('owned_by_script group', guard)

    def test_every_target_is_snapshotted_before_any_write(self):
        """A nested repository lies inside the outer tree's dump."""
        provision = SCRIPT.read_text(encoding='utf-8').split('function provision', 1)[1]
        self.assertIn('# Pass 1: record', provision)
        self.assertIn('# Pass 2: write', provision)
        self.assertLess(provision.index('# Pass 1: record'), provision.index('# Pass 2: write'))


    def test_the_password_lock_is_undone_for_an_adopted_account(self):
        """`apply` must lock it; rollback must not keep an adopted account locked."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_ownership passwd $password_state', provision, 'the state before the lock')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('recorded_value passwd', rollback)
        self.assertIn('passwd --unlock', rollback)
        # Only where it was usable before: `L` and `NP` stay as they are.
        self.assertIn('test "$was_password" = P', rollback)


class EffectivePermissionTest(AgentAccountTest):
    """`check_acl` judged the mask by grepping a letter, in one direction only.

    These run the script's own `entry_bits` / `effective_bits` / `bits_cover`
    against real `getfacl` output on a throwaway directory — the two mask
    directions are not symmetric and the old check called one of them healthy.
    """

    def _functions(self) -> str:
        body = SCRIPT.read_text(encoding='utf-8')
        parts = []
        for name in ('entry_bits', 'entry_subject', 'effective_bits', 'bits_cover'):
            tail = body.split(f'\nfunction {name}', 1)[1]
            signature, _, _ = tail.partition('\nend')
            parts.append(f'function {name}{signature}\nend')
        return '\n'.join(parts)

    def _verdict(self, path: pathlib.Path, entry: str) -> str:
        """`covers` when the grant survives the mask, `limited` when it does not."""
        # The dump is joined with a REAL newline, not `string join \\n`: in a
        # fish word `\\n` is the two characters backslash-n, so the lines never
        # split and every entry looked absent (measured).
        script = self._functions() + (
            f'\nset -l acl_dump (getfacl --absolute-names "{path}" | string join "|")\n'
            f'set -l effective (effective_bits $acl_dump "{entry}")\n'
            f'set -l needed (entry_bits "{entry}")\n'
            f'echo "$effective|$needed"\n'
            f'bits_cover "$effective" "$needed"; and echo covers; or echo limited\n'
        )
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result.stdout.strip().splitlines()[-1]

    def test_a_mask_that_denies_read_is_reported(self):
        """`mask::-wx` limits `rX` to no read — and `#effective:-wx` has no `r`."""
        tree = self.tmp / 'deny-read'
        tree.mkdir()
        subprocess.run(['setfacl', '-m', 'u:nobody:rX', '-m', 'm::-wx', str(tree)], check=True)
        self.assertEqual('limited', self._verdict(tree, 'user:nobody:rX'))

    def test_a_sufficient_mask_is_accepted(self):
        """The same grant under a mask that keeps read is fine."""
        tree = self.tmp / 'allow-read'
        tree.mkdir()
        subprocess.run(['setfacl', '-m', 'u:nobody:rX', '-m', 'm::r-x', str(tree)], check=True)
        self.assertEqual('covers', self._verdict(tree, 'user:nobody:rX'))

    def test_an_unlimited_entry_is_accepted(self):
        """No `#effective:` marker at all means the mask does not constrain it."""
        tree = self.tmp / 'unlimited'
        tree.mkdir()
        subprocess.run(['setfacl', '-m', 'u:nobody:rwx', str(tree)], check=True)
        self.assertEqual('covers', self._verdict(tree, 'user:nobody:rwx'))

    def test_a_mask_that_denies_write_is_reported(self):
        """The other direction: `rwx` under `mask::r--` is read-only."""
        tree = self.tmp / 'deny-write'
        tree.mkdir()
        subprocess.run(['setfacl', '-m', 'u:nobody:rwx', '-m', 'm::r--', str(tree)], check=True)
        self.assertEqual('limited', self._verdict(tree, 'user:nobody:rwx'))

    def test_check_acl_asks_the_bit_wise_question(self):
        """The helpers are right; `check` must actually use them for both entries."""
        body = SCRIPT.read_text(encoding='utf-8')
        check = body.split('\nfunction check_acl', 1)[1].split('\nend', 1)[0]
        self.assertEqual(
            2, check.count('bits_cover "$effective" "$needed"'),
            'both the named entry and the default entry are judged by bits, not by a grep',
        )
        self.assertNotIn('#effective:r', check, 'a read-only grep cannot answer this')
        # And the entry has to be found by SUBJECT: `getfacl` rewrites `rX` to
        # `r-x`/`r--`, so matching the whole entry finds nothing at all.
        self.assertIn('(entry_subject $entry)', check)
        self.assertIn('(entry_subject $default_entry)', check)


    def test_the_registry_round_trips_a_path_with_a_newline(self):
        """A filename may contain a newline; a line-per-target registry must not care."""
        registry = self.tmp / 'targets'
        # The newline is passed through the ENVIRONMENT: every shell rewrites a
        # literal `\\n` into two characters, so a test that built the path in the
        # script would test nothing at all (measured: `string length` answered 1).
        nasty = f'{self.tmp}/a\nb'
        script = (
            'set -l t $NASTY\n'
            f'printf "%s\\n" (string escape --style=var -- $t) >> {registry}\n'
            f'echo "lines="(count (cat {registry}))\n'
        )
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
            env={**os.environ, 'NASTY': nasty},
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('lines=1', result.stdout, 'one path must be ONE entry')

        # And the bytes in the file are one line: a raw write would have put the
        # newline in and made the registry answer two entries for one path.
        raw = registry.read_text(encoding='utf-8')
        self.assertEqual(1, len(raw.splitlines()), repr(raw))
        self.assertIn('_0A_', raw, 'the newline must be escaped, not written through')

    def test_the_registry_writer_escapes_and_its_reader_unescapes(self):
        """The primitive is useless unless the shipped code uses it, twice."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('string escape --style=var -- $target', record, 'the writer must escape')
        self.assertNotIn("printf '%s\\n' $target", record, 'a raw write splits on a newline')
        reader = body.split('function recorded_targets', 1)[1].split('\nend', 1)[0]
        self.assertIn('string unescape --style=var', reader, 'the reader must undo it')
        # And rollback reads the registry through that reader, not through `cat`.
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('(recorded_targets)', rollback)
        self.assertNotIn('(cat (targets_file))', rollback)


    def test_the_rollback_record_outlives_every_destructive_step(self):
        """`userdel` refuses a busy account; the snapshots must still be there after that."""
        rollback = SCRIPT.read_text(encoding='utf-8').split('function rollback', 1)[1]
        deletions = [
            rollback.index('run_cmd rm --force (targets_file)'),
            rollback.index('run_cmd rm --force --recursive (acl_dir)'),
            rollback.index('run_cmd rm --force (state_file)'),
        ]
        for marker in (
            'run_cmd userdel --remove $AGENT_USER',
            'run_cmd groupdel $AGENT_GROUP',
        ):
            self.assertLess(
                rollback.index(marker), min(deletions),
                f'{marker} must run while the snapshots still exist — a failure there '
                f'would leave the host half-rolled-back with nothing to restore from',
            )


class RoundElevenRegressionTest(PlanTest):
    """The eleventh round: one BLOCKING cross of the boundary, four rollback gaps."""

    def test_run_refuses_an_account_that_is_root_or_the_developer(self):
        """`run` is independently callable and never asked what uid it would cross to."""
        body = SCRIPT.read_text(encoding='utf-8')
        branches = body.split('switch $verb', 1)[1].split('case rollback', 1)[0]
        run_branch = branches.split('case run', 1)[1]
        self.assertIn(
            'require_unprivileged_uid', run_branch,
            'the invariant belongs on the verb that EXECUTES, not only on the one that provisions',
        )

    def test_an_account_that_is_root_cannot_run_a_command(self):
        """The failure the finding describes, measured end to end."""
        getent = self.stubs / 'getent'
        getent.write_text(
            '#!/usr/bin/env bash\n'
            "case \"$1 ${2:-}\" in\n"
            f"  'passwd devel') echo 'devel:x:1000:1000:Devel:{self.home}:/bin/bash' ;;\n"
            "  'passwd agent') echo 'agent:x:0:0::/home/agent:/bin/bash' ;;\n"
            "  'group sing-dev') echo 'sing-dev:x:2001:devel,agent' ;;\n"
            '  *) exit 2 ;;\n'
            'esac\n',
            encoding='utf-8',
        )
        result = self._run('run', '--', 'id')
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn('uid 0', result.stderr)
        self.assertFalse(self.sudo_record.exists(), 'the command must not be handed to sudo at all')

    def test_rollback_never_touches_a_worktree_it_did_not_provision(self):
        """`rollback <some other worktree>` must not remove an ACL this script never wrote."""
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('this script never provisioned it', rollback)
        # The targets come from the RECORD alone; a caller-supplied root is not a licence.
        self.assertIn('for target in $recorded', rollback)
        self.assertNotIn('$recorded $targets', rollback)
        self.assertNotIn('set -a targets', rollback)

    def test_a_precreated_sudoers_file_is_adopted_not_owned(self):
        """Content this script would have written is not proof it wrote it."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        self.assertIn('record_ownership sudoers adopted', provision)
        self.assertIn('record_ownership sudoers created', provision)
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('not owned_by_script sudoers', rollback)
        self.assertIn('adopted it rather than created it', rollback)

    def test_check_reports_a_foreign_member_of_the_group(self):
        """The scan lived only in `apply`, so a member added later was invisible."""
        body = SCRIPT.read_text(encoding='utf-8')
        check = body.split('function check\n', 1)[1]
        self.assertIn('foreign_members $dev $AGENT_USER', check)
        self.assertIn('also holds', check)
        # And `apply` asks the SAME predicate, not a copy of it.
        provision = body.split('function provision', 1)[1]
        self.assertIn('set -l foreign (foreign_members $dev $AGENT_USER)', provision)

    def test_paths_created_after_the_snapshot_lose_the_grant(self):
        """A dump cannot name a path that did not exist when it was taken."""
        body = SCRIPT.read_text(encoding='utf-8')
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('scrub_unrecorded $target', rollback)
        helper = body.split('function scrub_unrecorded', 1)[1].split('\nend', 1)[0]
        # Matched in `getfacl`'s OWN printed form, not the raw path: it escapes a
        # newline as `\012`, so a raw compare never matches such a path and the
        # scrub then strips an ACL the restore had just put back.
        self.assertIn('getfacl_path $path', helper, 'only the paths the dump skipped')
        printer = body.split('function getfacl_path', 1)[1].split('\nend', 1)[0]
        self.assertIn('# file: ', printer, 'the canonical form is asked of the tool, not reimplemented')

    def test_the_scrub_removes_the_grant_from_a_new_file(self):
        """Measured on a real tree: `--restore` leaves the residue in place."""
        tree = self.tmp / 'scrub'
        (tree / 'sub').mkdir(parents=True)
        keep = tree / 'keep'
        keep.write_text('x', encoding='utf-8')
        dump = subprocess.run(
            ['getfacl', '--recursive', '--physical', '--absolute-names', str(tree)],
            capture_output=True, text=True, check=True,
        ).stdout
        # `-m d:...` is for directories; a default entry on a regular file is
        # "Invalid argument" (measured).
        subprocess.run(['setfacl', '-R', '-m', 'u:nobody:rX', '-m', 'd:u:nobody:rX', str(tree)], check=True)
        fresh = tree / 'created-after'
        fresh.write_text('x', encoding='utf-8')
        self.assertIn('user:nobody', subprocess.run(
            ['getfacl', '--omit-header', str(fresh)], capture_output=True, text=True, check=True).stdout)

        # The shipped scrub, with the dump where it looks for it.
        body = SCRIPT.read_text(encoding='utf-8')
        gp_tail = body.split('\nfunction getfacl_path', 1)[1]
        gp_sig, _, after_gp = gp_tail.partition('\nend')
        tail = after_gp.split('\nfunction scrub_unrecorded', 1)[1]
        signature, _, _ = tail.partition('\nend')
        # The identities the scrub removes are the script's own, so the fixture
        # must grant them — `nobody` here and `agent` in the code removes
        # nothing at all, which looks exactly like a broken scrub.
        script = (
            'set -g AGENT_USER nobody\n'
            'set -g AGENT_GROUP nobody\n'
            f'function acl_dump_file; echo {self.tmp}/scrub.acl; end\n'
            'set -g DRY_RUN 0\n'
            # The SHIPPED run_cmd, not an imitation: it strips `--tolerate`, and
            # an imitation that forgot to made the scrub look broken.
            + 'function run_cmd' + body.split('\nfunction run_cmd', 1)[1].split('\nend', 1)[0] + '\nend\n'
            +
            f'function getfacl_path{gp_sig}\nend\n'
            f'function scrub_unrecorded{signature}\nend\n'
            f'scrub_unrecorded "{tree}"\n'
        )
        (self.tmp / 'scrub.acl').write_text(dump, encoding='utf-8')
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        after = subprocess.run(
            ['getfacl', '--omit-header', str(fresh)], capture_output=True, text=True, check=True).stdout
        self.assertNotIn('user:nobody', after, 'the file created after the snapshot kept the grant')
        # And the path the dump DID cover is left as the restore put it.
        kept = subprocess.run(
            ['getfacl', '--omit-header', str(keep)], capture_output=True, text=True, check=True).stdout
        self.assertIn('user:nobody', kept, 'a recorded path must not be scrubbed')


class RoundTwelveRegressionTest(PlanTest):
    """The twelfth round: the sudo audit's placement, and the primary-gid restore."""

    def test_the_sudo_audit_covers_a_created_account_and_group_scoped_rules(self):
        """It lived inside the existing-account branch and ran before `sing-dev`."""
        body = SCRIPT.read_text(encoding='utf-8')
        provision = body.split('function provision', 1)[1]
        # ONE call, at the end of provisioning — not inside either account branch.
        self.assertEqual(1, provision.count('\n    audit_sudo\n'), provision[-800:])
        self.assertLess(
            provision.index('run_cmd usermod --append --groups $AGENT_GROUP $AGENT_USER'),
            provision.index('\n    audit_sudo\n'),
            'a group-scoped rule is only visible once the membership exists',
        )
        created = provision.split('if test -z "$account"', 1)[1].split('\n    else\n', 1)[0]
        self.assertNotIn('audit_sudo', created, 'a created account must be audited too, by the same call')

    def test_a_group_held_as_the_primary_gid_is_restored_as_one(self):
        """`usermod --append` restores the other kind of membership, not this one."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_removed_group', 1)[1].split('\nend', 1)[0]
        self.assertIn('record_ownership removed-primary-group', record)
        self.assertIn('record_ownership removed-group', record)
        rollback = body.split('function rollback', 1)[1]
        self.assertIn('previously_removed_primary_groups', rollback)
        self.assertIn('usermod --gid $gid $AGENT_USER', rollback)


    def test_setfacl_recursive_accepts_a_default_entry_on_a_tree_of_files(self):
        """A review claimed this aborts provisioning; measured, it does not.

        `setfacl --recursive --modify default:...` over a tree that contains
        regular files exits 0 — the default entry simply applies to the
        directories only. The claim was that `run_cmd` would die mid-provision,
        which would be a P1; the test exists so the claim has to be re-measured
        rather than re-asserted.
        """
        tree = self.tmp / 'defaults'
        (tree / 'sub').mkdir(parents=True)
        (tree / 'file').write_text('x', encoding='utf-8')
        (tree / 'sub' / 'nested').write_text('x', encoding='utf-8')
        result = subprocess.run(
            ['setfacl', '--recursive', '--modify', 'user:nobody:rX', '--modify', 'default:user:nobody:rX', str(tree)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        # The file got the ACCESS entry and no default one, which is what the
        # script relies on: files must be readable, not inheritable.
        entries = subprocess.run(
            ['getfacl', '--omit-header', str(tree / 'file')],
            capture_output=True, text=True, check=True,
        ).stdout
        # `rX` on a regular file prints as `r--`: `X` is "execute if a
        # directory", which is why the entry is matched by SUBJECT elsewhere.
        self.assertIn('user:nobody:r', entries)
        self.assertNotIn('default:', entries)

    def test_the_scrub_survives_a_path_containing_a_newline(self):
        """`find` without `-print0` splits such a path into two entries."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('function scrub_unrecorded', 1)[1].split('\nend', 1)[0]
        self.assertIn('-print0', helper)
        self.assertIn('string split0', helper)

        # The property itself, on a real file whose name holds a newline: the
        # walk must name it ONCE, since two halves would have one half scrubbed.
        tree = self.tmp / 'nl'
        tree.mkdir()
        nasty = tree / 'a\nb'
        nasty.write_text('x', encoding='utf-8')
        script = f'echo (count (find "{tree}" -mindepth 1 -print0 | string split0))\n'
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('1', result.stdout.strip(), f'one path must be one entry: {result.stdout!r}')


    def test_a_pre_existing_acl_on_a_newline_path_survives_the_scrub(self):
        """The end-to-end property the review asked for.

        A path with a newline is inside the dump, so the scrub must recognise it
        as already restored and leave it alone. Matching the raw path never
        matches (`getfacl` prints `\\012`), so the file would be misclassified as
        newly created and lose the ACL the restore had just put back.
        """
        tree = self.tmp / 'nltree'
        tree.mkdir()
        nasty = tree / 'a\nb'
        nasty.write_text('x', encoding='utf-8')
        # A grant that is NOT this script's, and that must survive.
        subprocess.run(['setfacl', '-m', 'u:nobody:rwx', str(nasty)], check=True)
        dump = subprocess.run(
            ['getfacl', '--recursive', '--physical', '--absolute-names', str(tree)],
            capture_output=True, text=True, check=True,
        ).stdout

        body = SCRIPT.read_text(encoding='utf-8')
        gp_tail = body.split('\nfunction getfacl_path', 1)[1]
        gp_sig, _, after_gp = gp_tail.partition('\nend')
        tail = after_gp.split('\nfunction scrub_unrecorded', 1)[1]
        signature, _, _ = tail.partition('\nend')
        (self.tmp / 'nl.acl').write_text(dump, encoding='utf-8')
        script = (
            'set -g AGENT_USER nobody\n'
            'set -g AGENT_GROUP nobody\n'
            f'function acl_dump_file; echo {self.tmp}/nl.acl; end\n'
            'set -g DRY_RUN 0\n'
            # The SHIPPED run_cmd, not an imitation: it strips `--tolerate`, and
            # an imitation that forgot to made the scrub look broken.
            + 'function run_cmd' + body.split('\nfunction run_cmd', 1)[1].split('\nend', 1)[0] + '\nend\n'
            +
            f'function getfacl_path{gp_sig}\nend\n'
            f'function scrub_unrecorded{signature}\nend\n'
            f'scrub_unrecorded "{tree}"\n'
        )
        result = subprocess.run(
            [fish_interpreter(), '--no-config', '-c', script],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        after = subprocess.run(
            ['getfacl', '--omit-header', str(nasty)],
            capture_output=True, text=True, check=True,
        ).stdout
        self.assertIn('user:nobody:rwx', after, 'the dump covered this path; the scrub must skip it')

        # And the recognition is the tool's own form, not the raw path: that is
        # what cannot drift when `getfacl` chooses another escape.
        printer = subprocess.run(
            [fish_interpreter(), '--no-config', '-c',
             'function getfacl_path{sig}\nend\necho (getfacl_path $NASTY)'.replace('{sig}', gp_sig)],
            capture_output=True, text=True, check=False,
            env={**os.environ, 'NASTY': str(nasty)},
        ).stdout.strip()
        self.assertIn(
            f'# file: {printer}', dump,
            'the printed form must be the one the dump carries',
        )
        self.assertNotEqual(str(nasty), printer, 'a newline path is escaped by getfacl')


class DryRunPurityTest(PlanTest):
    """`rollback --dry-run` must describe, never mutate.

    A review read the code and found that `restore_acl` and `scrub_unrecorded`
    called `setfacl` directly, bypassing `run_cmd` — so a dry run AS ROOT
    rewrote the ACLs it was printing. Measured: the call sites are `run_cmd`
    now and the two direct ones sit behind an explicit `$DRY_RUN` check.
    """

    def test_no_state_changing_call_bypasses_run_cmd(self):
        """Every `setfacl` is either printed by `run_cmd` or guarded by DRY_RUN."""
        body = SCRIPT.read_text(encoding='utf-8')
        for number, line in enumerate(body.splitlines(), 1):
            stripped = line.strip()
            if not stripped.startswith('setfacl '):
                continue
            # A direct call is legal only immediately after a dry-run return.
            guarded = 'test $DRY_RUN -eq 1' in stripped or '$DRY_RUN -eq 1' in stripped
            if guarded:
                continue
            context = body.splitlines()[max(0, number - 6):number - 1]
            self.assertTrue(
                any('DRY_RUN -eq 1' in c for c in context),
                f'line {number}: a state-changing setfacl with no dry-run guard: {stripped}',
            )

    def test_the_scrub_and_restore_route_through_run_cmd(self):
        body = SCRIPT.read_text(encoding='utf-8')
        scrub = body.split('function scrub_unrecorded', 1)[1].split('\nend', 1)[0]
        self.assertIn('run_cmd', scrub, 'the scrub must print under --dry-run')
        restore = body.split('function restore_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('run_cmd setfacl --restore', restore)

    def test_the_snapshot_directory_is_readable_by_an_unprivileged_dry_run(self):
        """At 0700 root, a dry run could not see the dumps and printed the wrong branch."""
        body = SCRIPT.read_text(encoding='utf-8')
        record = body.split('function record_acl', 1)[1].split('\nend', 1)[0]
        self.assertIn('--mode=0755 (acl_dir)', record, 'the dumps are 0600; the DIRECTORY must be traversable')
        self.assertNotIn('--mode=0700 (acl_dir)', record)

    def test_run_cmd_honours_the_tolerate_flag_it_documents(self):
        """The comment promised `--tolerate`; the option did not exist."""
        body = SCRIPT.read_text(encoding='utf-8')
        helper = body.split('\nfunction run_cmd', 1)[1].split('\nend', 1)[0]
        self.assertIn('--tolerate', helper)
        self.assertIn('test $tolerate -eq 0', helper, 'the opt-in must actually gate the failure')

    def test_the_password_lock_does_not_print_twice(self):
        """`run_cmd` already prints under --dry-run; the manual test duplicated it."""
        provision = SCRIPT.read_text(encoding='utf-8').split('function provision', 1)[1]
        self.assertIn('test "$password_state" = P; and run_cmd passwd --lock', provision)
        self.assertNotIn('DRY_RUN -eq 1; and echo "passwd --lock', provision)


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
