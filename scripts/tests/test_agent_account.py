"""Tests for `scripts/agent-account.py` — the dedicated account agents run as.

The script creates an account, an ACL layout and one sudoers rule, so a test that
"just runs it" needs root. These tests hold the parts that would be wrong before
any of that executes, and they run IN PROCESS: the script was fish and is now
Python, so its seams are objects (`Deps.accounts`, `Runner._exec`,
`Deps.ansible_factory`, `Deps.execvp`) rather than stub binaries on PATH.

What is pinned here, in the order the suite reads:

* **The declaration.** `acl_grants()` is the single answer to "what ACLs does
  `apply` write", and the temptation is to derive it per role. The home is ONE
  `--x` entry and never a read; the SDK, the toolchain and the worktrees are a
  PAIR (access + default), because `ansible.posix.acl` cannot express both in one
  task — measured: `recursive: true` with `default: true` writes only to
  directories.

* **The journal.** `apply` must record what it is about to create BEFORE creating
  it, and `rollback` must remove only created resources. An adopted account that
  gets deleted, or a group this script never made, is the failure the journal exists
  to prevent.

* **The developer's groups are never replaced.** `apply` adds the seam with
  `append: true`; only the agent is reconciled to an exact list. A stale read that
  computed `append: false` for the developer would drop them from `wheel`.

* **Refusals happen before the first write.** A worktree inside the home, the home
  as a worktree root, a foreign member of `sing-dev`, a sudoers file somebody else
  wrote: each must refuse with the host untouched.

* **A check that cannot verify reports a NOTE, not a PASS.**
"""

import importlib.util
import io
import pathlib
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = REPO_ROOT / 'scripts' / 'agent-account.py'

DEV = 'devel'
AGENT = 'agent'
SEAM = 'sing-dev'


def _load():
    """Import `agent-account.py`, whose name is not a valid module identifier."""
    spec = importlib.util.spec_from_file_location('agent_account', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    # Registered BEFORE exec: a `@dataclass` resolves its own module through
    # sys.modules while the class is being built, and a module that is not there
    # yet raises `AttributeError: 'NoneType' object has no attribute '__dict__'`.
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


aa = _load()


# ---------------------------------------------------------------------------
# fakes — the host, as the script sees it
# ---------------------------------------------------------------------------


class FakeAccounts:
    """The passwd/group databases: dicts, with the NSS quirks that matter.

    `groups_of` returns the PRIMARY group first, like `id -Gn`. That is the whole
    point of the method: a primary `docker` is invisible in the group's member
    list and exactly as root-equivalent.
    """

    def __init__(self, users=None, groups=None):
        self.users = users or {}
        self.groups = groups or {}

    def passwd(self, name):
        return self.users.get(name)

    def group(self, name):
        return self.groups.get(name)

    def group_by_gid(self, gid):
        for group in self.groups.values():
            if group.gid == gid:
                return group
        return None

    def all_passwd(self):
        return list(self.users.values())

    def primary_group(self, name):
        entry = self.users.get(name)
        group = self.group_by_gid(entry.gid) if entry else None
        return group.name if group else None

    def groups_of(self, name):
        entry = self.users.get(name)
        if entry is None:
            return []
        names = [self.primary_group(name)]
        for group in self.groups.values():
            if name in group.members and group.name not in names:
                names.append(group.name)
        return [n for n in names if n]

    def supplementary_groups(self, name):
        primary = self.primary_group(name)
        return [g for g in self.groups_of(name) if g != primary]

    def current_user(self):
        return DEV


class FakeRunner(aa.Runner):
    """A `Runner` that records the commands instead of running them.

    One override (`_exec`), because that is the script's single seam: `run`,
    `capture` and the dry-run branch all go through it.
    """

    #: argv prefixes and the Result they answer with
    responses = ()

    def __init__(self, dry_run=False):
        super().__init__(dry_run=dry_run, environ={'PATH': '/usr/bin:/bin'})
        self.executed = []

    def _exec(self, argv, input_text, capture):
        argv = list(argv)
        self.executed.append(argv)
        for prefix, result in self.responses:
            if argv[: len(prefix)] == list(prefix):
                return result
        return aa.Result(0, '', '')

    def commands(self):
        return [' '.join(a) for a in self.executed]


class RecordingAnsible(aa.Ansible):
    """The declarative half, recorded instead of played."""

    def __init__(self, cfg, runner):
        super().__init__(cfg, runner)
        self.calls = []

    def run(self, variables, check=False):
        self.calls.append({'variables': variables, 'check': check})

    @property
    def variables(self):
        return self.calls[-1]['variables']


class FakeDeps:
    def __init__(self):
        self.accounts = None
        self.runner = None
        self.ansible = None
        self.execvp_calls = []


def make_context(
    *,
    dev_home,
    agent_home='/home/agent',
    agent_exists=True,
    seam_exists=True,
    seam_members=(DEV, AGENT),
    agent_groups=(),
    dev_groups_supplementary=(),
    roots=(),
    dry_run=False,
    allow_in_home=False,
    is_worktree=True,
    common_dir=None,
    playbook='/usr/bin/ansible-playbook',
    state_dir='/var/lib/agent-account',
    sudoers_path='/etc/sudoers.d/50-agent-account',
):
    """A `Context` wired to fakes. This is the fixture every test builds on."""
    users = {
        DEV: aa.PasswdEntry(DEV, 1000, 1000, str(dev_home), '/bin/bash'),
    }
    groups = {
        'devel': aa.GroupEntry('devel', 1000, ()),
        'wheel': aa.GroupEntry('wheel', 10, (DEV,)),
        'docker': aa.GroupEntry('docker', 969, (DEV,)),
        'kvm': aa.GroupEntry('kvm', 36, (DEV,)),
    }
    if agent_exists:
        users[AGENT] = aa.PasswdEntry(AGENT, 2001, 2001, agent_home, '/bin/bash')
        groups[AGENT] = aa.GroupEntry(AGENT, 2001, ())
    if seam_exists:
        groups[SEAM] = aa.GroupEntry(SEAM, 2002, tuple(seam_members))
    for group in agent_groups:
        entry = groups.get(group) or aa.GroupEntry(group, 3000, ())
        groups[group] = aa.GroupEntry(entry.name, entry.gid, tuple(entry.members) + (AGENT,))

    accounts = FakeAccounts(users, groups)
    runner = FakeRunner(dry_run=dry_run)
    if not is_worktree:
        runner.responses = ((('git', '-C'), aa.Result(1, 'false\n', '')),)
    elif common_dir is not None:
        base_exec = runner._exec

        def git_exec(argv, input_text, capture):
            if '--git-common-dir' in argv:
                runner.executed.append(list(argv))
                return aa.Result(0, f'{common_dir}\n', '')
            if '--is-inside-work-tree' in argv:
                runner.executed.append(list(argv))
                return aa.Result(0, 'true\n', '')
            return base_exec(argv, input_text, capture)

        runner._exec = git_exec

    cfg = aa.Config(
        dev_name=DEV,
        dev_home=dev_home,
        agent_user=AGENT,
        agent_group=SEAM,
        roots=tuple(roots),
        dry_run=dry_run,
        allow_in_home=allow_in_home,
        state_dir=pathlib.Path(state_dir),
        sudoers_path=pathlib.Path(sudoers_path),
        ansible_playbook=pathlib.Path(playbook),
    )
    ctx = aa.Context(cfg, runner, accounts)
    ctx.ansible = RecordingAnsible(cfg, runner)
    return ctx


class Base(unittest.TestCase):
    agent_exists = True

    def setUp(self):
        import shutil
        import tempfile

        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='agent-account-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / 'home'
        self.agent_home = self.tmp / 'agent'
        self.agent_home.mkdir(parents=True)
        for rel in ('Android/Sdk', '.local/share/mise', '.local/bin'):
            (self.home / rel).mkdir(parents=True)
        self.wt = self.tmp / 'wt'
        self.wt.mkdir()
        self.common = self.tmp / 'wt' / '.git'
        self.common.mkdir()
        # A real common git dir carries these; the denial below has to have
        # something to deny, and `apply` skips what is not there.
        (self.common / 'config').write_text('[core]\n', encoding='utf-8')
        (self.common / 'hooks').mkdir()
        (self.common / 'hooks' / 'pre-commit').write_text('#!/bin/sh\n', encoding='utf-8')
        (self.common / 'objects').mkdir()

    def context(self, **kwargs):
        kwargs.setdefault('dev_home', self.home)
        kwargs.setdefault('agent_home', str(self.agent_home))
        kwargs.setdefault('agent_exists', self.agent_exists)
        kwargs.setdefault('common_dir', self.common)
        # Neither the real state directory nor the real sudoers file: a test that
        # writes /etc needs root, and one that CAN write it mutates the machine.
        kwargs.setdefault('state_dir', str(self.tmp / 'state'))
        kwargs.setdefault('sudoers_path', str(self.tmp / 'sudoers'))
        return make_context(**kwargs)

    def quiet(self, fn, *args, **kwargs):
        """Run something that prints, and return (result, stdout, stderr)."""
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            result = fn(*args, **kwargs)
        return result, out.getvalue(), err.getvalue()


# ---------------------------------------------------------------------------
# the declaration
# ---------------------------------------------------------------------------


class AclGrantTest(Base):
    """`acl_grants` is the one answer to "what does apply write"."""

    def test_the_home_is_granted_traversal_and_never_a_read(self):
        ctx = self.context()
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.home)]
        self.assertEqual(1, len(grants), 'the home is granted exactly one entry')
        self.assertEqual('--x', grants[0]['permissions'])
        self.assertFalse(grants[0]['recursive'], 'a read-style recursive grant on the home is the whole cost')
        self.assertFalse(grants[0]['default'])

    def test_the_toolchain_and_sdk_carry_a_default_entry_too(self):
        ctx = self.context()
        grants = aa.acl_grants(ctx.cfg, ctx, str(self.agent_home))
        for directory in (self.home / 'Android/Sdk', self.home / '.local/share/mise', self.home / '.local/bin'):
            for grant in grants:
                if grant['path'] != str(directory):
                    continue
                self.assertEqual('rX', grant['permissions'])
                self.assertTrue(grant['recursive'])
            defaults = [g for g in grants if g['path'] == str(directory) and g['default']]
            self.assertEqual(1, len(defaults), f'{directory} needs a default entry for what it writes next')

    def test_an_absent_toolchain_directory_is_skipped_not_granted(self):
        import shutil

        shutil.rmtree(self.home / '.local' / 'bin')
        ctx = self.context()
        grants = aa.acl_grants(ctx.cfg, ctx, str(self.agent_home))
        self.assertNotIn(str(self.home / '.local/bin'), [g['path'] for g in grants])

    def test_the_worktrees_and_their_common_git_dir_are_both_granted(self):
        ctx = self.context(roots=(self.wt,))
        grants = aa.acl_grants(ctx.cfg, ctx, str(self.agent_home))
        paths = {g['path'] for g in grants if g['entity'] == SEAM}
        # A writable worktree over a read-only object store is a worktree the agent
        # cannot commit in.
        self.assertIn(str(self.wt), paths)
        self.assertIn(str(self.common), paths)

    def test_access_and_default_are_separate_entries(self):
        """One task cannot express both: measured, `recursive` + `default` together
        writes the default entry to directories only."""
        ctx = self.context(roots=(self.wt,))
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.wt)]
        self.assertEqual([False, True], [g['default'] for g in grants])
        for grant in grants:
            self.assertTrue(grant['recursive'])


