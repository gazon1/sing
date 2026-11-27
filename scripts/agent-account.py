#!/usr/bin/env python3
"""scripts/agent-account.py — the dedicated unprivileged account coding agents run as.

    scripts/agent-account.py apply --dry-run ~/work/wt1        # the plan, no root
    scripts/agent-account.py apply ~/work/wt1 ~/work/wt2       # root: creates it
    scripts/agent-account.py check ~/work/wt1                  # the verdict, no root
    scripts/agent-account.py run -- git status                 # a command AS the agent
    scripts/agent-account.py rollback                          # removes the account

WHY

Today an agent is the developer: uid 1000, the groups `wheel`, `kvm` and `docker`,
and the SELinux domain `unconfined_t`. Every boundary the emulator guard builds
(#3240 protects the emulator install and /dev/kvm, #3241 confines the execution)
starts from a uid that owns the files it is protecting — and `docker` membership
is root-equivalent, so a file permission means nothing against an agent that can
run `docker --privileged -v /:/host`. This script is the "somebody else" the rest
of the guard needs.

WHAT IT CREATES, AND NOTHING ELSE

  user `agent`      no password, so no login; a real /bin/bash, because a coding
                    agent has to run commands
  group `sing-dev`  narrow: the developer and `agent`. It is the collaboration
                    seam for the authorized worktrees
  ACLs              --x to traverse the developer home (path access, never a
                    listing), rX on the Android SDK and the toolchain, and
                    rwX + a default rwX on the authorized worktree roots
  sudoers           one line: the DEVELOPER may re-execute /usr/bin/env AS
                    `agent`, which is what `run` uses. `agent` gets no sudo rule
                    at all, so an agent cannot leave the account.

WHAT IT DELIBERATELY DOES NOT DO

  - It does not share the developer's Gradle home. `run` points GRADLE_USER_HOME
    at the agent's own home: the honest price of "the agent cannot write what the
    developer's build reads".
  - It does not move, copy or re-own the SDK (#3240's job). The SDK is read where
    it lives; when #3240 lands, the traverse grant is no longer needed.
  - It does not change any agent's launcher (docs/architecture/agent-uid.md).

THE ONE COST, STATED

Traversing `$HOME` (--x) lets `agent` open any file under it whose own mode
allows it — `.gitconfig`, `.envrc`, the mode-0644 dotfiles. It does not let it
list the home. The secrets stay shut (the home is 0700, `~/.ssh` and `.gnupg` are
0700), but a dotfile that was unreachable from outside becomes reachable, which is
why the authorized worktree root is recommended outside the home
(`--allow-in-home` opts in, loudly). It goes away with #3240.

Exit codes: 0 every invariant holds, 1 at least one does not, 2 usage/error.

HOW THE WORK IS SPLIT

`scripts/ansible/agent-account.yml` owns the declared system state — the group,
the account, its home and private directories, its memberships, its ACLs, and the
reverse of each. This file owns everything Ansible cannot decide:

  - WHICH resources may be removed. Ansible makes the host match a declaration;
    it cannot know that an `agent` account predated this tool and therefore must
    survive `rollback`. That difference — created vs adopted — is the journal.
  - ACL SNAPSHOTS. `getfacl`'s own output before `apply` writes is the only thing
    that can restore a tree, and it is not a declared state.
  - sudoers OWNERSHIP. The path is shared with whoever else may have written it.
  - The VERDICTS. `check` asks the kernel (access(2), SELinux, mount options) and
    `sudo -l`; none of that is declared state.
  - The TRANSITION. `run` execs `sudo --user` with a built environment.

PORTING NOTES

  - The root half is re-executed as `sudo -- <sys.executable> -I <this file>`.
    `-I` (isolated mode) ignores PYTHON* variables and the user site. sudo's
    secure_path is never consulted for the interpreter, and it is not consulted
    for `ansible-playbook` either: both paths are resolved by the UNPRIVILEGED
    caller and passed through, because a developer-installed Ansible is not on
    root's secure_path.

  - `ANSIBLE_COLLECTIONS_PATH` is passed explicitly for the same reason. Measured:
    under a root-like HOME the playbook fails with "couldn't resolve
    module/action 'ansible.posix.acl'" because Ansible's default collection path
    is `{{ ANSIBLE_HOME }}/collections` and root's ANSIBLE_HOME is not the
    developer's.

  - pydantic validates what comes from outside: the command line (`Config`) and
    the state file (`State`). It cannot carry a lone surrogate, so a path that is
    not valid UTF-8 is refused up front instead of being corrupted in the record.

  - Every subprocess runs with LC_ALL=C (so `sudo -l`, `getfacl`, `passwd -S` are
    parsed in one language) and without GIT_* variables.

  - State is ONE json file, replaced atomically under an flock, plus one `getfacl`
    dump per target: no delimiter, escaping or line format to get wrong. The
    `state` and `targets` files the fish version wrote are still read.

See docs/architecture/agent-uid.md
    docs/decisions/2026-11-26-agents-run-under-a-dedicated-uid.md
    docs/decisions/2026-11-27-the-agent-account-is-declared-in-ansible.md
"""

from __future__ import annotations

import contextlib
import fcntl
import grp
import hashlib
import json
import os
import pwd
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated, Callable, Iterator, Literal, Mapping, Sequence

import click
from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    StringConstraints,
    ValidationError,
    field_validator,
    model_validator,
)

SELF_NAME = sys.argv[0] if sys.argv and sys.argv[0] else "agent-account.py"
SELF_PATH = Path(__file__).resolve()
PLAYBOOK = SELF_PATH.parent / "ansible" / "agent-account.yml"

# Root-equivalent or device-owning. `docker` alone defeats every file permission.
PRIVILEGED_GROUPS = ("wheel", "docker", "kvm")
SUDO_COMMAND = "/usr/bin/env"
DEFAULT_STATE_DIR = Path("/var/lib/agent-account")
DEFAULT_SUDOERS_PATH = Path("/etc/sudoers.d/50-agent-account")


class AgentAccountError(Exception):
    """A refusal or a failed step. The CLI prints it and exits 2."""


def eprint(*args: object, **kwargs: object) -> None:
    print(*args, file=sys.stderr, **kwargs)


def describe_validation_error(error: ValidationError) -> str:
    parts = []
    for item in error.errors(include_url=False):
        where = ".".join(str(part) for part in item["loc"])
        message = item["msg"].removeprefix("Value error, ")
        parts.append(f"{where}: {message}" if where else message)
    return "; ".join(parts)


def _require_utf8(text: str) -> str:
    # The state is JSON, and pydantic cannot carry a lone surrogate (an undecodable
    # byte in a path): it fails on a value and silently garbles a dictionary key.
    # A path that cannot be stored exactly is refused, not recorded wrongly.
    try:
        text.encode("utf-8")
    except UnicodeEncodeError:
        raise ValueError(f"not valid UTF-8, so it cannot be recorded exactly: {text!r}") from None
    return text


def _absolute_utf8(path: Path) -> Path:
    if not path.is_absolute():
        raise ValueError(f"not an absolute path: {path}")
    _require_utf8(str(path))
    return path


def _relative(rel: str) -> str:
    if rel.startswith("/") or ".." in Path(rel).parts:
        raise ValueError(f"must be relative to the developer's home and stay inside it: {rel}")
    return rel


def require_storable(path: str) -> None:
    try:
        _require_utf8(path)
    except ValueError as error:
        raise AgentAccountError(str(error)) from None


SystemName = Annotated[str, StringConstraints(pattern=r"^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")]
AbsolutePath = Annotated[Path, AfterValidator(_absolute_utf8)]
RelativePath = Annotated[str, AfterValidator(_relative)]


# ---------------------------------------------------------------------------
# configuration
# ---------------------------------------------------------------------------


class Config(BaseModel):
    """Everything a verb needs to know, validated ONCE, where it enters."""

    model_config = ConfigDict(frozen=True)

    agent_user: SystemName = "agent"
    agent_group: SystemName = "sing-dev"
    # Where the SDK is, relative to the developer's home: the only place it is on
    # this host (docs/architecture/emulator-launch-inventory.md §2).
    sdk_dir_rel: RelativePath = "Android/Sdk"
    # mise owns java, node, heavy and ktlint; `.local/bin` holds `mise` itself.
    toolchain_rels: tuple[RelativePath, ...] = (".local/share/mise", ".local/bin")
    state_dir: AbsolutePath = DEFAULT_STATE_DIR
    sudoers_path: AbsolutePath = DEFAULT_SUDOERS_PATH
    dev_name: SystemName
    dev_home: AbsolutePath
    roots: tuple[AbsolutePath, ...] = ()
    dry_run: bool = False
    allow_in_home: bool = False
    ansible_playbook: AbsolutePath = Path("/usr/bin/ansible-playbook")
    ansible_collections_path: str = ""

    @model_validator(mode="after")
    def _the_agent_is_somebody_else(self) -> "Config":
        if self.dev_name == "root":
            raise ValueError("name the developer: --dev <user>")
        if self.agent_user == "root":
            raise ValueError("the agent cannot be root")
        if self.agent_user == self.dev_name:
            raise ValueError(f"the agent cannot be the developer ({self.dev_name}) — it is the same identity")
        return self

    @property
    def sudoers_rule(self) -> str:
        return f"{self.dev_name} ALL=({self.agent_user}) NOPASSWD: {SUDO_COMMAND}"

    @property
    def sdk_dir(self) -> Path:
        return self.dev_home / self.sdk_dir_rel

    @property
    def toolchain_dirs(self) -> tuple[Path, ...]:
        return tuple(self.dev_home / rel for rel in self.toolchain_rels)


# ---------------------------------------------------------------------------
# running commands
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Result:
    rc: int
    out: str = ""
    err: str = ""


class Runner:
    """The only way this script changes the host, and the owner of --dry-run.

    `run` changes state: under --dry-run it prints the command and does nothing,
    otherwise it executes it and FAILS when it fails. `capture` only reads and
    always executes, dry-run or not (the plan has to be computed from the host).
    `do` is `run` for steps that are not a command (removing the state files).
    """

    def __init__(self, dry_run: bool = False, environ: Mapping[str, str] | None = None):
        self.dry_run = dry_run
        base = dict(os.environ if environ is None else environ)
        self.env = {k: v for k, v in base.items() if not k.startswith("GIT_")}
        self.env["LC_ALL"] = "C"

    # the single seam a test replaces
    def _exec(self, argv: Sequence[str], input_text: str | None, capture: bool) -> Result:
        data = None if input_text is None else input_text.encode("utf-8", "surrogateescape")
        try:
            proc = subprocess.run(
                list(argv),
                input=data,
                env=self.env,
                stdout=subprocess.PIPE if (capture or data is not None) else None,
                stderr=subprocess.PIPE if (capture or data is not None) else None,
                check=False,
            )
        except FileNotFoundError:
            return Result(127, "", f"{argv[0]}: command not found")
        # Decode with surrogateescape: a path byte that is not valid UTF-8 must not
        # be dropped from `getfacl`'s output on the way to a snapshot.
        return Result(
            proc.returncode,
            proc.stdout.decode("utf-8", "surrogateescape") if proc.stdout is not None else "",
            proc.stderr.decode("utf-8", "surrogateescape") if proc.stderr is not None else "",
        )

    def plan(self, line: str) -> None:
        """A line of the plan: printed under --dry-run, silent otherwise."""
        if self.dry_run:
            print(line)

    def run(self, *argv: str, tolerate: bool = False) -> None:
        # `tolerate` is the explicit opt-in for a step whose failure is expected
        # and harmless. It is never silent: the step is still printed under --dry-run.
        if self.dry_run:
            print(shlex.join(argv))
            return
        result = self._exec(argv, None, capture=False)
        if result.rc != 0 and not tolerate:
            raise AgentAccountError(f"step failed ({result.rc}): {shlex.join(argv)}")

    def capture(self, *argv: str, input_text: str | None = None) -> Result:
        return self._exec(argv, input_text, capture=True)

    def do(self, label: str, fn: Callable[[], None]) -> None:
        if self.dry_run:
            print(f"# {label}")
        else:
            fn()


