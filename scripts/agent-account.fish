#!/usr/bin/env -S fish --no-config
#
# scripts/agent-account.fish — the dedicated unprivileged account coding agents run as.
#
#   scripts/agent-account.fish apply --dry-run ~/work/wt1        # the plan, no root
#   scripts/agent-account.fish apply ~/work/wt1 ~/work/wt2       # root: creates it
#   scripts/agent-account.fish check ~/work/wt1                 # the verdict, no root
#   scripts/agent-account.fish run -- git status                # a command AS the agent
#   scripts/agent-account.fish rollback                         # removes the account
#
# WHY
#
# Today an agent is the developer: uid 1000, the groups `wheel`, `kvm` and
# `docker`, and the SELinux domain `unconfined_t`. Every boundary the emulator
# guard builds (#3240 protects the emulator install and /dev/kvm, #3241 confines
# the execution) starts from a uid that owns the files it is protecting — and
# `docker` membership is root-equivalent, so a file permission means nothing
# against an agent that can run `docker --privileged -v /:/host`. This script is
# the "somebody else" the rest of the guard needs.
#
# WHAT IT CREATES, AND NOTHING ELSE
#
#   user `agent`      no password, so no login; a real /bin/bash, because a
#                     coding agent has to run commands
#   group `sing-dev`  narrow: the developer and `agent`. It is the collaboration
#                     seam for the authorized worktrees
#   ACLs              --x to traverse the developer home (path access, never a
#                     listing), rX on the Android SDK and the toolchain, and
#                     rwX + a default rwX on the authorized worktree roots
#   sudoers           one line: the DEVELOPER may re-execute /usr/bin/env AS
#                     `agent`, which is what `run` uses. `agent` gets no sudo
#                     rule at all, so an agent cannot leave the account.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
#   - It does not share the developer's Gradle home. `run` points
#     GRADLE_USER_HOME at the agent's own home, so the Gradle distribution and
#     the toolchain JDK are downloaded once per agent account. That is the
#     honest price of "the agent cannot write what the developer's build reads";
#     sharing the cache read-only would instead fail the first time a build
#     needs to write into it, which reads as a permission bug, not as a policy.
#   - It does not move, copy or re-own the SDK (7.8 GB, and re-rooting it is
#     #3240's job). The SDK is read where it lives; when #3240 lands, the
#     traverse grant below is no longer needed.
#   - It does not change any agent's launcher. Which launchers have to switch to
#     `run`, and which of them live outside this repository, is
#     docs/architecture/agent-uid.md.
#
# THE ONE COST, STATED
#
# Traversing `$HOME` (--x) lets `agent` open any file under it whose own mode
# allows it — `.gitconfig`, `.envrc`, the mode-0644 dotfiles. It does not let it
# list the home. The secrets stay shut (the home is 0700, `~/.ssh` and `.gnupg`
# are 0700), but a dotfile that was unreachable from outside becomes reachable,
# which is why the authorized worktree root is recommended outside the home
# (`--allow-in-home` opts in, loudly). It goes away with #3240.
#
# Exit codes: 0 every invariant holds, 1 at least one does not, 2 usage/error.
#
# PORTING NOTES (bash -> fish)
#
#   - The script itself is fish; the agent's login shell stays /bin/bash, and
#     `run` still sets SHELL=/bin/bash, so agent tooling sees the shell it expects.
#   - The shebang carries `--no-config` (`env -S` splits it). Without it the
#     reader's fish config runs first, and a function or alias in it shadows the
#     binaries this script calls as root: measured with a config defining
#     `function setfacl`, the misconfigured run printed "HIJACKED setfacl" where
#     the real tool should have run. A provision script must not inherit the
#     caller's shell environment.
#   - The root half re-executes this file through sudo, and `sudo` runs with its
#     own secure_path — where a developer-installed fish is NOT. The shebang is
#     therefore never what runs the root half: `as_root` names the interpreter
#     explicitly (`(status fish-path)`), measured against `env -i PATH=<secure>`
#     where the shebang alone fails with "env: 'fish': No such file or directory".
#   - The --dry-run plan is printed with `string escape`, so it is fish syntax.
#   - fish has no `set -u` / `pipefail`; every variable below is declared up
#     front, and no check depends on a failure in the middle of a pipeline.
#
# See docs/architecture/agent-uid.md
#     docs/decisions/2026-11-26-agents-run-under-a-dedicated-uid.md
 
function _default --argument-names name value
    # ${NAME:-value}: an unset OR empty variable takes the default.
    if not set -q $name; or test -z "$$name"
        set -g $name $value
    end
end
 
_default AGENT_USER agent
_default AGENT_GROUP sing-dev
# Where the SDK is, relative to the developer's home: the only place it is on
# this host (docs/architecture/emulator-launch-inventory.md §2).
_default SDK_DIR_REL Android/Sdk
# The toolchain the builds call. mise owns java, node, heavy and ktlint, and
# `shims` is how they reach PATH; `.local/bin` holds `mise` itself.
set -g TOOLCHAIN_RELS .local/share/mise .local/bin
 
set -g DRY_RUN 0
set -g ALLOW_IN_HOME 0
set -g DEV
set -g ROOTS
set -g DEV_NAME
set -g DEV_HOME
set -g FAILED 0
set -g SELF_NAME (status filename)
 
function die
    echo "agent-account: $argv" >&2
    exit 2
end
 
# Runs a command, or prints it under --dry-run. One code path, so the plan a
# reader approves is the plan that executes.
# Run one step, or print it under --dry-run — and FAIL when it fails.
#
# It used to return the command's status and every caller ignored it, so a
# `usermod` or `setfacl` that failed was followed by the rest of the run and a
# "=== done ===" line: a half-provisioned host reported as provisioned. A verb
# that cannot complete an essential step has to say so and stop.
#
# `setfacl` exits 1 when a file to be modified is already in the requested
# state? No — it exits 0 idempotently. The tolerated non-zero is an explicit
# opt-in (`--tolerate`), never silent.
function run_cmd
    if test $DRY_RUN -eq 1
        string escape -- $argv | string join ' '
        return 0
    end
    $argv
    set -l rc $status
    if test $rc -ne 0
        die "step failed ($rc): $argv"
    end
    return 0
