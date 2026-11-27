---
title: The agent account is declared in Ansible, and the script that owns its journal is Python
date: 2026-11-27
status: accepted
tags: [process, tooling, agent, security, host]
---

## Context

`2026-11-26-agents-run-under-a-dedicated-uid.md` took the decision — a dedicated unprivileged
uid with a narrow group, ACLs and one sudoers rule — and implemented it as
`scripts/agent-account.fish`, 1636 lines that called `useradd`, `usermod`, `groupadd`,
`install`, `setfacl` and `chmod` by hand.

The script's value was never in those calls. It was in the six things around them:

1. the journal of `created` vs `adopted`, which is what makes `rollback` safe;
2. ACL snapshots taken before the first write, and the scrub of paths created after them;
3. sudoers ownership, because the path is shared;
4. `check`, which asks the kernel and `sudo -l` rather than reading ACL text;
5. the refusals (a worktree in the home, `$HOME` as a root, a foreign group member);
6. the transition, `run`.

The hand-written provisioning around them was 35 call sites of the same shape: build an argv,
run it, hope it is idempotent. Each one was a place where a stale read could rewrite state that
belonged to somebody else — measured twice during that work: `usermod` with a computed group
list would have replaced the developer's supplementary groups, and `usermod --user-group` fails
on a pre-existing own-group.

## Idea

Move only the **declared system state** into Ansible, and keep the decisions in a language that
can hold them:

- `scripts/ansible/agent-account.yml` — group, account, home, private directories, memberships,
  password lock, primary group, ACLs, and the `absent` direction of each;
- `scripts/agent-account.py` — the journal, the snapshots, the scrub, the sudoers ownership
  question, `check`, `run`, and the CLI.

Ansible receives an already-validated declaration. It never decides *whether* a resource may be
removed, because that is the one question its model cannot express: it makes the host match a
declaration, and "this account predated the tool" is not part of any declaration.

Alternatives rejected:

- **A Python wrapper that shells out to Ansible per command.** Keeps every argv site and adds a
  process boundary.
- **pyinfra.** Playbooks in Python, so the port would be smaller — but it has no ACL operation,
  so the ACL half would become `setfacl` wrappers again, which is the code this change removes.
- **`template` for sudoers.** The module validates with `visudo -cf %s`, which is right, but it
  replaces the target without asking who wrote it. The ownership question is answered by the
  journal, so the install stays next to its record.
- **Keeping the fish script and adding a playbook beside it.** Two implementations of one
  contract, and the tests would pin the one nobody runs.

## Decision

Adopt the split above, and delete `scripts/agent-account.fish`.

Concretely:

- `apply --dry-run` is `ansible-playbook --check --diff`. Measured limitation, surfaced in the
  plan rather than hidden: on a host with no `agent` yet, `setfacl` and `user` resolve the
  entry's NAME while planning, so they cannot plan it. The plan prints those memberships and
  ACL entries itself, from the same declaration the playbook executes.
- `rollback` decides ownership from the journal and performs the removal through the same
  playbook (`state: absent`), so what `apply` creates and what `rollback` removes are one
  resource set.
- `check` and `run` do not touch Ansible: both are behaviour and transition.
- The playbook's ACL grants are a PAIR per target (access + default), never one task with both
  flags. Measured with `ansible.posix` 2.2.2: `recursive: true` with `default: true` writes the
  default entry to directories only and leaves existing files untouched — not what
  `setfacl --recursive --modify … --modify default:…` does.
- `ansible-core` is pinned in `mise.toml`; `ansible.posix` is pinned in
  `scripts/ansible/requirements.yml`, because mise has no galaxy backend.
- `ansible-playbook` and `ANSIBLE_COLLECTIONS_PATH` are resolved by the **unprivileged**
  caller and passed through `sudo`. Measured: under a root-like `HOME` the playbook fails with
  `couldn't resolve module/action 'ansible.posix.acl'`, since Ansible's default collection path
  is `{{ ANSIBLE_HOME }}/collections`.