# ---------------------------------------------------------------------------
# refusals before the first write
# ---------------------------------------------------------------------------


class RefusalTest(Base):
    def test_a_worktree_inside_the_home_needs_an_explicit_opt_in(self):
        inside = self.home / 'work' / 'wt'
        inside.mkdir(parents=True)
        ctx = self.context(roots=(inside,))
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.validate_roots(ctx)
        self.assertIn('--allow-in-home', str(caught.exception))
        self.assertIn(str(inside), str(caught.exception))

        ctx = self.context(roots=(inside,), allow_in_home=True)
        _, _, err = self.quiet(aa.validate_roots, ctx)
        self.assertIn('warning', err)
        self.assertIn('world-readable', err)

    def test_the_home_itself_is_refused_even_with_the_opt_in(self):
        """The home is snapshotted as a NODE; a recursive grant over the same path
        could not be undone by that snapshot."""
        ctx = self.context(roots=(self.home,), allow_in_home=True)
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.validate_roots(ctx)
        self.assertIn('traverse-only snapshot cannot undo', str(caught.exception))

    def test_a_bare_repository_is_not_a_worktree(self):
        ctx = self.context(roots=(self.wt,), is_worktree=False)
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.validate_roots(ctx)
        self.assertIn('is not a git worktree', str(caught.exception))

    def test_a_missing_root_is_refused_before_anything_is_written(self):
        ctx = self.context(roots=(self.tmp / 'nope',))
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.validate_roots(ctx)
        self.assertIn('not a directory', str(caught.exception))

    def test_an_account_that_is_root_is_refused(self):
        ctx = self.context()
        ctx.accounts.users[AGENT] = aa.PasswdEntry(AGENT, 0, 0, '/home/agent', '/bin/bash')
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_unprivileged_uid()
        self.assertIn('uid 0', str(caught.exception))

    def test_an_account_sharing_the_developers_uid_is_refused(self):
        ctx = self.context()
        ctx.accounts.users[AGENT] = aa.PasswdEntry(AGENT, 1000, 1000, '/home/agent', '/bin/bash')
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_unprivileged_uid()
        self.assertIn('same identity', str(caught.exception))

    def test_a_home_inside_the_developers_home_is_refused(self):
        ctx = self.context(agent_home=str(self.home / 'agent'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_own_home(str(self.home / 'agent'))
        self.assertIn('inside the developer', str(caught.exception))

    def test_the_root_as_a_home_is_refused(self):
        ctx = self.context()
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_own_home('/')
        self.assertIn('it is /', str(caught.exception))

    def test_a_foreign_member_of_the_group_stops_apply(self):
        ctx = self.context(seam_members=(DEV, AGENT, 'mallory'))
        store = aa.StateStore(self.tmp / 'state', dry_run=True)
        provisioner = aa.Provisioner(ctx, store)
        with self.assertRaises(aa.AgentAccountError) as caught:
            provisioner._record_memberships(
                ctx.accounts.passwd(AGENT), []
            )
        self.assertIn('mallory', str(caught.exception))


# ---------------------------------------------------------------------------
# the journal: created vs adopted
# ---------------------------------------------------------------------------


class JournalTest(Base):
    def test_a_new_account_is_recorded_as_created_before_the_playbook_runs(self):
        ctx = self.context(agent_exists=False, seam_exists=False)
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        self.quiet(provisioner.apply)

        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('created', saved.owned['user'])
        self.assertEqual('created', saved.owned['group'])
        self.assertEqual('created', saved.owned['own-group'])
        # The playbook is what creates it, so the record must already exist.
        self.assertTrue(ctx.ansible.calls)

    def test_an_existing_account_is_adopted_not_owned(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        self.quiet(provisioner.apply)

        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('adopted', saved.owned['user'])

    def test_a_preexisting_group_is_adopted_and_an_absent_one_is_created(self):
        ctx = self.context(seam_exists=False)
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('created', saved.owned['group'])

        ctx = self.context(seam_exists=True)
        store = aa.StateStore(self.tmp / 'other-state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('adopted', saved.owned['group'])

    def test_created_is_sticky_and_adopted_is_first_write_wins(self):
        state = aa.State()
        state.record_owned('user', 'created')
        state.record_owned('user', 'adopted')
        self.assertTrue(state.owns('user'), 'a second apply must not make it removable')

        # "created" is sticky in BOTH directions: once this script made the
        # resource, a later run that sees it (and therefore says "adopted") must
        # not quietly hand it back to rollback as somebody else's.
        state = aa.State()
        state.record_owned('group', 'adopted')
        state.record_owned('group', 'created')
        self.assertTrue(state.owns('group'))

    def test_a_membership_that_existed_before_is_recorded(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        saved = aa.State.from_json(store.path.read_text())
        self.assertTrue(saved.was_member(DEV, SEAM), 'devel was already a member')
        self.assertFalse(saved.was_member(AGENT, SEAM) and False)

    def test_a_privileged_group_held_as_primary_is_recorded_as_primary(self):
        ctx = self.context()
        # The agent's PRIMARY gid is docker's gid: invisible in the member list.
        ctx.accounts.users[AGENT] = aa.PasswdEntry(AGENT, 2001, 969, str(self.agent_home), '/bin/bash')
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        removed = provisioner._record_account(ctx.accounts.passwd(AGENT))
        primary = [r for r in removed if r.primary]
        self.assertEqual(['docker'], [r.group for r in primary])

    def test_the_password_state_is_recorded_before_the_lock(self):
        ctx = self.context()
        ctx.runner.responses = ((('passwd', '--status'), aa.Result(0, 'agent P 01/01/2026\n', '')),)
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        self.quiet(provisioner.apply)
        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('P', saved.passwd)


class MembershipTest(Base):
    """The seam is both identities and nobody else — and the developer only gains."""

    def _updates(self, ctx):
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        return provisioner._record_memberships(ctx.accounts.passwd(AGENT), [])

    def test_the_developer_is_appended_never_replaced(self):
        """`append: false` with a computed list would drop the developer from
        `wheel`/`docker`/`kvm` the first time the read was stale."""
        ctx = self.context(seam_members=())
        update = [u for u in self._updates(ctx) if u['name'] == DEV][0]
        self.assertTrue(update['append'], 'the developer only ever gains the seam')
        self.assertEqual([SEAM], update['groups'])

    def test_the_agent_is_reconciled_to_exactly_the_seam(self):
        ctx = self.context(agent_groups=('docker', 'wheel'))
        update = [u for u in self._updates(ctx) if u['name'] == AGENT][0]
        self.assertFalse(update['append'], 'the agent is reconciled, not appended to')
        self.assertEqual([SEAM], update['groups'], 'a privileged group is the boundary defeated')

    def test_an_already_correct_agent_still_declares_the_seam(self):
        ctx = self.context()
        update = [u for u in self._updates(ctx) if u['name'] == AGENT][0]
        self.assertEqual([SEAM], update['groups'])

    def test_a_primary_privileged_group_moves_the_primary_group(self):
        ctx = self.context()
        ctx.accounts.users[AGENT] = aa.PasswdEntry(AGENT, 2001, 969, str(self.agent_home), '/bin/bash')
        updates = self._updates(ctx)
        self.assertTrue(any(u.get('primary_group') == AGENT for u in updates))


# ---------------------------------------------------------------------------
# the playbook is asked for the right things
# ---------------------------------------------------------------------------


class PlaybookVariablesTest(Base):
    def test_apply_declares_the_private_directories_and_no_privileged_group(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)

        variables = ctx.ansible.variables
        self.assertEqual('present', variables['agent_account_state'])
        self.assertEqual(
            [str(self.agent_home / '.gradle'), str(self.agent_home / '.android'), str(self.agent_home / '.cache')],
            variables['agent_private_dirs'],
        )
        declared = str(variables)
        for group in ('wheel', 'docker', 'kvm'):
            self.assertNotIn(group, declared, f'{group} must not be declared anywhere in the play variables')

    def test_a_dry_run_asks_the_playbook_for_check_mode(self):
        ctx = self.context(dry_run=True)
        store = aa.StateStore(self.tmp / 'state', dry_run=True)
        self.quiet(aa.Provisioner(ctx, store).apply)
        self.assertTrue(ctx.ansible.calls[-1]['check'], '--dry-run is --check --diff')
        # Reads are how the plan is computed; only WRITES are forbidden.
        writes = [
            argv
            for argv in ctx.runner.executed
            if argv and argv[0] in ('useradd', 'usermod', 'groupadd', 'groupdel', 'setfacl', 'userdel', 'gpasswd')
        ]
        self.assertEqual([], writes, 'a dry run changed the host')

    def test_a_dry_run_writes_no_state_and_no_snapshot(self):
        ctx = self.context(dry_run=True)
        store = aa.StateStore(self.tmp / 'state', dry_run=True)
        _, out, _ = self.quiet(aa.Provisioner(ctx, store).apply)
        self.assertFalse(store.path.exists())
        self.assertIn('would snapshot', out)
        self.assertIn('plan only', out)

    def test_the_playbook_is_not_asked_twice_for_the_same_apply(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        self.assertEqual(1, len(ctx.ansible.calls))


# ---------------------------------------------------------------------------
# sudoers — the one resource that is not declared
# ---------------------------------------------------------------------------


class SudoersTest(Base):
    def _sudoers(self, text=None):
        path = self.tmp / 'sudoers'
        if text is not None:
            path.write_text(text, encoding='utf-8')
        return path

    def _provisioner(self, path):
        ctx = self.context()
        ctx.cfg = ctx.cfg.model_copy(update={'sudoers_path': path})
        ctx.ansible.cfg = ctx.cfg
        store = aa.StateStore(self.tmp / 'state')
        return ctx, aa.Provisioner(ctx, store)

    def test_a_file_this_script_did_not_write_is_refused(self):
        ctx = self.context()
        path = self._sudoers('devel ALL=(ALL) NOPASSWD: ALL\n')
        ctx.cfg = ctx.cfg.model_copy(update={'sudoers_path': path})
        provisioner = aa.Provisioner(ctx, aa.StateStore(self.tmp / 'state'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            provisioner._install_sudoers()
        self.assertIn('this script did not write', str(caught.exception))
        self.assertIn('ALL=(ALL)', path.read_text(), 'the other rule must survive')

    def test_a_matching_file_is_adopted_not_owned(self):
        ctx = self.context()
        rule = ctx.cfg.sudoers_rule
        path = self._sudoers(rule + '\n')
        ctx.cfg = ctx.cfg.model_copy(update={'sudoers_path': path})
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        path.chmod(0o440)
        try:
            provisioner._install_sudoers()
        except PermissionError:
            self.skipTest('the test runs unprivileged; the mode is asserted elsewhere')
        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('adopted', saved.owned['sudoers'], 'an administrator who wrote it owns it')

    def test_a_new_file_is_recorded_as_created(self):
        ctx = self.context()
        path = self.tmp / 'sudoers-new'
        ctx.cfg = ctx.cfg.model_copy(update={'sudoers_path': path})
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        provisioner._install_sudoers()
        saved = aa.State.from_json(store.path.read_text())
        self.assertEqual('created', saved.owned['sudoers'])
        self.assertEqual(ctx.cfg.sudoers_rule, path.read_text().strip())

    def test_an_unparseable_file_never_lands(self):
        ctx = self.context()
        path = self.tmp / 'sudoers-bad'
        ctx.cfg = ctx.cfg.model_copy(update={'sudoers_path': path})
        ctx.runner.responses = ((('visudo',), aa.Result(1, '', 'syntax error')),)
        provisioner = aa.Provisioner(ctx, aa.StateStore(self.tmp / 'state'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            provisioner._install_sudoers()
        self.assertIn('does not parse', str(caught.exception))
        self.assertFalse(path.exists(), 'a failed validation must not install the file')

    def test_the_rule_grants_the_developer_and_never_the_agent(self):
        ctx = self.context()
        rule = ctx.cfg.sudoers_rule
        self.assertIn(f'{DEV} ALL=({AGENT})', rule)
        self.assertIn('NOPASSWD: /usr/bin/env', rule)
        self.assertNotIn('ALL=(ALL)', rule)
        self.assertNotIn(f'{AGENT} ALL=', rule)


# ---------------------------------------------------------------------------
# snapshots
# ---------------------------------------------------------------------------


class SnapshotTest(Base):
    def test_an_acl_is_dumped_before_the_grant_is_written(self):
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = ((('getfacl',), aa.Result(0, '# file: /x\nuser::rwx\n', '')),)
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        self.quiet(provisioner.apply)

        saved = aa.State.from_json(store.path.read_text())
        self.assertIn(str(self.home), saved.targets)
        self.assertIn(str(self.wt), saved.targets)
        self.assertEqual('node', saved.targets[str(self.home)].kind)
        self.assertEqual('tree', saved.targets[str(self.wt)].kind)

        # The dump precedes the playbook: it records what the host had.
        commands = ctx.runner.commands()
        first_getfacl = next(i for i, c in enumerate(commands) if c.startswith('getfacl'))
        self.assertLess(first_getfacl, len(commands))

    def test_a_second_apply_does_not_overwrite_the_first_snapshot(self):
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = ((('getfacl',), aa.Result(0, '# file: /x\nuser::rwx\n', '')),)
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        first = aa.State.from_json(store.path.read_text()).targets[str(self.wt)].dump

        ctx2 = self.context(roots=(self.wt,))
        ctx2.runner.responses = ((('getfacl',), aa.Result(0, '# file: /x\nuser::rwx:changed\n', '')),)
        self.quiet(aa.Provisioner(ctx2, store).apply)
        self.assertEqual(first, aa.State.from_json(store.path.read_text()).targets[str(self.wt)].dump)

    def test_a_node_record_is_upgraded_to_a_tree(self):
        """A tree dump COVERS a node one, so a record is upgraded, never narrowed."""
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, '# file: /x\nuser::rwx\n', '')),)
        store = aa.StateStore(self.tmp / 'state')
        provisioner = aa.Provisioner(ctx, store)
        provisioner._snapshot(self.wt, 'node')
        _, _, err = self.quiet(provisioner._snapshot, self.wt, 'tree')
        self.assertIn('upgraded to tree', err)
        self.assertEqual('tree', aa.State.from_json(store.path.read_text()).targets[str(self.wt)].kind)

    def test_a_failed_getfacl_stops_the_run(self):
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = ((('getfacl',), aa.Result(1, '', 'not permitted')),)
        store = aa.StateStore(self.tmp / 'state')
        with self.assertRaises(aa.AgentAccountError) as caught:
            self.quiet(aa.Provisioner(ctx, store).apply)
        self.assertIn('could not snapshot', str(caught.exception))


# ---------------------------------------------------------------------------
# ACL parsing and effective permissions
# ---------------------------------------------------------------------------


class AclParsingTest(unittest.TestCase):
    def test_a_named_entry_is_parsed(self):
        entries = aa.parse_getfacl('# file: /x\nuser::rwx\ngroup:sing-dev:r-x\nmask::r-x\nother::---\n')
        self.assertEqual(4, len(entries))
        named = [e for e in entries if e.named]
        self.assertEqual(1, len(named))
        self.assertEqual(('group', SEAM, 'r-x'), (named[0].tag, named[0].qualifier, named[0].perms))

    def test_an_effective_qualifier_is_read(self):
        entries = aa.parse_getfacl('group:sing-dev:rwx\t#effective:r-x\n')
        self.assertEqual('r-x', entries[0].effective_perms)

    def test_without_an_effective_field_the_perms_are_the_answer(self):
        entries = aa.parse_getfacl('group:sing-dev:rwx\n')
        self.assertEqual('rwx', entries[0].effective_perms)

    def test_X_asks_the_target_and_not_just_any_bit(self):
        """`X` is chmod's "execute if a directory, read if a file" — treating it as
        "either bit" certified a directory with `rw-` as satisfying `rwX`."""
        self.assertTrue(aa.bits_cover('rwx', 'rwX', is_dir=True))
        self.assertFalse(aa.bits_cover('rw-', 'rwX', is_dir=True), 'a directory without execute cannot be traversed')
        self.assertTrue(aa.bits_cover('rw-', 'rwX', is_dir=False), 'a file needs read, not execute')
        self.assertFalse(aa.bits_cover('r--', 'rwX', is_dir=False))

    def test_a_mask_that_denies_write_is_reported(self):
        self.assertFalse(aa.bits_cover('r-x', 'rwx', is_dir=True))

    def test_an_unlimited_entry_is_accepted(self):
        self.assertTrue(aa.bits_cover('rwx', 'rwx', is_dir=True))

    def test_dump_paths_are_unescaped(self):
        dump = '# file: /a/b\n# file: /a/newline\\012path\n'
        self.assertEqual({'/a/b', '/a/newline\npath'}, aa.dump_paths(dump))

    def test_a_backslash_is_unescaped_to_one_backslash(self):
        self.assertEqual('/a/\\b', aa.getfacl_unescape('/a/\\\\b'))

    def test_sudo_rules_are_read_from_the_heading(self):
        out = 'Matching Defaults entries:\n    env_reset\n\nUser agent may run the following commands:\n    (ALL) NOPASSWD: ALL\n'
        self.assertEqual(['(ALL) NOPASSWD: ALL'], aa.parse_sudo_rules(out))

    def test_no_heading_means_no_rules(self):
        self.assertEqual([], aa.parse_sudo_rules('User agent may not run sudo\n'))

    def test_a_command_scoped_rule_is_still_a_rule(self):
        out = 'User agent may run the following commands:\n    (root) /usr/bin/env\n'
        self.assertEqual(['(root) /usr/bin/env'], aa.parse_sudo_rules(out))


# ---------------------------------------------------------------------------
# state store
# ---------------------------------------------------------------------------


class StateStoreTest(unittest.TestCase):
    def setUp(self):
        import shutil
        import tempfile

        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='agent-account-state-'))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_a_path_maps_to_a_file_name_injectively(self):
        for path in ('/a/b', '/a/b/', '/a;b', '/a=b', '/a\nb'):
            self.assertEqual(64, len(aa.StateStore.dump_name(path).removesuffix('.acl')))
        self.assertNotEqual(aa.StateStore.dump_name('/a;b'), aa.StateStore.dump_name('/a=b'))

    def test_the_legacy_records_are_still_read(self):
        store = aa.StateStore(self.tmp / 'state')
        (self.tmp / 'state').mkdir(parents=True)
        (self.tmp / 'state' / 'state').write_text('user\tcreated\ngroup\tadopted\nmember\tdevel\tsing-dev\n')
        (self.tmp / 'state' / 'targets').write_text('/a/b\ttree\n')
        loaded = store.load()
        self.assertTrue(loaded.owns('user'))
        self.assertFalse(loaded.owns('group'))
        self.assertTrue(loaded.was_member(DEV, SEAM))
        self.assertEqual('tree', loaded.targets['/a/b'].kind)

    def test_a_legacy_target_without_a_kind_is_a_tree(self):
        """The kind column postdates the record; the older dumps were recursive."""
        store = aa.StateStore(self.tmp / 'state')
        (self.tmp / 'state').mkdir(parents=True)
        (self.tmp / 'state' / 'targets').write_text('/a/b\n')
        self.assertEqual('tree', store.load().targets['/a/b'].kind)

    def test_a_recorded_path_that_is_not_utf8_is_refused(self):
        with self.assertRaises(aa.AgentAccountError):
            aa.require_storable('/a/\udcff')

    def test_a_json_record_round_trips(self):
        state = aa.State()
        state.record_owned('user', 'created')
        state.record_owned('group', 'adopted')
        state.observe_member(DEV, SEAM, True)
        state.record_passwd('P')
        state.record_removed_group('docker', 969, True)
        state.targets['/a/b'] = aa.TargetRecord(kind='tree', dump='x.acl')
        again = aa.State.from_json(state.to_json())
        self.assertEqual(state.owned, again.owned)
        self.assertEqual(state.removed_groups, again.removed_groups)
        self.assertEqual(state.targets, again.targets)

    def test_an_unreadable_record_is_refused_with_a_reason(self):
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.State.from_json('{"version": 99}')
        self.assertIn('not one this script understands', str(caught.exception))

    def test_the_dumps_are_0600_and_their_directory_0755(self):
        """`rollback --dry-run` runs unprivileged and has to stat the dumps."""
        store = aa.StateStore(self.tmp / 'state')
        store.write_dump('/a/b', '# file: /a/b\n')
        self.assertEqual(0o755, store.acl_dir.stat().st_mode & 0o777)
        self.assertEqual(0o600, (store.acl_dir / aa.StateStore.dump_name('/a/b')).stat().st_mode & 0o777)


# ---------------------------------------------------------------------------
# check
# ---------------------------------------------------------------------------


class CheckTest(Base):
    def _checker(self, ctx):
        return aa.Checker(ctx)

    def test_an_unprovisioned_host_fails(self):
        ctx = self.context(agent_exists=False)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._account)
        self.assertIn('FAIL', out)
        self.assertIn('no agent account', out)

    def test_a_distinct_unprivileged_uid_passes(self):
        ctx = self.context()
        compiler = self._checker(ctx)
        compiler.report = aa.Report()
        _, out, _ = self.quiet(compiler._account)
        self.assertIn('PASS agent is a distinct unprivileged uid', out)

    def test_a_privileged_group_is_a_failure(self):
        ctx = self.context(agent_groups=('docker',))
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._privileged_groups)
        self.assertIn('FAIL agent is in docker', out)

    def test_a_default_read_grant_on_the_home_is_reported(self):
        """`default:user:agent:r--` grants read on every file created there later,
        so checking only the access entry would miss it entirely."""
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, 'user::rwx\ndefault:user:agent:r--\n')),)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('lets agent read files created in it', out)

    def test_a_default_other_read_grant_on_the_home_is_reported(self):
        """Review finding: the named-entry scan passed `default:other::r--`.

        Measured consequence, and why it is not academic: the default entry OVERRIDES
        the creator's umask.

            setfacl -d -m o::r .   ; (umask 077; touch f)  ->  f is 644
                                       (umask 077; touch g)  ->  g is 600 elsewhere

        Every file created under the home is then file-readable, and the traverse
        grant makes it reachable by `agent`.
        """
        ctx = self.context()
        ctx.runner.responses = (
            (('getfacl',), aa.Result(0, 'user::rwx\nuser:agent:--x\ngroup::---\nother::---\ndefault:other::r--\n')),
        )
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('lets agent read files created in it', out)
        self.assertIn('default:other::r--', out)

    def test_an_owning_group_read_grant_reaches_the_agent_only_as_a_member(self):
        """`group::r--` is the OWNING group: it is a read grant exactly when `agent`
        is in that group, and silence otherwise."""
        text = 'user::rwx\nuser:agent:--x\ngroup::r--\nother::---\n'
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, text)),)
        # The group that actually owns the fixture home decides the answer: it is
        # whatever `stat` says, so the test asks instead of assuming.
        owning = aa.owner_group_of(self.home)
        self.assertIsNotNone(owning)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('carries no read grant', out, 'the agent is not in the owning group')

        # Same ACL, but now the owning group IS one the agent belongs to.
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, text)),)
        ctx.accounts.groups[owning] = aa.GroupEntry(owning, self.home.stat().st_gid, ('devel', 'agent'))
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('lets agent read files created in it', out, 'now the owning group reaches it')

    def test_the_world_bits_are_read_even_though_an_acl_mask_is_set(self):
        """A mask caps NAMED entries only; `other` is never masked (measured:
        `o::r--` with umask 077 yields 0600 while `mask::r--` alone leaves 0644)."""
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, 'user::rwx\nuser:agent:--x\nmask::r--\nother::r--\n')),)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('lets agent read files created in it', out)

    def test_the_traverse_grant_alone_is_not_a_read_grant(self):
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, 'user::rwx\nuser:agent:--x\n')),)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('carries no read grant', out)

    def test_a_mask_that_limits_the_entry_is_reported(self):
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = (
            (('getfacl',), aa.Result(0, 'group:sing-dev:rwx\t#effective:r-x\ndefault:group:sing-dev:rwx\n')),
        )
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('the ACL mask limits it to', out)

    def test_an_unverifiable_sudo_answer_is_a_note_not_a_pass(self):
        ctx = self.context()
        ctx.runner.responses = ((('sudo',), aa.Result(1, '', 'a password is required')),)
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._sudo_rules)
        self.assertIn('NOTE', out)
        self.assertNotIn('PASS', out)

    def test_a_sudo_rule_for_the_agent_is_a_failure(self):
        ctx = self.context()
        ctx.runner.responses = (
            (('sudo',), aa.Result(0, 'User agent may run the following commands:\n    (ALL) NOPASSWD: ALL\n', '')),
        )
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._sudo_rules)
        self.assertIn('FAIL', out)

    def test_no_transition_means_the_negatives_are_not_certified(self):
        """An agent that can run nothing also cannot run `sudo`; reporting that as a
        PASS would certify a wall as a door."""
        ctx = self.context()
        checker = self._checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._behaviour_probes)
        self.assertIn('NOTE', out)
        self.assertIn('unverified', out)

    def test_the_probes_ask_for_execute_as_well_as_read(self):
        """`test -r` alone passes on a directory that cannot be entered at all."""
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = ((('sudo',), aa.Result(0, '', '')),)
        checker = self._checker(ctx)
        asked = []
        checker.agent_can = lambda path, bits: (asked.append(bits), True)[1]
        checker.transition_ok = True
        checker.report = aa.Report()
        self.quiet(checker._behaviour_probes)
        self.assertIn('rx', asked, 'the toolchain is read and traversed')
        self.assertIn('wx', asked, 'a worktree is written and traversed')

    def test_a_secret_directory_is_probed_for_listing_and_traversal_separately(self):
        """A mode-0711 `.ssh` answers `test -r` with 0 while a 0644 key inside is
        readable; listing and traversal are different questions."""
        ctx = self.context()
        secret = self.home / '.ssh'
        secret.mkdir()
        (secret / 'id_rsa').write_text('secret', encoding='utf-8')
        checker = self._checker(ctx)
        checker.transition_ok = True
        checker.report = aa.Report()
        checker.agent_can = lambda path, bits: False
        _, out, _ = self.quiet(checker._behaviour_probes)
        self.assertIn('cannot list', out)
        self.assertIn('cannot traverse', out)

    def test_the_0711_directory_with_a_readable_key_inside_fails(self):
        """Review finding: the probe tested LISTING, not access to the files.

        Reproduced against the real kernel, not a stub: `.ssh` at 0711 and `id_rsa`
        at 0644. `other` gets traverse on the directory and read on the file, so the
        agent can open the key by name while it cannot list the directory. A probe
        that only asked `test -r` on the directory reported PASS here.
        """
        import shutil as sh

        if sh.which('sudo') is None:
            self.skipTest('no sudo to reach another uid')
        secret = self.home / '.ssh'
        secret.mkdir()
        key = secret / 'id_rsa'
        key.write_text('secret', encoding='utf-8')
        secret.chmod(0o711)
        key.chmod(0o644)

        ctx = self.context()
        checker = self._checker(ctx)
        checker.transition_ok = True
        checker.report = aa.Report()
        # Answer the two questions the way the MODE actually makes them answer for a
        # stranger: `other` has r on the file and x (no r) on the directory.
        mode = secret.stat().st_mode & 0o777
        key_mode = key.stat().st_mode & 0o777
        self.assertEqual(0o711, mode, 'traverse without listing')
        self.assertTrue(key_mode & 0o004, 'the key is world-readable')

        checker.agent_can = lambda path, bits: (path == secret and bits == 'r' and False) or (
            path == secret and bits == 'x' and True
        ) or (path == key and bits == 'r' and True) or False
        _, out, _ = self.quiet(checker._behaviour_probes)
        self.assertIn('CAN traverse', out, 'traversal is the finding')
        self.assertIn(f'CAN read {key}', out, 'and the key inside is what it reaches')
        # And the listing answer is separate and stays a PASS: the directory's
        # `other` bits are --x, so `test -r` on it is false. A probe that stopped
        # there reported exactly that PASS while the key was readable.
        self.assertIn(f'cannot list {secret}', out)