end
 
function usage
    sed -n '3,9p' $SELF_NAME | sed 's/^# \{0,1\}//'
    exit 2
end
 
# --dev names the interactive developer. Under sudo it is the caller, and
# `SUDO_USER` is that; neither may be root, because a root-owned home is not
# the home this provision means. Resolved once, after the options are parsed.
function resolve_dev
    set -l name $DEV
    test -n "$name"; or set name $SUDO_USER
    test -n "$name"; or set name (id -un)
    test "$name" = root; and die "name the developer: --dev <user>"
 
    set -l home (getent passwd $name | cut -d: -f6)
    test -n "$home"; or die "no such user: $name"
 
    set -g DEV_NAME $name
    set -g DEV_HOME $home
end
 
function agent_home
    set -l home (getent passwd $AGENT_USER | cut -d: -f6)
    test -n "$home"; or set home /home/$AGENT_USER
    printf '%s' $home
end

# The agent's home has to be its OWN. An adopted `agent` whose passwd entry
# points at `/home/devel`, `/`, or the developer's home makes `apply` chmod 700
# and install `.gradle`/`.android`/`.cache` INSIDE the developer's home — the
# exact separation this script exists to create, undone by one stale record.
# Nothing asked this question, and the grants it writes then land in a home the
# agent could already write.
function require_own_home --argument-names home
    test -n "$home"; or die "$AGENT_USER has no home directory in the passwd database"
    # The glob has to be QUOTED: `string match -q -- /* $home` matches nothing,
    # because an unquoted `/*` is expanded by the shell before the test sees it.
    string match -q -- '/*' "$home"; or die "$AGENT_USER's home is not absolute: $home"
    set -l resolved (readlink -f -- $home 2>/dev/null)
    test -n "$resolved"; or set resolved (string replace -r '/*$' '' -- $home)
    if test "$resolved" = /
        die "$AGENT_USER's home is / — apply would write the agent's state into the root"
    end
    set -l dev_home (readlink -f -- $DEV_HOME 2>/dev/null)
    test -n "$dev_home"; or set dev_home $DEV_HOME
    for forbidden in $DEV_HOME $dev_home
        if test "$resolved" = "$forbidden"; or string match -q -- "$forbidden/*" "$resolved"
            die "$AGENT_USER's home is inside the developer's home ($resolved) — it is the same identity's files"
        end
    end
end
 
function agent_uid
    getent passwd $AGENT_USER | cut -d: -f3
end

# The invariant the whole boundary rests on: the agent is NOT root and NOT the
# developer. Nothing in this script asked that question before: an `agent` that
# already existed with uid 0 was provisioned as though it were unprivileged, and
# `run` then executed commands as root while `check` compared 0 to 0 and passed.
function require_unprivileged_uid
    set -l uid (agent_uid)
    test -n "$uid"; or return 0        # a fresh host has no account yet
    if test "$uid" -eq 0
        die "$AGENT_USER has uid 0 — that is root, not an unprivileged agent"
    end
    set -l dev_uid (id -u $DEV_NAME 2>/dev/null)
    if test -n "$dev_uid"; and test "$uid" -eq "$dev_uid"
        die "$AGENT_USER has the developer's uid $uid — it is the same identity"
    end
end
 
# The object store a worktree's git operations write to. One clone with many
# worktrees shares exactly one of these, and it is NOT under the worktree: a
# linked worktree only carries a `.git` file pointing here.
# What this script CREATED, as opposed to what it reconciled.
#
# `apply` deliberately adopts a pre-existing `agent`, `sing-dev` or sudoers file.
# `rollback` deleted all three unconditionally, so a host that already had an
# `agent` account lost it — and its home directory — to a rollback of a change
# that only touched its group memberships. The record is written before the
# first write of `apply`, so a host that already carries the file is protected
# by the same rule that protects the sudoers file: content we did not write.
function state_file
    echo /var/lib/agent-account/state
end

# "created" when this script created it, "adopted" when it was already there.
#
# `argv` is taken whole and joined with REAL tabs: `$subject\t$group` in a word
# is a literal backslash-t, not a separator (measured), so a caller that passed
# its own `\t` wrote a record no lookup matched.
function record_ownership
    set -l file (state_file)
    test $DRY_RUN -eq 1; and return 0
    test -e $file; or run_cmd install --directory --owner=root --group=root --mode=0755 (dirname $file)
    printf '%s\n' (string join \t -- $argv) >> $file
end

function owned_by_script
    set -l file (state_file)
    test -e $file; or return 1
    test (grep -F -- (printf '%s\tcreated' $argv[1]) $file | wc -l) -gt 0
end

# Membership is not reversible by symmetry. `apply` appends to both sides of the
# seam and takes the privileged groups away from the agent; `rollback` deleting
# either would take a membership somebody else configured. So the state that a
# rollback may NOT undo is recorded before `apply` changes it — a developer who
# was already in `sing-dev` keeps it, and the `wheel` the agent used to hold
# comes back, because losing a group is exactly the change this script reports.
function record_membership --argument-names subject group
    test $DRY_RUN -eq 1; and return 0
    in_group $group $subject; or return 0
    record_ownership member $subject $group
end

function was_member --argument-names subject group
    set -l file (state_file)
    test -e $file; or return 1
    grep -qF -- (printf 'member\t%s\t%s' $subject $group) $file
end

# The privileged groups taken from a pre-existing agent, so rollback can give
# them back. The boundary has to be removed; the host it was removed from is
# the host's own configuration.
function record_removed_group --argument-names group
    test $DRY_RUN -eq 1; and return 0
    record_ownership removed-group $group