- The `ansible-playbook` that gets passed through is the **real binary**, never a mise shim.
  Measured while preparing the first live run: the shim in `~/.local/share/mise/shims/` run
  with root's environment (no `MISE_*`, `HOME=/root`) answers `ansible-playbook is not a valid
  shim … Run 'mise use <TOOL>'`, while `~/.local/share/mise/installs/ansible-core/<v>/bin/
  ansible-playbook` runs. A shim is a launcher for the developer's shell, and the root half is
  not that shell. `mise which` is asked for the install path — by path, because a shim
  dispatches on `argv[0]` and `shim which ansible-playbook` becomes
  `ansible-playbook which …` ("the playbook: which could not be found").

## Rationale

- **The declared resources are now declared once.** A second `apply` asks Ansible, which
  reports `changed: false`; the hand-written version had to be idempotent by construction at
  every one of 35 sites.
- **The decisions kept their home.** The journal, the snapshots and the verdicts are unchanged
  in behaviour and now have a language with types, a test seam that is an object rather than a
  stub binary on `PATH`, and no shell quoting to get wrong. The suite went from 166 tests run
  through a stubbed shell to 92 in-process tests over the same invariants.
- **The split answers "why not Ansible for everything"** in one line: Ansible cannot tell an
  account it created from one it found, and that distinction is the whole of `rollback`
  safety.
- **A cheaper `run`.** `run` is a transition, not state; declaring it would add a playbook run
  to every agent command.

## Consequences

- **`config`/`hooks` had to become "`.git/` is not writable at all".** Denying the two files
  outright does NOT hold, and a live probe broke it in one command: `git config --local` creates
  `config.lock` in `.git/` and RENAMES it over `config`, and rename needs write on the directory,
  not the target — the removed entry protected only the old inode. Measured with a second uid:
  `.git/config` came back owned by it, carrying the inherited `rwX` entry, and the
  `core.hooksPath` it then named ran as the developer. `.git/` is `r-x` now and only `objects`,
  `refs`, `logs` and `worktrees` are granted. Measured: status/diff/add/commit/branch/checkout
  all still work; `git config --local` is refused.
- **An ACL mask that would widen a third party's permissions is a refusal.** Measured:
  `user:nobody:rwx #effective:r--` under `mask::r--` became fully effective when the mask grew
  for our grant. Provisioning now compares every existing entry's effective bits against the
  widened mask and stops with the entry named, rather than silently loosening somebody's ACL
  (the snapshot would restore it only at rollback; the expansion lasts the whole session).
  Two holes in the first version of that check, found after it merged and both verified on
  the kernel: `AclEntry.named` is FALSE for `group::`, so the OWNING GROUP was skipped —
  `group::rwx #effective:r--` under `mask::r--` widened to `rwx` just like a named entry,
  because the mask caps the whole group class; and `X` was compared as a letter, so
  `'x' in "rX"` was False and the execute bit vanished from the calculation. `user::`
  (owner) and `other::` are deliberately NOT reported: both sit outside the mask —
  measured, `user::rwx` under `mask::r--` stays `rwx`.
  Two holes in the first version of that check, both found after it merged and both
  verified on the kernel: `AclEntry.named` is FALSE for `group::`, so the OWNING GROUP
  was skipped — `group::rwx #effective:r--` under `mask::r--` widened to `rwx` just
  like a named entry, because the mask caps the whole group class; and `X` was
  compared as a letter, so `'x' in "rX"` was False and the execute bit vanished from
  the calculation. `user::` (owner) and `other::` are deliberately NOT reported: both
  sit outside the mask — measured, `user::rwx` under `mask::r--` stays `rwx`.
- **The shared `.git` grant stops at objects, refs and the index.** A review called the
  recursive write grant on the common git dir a merge blocker, and it was right about the
  mechanism: `config` chooses `core.hooksPath` and every setting for later invocations, and
  `hooks/` holds programs git runs — **as the developer**. That is code execution across the
  boundary this account exists to create. The grant now excludes both by removing the entry
  (access and default), and `check` fails on a write grant there. Measured: with `config` at
  `0444` and `hooks` at `0555`, commit, branch and checkout still work. The deeper
  recommendation — a separate agent-owned clone with an explicit patch/commit transfer — is a
  design change and is NOT taken here.
- **The ACL mask is preserved where it covers the grant.** Also from that review, and also
  measured: `setfacl --modify` recalculates the mask, and an unrelated named entry capped by
  `mask::r--` became fully effective when our entry was added. `apply` now passes
  `recalculate_mask: no_mask` per target when the existing mask already covers its entry, and
  lets the mask grow where it does not — the alternative (`--no-mask` unconditionally) caps
  the agent's own entry to the group bits.