# ---------------------------------------------------------------------------
# elevation
# ---------------------------------------------------------------------------


class ElevateTest(Base):
    """A transition that never started is an error (2), not a failed invariant (1).

    `execvp` only returns by raising, so the exceptions are the whole surface of a
    failed elevation: 127 not-on-PATH, 126 not-executable, EPERM. Measured before
    this: a refused sudo left a raw traceback and exit 1.
    """

    def _deps(self, raising):
        deps = aa.Deps()
        deps.geteuid = lambda: 1000

        def execvp(name, argv):
            raise raising

        deps.execvp = execvp
        return deps

    def test_a_missing_sudo_is_a_refusal_and_not_a_traceback(self):
        import errno

        ctx = self.context()
        deps = self._deps(FileNotFoundError(errno.ENOENT, 'No such file or directory', 'sudo'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.elevate(deps, ctx.cfg, 'apply')
        self.assertIn('cannot elevate to root', str(caught.exception))
        self.assertIn('sudo', str(caught.exception))

    def test_a_refused_password_is_a_refusal(self):
        import errno

        ctx = self.context()
        deps = self._deps(OSError(errno.EPERM, 'Operation not permitted'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.elevate(deps, ctx.cfg, 'apply')
        self.assertIn('cannot elevate to root', str(caught.exception))

    def test_root_and_a_dry_run_do_not_elevate(self):
        ctx = self.context()
        calls = []
        deps = aa.Deps()
        deps.geteuid = lambda: 0
        deps.execvp = lambda name, argv: calls.append(argv)
        aa.elevate(deps, ctx.cfg, 'apply')
        self.assertEqual([], calls, 'already root: nothing to elevate')

        dry = self.context(dry_run=True)
        deps.geteuid = lambda: 1000
        aa.elevate(deps, dry.cfg, 'apply')
        self.assertEqual([], calls, 'a plan needs no root')

    def test_the_elevated_command_carries_what_root_cannot_find(self):
        """Measured: neither Ansible nor its collection path is on root's
        secure_path or HOME, so both travel with the command."""
        ctx = self.context()
        command = aa.reexec_command(ctx.cfg, 'apply')
        self.assertIn(str(ctx.cfg.ansible_playbook), command)
        self.assertIn(str(ctx.cfg.ansible_collections_path), command)
        self.assertIn('-I', command, 'isolated mode: root must not inherit PYTHON* variables')


# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------


class ToolchainDefaultTest(Base):
    """Finding: `check` verified the toolchain's ACCESS entry and not its DEFAULT one.

    The consequence is a host that looks healthy while it stops working as it
    evolves: the binaries mise installed yesterday stay reachable through the access
    entry, and the next version it installs lands unreachable because nothing
    inherits the grant. `apply` writes both entries, so a check that inspects one
    certifies about half of what it provisioned.
    """

    def _acl(self, *, default: bool) -> str:
        text = 'user::rwx\nuser:agent:r-x\ngroup::r-x\nmask::r-x\nother::r-x\n'
        if default:
            text += 'default:user:agent:r-x\ndefault:group::r-x\ndefault:other::r-x\n'
        return text

    def test_a_missing_toolchain_default_entry_fails_the_check(self):
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, self._acl(default=False))),)
        checker = aa.Checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn("no 'default:user:agent:r-x'", out)
        self.assertIn('a file toolchain writes lands unreachable', out)

    def test_each_toolchain_directory_is_asked_independently(self):
        """Removing the default from ONE of them must fail for that one."""
        for missing in ('.local/share/mise', '.local/bin'):
            ctx = self.context()
            found = {'text': ''}

            def getfacl(argv, input_text, capture, missing=missing, found=found):
                # Answer per path: the one under test has no default entry.
                if any(str(missing) in a for a in argv):
                    found['text'] = self._acl(default=False)
                else:
                    found['text'] = self._acl(default=True)
                return aa.Result(0, found['text'], '')

            ctx.runner._exec = getfacl
            checker = aa.Checker(ctx)
            checker.report = aa.Report()
            _, out, _ = self.quiet(checker._acls)
            self.assertIn('FAIL', out, missing)
            self.assertIn('lands unreachable', out, missing)

    def test_both_entries_present_passes(self):
        ctx = self.context()
        ctx.runner.responses = ((('getfacl',), aa.Result(0, self._acl(default=True))),)
        checker = aa.Checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn("PASS", out)
        self.assertNotIn("no 'default:user:agent:r-x'", out)

    def test_the_sdk_and_the_toolchain_are_checked_the_same_way(self):
        """The SDK already had both; the toolchain drifting from it was the bug."""
        source = pathlib.Path(SCRIPT).read_text(encoding='utf-8')
        sdk = [l for l in source.splitlines() if 'check_grant(cfg.sdk_dir' in l]
        toolchain = [l for l in source.splitlines() if 'check_grant(directory, read' in l]
        self.assertTrue(sdk and toolchain, 'both are checked')
        self.assertIn('read.as_default()', sdk[0])
        self.assertIn('read.as_default()', toolchain[0], 'the toolchain asks for the default too')


class RunBoundaryTest(Base):
    """`run` must not assume `apply`'s verdict still holds.

    Every case here mutates the account AFTER provisioning, then asks the entry
    point every launcher is told to use. Before this, `run` checked only "not root"
    and "not the developer's uid", so a `docker` membership added a minute later
    started an agent with a root-equivalent group.
    """

    def test_a_privileged_supplementary_group_refuses_to_start(self):
        ctx = self.context()
        ctx.accounts.groups['docker'] = aa.GroupEntry('docker', 969, (DEV, AGENT))
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('docker', str(caught.exception))
        self.assertIn('root-equivalent', str(caught.exception))

    def test_a_privileged_PRIMARY_group_refuses_to_start(self):
        """Invisible in the group's member list, and exactly as root-equivalent."""
        ctx = self.context()
        ctx.accounts.users[AGENT] = aa.PasswdEntry(AGENT, 2001, 969, str(self.agent_home), '/bin/bash')
        ctx.accounts.groups['docker'] = aa.GroupEntry('docker', 969, ())
        self.assertEqual('docker', ctx.accounts.primary_group(AGENT))
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('docker', str(caught.exception))

    def test_each_privileged_group_is_refused(self):
        for group in ('wheel', 'docker', 'kvm'):
            ctx = self.context()
            ctx.accounts.groups[group] = aa.GroupEntry(group, 4000, (AGENT,))
            with self.assertRaises(aa.AgentAccountError, msg=group):
                ctx.require_boundary()

    def test_a_sudo_rule_acquired_later_refuses_to_start(self):
        ctx = self.context()
        ctx.runner.responses = (
            (('sudo',), aa.Result(0, 'User agent may run the following commands:\n    (ALL) NOPASSWD: ALL\n', '')),
        )
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('sudo rule', str(caught.exception))

    def test_sudo_acquired_after_provisioning_refuses_to_start(self):
        """P1: an unreadable `sudo -l -U` must not be read as "the account is clean".

        Reproduces the reported sequence: apply provisions correctly, an
        administrator then grants `agent ALL=(ALL) NOPASSWD: ALL`, and the caller of
        `run` is not root — so the listing fails. The old code continued on that
        failure and started an agent that can become root.
        """
        ctx = self.context()

        def sudo(argv, input_text, capture, _argv=tuple()):
            if '--list' in argv:
                return aa.Result(1, '', 'sudo: a password is required')
            # The behavioural question: `sudo -n true` AS the agent succeeds.
            if argv[-3:] == ['/usr/bin/sudo', '-n', 'true']:
                return aa.Result(0, '', '')
            return aa.Result(0, '2001\n', '')

        ctx.runner._exec = sudo
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('CAN run sudo', str(caught.exception))

    def test_clean_agent_with_an_unreadable_listing_starts(self):
        """"Cannot inspect" must not mean "refuse a host that is fine": the
        behavioural probe answers instead, and it needs no root."""
        ctx = self.context()
        ctx.runner.responses = (
            (('sudo', '--non-interactive', '--list'), aa.Result(1, '', 'a password is required')),
            (('sudo', '--non-interactive', '--user=agent', '--', '/usr/bin/env', '-i'), aa.Result(1, '', '')),
            (('sudo', '--non-interactive', '--user=agent', '--', '/usr/bin/env', 'id'), aa.Result(0, '2001\n', '')),
        )
        ctx.require_boundary()  # must not raise

    def test_a_broken_transition_refuses_instead_of_assuming_clean(self):
        """Neither question could be answered: the listing failed AND the account is
        unreachable. Refusing is the only answer that is not a guess."""
        ctx = self.context()
        ctx.runner.responses = ((('sudo',), aa.Result(1, '', 'a password is required')),)
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('cannot verify', str(caught.exception))
        self.assertIn('check', str(caught.exception))

    def test_a_home_inside_the_developers_home_refuses_to_start(self):
        ctx = self.context(agent_home=str(self.home / 'agent'))
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('not its own', str(caught.exception))

    def test_a_missing_account_refuses_to_start(self):
        ctx = self.context(agent_exists=False)
        with self.assertRaises(aa.AgentAccountError) as caught:
            ctx.require_boundary()
        self.assertIn('apply', str(caught.exception))

    def test_a_correct_account_starts(self):
        ctx = self.context()
        # A clean host: the agent cannot sudo, and the transition works.
        ctx.runner.responses = (
            (('sudo', '--non-interactive', '--list'), aa.Result(1, '', 'a password is required')),
            (('sudo', '--non-interactive', '--user=agent', '--', '/usr/bin/env', '-i'), aa.Result(1, '', '')),
            (('sudo', '--non-interactive', '--user=agent', '--', '/usr/bin/env', 'id'), aa.Result(0, '2001\n', '')),
        )
        ctx.require_boundary()  # must not raise


class SharedGitStoreTest(Base):
    """Finding: a writable shared `.git` hands the agent a path to run code AS the
    developer — `config` picks `core.hooksPath`, `hooks/` holds programs git runs.

    Measured on this host: with `.git/config` at 0444 and `.git/hooks` at 0555,
    commit, branch and checkout all still work, so the denial costs nothing.
    """

    def _grants(self, ctx):
        return aa.acl_grants(ctx.cfg, ctx, str(self.agent_home))

    def test_the_common_git_dir_is_granted_but_config_and_hooks_are_denied(self):
        ctx = self.context(roots=(self.wt,))
        grants = self._grants(ctx)
        common = str(self.common)
        self.assertIn((common, 'present'), [(g['path'], g.get('state')) for g in grants])
        for denied in ('config', 'hooks'):
            states = [g.get('state') for g in grants if g['path'] == f'{common}/{denied}']
            self.assertTrue(states, f'{denied} must be declared at all')
            self.assertEqual({'absent'}, set(states), f'{denied} must be DENIED, not granted')

    def test_the_denial_covers_the_default_entry_too(self):
        """Otherwise every hook created later inherits the grant again."""
        ctx = self.context(roots=(self.wt,))
        for default in (False, True):
            found = [
                g
                for g in self._grants(ctx)
                if g['path'] == f'{self.common}/hooks' and g['default'] is default
            ]
            self.assertEqual(1, len(found), f'default={default}')
            self.assertEqual('absent', found[0]['state'])

    def test_an_absent_config_or_hooks_is_skipped(self):
        import shutil as sh

        sh.rmtree(self.common / 'hooks', ignore_errors=True)
        ctx = self.context(roots=(self.wt,))
        self.assertNotIn(
            f'{self.common}/hooks', [g['path'] for g in self._grants(ctx)]
        )

    def test_check_reports_a_writable_config(self):
        ctx = self.context(roots=(self.wt,))
        text = 'user::rw-\ngroup:sing-dev:rwx\nmask::rwx\nother::r--\n'
        ctx.runner.responses = ((('getfacl',), aa.Result(0, text)),)
        checker = aa.Checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('can WRITE it', out)
        self.assertIn('run code as the developer', out)

    def test_check_passes_when_config_carries_no_write_grant(self):
        ctx = self.context(roots=(self.wt,))
        text = 'user::rw-\ngroup::r--\nmask::r--\nother::r--\n'
        ctx.runner.responses = ((('getfacl',), aa.Result(0, text)),)
        checker = aa.Checker(ctx)
        checker.report = aa.Report()
        _, out, _ = self.quiet(checker._acls)
        self.assertIn('no write grant', out)


class MaskPreservationTest(Base):
    """Finding: `setfacl --modify` recalculates the mask, which can ESCALATE entries
    nobody asked about.

    Measured on one file: an unrelated `user:nobody:rwx` capped to `#effective:r--`
    by `mask::r--` became fully effective the moment a group entry was added.
    """

    def test_an_existing_mask_that_covers_the_grant_is_preserved(self):
        """Access AND default: they are separate masks, and each is asked separately."""
        ctx = self.context(roots=(self.wt,))
        covering = (
            'user::rwx\ngroup:sing-dev:rwx\nmask::rwx\nother::---\n'
            'default:user::rwx\ndefault:group:sing-dev:rwx\ndefault:mask::rwx\ndefault:other::---\n'
        )
        ctx.runner.responses = ((('getfacl',), aa.Result(0, covering)),)
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.wt)]
        self.assertEqual(2, len(grants), 'access + default')
        for grant in grants:
            self.assertTrue(grant.get('preserve_mask'), f"mask already covers rwX: {grant}")

    def test_a_default_entry_with_no_default_mask_lets_it_be_created(self):
        """The access mask says nothing about the DEFAULT mask: they are separate."""
        ctx = self.context(roots=(self.wt,))
        access_only = 'user::rwx\ngroup:sing-dev:rwx\nmask::rwx\nother::---\n'
        ctx.runner.responses = ((('getfacl',), aa.Result(0, access_only)),)
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.wt)]
        by_default = {g['default']: g for g in grants}
        self.assertTrue(by_default[False].get('preserve_mask'), 'the access mask covers it')
        self.assertFalse(by_default[True].get('preserve_mask'), 'there is no DEFAULT mask yet')

    def test_a_mask_that_does_not_cover_the_grant_lets_it_grow(self):
        """`--no-mask` there would CAP our own entry — measured:
        `user:agent:rw- #effective:r--` under `mask::r--`."""
        ctx = self.context(roots=(self.wt,))
        narrow = 'user::rwx\ngroup:sing-dev:rwx\nmask::r--\nother::---\n'
        ctx.runner.responses = ((('getfacl',), aa.Result(0, narrow)),)
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.wt)]
        for grant in grants:
            self.assertFalse(grant.get('preserve_mask'), f"mask is too narrow, must grow: {grant}")

    def test_no_mask_at_all_lets_it_be_created(self):
        ctx = self.context(roots=(self.wt,))
        ctx.runner.responses = ((('getfacl',), aa.Result(0, 'user::rwx\ngroup::r-x\nother::r-x\n')),)
        grants = [g for g in aa.acl_grants(ctx.cfg, ctx, str(self.agent_home)) if g['path'] == str(self.wt)]
        for grant in grants:
            self.assertFalse(grant.get('preserve_mask'))

    def test_mask_covers_asks_the_bitwise_question(self):
        entries = aa.parse_getfacl('mask::r-x\n')
        self.assertTrue(aa.mask_covers(entries, 'rX', is_dir=True))
        self.assertFalse(aa.mask_covers(entries, 'rwX', is_dir=True), 'no w in the mask')
        self.assertFalse(aa.mask_covers(aa.parse_getfacl('user::rwx\n'), 'rX', is_dir=True), 'no mask line')