end

function previously_removed_groups
    set -l file (state_file)
    test -e $file; or return 0
    grep -F -- (printf 'removed-group\t') $file | cut -f2
end

# The recorded value of a one-word property (`passwd` -> `P`/`L`/`NP`), or empty.
function recorded_value --argument-names key
    set -l file (state_file)
    test -e $file; or return 0
    grep -F -- (printf '%s\t' $key) $file | tail -1 | cut -f2
end

# What the ACLs looked like BEFORE `apply` wrote to them, so `rollback` can put
# them back instead of deleting what it finds.
#
# The record is `getfacl`'s own output, restored by `setfacl --restore`. Three
# attempts at a hand-written record failed for the same reason: the write is
# `--recursive`, so "the subject's entry at the target" is not the state that
# changed. Measured on a throwaway tree:
#
#   - `--modify` OVERWRITES a named entry that was already there, so a host that
#     granted `sing-dev` rwx on a worktree lost it to a rollback of a change
#     this script did not make;
#   - `-x` does not undo `--modify`: it leaves `mask::r-x` behind and takes the
#     WHOLE `default:` block with it, so "remove our entry" silently rewrote the
#     modes of files nobody here named — and `chmod g=rx` does not drop the mask
#     either (measured);
#   - a snapshot capped at 200 directories (`find | head -200`) is not a
#     snapshot of a tree `--recursive` wrote at every file of.
#
# The dump is the tool's own inverse, so there is no delimiter to guess at — a
# path may legally contain `;` and `=`, which is why the old line format could
# not round-trip one. A path maps to its dump file through
# `string escape --style=var`, which is injective for every byte.
function acl_dir
    echo /var/lib/agent-account/acl
end

# The targets whose ACL `apply` changed, so `rollback` can walk all of them even
# after a worktree is removed: a linked worktree's object store is NOT under the
# worktree, and the path cannot be re-derived once the worktree is gone.
function targets_file
    echo /var/lib/agent-account/targets
end

function acl_dump_file
    echo (acl_dir)/(string escape --style=var -- $argv[1]).acl
end

# Snapshot a target BEFORE it is modified. `path` is one node (`$HOME`, which is
# granted non-recursively); `tree` is everything under it, because `--recursive`
# writes at every one of them.
function record_acl --argument-names target kind
    set -l file (acl_dump_file $target)
    set -l flags --absolute-names --physical
    test "$kind" = tree; and set -a flags --recursive
    if test $DRY_RUN -eq 1
        echo "getfacl "(string join ' ' $flags)" $target > $file"
        return 0
    end
    run_cmd install --directory --owner=root --group=root --mode=0700 (acl_dir)
    # The FIRST snapshot is the one taken before `apply` ever ran. A second
    # `apply` would otherwise record the state `apply` itself wrote, and the
    # rollback would faithfully restore that instead of the host's own setup.
    #
    # `apply` therefore snapshots EVERY target before writing ANY of them: a
    # repository nested in another authorized root is inside the outer tree's
    # recursive dump, and an inner snapshot taken after the outer grant had
    # already written to it would record `sing-dev:rwX` as the previous state
    # and reintroduce the grant on rollback.
    if not test -e $file
        getfacl $flags $target > $file
        or die "could not snapshot the ACL of $target"
        run_cmd chmod 0600 $file
    end
    set -l listed
    test -e (targets_file); and set listed (cat (targets_file))
    contains -- $target $listed; or printf '%s\n' $target >> (targets_file)
end

# Puts a snapshot back — the inverse of the dump, so a grant, a `mask::` or a
# `default:` block that was already there comes back instead of vanishing.
#
# A path named in the dump may be gone by rollback time: a file, or a whole
# worktree, removed since `apply` ran is normal and not a failure. `setfacl`
# exits 1 for that alone (measured: it still restored every path that remained),
# so only an error OTHER than the missing path is fatal.
function restore_acl --argument-names target
    set -l file (acl_dump_file $target)
    test -e $file; or return 1
    set -l output (setfacl --restore=$file --physical 2>&1)
    test $status -eq 0; and return 0
    set -l errors (printf '%s\n' $output | string match -rv '^\s*$' |
        string match -rv 'No such file or directory')
    if test (count $errors) -gt 0
        die "could not restore the ACL of $target: "(string join '; ' $errors)
    end
    echo "note: $target's ACL restored in part; some of its paths are gone" >&2
    return 0
end

# Is the path a WORKING TREE? `--git-common-dir` cannot answer: on a bare
# repository it prints "." and exits 0. Measured on `git init --bare`:
# common-dir ".", `--is-inside-work-tree false`.
function is_worktree
    test (git -C $argv[1] rev-parse --is-inside-work-tree 2>/dev/null) = true
end

# The object store a worktree's git operations write to. One clone with many
# worktrees shares exactly one of these, and it is NOT under the worktree: a
# linked worktree only carries a `.git` file pointing here.
#
# It DIES on a path that is not a worktree, so it must never be called inside a
# `set -l x (…); or …` guard: an `exit` in a command substitution ends the whole
# script, not the substitution (measured), and `check` would print half a verdict
# and stop. Callers test `is_worktree` first.
function git_common_dir
    is_worktree $argv[1]; or die "$argv[1] is not a git worktree"
    set -l common (git -C $argv[1] rev-parse --git-common-dir 2>/dev/null)
    test -n "$common"; or die "$argv[1] is not a git worktree"
    string match -q -- '/*' "$common"; or set common $argv[1]/$common
    readlink -f $common
end

# Absolute, so a verdict names a path a reader can act on from anywhere — and
# the message names the spelling the caller used, because that is what they can
# correct.
#
# A path that no longer exists is still collected: `rollback` has to be able to
# clean up a host whose worktree was removed, and its grants on the developer's
# paths outlive the worktree. `validate_roots` refuses it for `apply`, where
# every root must be real before anything is written.
function add_root
    set -l resolved (readlink -f -- $argv[1])
    test -n "$resolved"; or set resolved (string replace -r '/*$' '' -- $argv[1])
    set -ga ROOTS $resolved