# ---------------------------------------------------------------------------
# identities
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PasswdEntry:
    name: str
    uid: int
    gid: int
    home: str
    shell: str


@dataclass(frozen=True)
class GroupEntry:
    name: str
    gid: int
    members: tuple[str, ...]


class SystemAccounts:
    """The passwd/group databases, through NSS (so sssd/ldap accounts count too)."""

    @staticmethod
    def _pw(p: pwd.struct_passwd) -> PasswdEntry:
        return PasswdEntry(p.pw_name, p.pw_uid, p.pw_gid, p.pw_dir, p.pw_shell)

    @staticmethod
    def _gr(g: grp.struct_group) -> GroupEntry:
        return GroupEntry(g.gr_name, g.gr_gid, tuple(g.gr_mem))

    def passwd(self, name: str) -> PasswdEntry | None:
        try:
            return self._pw(pwd.getpwnam(name))
        except KeyError:
            return None

    def group(self, name: str) -> GroupEntry | None:
        try:
            return self._gr(grp.getgrnam(name))
        except KeyError:
            return None

    def group_by_gid(self, gid: int) -> GroupEntry | None:
        try:
            return self._gr(grp.getgrgid(gid))
        except KeyError:
            return None

    def all_passwd(self) -> list[PasswdEntry]:
        return [self._pw(p) for p in pwd.getpwall()]

    def groups_of(self, name: str) -> list[str]:
        """EVERY group of the identity — primary first — like `id -Gn`.

        `getent group` lists supplementary members only: an account whose primary
        gid is `docker` does not appear there at all, and a primary group is
        exactly as root-equivalent as a supplementary one.
        """
        entry = self.passwd(name)
        if entry is None:
            return []
        names = []
        for gid in os.getgrouplist(name, entry.gid):
            group = self.group_by_gid(gid)
            if group is not None:
                names.append(group.name)
        return names

    def supplementary_groups(self, name: str) -> list[str]:
        """The groups `usermod -G` manages: everything except the primary one."""
        entry = self.passwd(name)
        if entry is None:
            return []
        primary = self.group_by_gid(entry.gid)
        return [g for g in self.groups_of(name) if primary is None or g != primary.name]

    def primary_group(self, name: str) -> str | None:
        entry = self.passwd(name)
        group = self.group_by_gid(entry.gid) if entry else None
        return group.name if group else None

    def current_user(self) -> str:
        return pwd.getpwuid(os.geteuid()).pw_name


def is_within(path: str, base: str) -> bool:
    base = base.rstrip("/")
    return path == base or path.startswith(base + "/")


class Git:
    def __init__(self, runner: Runner):
        self.runner = runner

    def is_worktree(self, path: Path | str) -> bool:
        # `--git-common-dir` cannot answer this: on a bare repository it prints
        # "." and exits 0, while `--is-inside-work-tree` says false.
        res = self.runner.capture("git", "-C", str(path), "rev-parse", "--is-inside-work-tree")
        return res.rc == 0 and res.out.strip() == "true"

    def common_dir(self, path: Path | str) -> Path:
        """The object store a worktree writes to.

        One clone with many worktrees shares exactly one of these, and it is NOT
        under the worktree: a linked worktree only carries a `.git` file.
        """
        if not self.is_worktree(path):
            raise AgentAccountError(f"{path} is not a git worktree")
        res = self.runner.capture("git", "-C", str(path), "rev-parse", "--git-common-dir")
        common = res.out.strip()
        if res.rc != 0 or not common:
            raise AgentAccountError(f"{path} is not a git worktree")
        resolved = Path(common)
        if not resolved.is_absolute():
            resolved = Path(path) / resolved
        return Path(os.path.realpath(resolved))


class Ansible:
    """The declarative half: one playbook, one variables file, one plan.

    `check` is `--check --diff`, which is what `--dry-run` means here: Ansible
    makes no change and prints the before/after of every resource it WOULD touch.
    """

    def __init__(self, cfg: Config, runner: Runner):
        self.cfg = cfg
        self.runner = runner

    def run(self, variables: Mapping[str, object], check: bool = False) -> None:
        argv = [
            str(self.cfg.ansible_playbook),
            str(PLAYBOOK),
            "-i",
            "localhost,",
        ]
        if check:
            argv += ["--check", "--diff"]
        env = dict(self.runner.env)
        # NOT LC_ALL=C, which every other subprocess here wants: Ansible refuses to
        # start without a UTF-8 locale ("Detected None"). The playbook parses no
        # localized tool output, so the one setting cannot make it misread anything.
        env.pop("LC_ALL", None)
        env["LANG"] = env.get("LANG") if (env.get("LANG") or "").upper().endswith(("UTF-8", "UTF8")) else "C.UTF-8"
        env["LC_ALL"] = env["LANG"]
        if self.cfg.ansible_collections_path:
            env["ANSIBLE_COLLECTIONS_PATH"] = self.cfg.ansible_collections_path
        # Ansible writes its temp files under the target's HOME; root's is not the
        # caller's, and a read-only or absent one fails the run.
        env.setdefault("ANSIBLE_LOCAL_TEMP", tempfile.mkdtemp(prefix="agent-account-ansible-"))
        env.setdefault("ANSIBLE_REMOTE_TEMP", env["ANSIBLE_LOCAL_TEMP"])

        with tempfile.NamedTemporaryFile(
            "w", prefix="agent-account-vars-", suffix=".json", delete=False, encoding="utf-8"
        ) as handle:
            json.dump(variables, handle, indent=2, sort_keys=True)
            vars_path = handle.name
        os.chmod(vars_path, 0o600)
        argv += ["-e", f"@{vars_path}"]
        try:
            result = subprocess.run(argv, env=env, check=False)
        finally:
            with contextlib.suppress(OSError):
                os.unlink(vars_path)
        if result.returncode != 0:
            raise AgentAccountError(f"the playbook failed ({result.returncode}): {' '.join(shlex.quote(a) for a in argv)}")


class Context:
    """What every verb needs: the config, the way to run things, the identities."""

    def __init__(
        self,
        cfg: Config,
        runner: Runner,
        accounts: SystemAccounts,
        git: Git | None = None,
        ansible: "Ansible | None" = None,
    ):
        self.cfg = cfg
        self.runner = runner
        self.accounts = accounts
        self.git = git or Git(runner)
        self.ansible = ansible or Ansible(cfg, runner)

    def agent_home(self) -> str:
        entry = self.accounts.passwd(self.cfg.agent_user)
        return entry.home if entry and entry.home else f"/home/{self.cfg.agent_user}"

    def agent_uid(self) -> int | None:
        entry = self.accounts.passwd(self.cfg.agent_user)
        return entry.uid if entry else None

    def in_group(self, group: str, member: str) -> bool:
        return group in self.accounts.groups_of(member)

    def foreign_members(self) -> list[str]:
        """Every member of `sing-dev` other than the developer and the agent.

        ONE predicate, so `apply` refusing a host and `check` reporting it cannot
        disagree. BOTH sources: the group's member list, and every account whose
        PRIMARY gid is the group (invisible in the member list).
        """
        group = self.accounts.group(self.cfg.agent_group)
        if group is None:
            return []
        members = list(group.members)
        members += [p.name for p in self.accounts.all_passwd() if p.gid == group.gid]
        allowed = {self.cfg.dev_name, self.cfg.agent_user}
        return sorted({m for m in members if m and m not in allowed})

    def home_problem(self, home: str) -> str:
        """Why the agent's home is unusable, or "" when it is fine.

        ONE predicate, so `apply` and `check` cannot disagree. An adopted `agent`
        whose passwd entry points at `/`, or into the developer's home, makes
        `apply` chmod 700 and install `.gradle`/`.android`/`.cache` INSIDE it —
        the exact separation this script exists to create, undone by one stale
        record.
        """
        if not home:
            return "it has no home directory in the passwd database"
        if not home.startswith("/"):
            return f"it is not absolute: {home}"
        resolved = os.path.realpath(home)
        if resolved == "/":
            return "it is / — apply would write the agent's state into the root"
        dev_home = str(self.cfg.dev_home)
        for forbidden in {dev_home, os.path.realpath(dev_home)}:
            if is_within(resolved, forbidden):
                return f"it is inside the developer's home ({resolved}) — it is the same identity's files"
        return ""

    def require_own_home(self, home: str) -> None:
        problem = self.home_problem(home)
        if problem:
            raise AgentAccountError(f"{self.cfg.agent_user}'s home: {problem}")

    def require_unprivileged_uid(self) -> None:
        """The invariant the whole boundary rests on: the agent is NOT root and
        NOT the developer. An `agent` that already existed with uid 0 would
        otherwise be provisioned as unprivileged and `run` would execute as root.
        """
        agent = self.cfg.agent_user
        entry = self.accounts.passwd(agent)
        if entry is None:  # a fresh host has no account yet
            return
        if entry.uid == 0:
            raise AgentAccountError(f"{agent} has uid 0 — that is root, not an unprivileged agent")
        dev = self.accounts.passwd(self.cfg.dev_name)
        if dev is not None and entry.uid == dev.uid:
            raise AgentAccountError(f"{agent} has the developer's uid {entry.uid} — it is the same identity")

    def require_boundary(self) -> None:
        """`run` re-checks the boundary it is about to rely on — at LAUNCH time.

        `apply`'s result is not a fact that stays true. An administrator, or another
        configuration tool, can add `agent` to `docker` a minute after provisioning;
        `run` is the entry point every launcher is told to use, so it is where the
        check has to happen. Without this, the prescribed way to start an agent
        happily starts one with a root-equivalent group.

        Everything here is a REFUSAL, not a fix: `run` has no root and must not try
        to reconcile a boundary it did not provision.
        """
        agent = self.cfg.agent_user
        entry = self.accounts.passwd(agent)
        if entry is None:
            raise AgentAccountError(f"no {agent} account — run '{SELF_NAME} apply' first")

        # EVERY group, primary first: a primary `docker` is invisible in the group's
        # member list and exactly as root-equivalent.
        privileged = [g for g in self.accounts.groups_of(agent) if g in PRIVILEGED_GROUPS]
        if privileged:
            raise AgentAccountError(
                f"{agent} is in {', '.join(privileged)} — root-equivalent or device-owning; "
                f"run '{SELF_NAME} apply' to reconcile it"
            )

        # The home is where `run` points GRADLE_USER_HOME and ANDROID_USER_HOME: a
        # home inside the developer's own would make the agent write the tree its
        # next build reads.
        problem = self.home_problem(entry.home)
        if problem:
            raise AgentAccountError(f"{agent}'s home is not its own: {problem}")

        # And the account must not be able to use sudo. Two questions, because either
        # alone has a hole:
        #
        # 1. What the RULES say: `sudo -n -l -U`. Reading another user's rules needs
        #    root, and the caller of `run` is not root, so this usually cannot answer.
        #    It stays as the cheap path when it can.
        # 2. Whether it actually WORKS: exec a non-interactive `sudo` AS the agent.
        #    That is the property itself, and it needs no root to ask. It is also the
        #    only check that cannot be defeated by a rule the listing would not show.
        #
        # An unreadable listing is NOT evidence of safety — an earlier version of this
        # function continued on it, so an administrator who added
        # `agent ALL=(ALL) NOPASSWD: ALL` after provisioning produced an agent that
        # `run` started happily, because the listing failed for lack of root.
        rules_verified = False
        listing = self.runner.capture("sudo", "--non-interactive", "--list", f"--other-user={agent}")
        if listing.rc == 0:
            rules = parse_sudo_rules(listing.out)
            if rules:
                raise AgentAccountError(
                    f"{agent} holds a sudo rule — it can leave its account: " + "; ".join(rules)
                )
            rules_verified = True

        # The sudo is named by ABSOLUTE PATH and the PATH is set explicitly: `env
        # sudo` would resolve through the agent's PATH, where mise puts shims, and a
        # rule scoped to `/usr/bin/sudo` would then be probed through a different
        # binary. This asks the question the launcher's own argv asks.
        probe = self.runner.capture(
            "sudo", "--non-interactive", f"--user={agent}", "--",
            SUDO_COMMAND, "-i", "PATH=/usr/local/bin:/usr/bin:/bin", "/usr/bin/sudo", "-n", "true",
        )
        if probe.rc == 0:
            raise AgentAccountError(f"{agent} CAN run sudo — it can leave its account")

        # A refusal from the probe means one of two things and they are not the same:
        # the agent cannot sudo (good), or the transition itself is broken (unknown).
        # The transition is the same invocation `run` is about to make, so re-ask it:
        # if a plain command does run, the sudo refusal above was a real answer.
        transition = self.runner.capture(
            "sudo", "--non-interactive", f"--user={agent}", "--", SUDO_COMMAND, "id", "-u"
        )
        if transition.rc != 0:
            if rules_verified:
                return  # the listing answered for it; the probe could not be asked
            raise AgentAccountError(
                f"cannot verify {agent}'s sudo rights, and cannot reach the account to ask: "
                f"run '{SELF_NAME} check' (needs root) to see why the transition is broken"
            )


