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
function run_cmd
    if test $DRY_RUN -eq 1
        string escape -- $argv | string join ' '
    else
        $argv
    end
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
 
function agent_uid
    getent passwd $AGENT_USER | cut -d: -f3
end
 
# The object store a worktree's git operations write to. One clone with many
# worktrees shares exactly one of these, and it is NOT under the worktree: a
# linked worktree only carries a `.git` file pointing here.
function git_common_dir
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
        git_common_dir $root >/dev/null; or exit 2
    end
end

# ---------------------------------------------------------------------------
# apply
# ---------------------------------------------------------------------------
 
function provision
    set -l dev $DEV_NAME
    set -l dev_home_dir $DEV_HOME
    set -l agent_home_dir /home/$AGENT_USER
 
    echo "=== agent account: $AGENT_USER (group $AGENT_GROUP) for $dev ==="
 
    # The account. No password, so no login; a real shell, because a coding
    # agent has to run one.
    set -l account (getent passwd $AGENT_USER)
    if test -z "$account"
        run_cmd groupadd --system $AGENT_GROUP
        run_cmd useradd --create-home --user-group --shell /bin/bash $AGENT_USER
        run_cmd chmod 700 $agent_home_dir
    else
        echo "account exists: $account"
        set -l login_shell (printf '%s' "$account" | cut -d: -f7)
        switch "$login_shell"
            case '*/nologin' '*/false'
                die "$AGENT_USER has no shell ($login_shell); an agent cannot run in it"
        end
        getent group $AGENT_GROUP >/dev/null; or run_cmd groupadd --system $AGENT_GROUP

        # A pre-existing account that still holds a privileged group is the
        # whole boundary defeated — `docker` alone is root-equivalent. `apply`
        # is what a human re-runs, so it reconciles instead of only reporting.
        for group in wheel docker kvm
            in_group $group $AGENT_USER; or continue
            test $DRY_RUN -eq 1; and echo "gpasswd --delete $AGENT_USER $group"
            test $DRY_RUN -eq 1; and continue
            gpasswd --delete $AGENT_USER $group
            or die "could not remove $AGENT_USER from $group"
        end
    end
 
    # The group is the collaboration seam: both identities, nobody else.
    run_cmd usermod --append --groups $AGENT_GROUP $dev
    run_cmd usermod --append --groups $AGENT_GROUP $AGENT_USER
 
    # The agent's own private state. Created here, so the first build does not
    # fail on a missing directory it has no right to create.
    run_cmd install --directory --owner=$AGENT_USER --group=$AGENT_USER --mode=0700 \
        $agent_home_dir/.gradle $agent_home_dir/.android $agent_home_dir/.cache
 
    # `run` re-executes /usr/bin/env as the agent. That single rule is the whole
    # grant, and it is the developer's, not the agent's: the account being
    # sandboxed is the one that must hold no sudo right of its own.
    if test $DRY_RUN -eq 1
        echo "printf '%s\\n' '$dev ALL=($AGENT_USER) NOPASSWD: /usr/bin/env' > /etc/sudoers.d/50-agent-run"
    else
        printf '%s\n' "$dev ALL=($AGENT_USER) NOPASSWD: /usr/bin/env" >/etc/sudoers.d/50-agent-run
        chmod 0440 /etc/sudoers.d/50-agent-run
        visudo --check --file /etc/sudoers.d/50-agent-run
        or die "/etc/sudoers.d/50-agent-run does not parse"
    end
 
    # Traverse the developer home: path access, never a listing.
    run_cmd setfacl --modify "user:$AGENT_USER:--x" $dev_home_dir
 
    # Read the SDK and the toolchain. `--recursive` covers today's files; the
    # default entry covers the files sdkmanager and mise write tomorrow.
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
    set -l home $DEV_HOME
    string match -q -- "$home/*" "$root"; or return 0
    if test $ALLOW_IN_HOME -eq 1
        echo "warning: authorized worktree inside the developer home: $root" >&2
        echo "         it relies on the traverse grant, which opens $home's world-readable files" >&2
        return 0
    end
    die "$root is inside $home; pass --allow-in-home to accept the traverse grant"
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
 
# Membership is a comma-separated field, so the name has to match whole: a grep
# for the bare name would also pass for `agent-smith`.
function in_group --argument-names group member
    set -l entry (getent group $group | cut -d: -f4); or return 1
    string split , -- "$entry" | string match -q -- $member
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
    if getfacl --absolute-names "$target" 2>/dev/null | grep -qx "$entry"
        pass "$target: $entry"
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
        set -l login_shell (printf '%s' "$account" | cut -d: -f7)
        pass "account $AGENT_USER exists (uid $uid)"
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
        if printf '%s\n' $sudo_agent_rules | grep -qE 'NOPASSWD|\(ALL|ALL\s*$'
            fail "$AGENT_USER holds a sudo rule — it can leave its account"
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
        check_acl $root "group:$AGENT_GROUP:rwx" "default:group:$AGENT_GROUP:rwx" git
        set -l common (git -C $root rev-parse --git-common-dir 2>/dev/null)
        if test -z "$common"
            fail "$root is not a git worktree"
            continue
        end
        string match -q -- '/*' "$common"; or set common $root/$common
        check_acl (readlink -f $common) "group:$AGENT_GROUP:rwx" "default:group:$AGENT_GROUP:rwx" git
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
    if test -z "$existing"
        echo "nothing to roll back: no $AGENT_USER"
        return 0
    end
 
    set -l home $DEV_HOME
 
    # Grants first: they sit on the developer's paths, and removing the account
    # while they are there leaves entries nobody can resolve.
    # Remove only the entries this script wrote. A plain `--remove-default`
    # takes EVERY default entry with it — including one another tool put there,
    # which would silently change the modes of files nobody here wrote.
    # `-x d:u:NAME` names the default entry; `-m` cannot (measured: "Option -m
    # incomplete"), and `--remove-default` alone is the all-or-nothing form.
    for rel in $SDK_DIR_REL $TOOLCHAIN_RELS
        test -e $home/$rel; or continue
        run_cmd setfacl --recursive -x "u:$AGENT_USER" -x "d:u:$AGENT_USER" $home/$rel
    end
    run_cmd setfacl --remove-user $AGENT_USER $home

    for root in $ROOTS
        test -d "$root"; or continue
        for target in $root (git_common_dir $root)
            test -d $target; or continue
            run_cmd setfacl --recursive -x "g:$AGENT_GROUP" -x "d:g:$AGENT_GROUP" $target
        end
    end
 
    run_cmd rm --force /etc/sudoers.d/50-agent-run
    run_cmd gpasswd --delete $AGENT_USER $AGENT_GROUP
    run_cmd userdel --remove $AGENT_USER
    run_cmd groupdel $AGENT_GROUP
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
 