end
 
function parse_args
    while set -q argv[1]
        switch $argv[1]
            case --dry-run
                set -g DRY_RUN 1
            case --allow-in-home
                set -g ALLOW_IN_HOME 1
            case --dev
                set -q argv[2]; or die "--dev needs a user"
                set -g DEV $argv[2]
                set -e argv[1]
            case -h --help
                usage
            case --
                set -e argv[1]
                for rest in $argv
                    add_root $rest
                end
                break
            case '-*'
                die "unknown option: $argv[1]"
            case '*'
                add_root $argv[1]
        end
        set -e argv[1]
    end
end
 
# The root half re-executes itself, so `apply` needs one password prompt and the
# plan cannot drift from what runs.
function as_root
    set -l self (readlink -f $SELF_NAME)
    set -l extra
    test $ALLOW_IN_HOME -eq 1; and set extra --allow-in-home
    # The interpreter is named, not found: sudo's secure_path has no fish.
    exec sudo -- (status fish-path) --no-config $self $argv[1] --dev $DEV_NAME $extra $ROOTS
end
 
# Everything that can refuse, refuses before the first write.
#
# `provision` used to reach `git_common_dir` only at the worktree loop, i.e.
# after useradd and the sudoers rule had already run: a root that is not a git
# worktree left an account created and a host half-provisioned. Measured on a
# plain directory. So the roots are validated here, where nothing has happened
# yet, and the loop below cannot be the first place that fails.
function validate_roots
    # Only `apply` needs the worktrees to exist. `rollback` must still clean up
    # a host whose worktree was removed — the grants on the developer's own
    # paths outlive it, and refusing here would leave them pointing at nothing.
    test "$argv[1]" = apply; or return 0
    for root in $ROOTS
        test -d "$root"; or die "not a directory: $root"
        in_home_guard $root
        is_worktree $root; or die "$root is not a git worktree"
    end
end

# ---------------------------------------------------------------------------
# apply
# ---------------------------------------------------------------------------
 