# ---------------------------------------------------------------------------
# ACL parsing — `getfacl` output as data, not as text to grep
# ---------------------------------------------------------------------------

_ENTRY_RE = re.compile(
    r"^(?P<default>default:)?(?P<tag>user|group|mask|other):(?P<qual>[^:]*):"
    r"(?P<perms>[r-][w-][x-])(?:\s+#effective:(?P<eff>[r-][w-][x-]))?\s*$"
)


@dataclass(frozen=True)
class AclEntry:
    tag: str  # user | group | mask | other
    qualifier: str  # "" for the owner, owning group, mask and other
    perms: str  # "r-x"
    effective: str | None = None  # None: the mask does not limit the entry
    default: bool = False

    @property
    def named(self) -> bool:
        return self.tag in ("user", "group") and self.qualifier != ""

    @property
    def effective_perms(self) -> str:
        return self.effective or self.perms

    def __str__(self) -> str:
        return f"{'default:' if self.default else ''}{self.tag}:{self.qualifier}:{self.perms}"


def home_read_grant(
    entries: Sequence[AclEntry],
    agent: str,
    agent_groups: Sequence[str] = (),
    owner_group: str | None = None,
) -> list[str]:
    """Every entry by which `agent` would READ a file newly created in the home.

    The home is deliberately traversable (`user:agent:--x`), so the question is not
    "is there a named entry with an `r`" — that predicate was measured to pass a home
    carrying `default:other::r--`, and that entry is a real read grant:

        $ setfacl -d -m o::r .        # then, with umask 077, a new file
        mode=644                       # instead of 600

    In other words the default entry OVERRIDES the creator's umask, and everything
    under the home is then reachable through the traverse grant. The same holds for
    an unnamed entry reached by ownership or group (`user::r--`, `group::r--`,
    `default:group::r--` when `agent` is in the owning group).

    So the answer is computed the way the kernel computes it for a new file: which
    entries apply to `agent`, and do they carry an `r`.

    `owner_group` is the name of the group that OWNS the path. It is not optional in
    spirit: `group::r--` applies to whichever group owns the directory, so passing
    the agent's own group list here would report a grant the agent does not have.

    The MASK is deliberately not applied. An ACL mask caps NAMED entries only;
    `other` is never masked (measured: `o::r--` with `umask 077` yields a 0600 file
    while `mask::r--` leaves a 0644 — the mask is not the mechanism), so a read
    grant through `other` is real whatever the mask says.
    """
    grants: list[str] = []
    for entry in entries:
        if entry.tag == "other":
            reaches = True
        elif entry.tag == "group" and entry.qualifier == "":
            # The OWNING group: it reaches the agent only if the agent is IN it.
            reaches = owner_group is not None and owner_group in set(agent_groups)
        elif entry.tag == "user" and entry.qualifier == "":
            # The unnamed user entry is the file OWNER — the developer, never the
            # agent, which owns nothing in this home.
            reaches = False
        elif entry.tag == "group":
            # A NAMED group entry (`group:sing-dev:`) reaches the agent as a member.
            reaches = entry.qualifier in set(agent_groups)
        elif entry.tag == "user":
            reaches = entry.qualifier == agent
        else:
            reaches = False
        if reaches and "r" in entry.perms:
            grants.append(str(entry))
    return grants


def mask_covers(entries: Sequence[AclEntry], needed: str, is_dir: bool, default: bool = False) -> bool:
    """Is our grant already inside the path's ACL mask?

    When it is, `setfacl --no-mask` adds the entry and leaves the mask alone, so no
    unrelated named entry changes effective permissions. When it is not (or there is
    no mask at all), the mask has to grow and `--no-mask` would instead cap our own
    entry to the group bits — measured: `user:agent:rw- #effective:r--`.

    An entry with no mask is reported by `getfacl` with no `mask::` line; the owning
    group's bits are then the effective ceiling for the group class.
    """
    found = find_entry(entries, AclSpec("mask", "", "rwx", default=default))
    ceiling = found.perms if found is not None else None
    if ceiling is None:
        return False
    return bits_cover(ceiling, needed, is_dir)


def owner_group_of(path: Path) -> str | None:
    """The owning group's name, so `group::` entries can be weighed at all."""
    try:
        return grp.getgrgid(path.stat().st_gid).gr_name
    except (OSError, KeyError):
        return None


@dataclass(frozen=True)
class AclSpec:
    """A grant the check looks for, written the way `getfacl` prints it."""

    tag: str
    qualifier: str
    perms: str
    default: bool = False

    def as_default(self) -> "AclSpec":
        return AclSpec(self.tag, self.qualifier, self.perms, True)

    def __str__(self) -> str:
        return f"{'default:' if self.default else ''}{self.tag}:{self.qualifier}:{self.perms}"


def parse_getfacl(text: str) -> list[AclEntry]:
    entries = []
    for line in text.split("\n"):
        match = _ENTRY_RE.match(line.strip("\r"))
        if match:
            entries.append(
                AclEntry(
                    tag=match["tag"],
                    qualifier=match["qual"],
                    perms=match["perms"],
                    effective=match["eff"],
                    default=bool(match["default"]),
                )
            )
    return entries


def find_entry(entries: Sequence[AclEntry], spec: AclSpec) -> AclEntry | None:
    for entry in entries:
        if (entry.tag, entry.qualifier, entry.default) == (spec.tag, spec.qualifier, spec.default):
            return entry
    return None


def bits_cover(granted: str, needed: str, is_dir: bool) -> bool:
    """Does `granted` (the EFFECTIVE bits) survive `needed`?

    `X` is NOT "read or execute". It is chmod's "execute if a DIRECTORY, read if
    a regular file", and which one it means has to be asked of the target:
    treating it as "either bit" told a directory with effective `rw-` that it
    satisfied `rwX`, and a directory without execute cannot be traversed at all.
    """
    for bit in needed:
        if bit in "rwx":
            if bit not in granted:
                return False
        elif bit == "X":
            if ("x" if is_dir else "r") not in granted:
                return False
    return True


def getfacl_unescape(text: str) -> str:
    """The inverse of getfacl's path quoting: `\\012` is a newline, `\\\\` a backslash."""
    raw = text.encode("utf-8", "surrogateescape")
    out = bytearray()
    i = 0
    while i < len(raw):
        if raw[i] == 0x5C:
            if raw[i + 1 : i + 2] == b"\\":
                out.append(0x5C)
                i += 2
                continue
            octal = re.match(rb"[0-7]{3}", raw[i + 1 : i + 4])
            if octal and int(octal.group(), 8) < 256:
                out.append(int(octal.group(), 8))
                i += 4
                continue
        out.append(raw[i])
        i += 1
    return os.fsdecode(bytes(out))


def dump_paths(dump: str) -> set[str]:
    """Every path a `getfacl --recursive` dump names, as the real path."""
    return {getfacl_unescape(line[len("# file: ") :]) for line in dump.split("\n") if line.startswith("# file: ")}


def parse_sudo_rules(text: str) -> list[str]:
    """The rule lines of `sudo -l`: those after "may run the following commands".

    The question is "is there a rule at all", not "does it look dangerous": a
    password-required, command-scoped run-as rule matches none of NOPASSWD,
    "(ALL" or a trailing ALL and would otherwise read as a clean account.
    """
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if "may run the following commands" in line:
            return [
                rule.strip()
                for rule in lines[i + 1 :]
                if rule.strip() and not rule.startswith("User ") and not rule.lstrip().startswith("may run")
            ]
    return []


def walk_below(root: str) -> Iterator[str]:
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for name in dirnames + filenames:
            yield os.path.join(dirpath, name)


# ---------------------------------------------------------------------------
# state — what this script CREATED, as opposed to what it reconciled
# ---------------------------------------------------------------------------


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RemovedGroup(_Model):
    """A privileged group taken from a pre-existing agent, so rollback can give it back.

    It records HOW the group was held: a supplementary membership is restored by
    appending to the group list, but an account whose PRIMARY gid was `docker`
    has to come back with `docker` as its primary group.
    """

    group: str
    gid: int | None
    primary: bool


class TargetRecord(_Model):
    kind: Literal["node", "tree"]  # "node": the path alone | "tree": everything under it
    dump: str | None  # file name under acl/, the `getfacl` output from BEFORE apply


class State(_Model):
    version: Literal[1] = 1
    # user | group | own-group | sudoers -> created | adopted
    owned: dict[Literal["user", "group", "own-group", "sudoers"], Literal["created", "adopted"]] = {}
    # "subject:group" -> was the subject a member BEFORE the first apply
    observed_members: dict[str, bool] = {}
    passwd: str | None = None  # `passwd -S` state before the first lock: P | L | NP
    removed_groups: list[RemovedGroup] = []
    targets: dict[str, TargetRecord] = {}

    @field_validator("targets")
    @classmethod
    def _targets_are_absolute_and_storable(cls, targets: dict[str, TargetRecord]) -> dict[str, TargetRecord]:
        for key in targets:
            _absolute_utf8(Path(key))
        return targets

    # "created" is sticky and everything else is first-write-wins: a second apply
    # over an account this script made sees it as "already there", and recording
    # that would make rollback keep what it created.
    def record_owned(self, key: str, how: str) -> None:
        if how == "created":
            self.owned[key] = "created"
        else:
            self.owned.setdefault(key, how)

    def owns(self, key: str) -> bool:
        return self.owned.get(key) == "created"

    def observe_member(self, subject: str, group: str, is_member: bool) -> None:
        self.observed_members.setdefault(f"{subject}:{group}", is_member)

    def was_member(self, subject: str, group: str) -> bool:
        return self.observed_members.get(f"{subject}:{group}", False)

    def record_passwd(self, code: str) -> None:
        if self.passwd is None:
            self.passwd = code

    def record_removed_group(self, group: str, gid: int | None, primary: bool) -> None:
        if not any(r.group == group and r.primary == primary for r in self.removed_groups):
            self.removed_groups.append(RemovedGroup(group=group, gid=gid, primary=primary))

    def to_json(self) -> str:
        return self.model_dump_json(indent=2) + "\n"

    @classmethod
    def from_json(cls, text: str) -> "State":
        try:
            return cls.model_validate_json(text)
        except ValidationError as error:
            raise AgentAccountError(
                f"the state file is not one this script understands: {describe_validation_error(error)}"
            ) from None


def unescape_var(text: str) -> str:
    """Undo fish's `string escape --style=var` (alnum kept, `_` -> `__`, else `_HH_`)."""
    out = bytearray()
    i = 0
    while i < len(text):
        if text[i] != "_":
            out += text[i].encode("utf-8", "surrogateescape")
            i += 1
        elif text.startswith("__", i):
            out += b"_"
            i += 2
        else:
            hexed = re.match(r"_([0-9A-Fa-f]{2})_", text[i:])
            if hexed:
                out.append(int(hexed.group(1), 16))
                i += len(hexed.group(0))
            else:
                out += b"_"
                i += 1
    return os.fsdecode(bytes(out))