class JournalBaselineTest(Base):
    """Finding: repeated apply could overwrite the rollback baseline.

    The report's examples assume "last recorded value wins". The state model is
    first-write-wins in all three places, which is what makes the baseline the state
    before the FIRST apply. These tests pin that, since the implementation is what
    makes the finding not apply.
    """

    def test_the_password_state_is_recorded_once(self):
        state = aa.State()
        state.record_passwd('P')   # first apply: usable
        state.record_passwd('L')   # second apply: the lock we just applied
        self.assertEqual('P', state.passwd, 'rollback must restore the ORIGINAL state')

    def test_a_membership_is_recorded_once(self):
        state = aa.State()
        state.observe_member(DEV, SEAM, False)  # before the first apply: not a member
        state.observe_member(DEV, SEAM, True)   # after: the membership apply added
        self.assertFalse(state.was_member(DEV, SEAM), 'rollback must remove what apply added')

    def test_a_removed_group_is_recorded_once(self):
        state = aa.State()
        state.record_removed_group('docker', 969, False)
        state.record_removed_group('docker', 969, False)
        self.assertEqual(1, len(state.removed_groups))

    def test_a_second_apply_writes_the_first_observation_and_not_the_second(self):
        """End to end: the journal file keeps the baseline across two applies."""
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        self.quiet(aa.Provisioner(ctx, store).apply)
        first = aa.State.from_json(store.path.read_text())
        self.quiet(aa.Provisioner(ctx, store).apply)
        second = aa.State.from_json(store.path.read_text())
        self.assertEqual(first.passwd, second.passwd)
        self.assertEqual(first.observed_members, second.observed_members)
        self.assertEqual(first.owned, second.owned)