function provision
    set -l dev $DEV_NAME
    set -l dev_home_dir $DEV_HOME
    # The SAME resolution `run` uses, so the state this verb creates is the state
    # the transition looks for. It used to be a hardcoded /home/agent, while
    # `run` asked the passwd database — an account with any other home got its
    # .gradle created in one directory and its environment built from another.
    set -l agent_home_dir (agent_home)
    # Asked before the first write: `apply` chmods this path and installs the
    # agent's private state in it, so a home that is the developer's would be a
    # separation undone by one stale passwd record.
    require_own_home $agent_home_dir
 
    echo "=== agent account: $AGENT_USER (group $AGENT_GROUP) for $dev ==="
 
    # The account. No password, so no login; a real shell, because a coding
    # agent has to run one.
    set -l account (getent passwd $AGENT_USER)
    if test -z "$account"
        record_ownership user created
        # The group is INDEPENDENT of the account: a host may carry `sing-dev`
        # and no `agent`. Recording it as created without asking made rollback
        # delete a group this script never made.
        if getent group $AGENT_GROUP >/dev/null
            record_ownership group adopted
        else
            record_ownership group created
            run_cmd groupadd --system $AGENT_GROUP
        end
        run_cmd useradd --create-home --user-group --shell /bin/bash $AGENT_USER
        run_cmd chmod 700 $agent_home_dir
    else
        echo "account exists: $account (adopted, not created by this script)"
        record_ownership user adopted
        if getent group $AGENT_GROUP >/dev/null
            record_ownership group adopted
        else
            record_ownership group created
        end
        set -l login_shell (printf '%s' "$account" | cut -d: -f7)
        switch "$login_shell"
            case '*/nologin' '*/false'
                die "$AGENT_USER has no shell ($login_shell); an agent cannot run in it"
        end

        # "No password, so no login" is the invariant, and a NEW account has it
        # by construction — an existing one may not. `passwd -S` reports the
        # password state: locked (L), usable (P), no password (NP).
        #
        # The state before the lock is recorded, because a lock is not undone by
        # symmetry either: `rollback` on an ADOPTED account must put back the
        # password state this script found, or it keeps an account locked that
        # it did not lock.
        set -l password_state (passwd --status $AGENT_USER 2>/dev/null | string split ' ')[2]
        test -n "$password_state"; and record_ownership passwd $password_state
        if test "$password_state" = P
            test $DRY_RUN -eq 1; and echo "passwd --lock $AGENT_USER"
            test $DRY_RUN -eq 1; or run_cmd passwd --lock $AGENT_USER
            or die "could not lock $AGENT_USER's password"
        end
        getent group $AGENT_GROUP >/dev/null; or run_cmd groupadd --system $AGENT_GROUP

        # A pre-existing account that still holds a privileged group is the
        # whole boundary defeated — `docker` alone is root-equivalent. `apply`
        # is what a human re-runs, so it reconciles instead of only reporting.
        for group in wheel docker kvm
            in_group $group $AGENT_USER; or continue
            record_removed_group $group
            remove_group $group
        end

        # And a pre-existing account may hold a sudo rule, which no group
        # reconciliation touches: that is the escalation this whole change
        # forbids, and it survives `apply` unless it is refused here. `check`
        # reports it, but reporting is not provisioning.
        #
        # This script does not edit sudoers to remove somebody else's rule, and
        # it cannot read another user's rules without root — so the human is
        # told to fix it, and the run stops rather than declaring a boundary
        # that does not hold. An unreadable answer is NOT proof of a clean one.
        set -l agent_sudo (sudo --non-interactive --list --other-user=$AGENT_USER 2>/dev/null)
        set -l sudo_known 0
        test $status -eq 0; and set sudo_known 1
        if test $sudo_known -eq 1
            set -l rules (printf '%s\n' $agent_sudo |
                sed -n '/may run the following commands/,$p' |
                string match -rv -- '^\s*$' |
                string match -rv -- '^User ' |
                string match -rv -- '^\s*may run')
            if test (count $rules) -gt 0
                die "$AGENT_USER holds a sudo rule; it can leave its account: "(string join '; ' $rules)
            end
        else
            echo "note: $AGENT_USER's sudo rights are unverified here (needs root to read);" >&2
            echo "      run '$SELF_NAME check' after provisioning" >&2
        end
    end
 
    # The group is the collaboration seam: both identities, NOBODY ELSE.
    #
    # It only ever appended, so a pre-existing member of `sing-dev` silently
    # received the same recursive write access to every authorized worktree and
    # to the clone's git store. Membership is reconciled to the documented two,
    # and an unexpected member is reported rather than quietly removed — it may
    # be somebody's deliberate configuration.
    set -l foreign
    # BOTH sources. `getent group` lists supplementary members, and a user whose
    # PRIMARY gid is `sing-dev` is invisible there — the same gap that made an
    # agent with `docker` as its primary group look clean. `getent passwd` gives
    # the primary one, `id -Gn` would need the account to be queryable as-is.
    set -l gid (getent group $AGENT_GROUP | cut -d: -f3)
    set -l members (getent group $AGENT_GROUP | cut -d: -f4 | string split ,)
    for row in (getent passwd | string match -r '.*')
        set -l fields (string split : -- $row)
        test (count $fields) -ge 4; or continue
        test "$fields[4]" = "$gid"; and set -a members $fields[1]
    end
    for member in $members
        test -n "$member"; or continue
        contains -- $member $dev $AGENT_USER; or set -a foreign $member
    end
    if test (count $foreign) -gt 0
        die "$AGENT_GROUP already holds $(string join , $foreign) — it is documented as $dev and $AGENT_USER only"
    end
    # Recorded BEFORE the append: a rollback that deleted a membership this
    # script did not add would remove the developer from a group they chose.
    record_membership $dev $AGENT_GROUP
    record_membership $AGENT_USER $AGENT_GROUP
    run_cmd usermod --append --groups $AGENT_GROUP $dev
    run_cmd usermod --append --groups $AGENT_GROUP $AGENT_USER
 
    # The agent's own private state. Created here, so the first build does not
    # fail on a missing directory it has no right to create.
    run_cmd install --directory --owner=$AGENT_USER --group=$AGENT_USER --mode=0700 \
        $agent_home_dir/.gradle $agent_home_dir/.android $agent_home_dir/.cache
 
    # `run` re-executes /usr/bin/env as the agent. That single rule is the whole
    # grant, and it is the developer's, not the agent's: the account being
    # sandboxed is the one that must hold no sudo right of its own.
    # The file is namespaced by owner too: a host may already carry its own
    # `/etc/sudoers.d/50-agent-account`, and overwriting it silently would be
    # someone else's rule replaced by ours — and `rollback` would then delete it.
    set -l sudoers /etc/sudoers.d/50-agent-account
    set -l rule "$dev ALL=($AGENT_USER) NOPASSWD: /usr/bin/env"
    if test $DRY_RUN -eq 1
        echo "printf '%s\\n' '$rule' > $sudoers"
    else
        # Refuse to replace a file this script does not own. One matching line
        # is not proof — a file that ALSO holds other administrators' grants
        # would lose them to the rewrite below, and `rollback` would delete the
        # rest. The file is ours only when it is exactly this one rule.
        if test -e $sudoers
            set -l existing (string trim -- (cat $sudoers))
            if test "$existing" != "$rule"
                die "$sudoers holds content this script did not write — inspect it, then remove it by hand"
            end
        end
        printf '%s\n' "$rule" > $sudoers
        chmod 0440 $sudoers
        visudo --check --file $sudoers
        or die "$sudoers does not parse"
    end
 
    # Snapshot EVERY target before writing to ANY of them. Two passes, not one
    # interleaved loop: a repository nested inside another authorized root lies
    # within the outer tree's recursive dump, so a snapshot taken after the
    # outer grant had already written there records `sing-dev:rwX` as the
    # "previous" state — and rollback then reintroduces the grant it was
    # supposed to undo. Measured on `git init` inside a worktree: the outer dump
    # names 27 paths under the inner `.git`.
    set -l writable $dev_home_dir
    set -l granted
    for rel in $SDK_DIR_REL $TOOLCHAIN_RELS
        test -d $dev_home_dir/$rel; and set -a granted $dev_home_dir/$rel
    end
    for root in $ROOTS
        for target in $root (git_common_dir $root)
            set -a granted $target
        end
    end

    # Pass 1: record. Nothing is written to the host here.
    record_acl $writable node
    for target in $granted
        record_acl $target tree
    end

    # Pass 2: write. The plan a reader approves is still one code path — the
    # snapshot above prints its own command under --dry-run.
    run_cmd setfacl --modify "user:$AGENT_USER:--x" $writable

    # Read the SDK and the toolchain. `--recursive` covers today's files; the
    # default entry covers the files sdkmanager and mise write tomorrow.
    #
    # Both carry grants of their own on a host that provisioned them, and the
    # `default:` entry below rewrites those. The snapshot is what puts them back.
    for rel in $SDK_DIR_REL $TOOLCHAIN_RELS
        if not test -d $dev_home_dir/$rel
            echo "skipped (not on this host): $dev_home_dir/$rel"
            continue
        end
        run_cmd setfacl --recursive --modify "user:$AGENT_USER:rX" \
            --modify "default:user:$AGENT_USER:rX" $dev_home_dir/$rel
    end

    # The authorized worktrees: today's files, and whatever git writes next —
    # which is why the default entry is here and `setgid` would not do.
    #
    # The common git dir is granted too, not only the worktree. A linked
    # worktree keeps its objects and refs in ONE shared store owned by the main
    # checkout, so a writable worktree over a read-only object store is a
    # worktree the agent cannot commit in. `check` verifies this path, and it
    # used to verify a grant `provision` never wrote.
    for root in $ROOTS
        for target in $root (git_common_dir $root)
            run_cmd setfacl --recursive --modify "group:$AGENT_GROUP:rwX" \
                --modify "default:group:$AGENT_GROUP:rwX" $target
        end
    end
 
    echo "=== done; verify with: $SELF_NAME check $ROOTS ==="