def atomic_write(path: Path, text: str, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", errors="surrogateescape") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(tmp)
        raise


class StateStore:
    """`state.json` plus one ACL dump per target, under /var/lib/agent-account.

    The dumps are separate files, not json strings: the Android SDK alone is tens
    of thousands of paths, and every record would otherwise rewrite all of them.
    A dump is the tool's own inverse — `setfacl --restore` puts it back — and is
    named by the sha256 of its path, so no path (`;`, `=`, a newline) can break it.
    """

    def __init__(self, directory: Path, dry_run: bool = False):
        self.directory = Path(directory)
        self.dry_run = dry_run

    @property
    def path(self) -> Path:
        return self.directory / "state.json"

    @property
    def acl_dir(self) -> Path:
        return self.directory / "acl"

    def exists(self) -> bool:
        return self.path.exists() or (self.directory / "state").exists() or (self.directory / "targets").exists()

    def load(self) -> State:
        if self.path.exists():
            return State.from_json(self.path.read_text(encoding="utf-8"))
        if self.exists():
            return self._load_legacy()
        return State()

    def save(self, state: State) -> None:
        if self.dry_run:
            return
        self.directory.mkdir(parents=True, exist_ok=True)
        os.chmod(self.directory, 0o755)
        atomic_write(self.path, state.to_json())

    @staticmethod
    def dump_name(target: str) -> str:
        return hashlib.sha256(os.fsencode(target)).hexdigest() + ".acl"

    def write_dump(self, target: str, text: str) -> str:
        name = self.dump_name(target)
        self.acl_dir.mkdir(parents=True, exist_ok=True)
        # 0755 on the directory, 0600 on the dumps: `rollback --dry-run` runs
        # unprivileged and has to `stat` them to know whether a target has one.
        os.chmod(self.acl_dir, 0o755)
        atomic_write(self.acl_dir / name, text, mode=0o600)
        return name

    def dump_exists(self, name: str) -> bool:
        return (self.acl_dir / name).exists()

    def read_dump(self, name: str) -> str:
        return (self.acl_dir / name).read_text(encoding="utf-8", errors="surrogateescape")

    @contextlib.contextmanager
    def lock(self) -> Iterator[None]:
        """One apply/rollback at a time: two interleaved runs would each snapshot
        the other's half-written grants as "the host's own configuration"."""
        if self.dry_run:
            yield
            return
        self.directory.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.directory / "lock", os.O_WRONLY | os.O_CREAT, 0o600)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise AgentAccountError("another apply/rollback is running (lock held)") from None
            yield
        finally:
            os.close(fd)

    def destroy(self, runner: Runner) -> None:
        def remove() -> None:
            for name in ("state.json", "state", "targets", "lock"):
                (self.directory / name).unlink(missing_ok=True)
            shutil.rmtree(self.acl_dir, ignore_errors=True)
            with contextlib.suppress(OSError):
                self.directory.rmdir()

        runner.do(f"rm --force --recursive {self.directory}/{{state.json,state,targets,lock,acl}}", remove)

    def _load_legacy(self) -> State:
        """Read what the fish version wrote (`state`, `targets`, `acl/<escaped>.acl`)."""
        state = State()
        legacy_state = self.directory / "state"
        if legacy_state.exists():
            for line in legacy_state.read_text(encoding="utf-8", errors="surrogateescape").split("\n"):
                f = line.split("\t")
                key = f[0]
                if key in ("user", "group", "own-group", "sudoers") and len(f) >= 2:
                    state.record_owned(key, f[1])
                elif key == "member" and len(f) >= 3:
                    state.observe_member(f[1], f[2], True)
                elif key in ("removed-group", "removed-primary-group") and len(f) >= 3:
                    gid = int(f[2]) if f[2].isdigit() else None
                    state.record_removed_group(f[1], gid, key == "removed-primary-group")
                elif key == "passwd" and len(f) >= 2:
                    state.record_passwd(f[1])
        legacy_targets = self.directory / "targets"
        if legacy_targets.exists():
            for line in legacy_targets.read_text(encoding="utf-8", errors="surrogateescape").split("\n"):
                f = line.split("\t")
                if not f[0]:
                    continue
                kind = f[1] if len(f) > 1 and f[1] in ("node", "tree") else "tree"  # no kind: predates it
                name = f"{f[0]}.acl"
                path = unescape_var(f[0])
                require_storable(path)
                state.targets[path] = TargetRecord(kind=kind, dump=name if self.dump_exists(name) else None)
        return state


class Journal:
    """State mutations that are persisted AT ONCE.

    The record is written before the write it describes: a `useradd` that
    succeeded followed by a record that failed to write leaves an account with
    nothing that says it is this script's, and rollback removes nothing.
    """

    def __init__(self, store: StateStore, state: State):
        self.store = store
        self.state = state

    def owned(self, key: str, how: str) -> None:
        self.state.record_owned(key, how)
        self.store.save(self.state)

    def observe_member(self, subject: str, group: str, is_member: bool) -> None:
        self.state.observe_member(subject, group, is_member)
        self.store.save(self.state)

    def passwd(self, code: str) -> None:
        self.state.record_passwd(code)
        self.store.save(self.state)

    def removed_group(self, group: str, gid: int | None, primary: bool) -> None:
        self.state.record_removed_group(group, gid, primary)
        self.store.save(self.state)

    def target(self, path: str, kind: str, dump: str) -> None:
        self.state.targets[path] = TargetRecord(kind=kind, dump=dump)
        self.store.save(self.state)


# ---------------------------------------------------------------------------
# the declaration the playbook receives
# ---------------------------------------------------------------------------


def acl_grants(cfg: Config, ctx: Context, agent_home: str, *, state: str = "present") -> list[dict[str, object]]:
    """Every ACL this tool writes, as declared entries.

    The home is ONE entry: `--x` and nothing else. A read grant there would open
    every world-readable dotfile, so it is written down as the single bit it is
    rather than derived from a role.

    The SDK, the toolchain and the worktrees are TWO entries each — the access
    entry and the default entry — because `recursive` and `default` cannot be one
    declaration (see the playbook's note). Measured: the pair produces exactly what
    `setfacl --recursive --modify … --modify default:…` produces.

    `preserve_mask` is asked per target, because `setfacl --modify` RECALCULATES the
    mask and that escalates entries nobody asked about — measured on one file: an
    unrelated `user:nobody:rwx` capped to `#effective:r--` by `mask::r--` became
    fully effective the moment a group entry was added. Where a mask already covers
    our grant, it is left alone.
    """
    def covered(path: Path, needed: str, default: bool) -> bool:
        entries = [
            e
            for e in parse_getfacl(ctx.runner.capture("getfacl", "--absolute-names", str(path)).out)
            if e.default == default
        ]
        return mask_covers(entries, needed, path.is_dir(), default=default)

    grants: list[dict[str, object]] = [
        {
            "path": str(cfg.dev_home),
            "entity": cfg.agent_user,
            "etype": "user",
            "permissions": "--x",
            "recursive": False,
            "default": False,
            "state": state,
        }
    ]
    for directory in (cfg.sdk_dir, *cfg.toolchain_dirs):
        if not directory.is_dir():
            continue
        for default in (False, True):
            grants.append(
                {
                    "path": str(directory),
                    "entity": cfg.agent_user,
                    "etype": "user",
                    "permissions": "rX",
                    "recursive": True,
                    "default": default,
                    "preserve_mask": covered(directory, "rX", default),
                    "state": state,
                }
            )
    for root in cfg.roots:
        for target in (root, ctx.git.common_dir(root)):
            for default in (False, True):
                grants.append(
                    {
                        "path": str(target),
                        "entity": cfg.agent_group,
                        "etype": "group",
                        "permissions": "rwX",
                        "recursive": True,
                        "default": default,
                        "preserve_mask": covered(target, "rwX", default),
                        "state": state,
                    }
                )
        # The shared git store is writable so the agent can commit — but NOT
        # everywhere: `config` chooses `core.hooksPath` and other settings for every
        # later git invocation, and `hooks/` holds programs git runs. A hook the agent
        # writes executes as the DEVELOPER on their next git command, which is the
        # escalation this whole boundary exists to prevent. Measured on this host:
        # with `.git/config` at 0444 and `.git/hooks` at 0555, commit, branch and
        # checkout all still work — the object store, refs, logs and the index are
        # the only things git needs to write.
        common = ctx.git.common_dir(root)
        for denied in (common / "config", common / "hooks"):
            if not denied.exists():
                continue
            for default in (False, True):
                grants.append(
                    {
                        "path": str(denied),
                        "entity": cfg.agent_group,
                        "etype": "group",
                        "default": default,
                        "recursive": True,
                        "state": "absent",
                    }
                )
    return grants


# ---------------------------------------------------------------------------
# apply
# ---------------------------------------------------------------------------