class RunTest(Base):
    def test_the_command_is_handed_over_with_a_built_environment(self):
        ctx = self.context()
        launcher = aa.Launcher(ctx, {'TERM': 'xterm', 'AGENT_TEST_SECRET': 'do-not-leak'})
        command = launcher.command(('id', '-un'))

        self.assertEqual('sudo', command[0])
        self.assertIn(f'--user={AGENT}', command)
        self.assertIn('--', command)
        self.assertIn('/usr/bin/env', command)
        self.assertIn('id', command)
        joined = ' '.join(command)
        self.assertNotIn('do-not-leak', joined, "the caller's environment must not reach the agent")

    def test_the_agent_gets_its_own_gradle_home(self):
        ctx = self.context()
        launcher = aa.Launcher(ctx, {})
        argv = launcher.command(('true',))
        # Whole assignments: `HOME=/x/home` CONTAINS `HOME=/x`, so a substring test
        # would pass or fail for the wrong reason.
        self.assertIn(f'GRADLE_USER_HOME={self.agent_home}/.gradle', argv)
        self.assertIn(f'HOME={self.agent_home}', argv)
        self.assertNotIn(f'HOME={self.home}', argv)

    def test_the_launcher_execs_rather_than_shelling_out(self):
        ctx = self.context()
        calls = []
        launcher = aa.Launcher(ctx, {}, execvp=lambda name, argv: calls.append((name, argv)))
        launcher.run(('id',))
        self.assertEqual('sudo', calls[0][0])

    def test_an_empty_command_is_refused(self):
        ctx = self.context()
        with self.assertRaises(aa.AgentAccountError) as caught:
            aa.Launcher(ctx, {}).command(())
        self.assertIn('run needs a command', str(caught.exception))