end
 
# A worktree inside the developer's home needs the traverse grant, which is the
# one cost this design avoids — so it has to be asked for, not discovered later.
function in_home_guard
    set -l root (readlink -f $argv[1])
    set -l home (readlink -f $DEV_HOME)
    # `$HOME` ITSELF is the worst case, and the glob `$home/*` did not match it:
    # `apply $HOME` granted `sing-dev:rwX` over the whole home without asking.
    if test "$root" = "$home"
        or string match -q -- "$home/*" "$root"
    else
        return 0
    end
    if test $ALLOW_IN_HOME -eq 1
        echo "warning: authorized worktree inside the developer home: $root" >&2
        echo "         it relies on the traverse grant, which opens $home's world-readable files" >&2
        return 0
    end
    die "$root is inside $home; pass --allow-in-home to accept the traverse grant"
end
 
# Takes `group` away from the agent, whether it holds it as a supplementary
# group or as its primary one. `gpasswd --delete` only edits the supplementary
# list; a primary gid is changed by `usermod --gid`.
function remove_group
    set -l group $argv[1]
    set -l primary (id -gn $AGENT_USER 2>/dev/null)
    if test "$primary" = "$group"
        # The new primary group has to exist. A fresh account gets its own group
        # from `useradd --user-group`; an account somebody else created may not
        # have one, and `usermod --gid` would then fail after the boundary was
        # already reported broken.
        getent group $AGENT_USER >/dev/null; or run_cmd groupadd --system $AGENT_USER
        run_cmd usermod --gid $AGENT_USER $AGENT_USER
        or die "could not move $AGENT_USER's primary group off $group"
        echo "moved $AGENT_USER's primary group off $group"
        return 0
    end
    run_cmd gpasswd --delete $AGENT_USER $group
    or die "could not remove $AGENT_USER from $group"
end

# ---------------------------------------------------------------------------
# check — every acceptance criterion as a verdict. Unprivileged.
# ---------------------------------------------------------------------------
 
function fail
    echo "FAIL $argv"
    set -g FAILED 1
end
 
function pass
    echo "PASS $argv"
end
 
function note
    echo "NOTE $argv"
end
 
# Is `member` in `group` — as a SUPPLEMENTARY group or as the PRIMARY one?
#
# `getent group` lists supplementary members only: an account whose primary gid
# is `docker` does not appear there at all, and a primary group is exactly as
# root-equivalent as a supplementary one. Measured: `id -Gn max` answers
# "max wheel kvm docker", while `getent group docker` names the members
# separately. So the question is asked of the identity, not of the group file:
# `id -Gn` prints every group, primary first.
function in_group --argument-names group member
    set -l groups (id -Gn $member 2>/dev/null | string split ' ')
    contains -- $group $groups
end
 
function check_acl
    set -l target $argv[1]
    set -l entry $argv[2]
    set -l default_entry
    set -q argv[3]; and set default_entry $argv[3]
    set -l writer 'the next write'
    set -q argv[4]; and test -n "$argv[4]"; and set writer $argv[4]
 
    if not test -e "$target"
        fail "missing path: $target"
        return
    end
    # The entry has to be EFFECTIVE, not merely present: the ACL mask constrains
    # every named entry, and `getfacl` writes the result as `#effective:`. A
    # `group:sing-dev:rwx` under `mask::r-x` is a read-only worktree, and a check
    # that only grepped the raw line would call it writable.
    if getfacl --omit-header --absolute-names "$target" 2>/dev/null | grep -qx "$entry"
        if getfacl --omit-header --absolute-names "$target" 2>/dev/null | grep -q "^$entry.*#effective:r"
            fail "$target: '$entry' is present but the ACL mask limits it to read — a write would fail"
        else
            pass "$target: $entry"
        end
    else
        fail "$target: no '$entry' — the agent cannot reach it"
    end
    test -n "$default_entry"; or return 0
    if getfacl --absolute-names "$target" 2>/dev/null | grep -qx "$default_entry"
        pass "$target: $default_entry"
    else
        fail "$target: no '$default_entry' — a file $writer writes lands unreachable"
    end
end
 