class Provisioner:
    def __init__(self, ctx: Context, store: StateStore):
        self.ctx = ctx
        self.cfg = ctx.cfg
        self.runner = ctx.runner
        self.accounts = ctx.accounts
        self.store = store
        self.state = store.load()
        self.journal = Journal(store, self.state)

    def apply(self) -> None:
        cfg = self.cfg
        agent_home = self.ctx.agent_home()
        # Asked before the first write: `apply` chmods this path and installs the
        # agent's private state in it.
        self.ctx.require_own_home(agent_home)
        print(f"=== agent account: {cfg.agent_user} (group {cfg.agent_group}) for {cfg.dev_name} ===")

        account = self.accounts.passwd(cfg.agent_user)
        # Every refusal and every record happens BEFORE the playbook runs, so a
        # refused host is untouched and rollback has a record to work from.
        removed = self._record_account(account)
        updates = self._record_memberships(account, removed)
        self._snapshot_targets(agent_home)
        self._install_sudoers()
        group_exists = self.accounts.group(cfg.agent_group) is not None
        if cfg.dry_run and not group_exists:
            # Ansible cannot declare this in check mode (it validates the group on
            # an existing account, and the group task above only "would create" it),
            # so the plan says it here. Still one code path: the playbook performs
            # exactly these memberships.
            for update in updates:
                if "groups" not in update:
                    continue
                state = " --append" if update.get("append") else ""
                print(f"usermod{state} --groups {','.join(update['groups'])} {update['name']}")
        if cfg.dry_run and account is None:
            # `setfacl` resolves the entry's NAME while it plans, so a host without
            # the account cannot be asked what the grant would be. The plan says it
            # in the shell form the reader can check, like the memberships above.
            for grant in acl_grants(cfg, self.ctx, agent_home):
                recurse = "--recursive " if grant["recursive"] else ""
                # `default:` is a PREFIX of the entry, not a field after the entity.
                default = "default:" if grant["default"] else ""
                entity = f"{default}{grant['etype']}:{grant['entity']}"
                if grant.get("state") == "absent":
                    # The denial of the shared store's `config` and `hooks`: printed
                    # too, because a plan that hides a removal is not the plan.
                    print(f"setfacl {recurse}-x {entity} {grant['path']}")
                    continue
                mask = " --no-mask" if grant.get("preserve_mask") else ""
                print(f"setfacl{mask} {recurse}--modify {entity}:{grant['permissions']} {grant['path']}")
        self.ctx.ansible.run(self._variables(agent_home, account, updates), check=cfg.dry_run)
        if cfg.dry_run:
            print("=== plan only; re-run without --dry-run as root to apply ===")
            return
        # LAST, for every account, and after the `sing-dev` membership is in place:
        # a rule this account can reach is the escalation the whole change forbids.
        self.audit_sudo()
        roots = " ".join(shlex.quote(str(r)) for r in cfg.roots)
        print(f"=== done; verify with: {SELF_NAME} check {roots} ===")

    def _record_account(self, account: PasswdEntry | None) -> list[RemovedGroup]:
        """Everything about the ACCOUNT that must be known before it is written."""
        cfg = self.cfg
        if account is None:
            self.journal.owned("user", "created")
            # The group is INDEPENDENT of the account: a host may carry `sing-dev`
            # and no `agent`. Recording it as created without asking made rollback
            # delete a group this script never made.
            self.journal.owned("group", "created" if self.accounts.group(cfg.agent_group) is None else "adopted")
            # `useradd --user-group` creates a group named `agent`; it is recorded
            # like any other adoption, BEFORE the account that will own it.
            self.journal.owned("own-group", "created" if self.accounts.group(cfg.agent_user) is None else "adopted")
            return []

        print(
            f"account exists: {account.name} (uid {account.uid}, home {account.home}, shell {account.shell})"
            " — adopted, not created by this script"
        )
        self.journal.owned("user", "adopted")
        self.journal.owned("group", "created" if self.accounts.group(cfg.agent_group) is None else "adopted")
        self.journal.owned("own-group", "adopted" if self.accounts.group(cfg.agent_user) else "created")
        if account.shell.endswith(("/nologin", "/false")):
            raise AgentAccountError(f"{cfg.agent_user} has no shell ({account.shell}); an agent cannot run in it")

        # "No password, so no login" is the invariant, and a NEW account has it by
        # construction — an existing one may not. The state before the lock is
        # recorded, because a lock is not undone by symmetry either.
        status = self.runner.capture("passwd", "--status", cfg.agent_user).out.split()
        password_state = status[1] if len(status) > 1 else ""
        if password_state:
            self.journal.passwd(password_state)

        # A pre-existing account that still holds a privileged group is the whole
        # boundary defeated — `docker` alone is root-equivalent. `apply` is what a
        # human re-runs, so it reconciles instead of only reporting.
        removed: list[RemovedGroup] = []
        primary = self.accounts.primary_group(cfg.agent_user)
        for group in PRIVILEGED_GROUPS:
            if not self.ctx.in_group(group, cfg.agent_user):
                continue
            entry = self.accounts.group(group)
            held_as_primary = primary == group
            self.journal.removed_group(group, entry.gid if entry else None, held_as_primary)
            removed.append(RemovedGroup(group=group, gid=entry.gid if entry else None, primary=held_as_primary))
        return removed

    def _record_memberships(self, account: PasswdEntry | None, removed: list[RemovedGroup]) -> list[dict[str, object]]:
        """The `user` facets the playbook applies, computed here because the DECISION
        (which group may stay, what it was before) is the journal's, not Ansible's."""
        cfg = self.cfg
        # The group is the collaboration seam: both identities, NOBODY ELSE. A
        # pre-existing member would silently receive the same recursive write access
        # to every authorized worktree. Reported, not removed: it may be somebody's
        # deliberate configuration.
        foreign = self.ctx.foreign_members()
        if foreign:
            raise AgentAccountError(
                f"{cfg.agent_group} already holds {', '.join(foreign)} — "
                f"it is documented as {cfg.dev_name} and {cfg.agent_user} only"
            )
        subjects = (cfg.dev_name, cfg.agent_user)
        # Recorded BEFORE the membership changes: a rollback that deleted a
        # membership this script did not add would remove the developer from a group
        # they chose.
        for subject in subjects:
            self.journal.observe_member(subject, cfg.agent_group, self.ctx.in_group(cfg.agent_group, subject))

        updates: list[dict[str, object]] = []
        # The agent is RECONCILED to exactly {seam} minus the privileged groups: that
        # is the boundary, and a pre-existing `docker`/`wheel`/`kvm` is the thing
        # this whole change exists to remove. `append: false` with the full list, so
        # the same task both adds the seam and drops a privileged group, and a second
        # apply is a no-op because the list already matches.
        agent_groups = [
            g
            for g in self.accounts.supplementary_groups(cfg.agent_user)
            if g not in PRIVILEGED_GROUPS and g != cfg.agent_group
        ]
        updates.append({"name": cfg.agent_user, "groups": [*agent_groups, cfg.agent_group], "append": False})

        # The DEVELOPER only ever gains the seam. `append: true` is not a convenience
        # here, it is the safety property: an `append: false` with a computed list
        # replaces everything the developer is in, so one stale read of the group
        # database would drop them from `wheel`/`docker`/`kvm` and lock them out of
        # their own machine.
        if cfg.agent_group not in self.accounts.supplementary_groups(cfg.dev_name):
            updates.append({"name": cfg.dev_name, "groups": [cfg.agent_group], "append": True})

        if account is not None:
            primary = self.accounts.primary_group(cfg.agent_user)
            if primary in PRIVILEGED_GROUPS:
                # The new primary group has to exist; an account somebody else
                # created may not have one of its own.
                updates.append({"name": cfg.agent_user, "primary_group": cfg.agent_user})
            status = self.runner.capture("passwd", "--status", cfg.agent_user).out.split()
            if len(status) > 1 and status[1] == "P":
                updates.append({"name": cfg.agent_user, "password_lock": True})
        return updates

    def _snapshot_targets(self, agent_home: str) -> None:
        """`getfacl`'s own output BEFORE the playbook writes, so rollback can put it back.

        Snapshot EVERY target before writing to ANY of them. Two passes, not one
        interleaved loop: a repository nested inside another authorized root lies
        within the outer tree's recursive dump, so a snapshot taken after the outer
        grant had already written there records `sing-dev:rwX` as the "previous"
        state — and rollback then reintroduces the grant.

        Hand-written records failed three times: `--modify` OVERWRITES a named entry
        that was already there, `-x` leaves `mask::` behind and takes the whole
        `default:` block with it. The dump is the inverse.
        """
        cfg = self.cfg
        targets: list[tuple[Path, str]] = [(cfg.dev_home, "node")]  # the `--x` grant is not recursive
        for directory in (cfg.sdk_dir, *cfg.toolchain_dirs):
            if directory.is_dir():
                targets.append((directory, "tree"))
        for root in cfg.roots:
            for target in (root, self.ctx.git.common_dir(root)):
                if (target, "tree") not in targets:
                    targets.append((target, "tree"))
        for target, kind in targets:
            self._snapshot(target, kind)

    def _snapshot(self, target: Path, kind: str) -> None:
        flags = ["--absolute-names", "--physical"] + (["--recursive"] if kind == "tree" else [])
        key = str(target)
        require_storable(key)
        if self.cfg.dry_run:
            # The plan is the playbook's: nothing is written, so nothing needs a
            # snapshot, and the dump would be the state this dry run never changed.
            self.runner.plan(f"# would snapshot: getfacl {' '.join(flags)} {shlex.quote(key)}")
            return
        record = self.state.targets.get(key)
        # The FIRST snapshot is the one taken before `apply` ever ran: a second apply
        # would otherwise record the state `apply` itself wrote. A `tree` dump COVERS
        # a `node` one, so a record is upgraded, never narrowed.
        upgrade = record is not None and kind == "tree" and record.kind != "tree"
        if record is not None and record.dump is not None and not upgrade:
            return
        result = self.runner.capture("getfacl", *flags, key)
        if result.rc != 0:
            raise AgentAccountError(f"could not snapshot the ACL of {target}")
        if upgrade:
            eprint(f"note: {target} was recorded as '{record.kind}'; upgraded to tree")
        self.journal.target(key, kind, self.store.write_dump(key, result.out))

    def _install_sudoers(self) -> None:
        """One rule: the DEVELOPER may re-execute /usr/bin/env AS the agent.

        The account being sandboxed is the one that must hold no sudo right of its
        own. A file this script does not own is never replaced, and the new one is
        validated BEFORE it lands where sudo reads it.

        This one resource is NOT declared in the playbook: `template` would replace
        the target without asking who wrote it, and the path is shared.
        """
        path, rule = self.cfg.sudoers_path, self.cfg.sudoers_rule
        if self.cfg.dry_run:
            self.runner.plan(f"printf '%s\\n' {shlex.quote(rule)} > {shlex.quote(str(path))}")
            return
        if path.exists():
            # One matching line is not proof: a file that ALSO holds other
            # administrators' grants would lose them. It is ours only when it is
            # exactly this one rule — and then it is ADOPTION, not authorship.
            if path.read_text(encoding="utf-8").strip() != rule:
                raise AgentAccountError(
                    f"{path} holds content this script did not write — inspect it, then remove it by hand"
                )
            self.journal.owned("sudoers", "adopted")
        else:
            self.journal.owned("sudoers", "created")
        # sudo ignores include-dir files whose name contains a dot, so the temporary
        # name cannot match it.
        tmp = path.with_name(path.name + ".tmp")
        tmp.unlink(missing_ok=True)
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o440)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(rule + "\n")
        if self.runner.capture("visudo", "--check", "--file", str(tmp)).rc != 0:
            tmp.unlink(missing_ok=True)
            raise AgentAccountError(f"{path} does not parse")
        os.replace(tmp, path)

    def _variables(self, agent_home: str, account: PasswdEntry | None, updates: list[dict[str, object]]) -> dict:
        cfg = self.cfg
        created = account is None
        return {
            "agent_account_state": "present",
            "agent_user": cfg.agent_user,
            "agent_group": cfg.agent_group,
            "agent_home": agent_home,
            "agent_create_user": created,
            "agent_create_group": self.accounts.group(cfg.agent_group) is None,
            "agent_private_dirs": [
                f"{agent_home}/.gradle",
                f"{agent_home}/.android",
                f"{agent_home}/.cache",
            ],
            "agent_group_exists": self.accounts.group(cfg.agent_group) is not None,
            "agent_user_exists": account is not None,
            "agent_user_updates": updates,
            "agent_acl_grants": acl_grants(cfg, self.ctx, agent_home),
        }

    def audit_sudo(self) -> None:
        """The escalation this change forbids: a sudo rule for `agent`, or one that
        reaches it through `sing-dev`.

        This script does not edit somebody else's sudoers line and cannot read
        another user's rules without root, so an unreadable answer is a WARNING and
        not a pass: a run that could not verify is not a run that verified.
        """
        agent = self.cfg.agent_user
        result = self.runner.capture("sudo", "--non-interactive", "--list", f"--other-user={agent}")
        if result.rc != 0:
            eprint(f"note: {agent}'s sudo rights are unverified here (needs root to read);")
            eprint(f"      run '{SELF_NAME} check' after provisioning")
            return
        rules = parse_sudo_rules(result.out)
        if rules:
            raise AgentAccountError(f"{agent} holds a sudo rule; it can leave its account: " + "; ".join(rules))


def in_home_guard(cfg: Config, root: Path) -> None:
    """A worktree inside the developer's home needs the traverse grant, which is the
    one cost this design avoids — so it has to be asked for, not discovered later."""
    resolved, home = os.path.realpath(root), os.path.realpath(cfg.dev_home)
    if not is_within(resolved, home):
        return
    # `$HOME` ITSELF is refused even WITH the opt-in. `apply` snapshots `$HOME` as a
    # single NODE (the `--x` grant is not recursive); a recursive grant over the same
    # path could not be undone by that snapshot. A directory cannot be both the
    # non-recursive seam and a recursive root.
    if resolved == home:
        raise AgentAccountError(
            f"{resolved} is the developer's home; authorizing it as a worktree root would also grant it "
            "recursively, which the traverse-only snapshot cannot undo — pass a worktree inside it instead"
        )
    if cfg.allow_in_home:
        eprint(f"warning: authorized worktree inside the developer home: {resolved}")
        eprint(f"         it relies on the traverse grant, which opens {home}'s world-readable files")
        return
    raise AgentAccountError(f"{resolved} is inside {home}; pass --allow-in-home to accept the traverse grant")


def validate_roots(ctx: Context) -> None:
    """Everything that can refuse, refuses before the first write — a root that is
    not a git worktree must not leave an account created and a host half-provisioned."""
    for root in ctx.cfg.roots:
        if not root.is_dir():
            raise AgentAccountError(f"not a directory: {root}")
        in_home_guard(ctx.cfg, root)
        if not ctx.git.is_worktree(root):
            raise AgentAccountError(f"{root} is not a git worktree")


# ---------------------------------------------------------------------------
# check — every acceptance criterion as a verdict. Unprivileged.
# ---------------------------------------------------------------------------


class Report:
    def __init__(self) -> None:
        self.verdicts: list[tuple[str, str]] = []

    def _add(self, level: str, message: str) -> None:
        self.verdicts.append((level, message))
        print(f"{level} {message}")

    def ok(self, message: str) -> None:
        self._add("PASS", message)

    def fail(self, message: str) -> None:
        self._add("FAIL", message)

    def note(self, message: str) -> None:
        self._add("NOTE", message)

    @property
    def failures(self) -> list[str]:
        return [m for level, m in self.verdicts if level == "FAIL"]

    @property
    def failed(self) -> bool:
        return bool(self.failures)


