# The agent account — how a coding agent runs as somebody else

## What this document is

Issue #3239: every coding agent in scope runs under a dedicated unprivileged UID, so that
the rest of the emulator guard (#3240 the emulator install and `/dev/kvm`, #3241 execution
confinement, #3242 the launcher service) has a boundary to build on. Without it, an agent
*is* the developer — same UID, same `wheel`/`kvm`/`docker` groups, same `unconfined_t` —
and a file permission that protects the emulator from the developer's own UID protects
nothing.

This is the **setup and migration procedure**, the **rollback**, and the honest limits.
The evidence it is written against is
[`emulator-launch-inventory.md`](emulator-launch-inventory.md); the decision is
`docs/decisions/2026-11-26-agents-run-under-a-dedicated-uid.md`.

**Everything here was measured on the host on 2026-11-26.** Nothing in this procedure was
executed: `apply` needs root, and `sudo -n true` on this host demands a password, so the
live run is the human's step (§4). What *was* executed is the plan (`--dry-run`) and the
verdict (`check`) against an unprovisioned host, and the test suite in
`scripts/tests/test_agent_account.py`.

## The one-line answer

`scripts/agent-account.py` creates a user `agent` (no password, real shell, in no
privileged group), a group `sing-dev` holding the developer and `agent`, ACLs that let
`agent` traverse the developer's home and read the SDK and the toolchain but write only the
worktrees it was given, and one sudoers rule that lets the *developer* re-execute
`/usr/bin/env` as `agent`. Agents start through `scripts/agent-account.py run -- …`, and
`check` turns each acceptance criterion of #3239 into PASS/FAIL.

## Part 1 — What the account is, and what it deliberately is not

### The acceptance criteria, one row each

| #3239 says | State | Where it is held |
|---|---|---|
| Every agent in scope runs with UID `agent` | **Partly.** The transition exists and is verified; two launchers that must use it live outside this repository | §3, §5 |
| Agents can read/write their authorized worktrees, including multiple Git worktrees | Held — an ACL on each root plus a default entry, and on the clone's shared `.git` | §2 |
| Agents cannot read unrelated private files in the developer home | **Partly.** `~/.ssh`, `~/.gnupg` (0700/0600) and any 0700 directory stay shut, and the home cannot be listed; the world-readable dotfiles under it remain readable | §2, §5 |
| Agents cannot obtain a shell or arbitrary command execution as the emulator service account | Not applicable yet — the emulator service account is #3242 and does not exist. What is held today is the inverse: `agent` holds **no** sudo rule, and `check` fails if one appears | §1, §4.1 |
| Git, Gradle and required Android SDK build tools work under the agent UID | **Unverified on this host.** The grants and the environment are what those tools need; `apply` needs root, which an agent session cannot supply, so the live build is a human step nobody has run yet | §2, §3 |
| The setup and migration/rollback procedure are documented | This document, plus `apply --dry-run` / `check` / `rollback` as the executable form of it | §4 |

| Thing | Value | Why |
|---|---|---|
| User | `agent`, uid auto — and never 0, never the developer's | The boundary itself. `apply` refuses an existing `agent` that is root or the developer, and `check` verifies both |
| Password | none | No login. A password is a login an agent could use, and nothing needs one |
| Shell | `/bin/bash` | A coding agent runs commands; `nologin` would make the account useless |
| Groups | `sing-dev` (and its own primary group) — exactly two identities, checked against BOTH the group file and every account's primary gid | `wheel`, `docker` and `kvm` are root-equivalent or own the device — §1.4 of the inventory. A group held as the **primary** gid counts too, and `apply` reconciles it with `usermod --gid`, not only with `gpasswd` |
| Home | `/home/agent`, 0700 | The agent's Gradle home, AVD home and cache live here and nowhere else |
| sudo | **nothing** | An agent that can escalate is not sandboxed. The only rule is the developer's |
| SELinux domain | unchanged, still `unconfined_t` | Confinement is #3241's scope. This issue gives it a UID to confine; it does not confine it |
| Collaboration group | `sing-dev` = the developer + `agent` | The narrow seam for worktrees. It grants nothing by itself |

**The one sudoers rule, and why it is written this way.** `agent` gets no rule. The
developer gets one, in a file this script owns — `/etc/sudoers.d/50-agent-account`. `apply`
refuses to replace that file if it exists and does not already hold this exact rule, because
a host may carry its own file under a similar name and `rollback` would delete it.

```
devel ALL=(agent) NOPASSWD: /usr/bin/env
```

That is what `run` uses to become the agent. It is the *trusted* identity re-executing a
fixed binary as the sandboxed one — the direction that keeps the sandbox a sandbox. The
inverse rule (the agent may run something as the developer) is the escalation #3239
forbids, and `check` fails if one exists.

**A failing step stops the run.** Every system operation goes through `run_cmd`, which dies on
a non-zero exit. It used to return the status and let every caller ignore it, so a failing
`usermod` or `setfacl` was followed by the rest of the run and a `=== done ===` line — a half
provisioned host reported as provisioned.

**What `apply` reconciles on an account that already exists.** It does not assume the
account was created by this script. It refuses a `nologin` shell, locks a usable password
(`passwd --lock` when `passwd -S` says `P`), moves the primary group off `wheel`/`docker`/`kvm`
if the account holds one of them there, drops those groups from the supplementary list, and
resolves the home through the same `agent_home` helper `run` uses — so the state it creates is
the state the transition looks for.

**What is not shared.** The developer's Gradle home is not shared with the agent, and
`run` points `GRADLE_USER_HOME` at `/home/agent/.gradle`. The consequence is stated rather
than hidden: the first build under the agent downloads the Gradle distribution and the
toolchain JDK into the agent's home. That is the honest cost of "an agent cannot write what
the developer's next build reads". Sharing the cache read-only was considered and rejected:
Gradle writes into its own cache during a build, so the first write fails with EACCES —
which a reader diagnoses as a permission bug, not as a policy.

**What is not moved.** The Android SDK (7.8 GB) stays where it is, in the developer's home,
and is read from there. Re-rooting it and protecting it is #3240. Until then the SDK is
reachable through an ACL (§2), and that is the cost §5 states.

## Part 2 — The grant, path by path

`scripts/agent-account.py apply --dry-run <worktree…>` prints exactly this, and the plan is
produced by the same code path that executes it.

| Path | Grant | Who reads it |
|---|---|---|
| `$HOME` | `user:agent:--x` — traverse, **never** a listing | Reaching anything below it, including the SDK |
| `$HOME/Android/Sdk` | `rX` + default `rX`, recursive | `emulator`, `adb`, `sdkmanager`, the system images |
| `$HOME/.local/share/mise` | `rX` + default `rX`, recursive | `java`, `node`, `heavy`, `ktlint` — the toolchain every build calls |
| `$HOME/.local/bin` | `rX` + default `rX`, recursive | `mise` itself |
| each authorized worktree | `group:sing-dev:rwX` + default `rwX`, recursive | The sources, the build outputs, the worktree's `.git` file |
| the worktree's common git dir | same grant, resolved per worktree | Objects and refs — **one** store shared by every worktree of the clone |
| the clone's common `.git` | `group:sing-dev:rwX` + default `rwX`, recursive | Objects, refs, the index — a worktree an agent cannot commit in is not a worktree |

**Why a default ACL and not `setgid`.** `setgid` propagates the group to new files; it does
not give the group a permission bit the umask would otherwise take away. `git` writes
`.git/objects/xx/…`, `git gc` rewrites refs, Gradle writes `build/` — all of them at modes
the developer's umask decides. The default ACL is the entry that applies to whatever those
tools create next, which is the requirement "agents can read/write their authorized
worktrees, including multiple Git worktrees".

**Why the common `.git` is granted, and what that means.** This clone has 74 worktrees and
**one** object store, 2.8 GB. Granting the worktree alone would let an agent read its
sources and fail on `git status`. Granting the common dir means an agent with one authorized
worktree can read the objects of every branch of every worktree in the clone. That is a
deliberate, bounded exception: branches are not secrets (they are pushed to the same remote),
and the alternative — a separate clone per agent — is exactly what #3239 puts out of scope.

## Part 3 — How each supported agent starts under the UID

`run` is the transition, and it needs no root from its caller:

```bash
scripts/agent-account.py run -- ./gw :shared:compileKotlinJvm
scripts/agent-account.py run -- bash -lc 'cd ~/work/wt && git status'
```

### How it is started, and where the work is split

`scripts/agent-account.py` is Python, and `scripts/ansible/agent-account.yml` is the
declaration it hands the work to. The split follows one rule: **Ansible owns the declared
system state; the script owns every decision Ansible cannot make.**

| Resource | Owner | Why there |
|---|---|---|
| Group `sing-dev`, user `agent`, its home and `.gradle`/`.android`/`.cache` | `ansible.builtin.group` / `user` / `file` | Idempotent declared state |
| Memberships, the password lock, a primary group change | `ansible.builtin.user` | Same, as facets of the account |
| ACLs on the home, the SDK, the toolchain and the worktrees | `ansible.posix.acl` | Declared grants, `--check --diff` gives the plan |
| **Which resource may be REMOVED** | the journal in `agent-account.py` | Ansible matches a declaration; it cannot know an `agent` account predated this tool |
| **ACL snapshots and the scrub** | `getfacl` dump + `setfacl --restore` in `agent-account.py` | A dump is the tool's own inverse; it is not a declared state |
| **The sudoers file** | `agent-account.py` | The path is shared, so "who wrote it" decides whether it may be replaced |
| **`check`** | `agent-account.py` | The verdicts are behaviour: `access(2)`, SELinux, `sudo -l` |
| **`run`** | `agent-account.py` | It is a transition, not a state |

`apply --dry-run` is `ansible-playbook --check --diff` plus the snapshot lines the plan would
write: it needs no root and changes nothing. Measured limitation, and why the plan prints two
things itself: under `--check` on a host that has no `agent` yet, `setfacl` and `user` cannot
be asked to plan an entry whose NAME does not resolve, so the memberships and ACLs appear as
`usermod`/`setfacl` lines from the same declaration the playbook executes.

Install once per machine:

```bash
mise install                                          # ansible-core, pinned in mise.toml
ansible-galaxy collection install -r scripts/ansible/requirements.yml   # ansible.posix
```

Three starting forms, each verified on this host:

```bash
./scripts/agent-account.py check ~/work/wt1     # the shebang; needs the script executable
python3 scripts/agent-account.py check ~/work/wt1
uv run scripts/agent-account.py check ~/work/wt1
```

Two facts about the root half, both measured:

- `sudo` runs with its own `secure_path` and its own `HOME`, and **neither** is the
  developer's. So the root half re-executes the script by an absolute interpreter
  (`sudo -- /usr/bin/python3 -I <script> …`), and `ansible-playbook` and
  `ANSIBLE_COLLECTIONS_PATH` are resolved by the **unprivileged** caller and passed through.
  Without that, the playbook fails with `couldn't resolve module/action 'ansible.posix.acl'`
  because Ansible's default collection path is `{{ ANSIBLE_HOME }}/collections`.
- The script runs as root, so its imports are root's code. That is why `ansible-core` is
  pinned in `mise.toml` and `ansible.posix` in `requirements.yml`: the plugins that became
  root are the ones this repository reviewed.

It executes `sudo -n --user=agent -- /usr/bin/env -i HOME=… PATH=… GRADLE_USER_HOME=… "$@"`.
The `PATH` it sets is `$HOME/.local/share/mise/shims:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin`
— the shims first, because `java`, `node` and `heavy` are **shims** mise generates there, not
files in `~/.local/bin`, so a `PATH` without that directory cannot find the toolchain the build
calls.
`-i` matters: without it, every token and credential path in the developer's shell rides
into the agent, which is the opposite of what a dedicated UID is for. `check` asserts that
the variable it plants in the caller's environment does not arrive.

| Agent | How it starts today | What has to change | Where |
|---|---|---|---|
| Terminal coding-agent sessions (the desktop app's per-PTY daemon) | the daemon spawns the agent as the developer, in `user@1000.service` | the daemon spawns `agent-account.py run -- <agent>` instead of the agent directly | **outside this repository** — the desktop app's PTY daemon |
| The AFK runner's sessions (`docs/architecture/afk-runner.md`) | the `pi-afk-runner` extension, as the developer | one issue → one worktree → one session, each wrapped in `run` | **outside this repository** — `gazon1/pi-afk-runner` |
| In-app ACP agents (Gemini CLI / Claude Code / Codex) | `SubprocessAcpAgentLauncher` (`shared/src/jvmMain/kotlin/com/singularity/todo/feature/ai/acp/AcpAgentLauncher.kt:78`), a child of the desktop app, as the developer | not changed here: the application starts agents on the user's behalf in the user's session, and #3239's scope is the agents that drive the emulator | this repository |

The two launchers outside this repository are the reason the acceptance criterion "every
agent in scope runs with UID `agent`" cannot be closed by this change alone: what this
repository can do is make the transition a one-line command and prove the boundary holds.
The desktop app's daemon is the one that matters for the emulator, because it is what a
coding agent in *this* checkout runs under.

**Why the in-app ACP launcher is left alone.** It spawns an agent the user selected, in the
user's own desktop session, to talk to it over a protocol — it is the application's feature,
not a coding agent working on this repository. Changing it would mean the app cannot connect
to a user-installed Gemini CLI that lives in the user's home, and the emulator guard gains
nothing: the app does not drive the emulator. If a later issue decides otherwise, the seam is
one file (`AcpAgentLauncher.kt`) and one DI binding (`AcpDiModule.jvm.kt:35`).

## Part 4 — Setup, migration, rollback

### 4.1 Setup, on a host that has no account

```bash
# 1. The plan. No root, nothing happens.
./scripts/agent-account.py apply --dry-run ~/work/wt1 ~/work/wt2

# 2. Apply. One password prompt; the script re-executes itself under sudo.
./scripts/agent-account.py apply ~/work/wt1 ~/work/wt2

# 3. The verdict: every acceptance criterion, one line each.
./scripts/agent-account.py check ~/work/wt1 ~/work/wt2
```

Prerequisites: `ansible-core` (pinned in `mise.toml` — `mise install` puts it on `PATH`), the
`ansible.posix` collection (`ansible-galaxy collection install -r scripts/ansible/requirements.yml`,
for the `acl` module) and `acl` (`setfacl`/`getfacl`, present on Fedora by default). Python 3.12+
with `click` and `pydantic` — both already required by `scripts/src/pyproject.toml`. `apply` is idempotent: it creates
what is missing and reconciles what is there. Before the first write it validates every root:
a directory that is not a git worktree is refused with **nothing created**, because a host
that is half-provisioned is worse than a refused run. `rollback` is the exception — it accepts
a worktree that was removed, since the grants it cleans up live on the developer's paths and
outlive the worktree. The worktree paths are the **authorized** ones — the roots an agent may
write. Passing one more is how a new worktree group is opened; nothing has to be re-run for a
worktree created inside an authorized root later, because the default ACL is what covers it.

**Where the worktrees should live.** Outside the developer's home. A path inside it works —
`--allow-in-home` opts in — but it is the worktree that makes the `--x` traverse grant
load-bearing rather than incidental, and the script says so at the top of its output. `$HOME`
itself is refused **outright, even with `--allow-in-home`** — a different reason than the traverse
cost. `apply` snapshots `$HOME` as a single NODE (the `--x` grant is not recursive) and would then
snapshot it again as a worktree TREE, while the target registry keys by path: the node record won,
the recursive `setfacl` granted over the whole home, and rollback restored the node alone while the
scrub skipped it — leaving `sing-dev:rwX` on every descendant for good. A directory cannot be both
the non-recursive seam and a recursive root. As a second defence the record is upgraded to `tree`
if that collision is reached any other way, since a tree snapshot covers a node one, never the
reverse.

### 4.2 Migration of the agents that exist today

Nothing about the running sessions changes by itself: a process that is already the developer
stays the developer until it restarts. The order that does not leave a window where an agent
is unguarded *and* trusted to write:

1. `apply` for the worktrees the agents use.
2. `check` — all green before any launcher changes.
3. Restart the launchers (§3) so new sessions start through `run`. Existing sessions finish
   as the developer; kill them if the boundary has to hold immediately.
4. Re-run `check` with a fresh agent session, and confirm the UID from inside it:
   `scripts/agent-account.py run -- id` → `uid=<agent's uid>(agent)`.

### 4.3 Rollback

```bash
./scripts/agent-account.py rollback --dry-run ~/work/wt1   # what goes
./scripts/agent-account.py rollback ~/work/wt1
```

It puts back the ACL **the target had before `apply` ran**, from a snapshot taken first. Three
attempts at removing "our entry" instead failed for one reason: the write is `--recursive`, so
"the subject's entry" is not the state that changed. Measured on a throwaway tree:

- `--modify` OVERWRITES a named entry that was already there, so a host that granted `sing-dev`
  on a worktree before `apply` ran lost that grant to a rollback of a change it never made;
- `-x` does not undo `--modify`. It leaves `mask::r-x` behind and takes the **whole `default:`
  block** with it, silently rewriting the modes of files nobody here named (`chmod g=rx` does not
  drop the mask either);
- a snapshot capped at 200 directories is not a snapshot of a tree that was written at every file.

So the record is `getfacl --recursive --physical`'s own output, one file per target in
`/var/lib/agent-account/acl/`, restored with `setfacl --restore` — the tool's own inverse, with
no delimiter to guess at. A path may legally contain `;` and `=`, which is why the old
`path=entry;path=entry` lines could not round-trip one; a path now maps to its file name through
`string escape --style=var`. The snapshot is taken **once**, before the first write: a second
`apply` would otherwise record the state `apply` itself wrote, and rollback would faithfully
restore that. The cost is honest — `getfacl -R` walks the SDK and the toolchain (~68–87 s each
on this host, ~25 MB of dumps) — and it is paid only by `apply`, which already walks them to
write the grants.

Targets are the `$HOME` traverse grant (non-recursive), the SDK and the toolchain, and every
worktree **and** its common git dir. The list lives in `/var/lib/agent-account/targets`, so
rollback walks every one of them and not only the roots it is passed — undoing one worktree used
to leave another writable. Each entry is `string escape --style=var`-escaped: a path may legally
contain a newline, and a raw line-per-target registry then answers two entries for one path.

`apply` snapshots **every** target before writing to **any** of them. A repository nested inside
another authorized root is inside the outer tree's recursive dump (measured: a `git init` inside a
worktree puts 27 paths of the inner `.git` into the outer dump), so an interleaved loop would
snapshot the inner target after the outer grant had already written there — recording
`sing-dev:rwX` as the previous state, which rollback would then faithfully reintroduce.

It then removes the sudoers rule, the account and the group — **only if this script created
them**. `apply` deliberately adopts a pre-existing `agent`, `sing-dev` or sudoers file, so it
records which of the three it created (`/var/lib/agent-account/state`, written before the first
write); `rollback` deletes an owned one and keeps an adopted one, saying so. The account's OWN
group is one of them: `useradd --user-group` creates a group named `agent`, and `userdel
--remove` deletes it again **only** when `USERGROUPS_ENAB` is `yes` in `/etc/login.defs`. It is
`yes` on this host, which is exactly why trusting either behaviour was wrong — on a host that
sets it `no`, every apply/rollback cycle left that group orphaned. The account is created with
an explicit `--home-dir $agent_home_dir`: without it the home base comes from
`/etc/default/useradd`, while `run` reads the home back from the passwd database, so a host with a
different base would have `apply` populate one directory and `run` build its environment from
another. Every append to this metadata is checked, because a record that silently failed to write
is the one failure rollback cannot survive: the account is created and recorded as created, the
write fails, and `rollback` then finds neither an ownership entry nor a target. The sudoers file is
re-checked against its exact content before deletion, because an administrator may have added a
rule to it after `apply` ran.

**Membership and privileged groups are not reversible by symmetry**, so they are recorded too.
`apply` appends the developer and the agent to `sing-dev` and takes `wheel`, `docker` and `kvm`
away from a pre-existing agent. A rollback that deleted the membership would remove a developer
from a group they chose, and one that left the privileged groups gone would erase the fact that
they were there. Both are recorded before the change (`member\t<user>\t<group>` and
`removed-group\t<group>` in the state file), and rollback keeps a membership it did not add and
returns a group `apply` took away — as the KIND it was. `usermod --append --groups` restores a
supplementary membership, so an account whose **primary** gid was `docker` came back with `agent`
as its primary group and `docker` as a supplementary one: provisioned correctly, rolled back
wrong. The gid is recorded with the group and restored with `usermod --gid`. The same asymmetry applies to the password: `apply` locks a
usable one, so rollback on an adopted account unlocks it again — the state is recorded before the
lock, and only a password that was usable is restored.

Rollback does not depend on the account existing. A failed `apply` that created the group and then
could not create the account left both behind, and an early "no `agent`, nothing to do" made them
permanent. The record decides what is undone; the passwd database only says whether the account
itself is still there to delete.

**Two things make `apply` refuse before it writes**, because each one makes the boundary a lie
that the rest of the run would then report as holding:

- an adopted `agent` whose home is `/`, or inside the developer's home. `apply` chmods that path
  and installs the agent's `.gradle`/`.android`/`.cache` into it, so a stale passwd record would
  put the agent's private state *inside* the files it must not share — the separation undone by
  one line of `/etc/passwd`;
- an `agent` that holds a **sudo rule** — created **or** adopted. Reconciling groups does not
  touch sudoers, and a rule is exactly the escalation this change forbids. This script does not
  edit somebody else's sudoers line — it refuses and names the rule, so a human decides. The
  audit runs ONCE, at the end of provisioning, after the `sing-dev` membership exists: it used to
  live inside the existing-account branch and run before that membership, so a host whose
  account this script had just created was never audited and a group-scoped rule was invisible.
  A run that cannot read the rules (no root) says so and tells the reader to run `check`
  afterwards, because an unverified claim is not a clean one.

Four things `rollback` and `check` must not take on trust, each measured:

- a `rollback <path>` argument is **not** a licence to clean that path. A target is this
  script's only when the record says so; an unrecorded root is reported and left alone, because
  an operator can always delete an entry by hand and cannot undelete one.
- a sudoers file whose content is exactly the rule is **adopted**, not owned. Content this
  script would have written is not proof that it wrote it, so authorship is recorded and an
  administrator's own file survives the rollback.
- `sing-dev` holds the documented two and nobody else — and `check` asks that question too, not
  only `apply`. A member added after provisioning holds the same recursive write access.
- a path the agent created **after** the snapshot cannot be named by the dump, so it keeps the
  grant the default entry gave it while the group is deleted underneath it. Those paths are
  scrubbed by subject; the ones the dump covered were just restored, and are left exactly as
  the restore put them. The kind of snapshot is recorded with the target for exactly this reason: a
`node` dump (the `$HOME` traverse grant) names one path and nothing below it, so "the dump does not
mention this child" is true for every child — including files that carried `sing-dev` before this
script ran. Only a `tree` snapshot is scrubbed. The walk is NUL-separated (`find -print0` + `string split0`), because a
  path may contain a newline and a plain `find` then emits it as two half-paths — one of which
  would be scrubbed while the other was skipped. The skip test asks `getfacl` for the path's own
  `# file:` line instead of rebuilding it: the tool escapes what cannot sit on one line (a
  newline becomes `\012`), and a matcher that guessed at the escape would drift.

`run` refuses an account with uid 0 or the developer's uid before it crosses. `apply` asking
that question is not enough: `run` is independently callable, and it is the verb that
*executes*.

`--dry-run` is a pure description on every verb. Every state-changing command goes through one
`run_cmd`, which prints the step instead of running it — `restore_acl` and `scrub_unrecorded`
called `setfacl` directly once, so `rollback --dry-run` **as root** rewrote the ACLs it was only
printing. The snapshot directory is `0755` (the dumps inside are `0600`) so an unprivileged dry
run can `stat` them: at `0700` it could not, and printed "no snapshot, remove the entries" where
the real run would have printed "restore".

### Asking the kernel, not the ACL text

The `getfacl` parsing above answers "is the entry there, and does the mask let it through". It
cannot answer "can the agent actually open this": SELinux, the mode on a parent directory and a
read-only mount all defeat a perfectly correct ACL, and none of them appears in `getfacl`. So
`check` also runs probes **as the agent**, where `test -r` / `test -w` are `access(2)`:

- `test -r` **and** `test -x` on the SDK and every toolchain directory it was granted. Both bits,
  because on a directory they answer different questions: a mode-`0644` directory passes
  `test -r` and cannot be entered at all (measured), so a read-only probe certified an SDK the
  agent could not walk into;
- `test -w` **and** `test -x` on each authorized worktree, for the same reason — write without
  traverse cannot create a file inside it;
- on `~/.ssh` and `~/.gnupg`, **three** questions, because they have different answers: `test -r`
  on the directory (can it LIST), `test -x` on the directory (can it TRAVERSE), and `test -r` on
  every file inside (can it OPEN one). A mode-0711 `.ssh` answers the first with "no" and the
  second with "yes", and a mode-0644 `id_rsa` inside is then readable by name — a probe that
  stopped at the directory's `test -r` reported PASS while the key was exposed (measured);
- `sudo -n true` and `docker ps` — **must** fail; and `test -w /dev/kvm`, which is #3240's
  boundary, so it is a NOTE today and a FAIL the moment it opens.

Every *negative* probe is gated on the transition itself having worked. An agent that can run
nothing at all also "cannot run sudo", and reporting that as a clean boundary would describe a
wall as a door — so when the transition is broken the probes say `NOTE unverified` instead.

The "no read grant on the home" verdict answers the question the kernel asks for a **newly
created file**, not "is there a named entry with an `r`". The weaker predicate was wrong twice
over:

- it covered named default entries and missed `default:other::r--`. That entry is a real read
  grant, and the default block **overrides the creator's umask** — measured: with
  `default:other::r--`, `(umask 077; touch f)` produces a mode-0644 `f`, where the same umask
  without the entry produces 0600. Every file created under the home is then readable, and the
  traverse grant makes it reachable;
- it ignored the unnamed entries reached by ownership and group (`group::r--` when the agent is
  in the group that owns the home).

So `check` weighs each entry the way the kernel would, and the mask is deliberately *not*
applied: a mask caps named entries only, and `other` is never masked (measured: `o::r--` with
`umask 077` yields 0600 while `mask::r--` alone leaves 0644 — the mask is not the mechanism).

`check` reads the **effective** permission, not the raw entry, for the grants it *looks for*: an
ACL mask constrains every named entry, so a `group:sing-dev:rwx` under `mask::r-x` is a read-only
worktree and is reported as such. `X` is compared the way `chmod` defines it — execute on a
**directory**, read on a regular file — because "read or execute" told a directory with effective `rw-` that it satisfied
`rwX`, and a directory without execute cannot be traversed at all. It compares the effective bits
against the bits the grant asks for, entry by entry —
a check that grepped for the word "read" called a mask of `-wx` (read denied) healthy. The entry
is addressed by its `subject:name:` prefix, because `getfacl` normalizes the bits it prints: a
requested `rX` comes back as `r-x` or `r--`, so matching the whole entry finds nothing at all. It does **not** touch the worktrees, their branches or the git data:
undoing the boundary must never be a reason to lose work. `/home/agent` goes with
`userdel --remove`, which means the agent's Gradle home and its AVD home go too — that is
cache, and the next `apply` rebuilds it. **Everything else the agent wrote there goes too**,
including its AVDs and any file it created outside the authorized worktrees: `rollback` is not
a cache clear, it is a home removal. `--dry-run` names the `userdel --remove` it would run, so
the deletion is visible before it happens; anything worth keeping has to be moved out first.

The rollback metadata under `/var/lib/agent-account` is deleted **last**, after every account
and group step has succeeded. `userdel` refuses an account that still has processes and
`groupdel` refuses a group that is still somebody's primary one — and an early deletion would
leave the host half-rolled-back with the snapshots gone, so the next rollback, finding none,
would *remove* the ACL entries instead of restoring them.

## Part 5 — What this does not do, and what it costs

| Limit | Consequence |
|---|---|
| The SELinux domain is still `unconfined_t` | The UID boundary is DAC. An agent is now somebody else, but it is not confined. #3241 owns that, and this issue's job was to give it a UID to confine |
| `$HOME` is traversable | `agent` can open any file under the developer's home whose own mode allows it — `.gitconfig`, `.envrc`, the mode-0644 dotfiles. It cannot *list* the home. The home itself is 0700, `~/.ssh` is 0700 and `~/.gnupg` is 0700, so the secrets stay shut — but a file that was unreachable from outside the home becomes reachable through the granted path, and that is new. This goes away when the SDK and the toolchain stop living in the developer's home (#3240) |
| The common `.git` is writable by `sing-dev` | An agent may read every branch in the clone, and write objects any worktree would trust. Bounded by the group: one developer, one agent account |
| The developer's Gradle cache is not shared | The first build per agent downloads the distribution and the JDK: minutes and gigabytes, once |
| The launchers are outside this repository | Two of the three cannot be switched from here (§3). The criterion is closed for the agent that drives the emulator when the desktop app's daemon is changed |
| `check` cannot read another user's sudo rules without root | It reports `NOTE … unverified` instead of a PASS. A check that could not run is not a clean bill of health |
| No adversarial test | #3246 owns bypass and recovery tests. `scripts/tests/test_agent_account.py` covers the plan, the opt-in and the environment the `run` transition hands over — not what a determined agent does with the UID |

## Part 6 — Re-verifying this document

```bash
# the plan and the verdict, which is the whole procedure
./scripts/agent-account.py apply --dry-run <worktree>
./scripts/agent-account.py check <worktree>

# the account, as the host holds it
getent passwd agent; getent group sing-dev
id agent                       # no wheel, no docker, no kvm
getfacl -p "$HOME" | sed -n '1,10p'          # --x, and no read grant
getfacl -p "$HOME/Android/Sdk" | sed -n '1,10p'
sudo -n -l -U agent            # nothing
sudo -n -l | grep -A2 'agent'  # the one /usr/bin/env rule, the developer's

# the transition
./scripts/agent-account.py run -- id

# the test suite
python3 -m unittest discover -s scripts/tests -p 'test_agent_account.py'

# the declaration itself, without touching the host
ansible-playbook --syntax-check -i localhost, scripts/ansible/agent-account.yml
yamllint scripts/ansible/agent-account.yml
ansible-lint scripts/ansible/agent-account.yml
```

The variables file the playbook receives exists only for the duration of a run (`apply` writes
it, hands it to `ansible-playbook` and unlinks it), so the way to read the declaration is the
plan: `apply --dry-run` prints the two lines the playbook cannot plan itself and then
`ansible-playbook --check --diff` prints the rest.