function check
    set -l dev $DEV_NAME
    set -l home $DEV_HOME
 
    set -l account (getent passwd $AGENT_USER)
    if test -n "$account"
        set -l uid (printf '%s' "$account" | cut -d: -f3)
        # The acceptance criterion is an UNPRIVILEGED account. uid 0 is root.
        if test "$uid" -eq 0
            fail "$AGENT_USER has uid 0 — that is root"
        else if test "$uid" -eq (id -u $dev 2>/dev/null)
            fail "$AGENT_USER has the developer's uid $uid"
        else
            pass "$AGENT_USER is a distinct unprivileged uid ($uid)"
        end
        set -l login_shell (printf '%s' "$account" | cut -d: -f7)
        switch "$login_shell"
            case '*/nologin' '*/false'
                fail "$AGENT_USER cannot run a shell"
            case '*'
                pass "$AGENT_USER has a shell"
        end
    else
        fail "no $AGENT_USER account — run '$SELF_NAME apply'"
    end
 
    # The three groups that are root-equivalent or own the device. `docker`
    # alone defeats every file permission the guard puts in place.
    for group in wheel docker kvm
        if in_group $group $AGENT_USER
            fail "$AGENT_USER is in $group (root-equivalent or device-owning)"
        else
            pass "$AGENT_USER is not in $group"
        end
    end
 
    if in_group $AGENT_GROUP $AGENT_USER; and in_group $AGENT_GROUP $dev
        pass "both identities are in $AGENT_GROUP"
    else if in_group $AGENT_GROUP $AGENT_USER
        note "$dev is not in $AGENT_GROUP yet (group membership applies on next login)"
    else
        fail "$AGENT_GROUP does not hold $AGENT_USER"
    end
 
    # A sudo rule for `agent` is the escalation this issue forbids. Reading
    # another user's rules needs root, so an unreadable answer is a NOTE and not
    # a PASS: a check that could not run is not a clean bill of health.
    set -l sudo_agent_rules (sudo --non-interactive --list --other-user=$AGENT_USER 2>/dev/null)
    if test $status -eq 0
        # The question is "is there a rule at all", not "does it look dangerous".
        # A password-required, command-scoped, run-as rule — `(emulator)
        # /usr/bin/foo` — matches none of NOPASSWD, "(ALL" or a trailing ALL and
        # would have been reported as a clean account. `sudo -l` prints
        # `Matching Defaults entries` and `User X may run the following
        # commands` even when it grants nothing, so the rule lines are the ones
        # that follow the latter.
        # Count the rule lines: collecting them produced an EMPTY list element
        # for a rule-free account, which printed as a `PASS ` with no verdict.
        set -l rules (printf '%s\n' $sudo_agent_rules |
            sed -n '/may run the following commands/,$p' |
            string match -rv -- '^\s*$' |
            string match -rv -- '^User ' |
            string match -rv -- '^\s*may run')
        if test (count $rules) -gt 0
            fail "$AGENT_USER holds a sudo rule — it can leave its account: "(string join '; ' $rules)
        else
            pass "$AGENT_USER holds no sudo rule"
        end
    else
        note "$AGENT_USER's sudo rights unverified (needs root): sudo -n -l -U $AGENT_USER"
    end
 
    check_acl $home "user:$AGENT_USER:--x"
    if getfacl --absolute-names $home 2>/dev/null | grep -qE '^(user|group):[^:]+:r'
        fail "$home carries a read grant; the home is meant to stay closed"
    else
        pass "$home carries no read grant"
    end
 
    check_acl $home/$SDK_DIR_REL "user:$AGENT_USER:r-x" "default:user:$AGENT_USER:r-x" sdkmanager
    for rel in $TOOLCHAIN_RELS
        test -d $home/$rel; and check_acl $home/$rel "user:$AGENT_USER:r-x"
    end
 
    # The worktrees. A writable worktree over a read-only object store is a
    # worktree an agent cannot commit in, so the common git dir is checked too —
    # one clone with many worktrees shares exactly one of those.
    for root in $ROOTS
        # Through the same predicate `apply` uses, so the verdict cannot certify
        # a repository that provisioning refuses — a bare repo answers
        # `--git-common-dir` with "." and used to pass here.
        if not is_worktree $root
            fail "$root is not a git worktree"
            continue
        end
        check_acl $root "group:$AGENT_GROUP:rwx" "default:group:$AGENT_GROUP:rwx" git
        check_acl (git_common_dir $root) "group:$AGENT_GROUP:rwx" "default:group:$AGENT_GROUP:rwx" git
    end
 
    # The transition itself: the one claim a file mode cannot prove.
    set -l ran_as (sudo --non-interactive --user=$AGENT_USER -- /usr/bin/env id -u 2>/dev/null)
    if test $status -eq 0
        set -l expected (agent_uid)
        if test "$ran_as" = "$expected"
            pass "a command runs as $AGENT_USER (uid $ran_as)"
        else
            fail "a command ran as uid $ran_as, not $AGENT_USER"
        end
    else
        fail "cannot run a command as $AGENT_USER — the sudoers rule is missing or wrong"
    end
 
    test $FAILED -eq 0; or return 1
    echo "=== every invariant holds ==="
end
 
# ---------------------------------------------------------------------------
# run — the transition. No root needed by the caller, and none gained.
# ---------------------------------------------------------------------------
 
function run_as_agent
    set -q argv[1]; or die "run needs a command: $SELF_NAME run -- <cmd> [args...]"
 
    set -l ahome (agent_home)
    set -l term_value $TERM
    test -n "$term_value"; or set term_value dumb
    set -l android_home $ANDROID_HOME
    test -n "$android_home"; or set android_home $DEV_HOME/$SDK_DIR_REL
 
    # The environment is built here rather than inherited: `--preserve-env` would
    # hand the agent every token and credential path the developer's shell
    # carries, which is the opposite of what a dedicated uid is for.
    exec sudo --non-interactive --user=$AGENT_USER -- /usr/bin/env -i \
        HOME=$ahome \
        USER=$AGENT_USER \
        LOGNAME=$AGENT_USER \
        SHELL=/bin/bash \
        TERM=$term_value \
        TMPDIR=/tmp \
        PATH=$DEV_HOME/.local/share/mise/shims:$DEV_HOME/.local/bin:/usr/local/bin:/usr/bin:/bin \
        GRADLE_USER_HOME=$ahome/.gradle \
        ANDROID_HOME=$android_home \
        ANDROID_USER_HOME=$ahome/.android \
        $argv
end
 
# ---------------------------------------------------------------------------
# rollback — the account and its grants go. Nothing the developer owns is touched.
# ---------------------------------------------------------------------------
 