class Checker:
    def __init__(self, ctx: Context):
        self.ctx = ctx
        self.cfg = ctx.cfg
        self.runner = ctx.runner
        self.accounts = ctx.accounts
        self.report = Report()
        # Set by the first probe that shows the transition itself works. Every "the
        # agent could NOT do X" verdict is meaningless without it: an agent that can
        # run nothing at all also cannot run `sudo`, and reporting that as a PASS
        # would certify a wall as a door.
        self.transition_ok = False

    def run(self) -> int:
        self._account()
        self._privileged_groups()
        self._seam()
        self._sudo_rules()
        self._acls()
        self._transition()
        # And now ask the KERNEL, not `getfacl`: access(2) also weighs SELinux, the
        # modes of every parent directory and the mount options.
        self._behaviour_probes()
        if self.report.failed:
            return 1
        print("=== every invariant holds ===")
        return 0

    # -- identities ---------------------------------------------------------

    def _account(self) -> None:
        cfg, report = self.cfg, self.report
        account = self.accounts.passwd(cfg.agent_user)
        if account is None:
            report.fail(f"no {cfg.agent_user} account — run '{SELF_NAME} apply'")
            return
        dev = self.accounts.passwd(cfg.dev_name)
        if account.uid == 0:
            report.fail(f"{cfg.agent_user} has uid 0 — that is root")
        elif dev is not None and account.uid == dev.uid:
            report.fail(f"{cfg.agent_user} has the developer's uid {account.uid}")
        else:
            report.ok(f"{cfg.agent_user} is a distinct unprivileged uid ({account.uid})")
        if account.shell.endswith(("/nologin", "/false")):
            report.fail(f"{cfg.agent_user} cannot run a shell")
        else:
            report.ok(f"{cfg.agent_user} has a shell")
        problem = self.ctx.home_problem(account.home)
        if problem:
            report.fail(f"{cfg.agent_user}'s home: {problem}")
        else:
            report.ok(f"{cfg.agent_user}'s home is its own ({account.home})")

    def _privileged_groups(self) -> None:
        for group in PRIVILEGED_GROUPS:
            if self.ctx.in_group(group, self.cfg.agent_user):
                self.report.fail(f"{self.cfg.agent_user} is in {group} (root-equivalent or device-owning)")
            else:
                self.report.ok(f"{self.cfg.agent_user} is not in {group}")

    def _seam(self) -> None:
        cfg, report, ctx = self.cfg, self.report, self.ctx
        agent_in = ctx.in_group(cfg.agent_group, cfg.agent_user)
        dev_in = ctx.in_group(cfg.agent_group, cfg.dev_name)
        if agent_in and dev_in:
            report.ok(f"both identities are in {cfg.agent_group}")
        elif agent_in:
            report.note(f"{cfg.dev_name} is not in {cfg.agent_group} yet (group membership applies on next login)")
        else:
            report.fail(f"{cfg.agent_group} does not hold {cfg.agent_user}")
        # Only the documented two: a third member holds the same recursive write
        # access to every authorized worktree and to the clone's git store.
        foreign = ctx.foreign_members()
        if foreign:
            report.fail(
                f"{cfg.agent_group} also holds {', '.join(foreign)} — "
                f"it is documented as {cfg.dev_name} and {cfg.agent_user} only"
            )
        else:
            report.ok(f"{cfg.agent_group} holds only {cfg.dev_name} and {cfg.agent_user}")

    def _sudo_rules(self) -> None:
        agent = self.cfg.agent_user
        result = self.runner.capture("sudo", "--non-interactive", "--list", f"--other-user={agent}")
        if result.rc != 0:
            # Reading another user's rules needs root: a check that could not run is
            # not a clean bill of health.
            self.report.note(f"{agent}'s sudo rights unverified (needs root): sudo -n -l -U {agent}")
            return
        rules = parse_sudo_rules(result.out)
        if rules:
            self.report.fail(f"{agent} holds a sudo rule — it can leave its account: " + "; ".join(rules))
        else:
            self.report.ok(f"{agent} holds no sudo rule")

    # -- ACLs ---------------------------------------------------------------

    def read_acl(self, path: Path) -> list[AclEntry]:
        result = self.runner.capture("getfacl", "--absolute-names", str(path))
        return parse_getfacl(result.out) if result.rc == 0 else []

    def check_grant(
        self,
        target: Path,
        grant: AclSpec,
        default_grant: AclSpec | None = None,
        writer: str = "the next write",
    ) -> None:
        report = self.report
        if not target.exists():
            report.fail(f"missing path: {target}")
            return
        entries = self.read_acl(target)
        is_dir = target.is_dir()
        # The entry has to be EFFECTIVE, not merely present: the ACL mask constrains
        # every named entry. A `group:sing-dev:rwx` under `mask::r-x` is a read-only
        # worktree.
        entry = find_entry(entries, grant)
        if entry is None:
            report.fail(f"{target}: no '{grant}' — the agent cannot reach it")
        elif bits_cover(entry.effective_perms, grant.perms, is_dir):
            report.ok(f"{target}: {grant}")
        else:
            report.fail(
                f"{target}: '{grant}' is present but the ACL mask limits it to '{entry.effective_perms}' "
                f"— a '{grant.perms}' operation would fail"
            )
        if default_grant is None:
            return
        entry = find_entry(entries, default_grant)
        if entry is None:
            report.fail(f"{target}: no '{default_grant}' — a file {writer} writes lands unreachable")
        elif bits_cover(entry.effective_perms, default_grant.perms, is_dir):
            report.ok(f"{target}: {default_grant}")
        else:
            report.fail(
                f"{target}: '{default_grant}' is limited to '{entry.effective_perms}' "
                f"— a file {writer} writes lands unreachable"
            )

    def check_denied(self, target: Path, grant: AclSpec) -> None:
        """The entry must be ABSENT, access and default.

        `config` picks `core.hooksPath` and every other setting for later git
        invocations, and `hooks/` holds programs git runs as the DEVELOPER. A grant
        on either is the privilege path back across the boundary this account exists
        to create.
        """
        if not target.exists():
            return
        entries = self.read_acl(target)
        live = [
            str(entry)
            for entry in entries
            if (entry.tag, entry.qualifier) == (grant.tag, grant.qualifier) and "w" in entry.perms
        ]
        if live:
            self.report.fail(
                f"{target}: '{grant.tag}:{grant.qualifier}' can WRITE it ({', '.join(live)}) — "
                "an agent that writes it can run code as the developer"
            )
        else:
            self.report.ok(f"{target}: no write grant for '{grant.tag}:{grant.qualifier}'")

    def _acls(self) -> None:
        cfg, report = self.cfg, self.report
        agent, group = cfg.agent_user, cfg.agent_group
        self.check_grant(cfg.dev_home, AclSpec("user", agent, "--x"))
        # What a NEW file in the home inherits, not a scan for named `r` entries:
        # the named-only predicate was measured to pass a home carrying
        # `default:other::r--`, which makes every file created there mode 644
        # through the traverse grant. See `home_read_grant`.
        read_grants = home_read_grant(
            self.read_acl(cfg.dev_home),
            agent,
            self.accounts.groups_of(agent),
            owner_group=owner_group_of(cfg.dev_home),
        )
        if read_grants:
            report.fail(
                f"{cfg.dev_home} lets {agent} read files created in it; the home is meant to stay "
                "closed (the traverse grant only opens the path): " + "; ".join(read_grants)
            )
        else:
            report.ok(f"{cfg.dev_home} carries no read grant, access or default")

        read = AclSpec("user", agent, "r-x")
        self.check_grant(cfg.sdk_dir, read, read.as_default(), "sdkmanager")
        for directory in cfg.toolchain_dirs:
            if directory.is_dir():
                # The DEFAULT entry too: `apply` writes it, and a host whose default
                # was removed still passes an access-only check while every file mise
                # installs next is unreachable.
                self.check_grant(directory, read, read.as_default(), "toolchain")

        # A writable worktree over a read-only object store is a worktree an agent
        # cannot commit in, so the common git dir is checked too.
        write = AclSpec("group", group, "rwx")
        for root in cfg.roots:
            # The same predicate `apply` uses: a bare repo answers
            # `--git-common-dir` with "." and used to pass here.
            if not self.ctx.git.is_worktree(root):
                report.fail(f"{root} is not a git worktree")
                continue
            self.check_grant(root, write, write.as_default(), "git")
            common = self.ctx.git.common_dir(root)
            self.check_grant(common, write, write.as_default(), "git")
            # And the two paths that must NOT be writable there. A plan that denies
            # them and a check that never looks would certify the escalation.
            self.check_denied(common / "config", AclSpec("group", group, "rwX"))
            self.check_denied(common / "hooks", AclSpec("group", group, "rwX"))

    # -- behaviour ----------------------------------------------------------

    def _as_agent(self, *argv: str) -> Result:
        cfg = self.cfg
        return self.runner.capture(
            "sudo", "--non-interactive", f"--user={cfg.agent_user}", "--",
            SUDO_COMMAND, "-i", f"HOME={self.ctx.agent_home()}", "PATH=/usr/local/bin:/usr/bin:/bin", *argv,
        )

    def agent_can(self, path: Path, bits: str) -> bool:
        """Ask `access(2)` — through `test` as the agent — one bit at a time.

        `test -r` alone passes on a mode-0644 directory that cannot be entered at
        all, so READ and TRAVERSE are separate questions.
        """
        return all(self._as_agent("test", f"-{bit}", str(path)).rc == 0 for bit in bits)

    def _transition(self) -> None:
        agent = self.cfg.agent_user
        result = self._as_agent("id", "-u")
        if result.rc != 0:
            self.report.fail(f"cannot run a command as {agent} — the sudoers rule is missing or wrong")
            return
        ran_as = result.out.strip()
        if ran_as == str(self.ctx.agent_uid()):
            self.report.ok(f"a command runs as {agent} (uid {ran_as})")
            self.transition_ok = True
        else:
            self.report.fail(f"a command ran as uid {ran_as}, not {agent}")

    def _behaviour_probes(self) -> None:
        cfg, report = self.cfg, self.report
        agent = cfg.agent_user
        if not self.transition_ok:
            report.note("behavioural permissions unverified: the transition itself does not work yet")
            return

        for path in (cfg.sdk_dir, *cfg.toolchain_dirs):
            if not path.exists():
                continue
            if self.agent_can(path, "rx"):
                report.ok(f"{agent} can read and traverse {path} (kernel, not ACL text)")
            else:
                report.fail(
                    f"{agent} cannot read or traverse {path} — the ACL is present but the kernel refuses "
                    "(SELinux, a parent directory's mode, or a mount option)"
                )
        for root in cfg.roots:
            if not root.is_dir():
                continue
            if self.agent_can(root, "wx"):
                report.ok(f"{agent} can write and traverse {root} (kernel, not ACL text)")
            else:
                report.fail(f"{agent} cannot write or traverse {root} — the grant is present but the kernel refuses")

        # What the traverse grant on `$HOME` means for a secret directory: `test -r`
        # on the DIRECTORY answers "can it list this", so traversal is checked
        # separately and every file inside is probed on its own. A mode-0711 `.ssh`
        # answers `test -r` with 0 while a 0644 `id_rsa` inside is readable — the
        # probe has to catch that, not the listing.
        for secret in (cfg.dev_home / ".ssh", cfg.dev_home / ".gnupg"):
            if not secret.exists():
                continue
            if self.agent_can(secret, "r"):
                report.fail(f"{agent} CAN list {secret} — the boundary does not hold")
            else:
                report.ok(f"{agent} cannot list {secret}")
            if self.agent_can(secret, "x"):
                report.fail(f"{agent} CAN traverse {secret} — a world-readable file inside it is then reachable")
            else:
                report.ok(f"{agent} cannot traverse {secret}")
            for name in sorted(os.listdir(secret)) if os.access(secret, os.R_OK) else []:
                entry = secret / name
                if entry.is_file() and self.agent_can(entry, "r"):
                    report.fail(f"{agent} CAN read {entry} — the boundary does not hold")
                elif entry.is_file():
                    report.ok(f"{agent} cannot read {entry}")

        if self._as_agent("sudo", "-n", "true").rc == 0:
            report.fail(f"{agent} CAN run sudo — it can leave its account")
        else:
            report.ok(f"{agent} cannot run sudo")
        # `docker` is root-equivalent even without sudo.
        if shutil.which("docker"):
            if self._as_agent("docker", "ps").rc == 0:
                report.fail(f"{agent} CAN talk to the docker daemon — that is root equivalent")
            else:
                report.ok(f"{agent} cannot use docker")
        if Path("/dev/kvm").exists():
            if self._as_agent("test", "-w", "/dev/kvm").rc == 0:
                report.fail(f"{agent} CAN write /dev/kvm — the emulator boundary is open")
            else:
                report.ok(f"{agent} cannot write /dev/kvm")