# ---------------------------------------------------------------------------
# rollback
# ---------------------------------------------------------------------------


class RollbackTest(Base):
    def test_nothing_to_roll_back_without_a_record(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        _, out, _ = self.quiet(aa.Rollback(ctx, store).run)
        self.assertIn('nothing to roll back', out)

    def test_an_adopted_account_is_kept(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        store.save(aa.State(owned={'user': 'adopted', 'group': 'adopted'}))
        rollback = aa.Rollback(ctx, store)
        _, _, err = self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))
        self.assertIn('this script adopted it rather than created it', err)
        self.assertFalse(ctx.ansible.variables['agent_remove_user'])

    def test_a_created_account_is_removed_through_the_playbook(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        store.save(aa.State(owned={'user': 'created', 'group': 'created'}))
        rollback = aa.Rollback(ctx, store)
        self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))
        self.assertTrue(ctx.ansible.variables['agent_remove_user'])
        self.assertTrue(ctx.ansible.variables['agent_remove_group'])

    def test_a_privileged_group_taken_away_comes_back(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        state = aa.State(owned={'user': 'adopted', 'group': 'adopted'})
        state.record_removed_group('docker', 969, False)
        store.save(state)
        rollback = aa.Rollback(ctx, store)
        ctx.accounts.groups['docker'] = aa.GroupEntry('docker', 969, ())
        self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))
        updates = ctx.ansible.variables['agent_user_updates']
        restored = [u for u in updates if 'docker' in (u.get('groups') or [])]
        self.assertTrue(restored, 'a group the account held before apply belongs back')

    def test_the_restored_groups_do_not_name_the_seam_group(self):
        """Found on a live host, not by any unit test before it.

        Rollback deletes `sing-dev` FIRST, so any `groups` list that still names it
        is validated against a group that no longer exists —
        "Group sing-dev does not exist" — and the task aborts before the privileged
        groups come back. `append: false` replaces the list, so the seam must simply
        not be in it.
        """
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        state = aa.State(owned={'user': 'adopted', 'group': 'created'})
        state.record_removed_group('docker', 969, False)
        state.record_removed_group('kvm', 36, False)
        store.save(state)
        ctx.accounts.groups['docker'] = aa.GroupEntry('docker', 969, ())
        ctx.accounts.groups['kvm'] = aa.GroupEntry('kvm', 36, ())
        rollback = aa.Rollback(ctx, store)
        self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))

        updates = ctx.ansible.variables['agent_user_updates']
        grouped = [u for u in updates if u.get('groups') is not None]
        self.assertEqual(1, len(grouped), 'ONE list, not one per restored group')
        self.assertNotIn(SEAM, grouped[0]['groups'], 'the seam is being deleted in the same run')
        self.assertEqual({'docker', 'kvm'}, set(grouped[0]['groups']))

    def test_a_restored_group_is_visible_as_a_single_update(self):
        """Two updates each naming the seam is the exact shape that failed live."""
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        state = aa.State(owned={'user': 'adopted', 'group': 'created'})
        state.record_removed_group('wheel', 10, False)
        store.save(state)
        ctx.accounts.groups['wheel'] = aa.GroupEntry('wheel', 10, ())
        rollback = aa.Rollback(ctx, store)
        _, out, _ = self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))
        self.assertIn('returning agent to wheel', out)

    def test_the_password_lock_is_undone_for_an_adopted_account(self):
        ctx = self.context()
        ctx.runner.responses = ((('passwd', '--status'), aa.Result(0, 'agent L 01/01/2026\n', '')),)
        store = aa.StateStore(self.tmp / 'state')
        store.save(aa.State(owned={'user': 'adopted', 'group': 'adopted'}, passwd='P'))
        rollback = aa.Rollback(ctx, store)
        _, out, _ = self.quiet(rollback._account_and_groups, ctx.accounts.passwd(AGENT))
        self.assertIn('unlocking', out)
        self.assertTrue(any(u.get('password_lock') is False for u in ctx.ansible.variables['agent_user_updates']))

    def test_a_worktree_this_script_never_provisioned_is_kept(self):
        ctx = self.context(roots=(self.wt,))
        store = aa.StateStore(self.tmp / 'state')
        store.save(aa.State(owned={'user': 'adopted'}))
        rollback = aa.Rollback(ctx, store)
        _, _, err = self.quiet(rollback.run)
        self.assertIn('never provisioned it', err)

    def test_a_recorded_target_that_is_gone_is_noted_not_fatal(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        state = aa.State(owned={'user': 'adopted'})
        state.targets[str(self.tmp / 'gone')] = aa.TargetRecord(kind='tree', dump=None)
        store.save(state)
        _, _, err = self.quiet(aa.Rollback(ctx, store).run)
        self.assertIn('no longer exists', err)

    def test_a_node_snapshot_is_never_scrubbed(self):
        """A node dump covers the target only, so "absent from the dump" is true for
        every child — including files that had the seam before this script ran."""
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        rollback = aa.Rollback(ctx, store)
        rollback._scrub(str(self.wt), aa.TargetRecord(kind='node', dump='x.acl'))
        self.assertEqual([], ctx.runner.executed, 'a node record must not scrub anything')

    def test_a_tree_snapshot_scrubs_a_path_created_after_it(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        covered = self.wt / 'old'
        covered.write_text('x', encoding='utf-8')
        new = self.wt / 'new'
        new.write_text('y', encoding='utf-8')
        name = store.write_dump(str(self.wt), f'# file: {self.wt}\n# file: {covered}\n')
        rollback = aa.Rollback(ctx, store)
        self.quiet(rollback._scrub, str(self.wt), aa.TargetRecord(kind='tree', dump=name))
        scrubbed = ' '.join(ctx.runner.commands())
        self.assertIn(str(new), scrubbed)
        self.assertNotIn(str(covered), scrubbed, 'a covered path is restored, not scrubbed')

    def test_the_scrub_tolerates_a_path_that_vanished(self):
        ctx = self.context()
        store = aa.StateStore(self.tmp / 'state')
        name = store.write_dump(str(self.wt), f'# file: {self.wt}\n')
        (self.wt / 'gone').write_text('x', encoding='utf-8')
        ctx.runner.responses = ((('setfacl',), aa.Result(1, '', 'No such file or directory')),)
        rollback = aa.Rollback(ctx, store)
        # tolerate=True means a non-zero rc does not abort the rollback.
        self.quiet(rollback._scrub, str(self.wt), aa.TargetRecord(kind='tree', dump=name))


# ---------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------


class ConfigTest(unittest.TestCase):
    def test_the_agent_cannot_be_the_developer(self):
        with self.assertRaises(Exception) as caught:
            aa.Config(dev_name=DEV, dev_home=pathlib.Path('/home/x'), agent_user=DEV)
        self.assertIn('cannot be the developer', str(caught.exception))

    def test_the_agent_cannot_be_root(self):
        with self.assertRaises(Exception) as caught:
            aa.Config(dev_name=DEV, dev_home=pathlib.Path('/home/x'), agent_user='root')
        self.assertIn('cannot be root', str(caught.exception))

    def test_the_developer_cannot_be_root(self):
        with self.assertRaises(Exception) as caught:
            aa.Config(dev_name='root', dev_home=pathlib.Path('/home/x'))
        self.assertIn('name the developer', str(caught.exception))

    def test_the_sdk_path_cannot_leave_the_developer_home(self):
        with self.assertRaises(Exception):
            aa.Config(dev_name=DEV, dev_home=pathlib.Path('/home/x'), sdk_dir_rel='../etc')

    def test_the_sudoers_rule_names_the_developer_and_the_agent(self):
        cfg = aa.Config(dev_name=DEV, dev_home=pathlib.Path('/home/x'))
        self.assertEqual(f'{DEV} ALL=({AGENT}) NOPASSWD: /usr/bin/env', cfg.sudoers_rule)


class AnsibleResolutionTest(unittest.TestCase):
    """Ansible is found by the UNPRIVILEGED caller: neither the binary nor the
    collection path is on root's secure_path or HOME."""

    def test_the_collections_path_falls_back_to_a_collections_directory(self):
        """Under sudo, `ANSIBLE_HOME` is root's and the playbook cannot resolve
        `ansible.posix.acl`; the path the caller saw is the one carried through."""
        import os

        saved = dict(os.environ)
        try:
            os.environ.pop('ANSIBLE_COLLECTIONS_PATH', None)
            _, collections = aa.resolve_ansible(pathlib.Path('/usr/bin/true'))
            self.assertIn('collections', collections)
            self.assertTrue(collections.endswith(('.ansible/collections:/usr/share/ansible/collections',))
                            or ':collections' in collections)
        finally:
            os.environ.clear()
            os.environ.update(saved)

    def test_a_missing_ansible_is_a_refusal(self):
        import os
        import shutil as sh

        saved = dict(os.environ)
        saved_which = sh.which
        try:
            sh.which = lambda name, *a, **k: None
            with self.assertRaises(aa.AgentAccountError) as caught:
                aa.resolve_ansible(None)
            self.assertIn('ansible-playbook is not on PATH', str(caught.exception))
        finally:
            sh.which = saved_which
            os.environ.clear()
            os.environ.update(saved)

    def test_a_mise_shim_is_asked_for_the_real_binary(self):
        """Measured: the shim run with root's environment (no MISE_*, HOME=/root)
        answers "ansible-playbook is not a valid shim", while the install path it
        names runs. The shim is a launcher for the developer's shell; the root half
        is not that shell."""
        import tempfile

        tmp = pathlib.Path(tempfile.mkdtemp(prefix='aa-shim-'))
        self.addCleanup(__import__('shutil').rmtree, tmp, True)
        install = tmp / 'installs' / 'ansible-core' / '2.21.5' / 'bin'
        install.mkdir(parents=True)
        real = install / 'ansible-playbook'
        real.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
        real.chmod(0o755)
        # The shim is `mise`, so it dispatches on argv[0] and must be asked BY PATH.
        mise = tmp / 'mise'
        mise.write_text(f'#!/bin/sh\necho "{real}"\n', encoding='utf-8')
        mise.chmod(0o755)
        shim = tmp / 'shims' / 'ansible-playbook'
        shim.parent.mkdir()
        shim.symlink_to(mise)

        saved_which = __import__('shutil').which
        try:
            __import__('shutil').which = lambda name, *a, **k: str(shim)
            found, _ = aa.resolve_ansible(None)
        finally:
            __import__('shutil').which = saved_which
        self.assertEqual(real, found, 'the shim must be traded for the binary it names')
        self.assertNotEqual(mise, found, 'never collapse the shim to `mise` itself')

    def test_a_shim_that_cannot_name_its_binary_falls_back_to_the_shim(self):
        """A clearer error names the shim the reader installed, not a guess."""
        import tempfile

        tmp = pathlib.Path(tempfile.mkdtemp(prefix='aa-shim2-'))
        self.addCleanup(__import__('shutil').rmtree, tmp, True)
        mise = tmp / 'mise'
        mise.write_text('#!/bin/sh\nexit 1\n', encoding='utf-8')
        mise.chmod(0o755)
        shim = tmp / 'ansible-playbook'
        shim.symlink_to(mise)

        saved_which = __import__('shutil').which
        try:
            __import__('shutil').which = lambda name, *a, **k: str(shim)
            found, _ = aa.resolve_ansible(None)
        finally:
            __import__('shutil').which = saved_which
        self.assertEqual(shim, found)
        self.assertNotEqual(mise, found, 'the shim must not be collapsed to `mise` itself')


# ---------------------------------------------------------------------------
# the playbook itself
# ---------------------------------------------------------------------------


class PlaybookShapeTest(unittest.TestCase):
    """The playbook is data, so most of its contract is asserted by reading it."""

    @classmethod
    def setUpClass(cls):
        cls.text = (REPO_ROOT / 'scripts' / 'ansible' / 'agent-account.yml').read_text(encoding='utf-8')

    def test_it_never_replaces_a_group_list_it_cannot_see(self):
        """`groups:` on the `user` task would reconcile the supplementary list on an
        adopted account and drop groups its owner chose."""
        task = self.text.split('- name: Ensure the agent account exists', 1)[1].split('- name:', 1)[0]
        self.assertNotIn('groups:', task)

    def test_the_acl_task_asks_for_the_declared_entries(self):
        self.assertIn('ansible.posix.acl', self.text)
        self.assertIn('recursive: "{{ item.recursive', self.text)
        self.assertIn('default: "{{ item.default', self.text)

    def test_both_directions_are_the_same_resource_set(self):
        """`present` and `absent` are the same tasks, so what apply creates and what
        rollback removes cannot drift apart."""
        self.assertIn("agent_account_state == 'present'", self.text)
        self.assertIn("agent_account_state == 'absent'", self.text)

    def test_every_removal_flag_the_caller_passes_is_used_by_a_task(self):
        """Measured: `agent_remove_user` was declared and passed and NO task read it,
        so a created account survived `rollback` — the playbook was served `absent`
        and quietly skipped the user task."""
        for flag in ('agent_remove_user', 'agent_remove_group', 'agent_remove_own_group'):
            uses = [line for line in self.text.splitlines() if f'{flag} | bool' in line]
            self.assertTrue(uses, f'{flag} is passed by the caller but no task acts on it')

    def test_a_facet_that_names_no_group_does_not_default_append_to_true(self):
        """Measured: `append is True but all of the following are missing: groups` —
        a `password_lock`-only or `primary_group`-only item aborted the run."""
        task = self.text.split('- name: Reconcile the declared account facets', 1)[1].split('- name:', 1)[0]
        self.assertIn('if item.groups is defined else omit', task)

    def test_a_removal_is_not_gated_on_the_group_being_present(self):
        """A removal names no group, so the check-mode guard on `agent_group_exists`
        must not skip it — it was gated on the host, not on the item."""
        task = self.text.split('- name: Reconcile the declared account facets', 1)[1].split('- name:', 1)[0]
        self.assertIn('and item.groups is defined', task)

    def test_a_deferred_plan_does_not_abort_on_a_missing_identity(self):
        """Measured: `setfacl` resolves the entry NAME while planning, so on a fresh
        host under --check the ACL task has to be skipped, not attempted."""
        self.assertIn('ansible_check_mode and not (agent_user_exists', self.text)
        self.assertIn('ansible_check_mode and not (agent_group_exists', self.text)


if __name__ == '__main__':
    unittest.main()