function rollback
    set -l existing (getent passwd $AGENT_USER)
    # The ACCOUNT may be gone while this script's own writes are not: a failed
    # `apply` that created the group and then could not create the account left
    # both behind, and an early return here made them permanent. The grants sit
    # on the developer's paths and outlive the account by design, so the record
    # decides what is rolled back — not the passwd database.
    set -l has_record 0
    test -e (targets_file); and set has_record 1
    if test -z "$existing"; and test $has_record -eq 0
        # Nothing this script did to undo, unless a group it created is there.
        if not owned_by_script group
            echo "nothing to roll back: no $AGENT_USER"
            return 0
        end
    end
    if test -z "$existing"
        echo "the $AGENT_USER account is already gone; undoing this script's remaining writes" >&2
    end
 
    set -l home $DEV_HOME

    # Every target this script ever touched, NOT only the roots passed in.
    # Undoing the grants of wt1 left wt2 writable, and then deleted the group
    # whose gid those entries named. A target with no snapshot was granted
    # before the record existed, or by a run that could not write it: its entry
    # is still removed, by subject, from the roots given.
    set -l recorded
    test -e (targets_file); and set recorded (cat (targets_file))
    set -l targets
    for root in $ROOTS
        test -d "$root"; or continue
        set -a targets $root
        # A root whose worktree was removed is normal on a rollback, and
        # `git_common_dir` EXITS on a non-worktree — inside a command
        # substitution that ends the whole script, so it is asked first.
        is_worktree $root; and set -a targets (git_common_dir $root)
    end
    # The developer's own paths are not passed to `rollback`; they are where the
    # traverse and read grants sit, and they are undone the same way — including
    # `$HOME` itself, which `apply` grants non-recursively. A target both
    # recorded and passed is not deduplicated: restoring a snapshot twice is
    # idempotent, and the loop below already skips what is gone.
    test -d $home; and set -a targets $home
    for rel in $SDK_DIR_REL $TOOLCHAIN_RELS
        test -d $home/$rel; and set -a targets $home/$rel
    end

    for target in $recorded $targets
        test -n "$target"; or continue
        test -e "$target"; or begin
            echo "note: recorded target $target no longer exists" >&2
            continue
        end
        if restore_acl $target
            echo "restored $target (the ACL it had before apply)"
        else
            # No snapshot: the entry is ours to remove. `-x` leaves the `mask::`
            # and the `default:` block it created, which is why a snapshot is
            # taken in the first place; without one there is nothing to say
            # those were not the host's own.
            run_cmd setfacl --recursive -x "u:$AGENT_USER" -x "d:u:$AGENT_USER" $target
            run_cmd setfacl --recursive -x "g:$AGENT_GROUP" -x "d:g:$AGENT_GROUP" $target
        end
    end
 
    # Undo only what `apply` wrote. A pre-existing account, group or sudoers
    # file is ADOPTED, not owned: deleting it would destroy host configuration
    # this script never created, and the account's home lives in it.
    set -l sudoers /etc/sudoers.d/50-agent-account
    if test -e $sudoers; and test (string trim -- (cat $sudoers 2>/dev/null)) = "$DEV_NAME ALL=($AGENT_USER) NOPASSWD: /usr/bin/env"
        run_cmd rm --force $sudoers
    else if test -e $sudoers
        echo "kept $sudoers: its content is not what this script wrote — inspect it by hand" >&2
    end
    run_cmd rm --force (targets_file)
    run_cmd rm --force --recursive (acl_dir)

    if owned_by_script user
        run_cmd userdel --remove $AGENT_USER
    else
        echo "kept the $AGENT_USER account: this script adopted it rather than created it" >&2
        # A membership `apply` did not add is not `apply`'s to remove. Both
        # sides of the seam are checked separately: the developer was in the
        # group as often as the agent was.
        if in_group $AGENT_GROUP $AGENT_USER; and not was_member $AGENT_USER $AGENT_GROUP
            run_cmd gpasswd --delete $AGENT_USER $AGENT_GROUP
        end
        # A privileged group `apply` took away comes back: removing the
        # boundary is what `rollback` is for, but this account held the group
        # before `apply` ran, and that is the host's own configuration.
        for group in (previously_removed_groups)
            in_group $group $AGENT_USER; and continue
            getent group $group >/dev/null; or continue
            run_cmd usermod --append --groups $group $AGENT_USER
            echo "returned $AGENT_USER to $group (it held it before apply)"
        end
        # And the password state: `apply` locked a usable password, which is
        # right for the boundary and wrong to keep on an account this script
        # adopted and is now leaving alone.
        set -l was_password (recorded_value passwd)
        if test "$was_password" = P; and test (passwd --status $AGENT_USER 2>/dev/null | string split ' ')[2] = L
            run_cmd passwd --unlock $AGENT_USER
            echo "unlocked $AGENT_USER's password (it was usable before apply)"
        end
    end
    if owned_by_script group
        run_cmd groupdel $AGENT_GROUP
    else
        echo "kept the $AGENT_GROUP group: this script adopted it rather than created it" >&2
        if in_group $AGENT_GROUP $DEV_NAME; and not was_member $DEV_NAME $AGENT_GROUP
            run_cmd gpasswd --delete $DEV_NAME $AGENT_GROUP
        end
    end
    run_cmd rm --force (state_file)
    echo "=== rolled back; the worktrees, their branches and their git data were not touched ==="
end
 
function main
    set -l verb $argv[1]
    test -n "$verb"; or usage
    set -e argv[1]
 
    switch $verb
        case apply
            parse_args $argv
            resolve_dev
            require_unprivileged_uid
            validate_roots apply
            if test $DRY_RUN -ne 1; and test (id -u) -ne 0
                as_root apply
            end
            provision
        case check
            parse_args $argv
            resolve_dev
            check
        case run
            set -q argv[1]; and test "$argv[1]" = --; and set -e argv[1]
            resolve_dev
            run_as_agent $argv
        case rollback
            parse_args $argv
            resolve_dev
            validate_roots rollback
            if test $DRY_RUN -ne 1; and test (id -u) -ne 0
                as_root rollback
            end
            rollback
        case '*'
            usage
    end
end
 
main $argv
exit $status
 