# ---------------------------------------------------------------------------
# run — the transition. No root needed by the caller, and none gained.
# ---------------------------------------------------------------------------


class Launcher:
    def __init__(
        self,
        ctx: Context,
        environ: Mapping[str, str],
        execvp: Callable[[str, list[str]], object] = os.execvp,
    ):
        self.ctx = ctx
        self.environ = environ
        self.execvp = execvp

    def command(self, argv: Sequence[str]) -> list[str]:
        if not argv:
            raise AgentAccountError(f"run needs a command: {SELF_NAME} run -- <cmd> [args...]")
        cfg = self.ctx.cfg
        home = self.ctx.agent_home()
        dev = cfg.dev_home
        # The environment is built here rather than inherited: `--preserve-env`
        # would hand the agent every token and credential path the developer's shell
        # carries, which is the opposite of what a dedicated uid is for.
        env = [
            f"HOME={home}",
            f"USER={cfg.agent_user}",
            f"LOGNAME={cfg.agent_user}",
            "SHELL=/bin/bash",
            f"TERM={self.environ.get('TERM') or 'dumb'}",
            "TMPDIR=/tmp",
            f"PATH={dev}/.local/share/mise/shims:{dev}/.local/bin:/usr/local/bin:/usr/bin:/bin",
            f"GRADLE_USER_HOME={home}/.gradle",
            f"ANDROID_HOME={self.environ.get('ANDROID_HOME') or cfg.sdk_dir}",
            f"ANDROID_USER_HOME={home}/.android",
        ]
        return ["sudo", "--non-interactive", f"--user={cfg.agent_user}", "--", SUDO_COMMAND, "-i", *env, *argv]

    def run(self, argv: Sequence[str]) -> None:
        command = self.command(argv)
        self.execvp(command[0], command)


# ---------------------------------------------------------------------------
# rollback — the account and its grants go. Nothing the developer owns is touched.
# ---------------------------------------------------------------------------


class Rollback:
    def __init__(self, ctx: Context, store: StateStore):
        self.ctx = ctx
        self.cfg = ctx.cfg
        self.runner = ctx.runner
        self.accounts = ctx.accounts
        self.store = store
        self.state = store.load()

    def run(self) -> None:
        cfg, state, accounts = self.cfg, self.state, self.accounts
        existing = accounts.passwd(cfg.agent_user)
        # Without a record there is nothing that says what is this script's.
        if not self.store.exists():
            print(f"nothing to roll back: no record of an apply for {cfg.agent_user}")
            return
        # The ACCOUNT may be gone while this script's own writes are not: a failed
        # `apply` that created the group and then could not create the account left
        # both behind. The record decides what is rolled back — not the passwd database.
        if existing is None and not state.targets and not state.owns("group"):
            print(f"nothing to roll back: no {cfg.agent_user}")
            return
        if existing is None:
            eprint(f"the {cfg.agent_user} account is already gone; undoing this script's remaining writes")

        # The roots passed to `rollback` are NOT a licence to clean them: a target is
        # this script's only when the RECORD says so.
        for root in cfg.roots:
            if root.is_dir() and str(root) not in state.targets:
                eprint(f"kept {root}: this script never provisioned it, so its ACL is not ours to change")

        # Every target this script ever touched, not only the roots passed in:
        # undoing wt1 and leaving wt2 writable would then delete the group whose gid
        # those entries name.
        for key, record in state.targets.items():
            if not os.path.exists(key):
                eprint(f"note: recorded target {key} no longer exists")
                continue
            if self._restore(key, record):
                print(f"restored {key} (the ACL it had before apply)")
                self._scrub(key, record)
            else:
                # No snapshot: the entry is ours to remove. `-x` leaves the `mask::`
                # and the `default:` block it created, which is why a snapshot exists.
                self.runner.run("setfacl", "--recursive", "-x", f"u:{cfg.agent_user}", "-x", f"d:u:{cfg.agent_user}", key)
                self.runner.run("setfacl", "--recursive", "-x", f"g:{cfg.agent_group}", "-x", f"d:g:{cfg.agent_group}", key)

        self._sudoers()
        self._account_and_groups(existing)
        # The record is removed LAST: a failed step above (`userdel` refuses an
        # account with running processes) must leave the snapshots in place, or the
        # next rollback falls back to REMOVING entries instead of restoring them.
        self.store.destroy(self.runner)
        print("=== rolled back; the worktrees, their branches and their git data were not touched ===")

    def _restore(self, key: str, record: TargetRecord) -> bool:
        """Put a snapshot back. A path named in the dump may be gone by now — a file
        or a whole worktree removed since `apply` is normal and not a failure."""
        if record.dump is None or not self.store.dump_exists(record.dump):
            return False
        if self.cfg.dry_run:
            self.runner.plan(f"setfacl --restore=- --physical {key} < <snapshot of {key}>")
            return True
        result = self.runner.capture(
            "setfacl", "--restore=-", "--physical", input_text=self.store.read_dump(record.dump)
        )
        if result.rc == 0:
            return True
        errors = [e for e in result.err.split("\n") if e.strip() and "No such file or directory" not in e]
        if errors:
            raise AgentAccountError(f"could not restore the ACL of {key}: " + "; ".join(errors))
        eprint(f"note: {key}'s ACL restored in part; some of its paths are gone")
        return True

    def _scrub(self, key: str, record: TargetRecord) -> None:
        """Remove this script's entries from the paths a restore did NOT cover — the
        ones created after the snapshot, which the default entries granted `rwX`."""
        # A `node` snapshot covers the target and nothing under it, so "the dump does
        # not mention this child" is TRUE for every child — including files that had
        # `sing-dev` before this script ran. Only a `tree` dump makes absence meaningful.
        if record.kind != "tree" or record.dump is None:
            return
        try:
            covered = dump_paths(self.store.read_dump(record.dump))
        except PermissionError:
            if self.cfg.dry_run:  # the dumps are 0600; the plan cannot be computed unprivileged
                self.runner.plan(f"# (needs root to read the snapshot of {key}: scrub of new paths not listed)")
                return
            raise
        agent, group = self.cfg.agent_user, self.cfg.agent_group
        for path in walk_below(key):
            if path in covered or os.path.islink(path):
                continue
            entries = [f"u:{agent}", f"g:{group}"]
            if os.path.isdir(path):
                entries += [f"d:u:{agent}", f"d:g:{group}"]
            argv = ["setfacl"]
            for entry in entries:
                argv += ["-x", entry]
            # `tolerate`: a path that vanished between the walk and this call must not
            # abort the rollback.
            self.runner.run(*argv, path, tolerate=True)

    def _sudoers(self) -> None:
        path = self.cfg.sudoers_path
        if not path.exists():
            return
        if not self.state.owns("sudoers"):
            eprint(f"kept {path}: this script adopted it rather than created it")
        elif path.read_text(encoding="utf-8").strip() == self.cfg.sudoers_rule:
            self.runner.do(f"rm --force {path}", lambda: path.unlink(missing_ok=True))
        else:
            eprint(f"kept {path}: its content is not what this script wrote — inspect it by hand")

    def _account_and_groups(self, existing: PasswdEntry | None) -> None:
        """Removal of the account and the group, decided from the journal and
        performed by the playbook — `state: absent` only for what this script created."""
        cfg, state, runner = self.cfg, self.state, self.runner
        agent, group = cfg.agent_user, cfg.agent_group
        updates: list[dict[str, object]] = []

        if state.owns("user"):
            # The removal itself is the playbook's `Remove the agent account this
            # tool created` task, driven by `agent_remove_user` — not a facet here.
            pass
        else:
            eprint(f"kept the {agent} account: this script adopted it rather than created it")
            # A membership `apply` did not add is not `apply`'s to remove.
            # ONE list, computed once. Two separate updates each naming the seam
            # failed on a live host: rollback deletes `sing-dev` first, so the
            # second declaration was validated against a group that no longer exists
            # — "Group sing-dev does not exist" — and the privileged groups never
            # came back. `append: false` REPLACES the list, so the seam must simply
            # not be in it, and every restored group must be in this same list.
            current = self.accounts.supplementary_groups(agent)
            desired = [g for g in current if g != group]
            restored: list[str] = []
            returned_primary: str | None = None
            for removed in state.removed_groups:
                if removed.primary:
                    # Held as the PRIMARY gid, so it comes back as the primary gid.
                    if removed.gid is None or self.accounts.group_by_gid(removed.gid) is None:
                        continue
                    returned_primary = removed.group
                    print(f"returning {agent}'s primary group to {removed.group} (gid {removed.gid})")
                else:
                    if self.ctx.in_group(removed.group, agent) or self.accounts.group(removed.group) is None:
                        continue
                    if removed.group not in desired:
                        desired.append(removed.group)
                    restored.append(removed.group)
            if desired != current:
                updates.append({"name": agent, "groups": desired, "append": False})
            for name in restored:
                print(f"returning {agent} to {name} (it held it before apply)")
            if returned_primary is not None:
                updates.append({"name": agent, "primary_group": returned_primary})
            # And the password state: `apply` locked a usable password, which is right
            # for the boundary and wrong to keep on an account it is leaving.
            if state.passwd == "P" and self._password_state() == "L":
                updates.append({"name": agent, "password_lock": False})
                print(f"unlocking {agent}'s password (it was usable before apply)")

        if not state.owns("group"):
            eprint(f"kept the {group} group: this script adopted it rather than created it")
            current = self.accounts.supplementary_groups(cfg.dev_name)
            # Only when this script added it. The developer's OTHER groups are not
            # in the list because `append: false` replaces it — so the list is the
            # current one minus the seam, read in the same pass.
            # shortcut: read-modify-write, so a group added between the read and the
            # task would be dropped; upgrade if rollback ever runs while people are
            # edited concurrently (it is a single-operator repair tool today).
            if group in current and not state.was_member(cfg.dev_name, group):
                updates.append({"name": cfg.dev_name, "groups": [g for g in current if g != group], "append": False})

        variables = {
            "agent_account_state": "absent",
            "agent_user": agent,
            "agent_group": group,
            "agent_home": self.ctx.agent_home(),
            "agent_group_exists": self.accounts.group(group) is not None,
            "agent_remove_user": state.owns("user") and existing is not None,
            "agent_remove_group": state.owns("group") and self.accounts.group(group) is not None,
            # The account's OWN group is removed by name, separately from the seam
            # group: `userdel --remove` deletes it only when USERGROUPS_ENAB is yes,
            # so rollback asks whether it is still there and declares its removal.
            "agent_remove_own_group": state.owns("own-group") and self.accounts.group(agent) is not None,
            "agent_user_updates": updates,
            # The snapshot restored the entries; the playbook must not re-write them.
            "agent_acl_grants": [],
        }
        self.ctx.ansible.run(variables, check=cfg.dry_run)

    def _password_state(self) -> str:
        parts = self.runner.capture("passwd", "--status", self.cfg.agent_user).out.split()
        return parts[1] if len(parts) > 1 else ""


# ---------------------------------------------------------------------------
# command line
# ---------------------------------------------------------------------------


class Refusal(click.ClickException):
    """A refusal or a failed step: `agent-account: <message>` on stderr, exit 2."""

    exit_code = 2

    def show(self, file: object = None) -> None:
        click.echo(f"agent-account: {self.message}", err=True)


class AgentGroup(click.Group):
    def invoke(self, ctx: click.Context) -> object:
        try:
            return super().invoke(ctx)
        except AgentAccountError as error:
            raise Refusal(str(error)) from error