- **`run` re-checks the boundary at launch, and asks sudo as a BEHAVIOUR.** `apply`'s result is
  not permanent; `run` refuses an account in a privileged group (primary included), a home that
  is not its own, or one that can run sudo. The first version of this check read
  `sudo -n -l -U agent` and continued when it failed — which is what happens for a non-root
  caller, so an administrator who granted `agent ALL=(ALL) NOPASSWD: ALL` after provisioning
  produced an agent `run` started happily. An unreadable listing is not evidence of safety:
  `run` now execs `sudo -n true` as the agent, which is the property itself and needs no root,
  and refuses when neither question can be answered.
- **sudoers is installed from a unique `mkstemp` file.** The fixed `<target>.tmp` came with
  `unlink(missing_ok=True)`, which deleted a file of that name whether or not this script made
  it, and two concurrent applies shared one path. It is `mkstemp` in the target's directory
  (atomic rename), mode set on the descriptor before any write, `visudo` before the replace, the
  directory fsynced after, and only the file this call created removed on failure.
- **The home read-grant verdict asks the kernel's question.** A review of the deleted fish
  script found the audit passing a home carrying `default:other::r--`. It was real, and it
  survived the port: the predicate listed NAMED entries with an `r`. Measured consequence — the
  default block overrides the creator's umask, so `default:other::r--` plus `umask 077` yields a
  0644 file where the same umask alone yields 0600, and everything under the home is then
  readable through the traverse grant. `check` now weighs each entry the way the kernel does for
  a new file (named user, named group, the path's OWNING group, and `other`), and does not apply
  the mask, which caps named entries only.
- **The journal baseline is first-write-wins, and stays that way.** A review reported that a
  second `apply` could overwrite the recorded pre-provisioning state. That was a real hazard in
  the fish version (`recorded_value` returned the LAST record); the state model here records
  each resource once (`record_passwd`, `observe_member`, `record_owned` all `setdefault`, and
  `created` is sticky). Pinned by `JournalBaselineTest` rather than left as a reading of the
  code.
- Two dependencies where there were none: `ansible-core` (pinned) and the `ansible.posix`
  collection. Both are installed by `mise install` and one `ansible-galaxy` command; both are
  pinned because the script that drives them runs as root.
- **The lifecycle ran for real on 2026-11-27, in a disposable Fedora container.** Both
  directions: `apply` created the account, the seam group, the one sudoers rule and the ACLs;
  `check` reported 20 PASS plus one correct FAIL (no Android SDK in a bare container); the agent
  committed as uid 1001 through `run`; `rollback` removed the account, its group, the sudoers
  file and the state directory, and left the worktree ACLs **byte-for-byte identical** to the
  pre-apply capture. An adopted account with `wheel`/`kvm` and a usable password was
  provisioned and rolled back too: privileges stripped, account kept, groups and password
  restored.

  Three findings came out of it, all fixed: `git` needs `safe.directory` for a worktree owned by
  another uid (a real onboarding step); rollback declared the seam group in a `groups` list
  after deleting it, so "Group sing-dev does not exist" aborted the restore of `wheel`/`kvm`;
  and a `mask::r--` that cannot cover the agent's `rwX` grows, making an unrelated
  `user:nobody` entry effective — the documented trade-off, now measured.

  **Still unverified:** Gradle and the Android SDK under the real agent uid (no SDK in the
  container), and the external launchers. `sudo` on the *development* host still needs a
  password, so the host itself has never been provisioned.
- `check` keeps asking the kernel; a green ACL and a red `access(2)` is still reported as the
  failure it is.
- The old journal format is read, not migrated: `state`, `targets` and the fish-escaped
  `acl/<escaped>.acl` names still load, so `rollback` works across the port.
- This does not change any boundary. The acceptance criteria and the migration order of the
  2026-11-26 entry stand.

## Links

- Supersedes (implementation only): `2026-11-26-agents-run-under-a-dedicated-uid.md`
- Procedure: [`docs/architecture/agent-uid.md`](../architecture/agent-uid.md)
- Script: `scripts/agent-account.py`; declaration: `scripts/ansible/agent-account.yml`;
  tests: `scripts/tests/test_agent_account.py`
- Issue: [gazon1/sing#3239](https://github.com/gazon1/sing/issues/3239)