@dataclass
class Deps:
    """The host, as the commands see it. Tests hand in fakes through `obj=`."""

    accounts: SystemAccounts = field(default_factory=SystemAccounts)
    runner_factory: Callable[[bool], Runner] = Runner
    execvp: Callable[[str, list[str]], object] = os.execvp
    geteuid: Callable[[], int] = os.geteuid
    state_dir: Path = DEFAULT_STATE_DIR
    sudoers_path: Path = DEFAULT_SUDOERS_PATH
    # Resolved by the UNPRIVILEGED caller and carried through sudo: neither
    # `ansible-playbook` nor the collection path is on root's secure_path/HOME.
    ansible_playbook: Path | None = None
    ansible_collections_path: str = ""
    #: The test seam: a fake declares nothing to Ansible and records the variables
    #: a real run would have handed the playbook.
    ansible_factory: Callable[["Config", Runner], "Ansible"] = Ansible


def resolve_dev(accounts: SystemAccounts, requested: str | None) -> tuple[str, Path]:
    """The interactive developer. Under sudo it is the caller (`SUDO_USER`); neither
    may be root, because a root-owned home is not the home this provision means."""
    name = requested or os.environ.get("SUDO_USER") or accounts.current_user()
    if name == "root":
        raise AgentAccountError("name the developer: --dev <user>")
    entry = accounts.passwd(name)
    if entry is None or not entry.home:
        raise AgentAccountError(f"no such user: {name}")
    return name, Path(entry.home)


def resolve_ansible(playbook: Path | None) -> tuple[Path, str]:
    """Where Ansible is, resolved BEFORE sudo drops the environment.

    Measured: under a root-like HOME, `ansible-playbook` is not on secure_path, and
    the playbook fails with "couldn't resolve module/action 'ansible.posix.acl'"
    because the default collection path is `{{ ANSIBLE_HOME }}/collections`.
    """
    found = Path(playbook) if playbook else None
    if found is None:
        located = shutil.which("ansible-playbook")
        if not located:
            raise AgentAccountError("ansible-playbook is not on PATH; install ansible-core (see mise.toml)")
        found = Path(located)
    collections = os.environ.get("ANSIBLE_COLLECTIONS_PATH", "")
    if not collections:
        # Run the binary AS FOUND, without `.resolve()`: a mise shim is a symlink to
        # `mise`, and collapsing it runs `mise <playbook>` — measured as
        # "ERROR `…agent-account.yml` is not executable. Run: chmod +x …".
        dumped = subprocess.run(
            [str(found), "--version"], capture_output=True, text=True, check=False
        ).stdout
        match = re.search(r"^  collections path = (.*)$", dumped, re.MULTILINE)
        if match:
            collections = match.group(1).strip()
    if not collections:
        collections = f"{Path.home()}/.ansible/collections:/usr/share/ansible/collections"
    return _unyielding(found), collections


def _unyielding(found: Path) -> Path:
    """The real binary behind a mise shim, because the shim needs mise's environment.

    Measured: `/home/max/.local/share/mise/shims/ansible-playbook` run with root's
    environment (no MISE_*, HOME=/root) answers "ansible-playbook is not a valid
    shim … Run `mise use <TOOL>`", while
    `~/.local/share/mise/installs/ansible-core/<v>/bin/ansible-playbook` runs and
    reports its version. The shim is a launcher for the developer's shell; the root
    half is not that shell.

    `mise which` is the supported way to ask, and it answers with the install path
    for exactly the version this checkout pins. A plain binary is returned as is,
    and so is a shim whose target cannot be determined — the error then names the
    shim, which is more use than a guess.
    """
    resolved = found.resolve()
    if resolved.name != "mise":
        return found.absolute()
    try:
        # `resolved` is `mise` itself, and it has to be: a shim dispatches on
        # argv[0], so running the SHIM as `mise which …` becomes
        # `ansible-playbook which …` — measured as "the playbook: which could not
        # be found".
        asked = subprocess.run(
            [str(resolved), "which", "ansible-playbook"], capture_output=True, text=True, check=False
        )
    except OSError:
        return found.absolute()
    answer = asked.stdout.strip()
    if asked.returncode == 0 and answer and Path(answer).is_file():
        return Path(answer)
    return found.absolute()


def _with_ansible(deps: Deps, playbook: Path | None, collections: str | None) -> Deps:
    """Carry the caller-resolved Ansible paths onto the deps the verb sees.

    Both are optional: without them `resolve_ansible` locates Ansible on the
    caller's PATH and reads the collection path out of `ansible-playbook`.
    """
    if playbook:
        deps.ansible_playbook = Path(playbook)
    if collections:
        deps.ansible_collections_path = collections
    return deps


def make_context(
    deps: Deps,
    *,
    dev: str | None,
    agent_user: str,
    agent_group: str,
    sdk_dir_rel: str,
    roots: Sequence[Path],
    dry_run: bool = False,
    allow_in_home: bool = False,
) -> Context:
    name, home = resolve_dev(deps.accounts, dev)
    playbook, collections = resolve_ansible(deps.ansible_playbook)
    try:
        cfg = Config(
            agent_user=agent_user,
            agent_group=agent_group,
            sdk_dir_rel=sdk_dir_rel,
            state_dir=deps.state_dir,
            sudoers_path=deps.sudoers_path,
            dev_name=name,
            dev_home=home,
            roots=tuple(roots),
            dry_run=dry_run,
            allow_in_home=allow_in_home,
            ansible_playbook=playbook,
            ansible_collections_path=collections,
        )
    except ValidationError as error:
        raise AgentAccountError(describe_validation_error(error)) from error
    runner = deps.runner_factory(cfg.dry_run)
    return Context(cfg, runner, deps.accounts, ansible=deps.ansible_factory(cfg, runner))


def reexec_command(cfg: Config, verb: str) -> list[str]:
    """The root half re-executes this file, so `apply` needs one password prompt and
    the plan cannot drift from what runs. The interpreter is NAMED, not found: sudo's
    secure_path has no developer-installed interpreters. Every setting is passed
    explicitly, because sudo drops the environment."""
    argv = [
        "sudo", "--", sys.executable, "-I", str(SELF_PATH), verb,
        "--dev", cfg.dev_name,
        "--agent-user", cfg.agent_user,
        "--agent-group", cfg.agent_group,
        "--sdk-dir", cfg.sdk_dir_rel,
        "--ansible-playbook", str(cfg.ansible_playbook),
        "--ansible-collections", cfg.ansible_collections_path,
    ]  # fmt: skip
    if cfg.allow_in_home:
        argv.append("--allow-in-home")
    return argv + ["--", *map(str, cfg.roots)]


def elevate(deps: Deps, cfg: Config, verb: str) -> None:
    if cfg.dry_run or deps.geteuid() == 0:
        return
    command = reexec_command(cfg, verb)
    try:
        deps.execvp(command[0], command)
    except OSError as error:
        # `execvp` only returns by RAISING: 127 "not on PATH", 126 "not
        # executable", EPERM/ENOEXEC the rest. Measured on a PATH without sudo: a
        # raw traceback and exit 1, which the CLI documents as "an invariant does
        # not hold" — the wrong answer for a transition that never started.
        #
        # A sudo that RUNS and cannot read a password is not this case: execvp
        # succeeded, so this process IS sudo, and its own exit 1 is the honest
        # answer. The message says so, in sudo's words, and nothing here can
        # improve on that without guessing.
        raise AgentAccountError(f"cannot elevate to root ({error.strerror or error}): {' '.join(command)}") from None


def agent_options(command: Callable) -> Callable:
    options = [
        click.option("--dev", metavar="USER", help="The interactive developer. Default: $SUDO_USER, then the caller."),
        click.option("--agent-user", envvar="AGENT_USER", default="agent", show_default=True, show_envvar=True),
        click.option("--agent-group", envvar="AGENT_GROUP", default="sing-dev", show_default=True, show_envvar=True),
        click.option(
            "--sdk-dir", "sdk_dir_rel", envvar="SDK_DIR_REL", default="Android/Sdk", show_default=True,
            show_envvar=True, help="The Android SDK, relative to the developer's home.",
        ),
        click.option(
            "--ansible-playbook", type=click.Path(path_type=Path), default=None,
            help="The ansible-playbook binary. Default: found on PATH, then passed through sudo.",
        ),
        click.option(
            "--ansible-collections", default=None,
            help="Ansible collections search path. Default: ansible-playbook's own, resolved before sudo.",
        ),
    ]  # fmt: skip
    for option in reversed(options):
        command = option(command)
    return command


roots_argument = click.argument("roots", nargs=-1, type=click.Path(path_type=Path, resolve_path=True))
dry_run_option = click.option("--dry-run", is_flag=True, help="Print the plan; change nothing. Needs no root.")


@click.group(
    cls=AgentGroup,
    context_settings={"help_option_names": ["-h", "--help"]},
    epilog="Exit codes: 0 every invariant holds, 1 at least one does not, 2 usage or error.",
)
@click.pass_context
def cli(ctx: click.Context) -> None:
    """The dedicated unprivileged account coding agents run as."""
    if ctx.obj is None:
        ctx.obj = Deps()


@cli.command()
@agent_options
@dry_run_option
@click.option(
    "--allow-in-home", is_flag=True,
    help="Accept a worktree inside the developer's home (opens its world-readable dotfiles to the agent).",
)  # fmt: skip
@roots_argument
@click.pass_obj
def apply(deps: Deps, dry_run: bool, allow_in_home: bool, roots: tuple[Path, ...],
         ansible_playbook: Path | None, ansible_collections: str | None, **identity: object) -> None:
    """Create or reconcile the account and authorize the worktree ROOTS.

    Needs root: re-executes itself through sudo, so there is one password prompt and
    the plan cannot drift from what runs.
    """
    deps = _with_ansible(deps, ansible_playbook, ansible_collections)
    ctx = make_context(deps, roots=roots, dry_run=dry_run, allow_in_home=allow_in_home, **identity)
    ctx.require_unprivileged_uid()
    validate_roots(ctx)  # everything that can refuse, refuses before the first write
    elevate(deps, ctx.cfg, "apply")
    store = StateStore(ctx.cfg.state_dir, ctx.cfg.dry_run)
    with store.lock():
        Provisioner(ctx, store).apply()


@cli.command()
@agent_options
@roots_argument
@click.pass_obj
def check(deps: Deps, roots: tuple[Path, ...],
         ansible_playbook: Path | None, ansible_collections: str | None, **identity: object) -> None:
    """Print a verdict on every invariant. Needs no root."""
    deps = _with_ansible(deps, ansible_playbook, ansible_collections)
    ctx = make_context(deps, roots=roots, **identity)
    click.get_current_context().exit(Checker(ctx).run())


@cli.command(context_settings={"ignore_unknown_options": True, "allow_interspersed_args": False})
@agent_options
@click.argument("command", nargs=-1, type=click.UNPROCESSED)
@click.pass_obj
def run(deps: Deps, command: tuple[str, ...],
       ansible_playbook: Path | None, ansible_collections: str | None, **identity: object) -> None:
    """Run COMMAND as the agent (needs no root, gains none): run -- CMD [ARGS...]"""
    deps = _with_ansible(deps, ansible_playbook, ansible_collections)
    ctx = make_context(deps, roots=(), **identity)
    # The invariant belongs on the verb that EXECUTES, not only on the one that
    # provisions: `run -- id` must not answer uid 0 — and must not start an agent
    # that acquired `docker` since `apply` ran.
    ctx.require_unprivileged_uid()
    ctx.require_boundary()
    Launcher(ctx, os.environ, deps.execvp).run(command)


@cli.command()
@agent_options
@dry_run_option
@roots_argument
@click.pass_obj
def rollback(deps: Deps, dry_run: bool, roots: tuple[Path, ...],
            ansible_playbook: Path | None, ansible_collections: str | None, **identity: object) -> None:
    """Undo what apply did, from the record it left. Needs root."""
    deps = _with_ansible(deps, ansible_playbook, ansible_collections)
    ctx = make_context(deps, roots=roots, dry_run=dry_run, **identity)
    elevate(deps, ctx.cfg, "rollback")
    store = StateStore(ctx.cfg.state_dir, ctx.cfg.dry_run)
    with store.lock():
        Rollback(ctx, store).run()


def main() -> None:
    cli(prog_name=SELF_NAME)


if __name__ == "__main__":
    main()
