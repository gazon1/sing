#!/usr/bin/env python3
"""check-git-environment.py — a git command that reads a tree must not inherit GIT_DIR,
and a git config write must name the file it writes.

Why this exists
---------------
Git exports ``GIT_DIR`` (and ``GIT_WORK_TREE``, ``GIT_INDEX_FILE``,
``GIT_COMMON_DIR``) to every hook it runs, and those variables override the
directory a git command was pointed at: ``git -C /some/tree ls-files`` inside a
hook lists the files of the tree the variables name, not the files of
``/some/tree``. A digest computed that way is a digest of a tree nobody asked
for, and the gate that reports it is green about the wrong tree.

The one script in this repository that reads a tree of its own choosing —
``scripts/pre-push-verdict.py``, which judges the tree being pushed — therefore
builds a fresh environment for every git command it runs (``tree_env()`` removes
the four variables). This gate is the mechanical half of that rule: it finds the
scripts that read a tree that is not their own checkout's root, and fails when
they do not neutralise the variables.

The rule
--------
A script that runs a git command that reads a tree, in a directory that is not
the root of its own checkout, must remove ``GIT_DIR``, ``GIT_WORK_TREE``,
``GIT_INDEX_FILE`` and ``GIT_COMMON_DIR`` from the environment that command
inherits.

What "reads a tree" means here
------------------------------
The git subcommands that change the repository or the index are not tree reads:
``git config``, ``git rm --cached`` and ``git add`` write, and a write in a tree
the caller chose is the caller's business. The rule covers the commands whose
verdict is a fact about the *contents* of a tree, and treats every other
subcommand as a write — a command this gate has never heard of is assumed to
write, because a rule that guesses "read" about an unknown command cries wolf
on the first command that turns out to be something else.

``git config`` is not a tree read for the first rule. The second rule below is
about WHERE its write lands.

The second rule — a ``git config`` write must name its destination
------------------------------------------------------------------
A plain ``git config <key> <value>`` always lands in the **SHARED** config —
the ``config`` file of the common directory — no matter which worktree ran it.
This repository is one clone with ~20 worktrees sharing one common ``.git``,
so one such write changes the config of every one of them, and the damage
shows up somewhere else: a ``core.bare = false`` that un-bares the repository
for everybody (ADR
``docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md``),
a lost ``core.hooksPath`` that leaves every worktree running no hooks at all.

So a script that writes a config value writes it into a file of its own
choosing:

  - ``git config --file <path> <key> <value>`` — exactly that file;
  - ``git config --worktree <key> <value>`` — this worktree's own config.

A write that carries NEITHER is a violation, unless the script writes the
shared config ON PURPOSE and says so in the code. Silence is not a decision,
so the opt-out has to be visible:

  - a marker comment on the command (or in the comment block right above it):
    ``# git-config-shared: <why>``.

There is exactly one escape, and it lives next to the write it excuses. The
rule used to have a second one — a registry of "scripts that write the shared
config on purpose" kept in this file. That list is somebody else's writes in
a file those writers do not touch, so it drifts from the scripts it describes.

Reads are not writes. ``--get``, ``--get-all``, ``--get-regexp``,
``--get-urlmatch``, ``--list``/``-l`` and the removal forms (``--unset``,
``--remove-section``, ``--rename-section``) do not SET a value, and this rule
is about the setting of one. (The removal forms do write the shared config
too; they are out of this rule's scope, not out of git's.)

How the tree is decided — and what the rule cannot see
------------------------------------------------------
The tree a command reads is the directory it is pointed at:

  - in Python: the ``cwd=`` of the call, or the ``-C <path>`` / ``--git-dir=``
    in the argv;
  - in shell: the directory the script has changed to.

A tree that is written as this checkout's own root — an expression that names
``__file__``, or a variable called ``ROOT`` / ``REPO_ROOT`` / ``repo`` / ``root``
— is the root, and the rule does not apply to it: the variables name this
checkout too. Anything else is another tree: a parameter, a path the caller
passes, a directory the script changed into.

Two things the rule cannot see from the source, and does not pretend to:

  - a command built at run time (``subprocess.run(cmd)`` where ``cmd`` is a
    variable) — the call site is not a literal, so it is not read;
  - a command that runs wherever the caller put the process — no ``cwd``, no
    ``-C``, no ``cd``. The caller owns that choice, and in this repository the
    caller is a CI job or the pre-push hook, which runs every script it calls
    with ``env -u GIT_DIR -u GIT_WORK_TREE -u GIT_INDEX_FILE`` itself.

The reference implementation
---------------------------
``scripts/pre-push-verdict.py`` — ``tree_env()`` removes the four variables and
every git command it runs gets that environment. The gate holds the other
scripts to the same standard.

Usage:
    python3 scripts/check-git-environment.py            # report
    python3 scripts/check-git-environment.py --quiet
    python3 scripts/check-git-environment.py --root PATH # another tree

Exit codes:
    0 — every script that reads a tree in another tree neutralises the variables,
        and every script that writes a git config value writes it into a file of
        its own choosing
    1 — a script reads a tree in another tree and inherits the variables, or a
        script writes the shared git config without saying that it means to
"""

from __future__ import annotations

import ast
import pathlib
import re
import sys

import click

# The sgate package lives in this checkout's own `scripts/src`, and the answer
# this script takes from it — the list of the variables git exports to a hook —
# is about this checkout. The bootstrap puts that root on the path before the
# import, the way `scripts/check-hook-gates.py` does: an import that happens
# first is already bound to another tree's package under the same name.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / 'src')
if _scripts_src not in sys.path:
    sys.path.insert(0, _scripts_src)

from sgate.commands.base import run  # noqa: E402
from sgate.gatekit.gitenv import INHERITED_FROM_HOOK  # noqa: E402

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

#: The variables git exports to a hook. They name the worktree the hook runs in,
#: and they win over the directory a git command is pointed at. The list is the
#: owner's — `sgate.gatekit.gitenv` answers this question for the whole
#: repository, and a second copy here is a list that goes stale.
GIT_VARS = INHERITED_FROM_HOOK

#: git subcommands that WRITE the repository or the index. They are not tree
#: reads: the rule is about a command that reports a fact about a tree's
#: contents, and a write in a tree the caller chose is the caller's business.
WRITE_SUBCOMMANDS = frozenset({
    'add', 'am', 'apply', 'bisect', 'checkout', 'cherry-pick', 'clean', 'clone',
    'commit', 'config', 'fetch', 'filter-branch', 'gc', 'init', 'merge', 'mv',
    'pull', 'push', 'rebase', 'repack', 'reset', 'restore', 'revert', 'rm',
    'sparse-checkout', 'stash', 'submodule', 'switch', 'tag', 'unpack-file',
    'update-index', 'update-ref', 'worktree', 'write-tree',
})

#: Names that mean "this checkout's own root". A tree written as one of them
#: is the root the git variables also name, so the rule does not apply to it.
ROOT_NAMES = frozenset({
    'ROOT', 'REPO_ROOT', 'ROOT_DIR', 'REPO', 'REPO_DIR',
    'root', 'root_dir', 'rootdir', 'repo', 'repo_dir', 'repo_root',
})

#: The `git config` forms that do not SET a value: they read it, or they
#: remove it. This rule is about the setting of a value, so these are not
#: writes here — `git config --get key` stays exactly as it was.
CONFIG_NON_WRITE_FLAGS = frozenset({
    '--get', '--get-all', '--get-color', '--get-colorbool', '--get-regexp',
    '--get-urlmatch', '--list', '-l',
    '--unset', '--unset-all', '--remove-section', '--rename-section',
})

#: The `git config` options that take a value: the value is not a positional.
CONFIG_OPTIONS_WITH_VALUE = frozenset({
    '--blob', '--default', '--file', '-f', '--get-color', '--get-urlmatch',
    '--type',
})

#: The `git config` options that send the write to a file the caller names:
#: `--file <path>` writes exactly that file, `--worktree` writes this
#: worktree's own config. Both keep the write out of the shared config.
CONFIG_SAFE_FLAGS = frozenset({'--file', '-f', '--worktree'})

#: The `git config` options that write a file OUTSIDE this repository: the
#: user's own `~/.gitconfig` and the system's. They are not writes to the
#: shared config of this clone — a rule that reported them would be reporting
#: the wrong file with the wrong explanation.
CONFIG_OTHER_SCOPE_FLAGS = frozenset({'--global', '--system'})

#: The marker that says a shared-config write is on purpose. Greppable, and
#: it lives in the script that does the write, next to the write.
SHARED_CONFIG_MARKER = 'git-config-shared'

#: The subprocess entry points that run a command.
SUBPROCESS_FUNCS = frozenset({'run', 'Popen', 'check_output', 'check_call'})

#: Global git options that take a value: the value is not the subcommand.
GIT_OPTIONS_WITH_VALUE = frozenset({
    '-c', '--namespace', '--exec-path', '--git-dir', '--work-tree', '--super-prefix',
})

#: The global git options that name the tree the command reads.
GIT_TREE_OPTIONS = ('-C', '--git-dir', '--work-tree')

#: A `cd` in a shell script.
SHELL_CD = re.compile(r'^\s*cd\s+(.+?)\s*$')

#: A git command inside a command substitution: `repo_root="$(git rev-parse …)"`.
#: The second group is the rest of the command, so that the second rule can
#: read what the command does (`$(git config --get key)` is a read).
SHELL_SUBSTITUTION = re.compile(r'\$\(\s*git\s+([a-z][a-z0-9-]*)((?:\s+[^)]*)?)')

#: An assignment in a shell script.
SHELL_ASSIGN = re.compile(r'^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$')

#: Words that may stand in front of a command in a shell line.
SHELL_PREFIXES = ('if', 'then', 'else', 'elif', 'do', 'while', 'until', 'exec', 'command')

#: The shell ways of removing a variable from the environment.
SHELL_UNSET = re.compile(
    r'(?:env\s+-[a-zA-Z]*u\s+GIT_DIR\b|unset\s+GIT_DIR\b|(?:^|\s)GIT_DIR=(?:""|\'\'|\s|$))'
)


class Site:
    """One git command, and the tree it reads."""

    def __init__(
        self,
        path: pathlib.Path,
        lineno: int,
        subcommand: str,
        tree: str,
        tree_is_root: bool = False,
        words: tuple[str, ...] = (),
        end_lineno: int = 0,
    ) -> None:
        self.path = path
        self.lineno = lineno
        self.subcommand = subcommand
        #: The expression that names the tree. Empty when the command is not
        #: pointed at any tree — it then runs wherever the caller put it.
        self.tree = tree
        #: Whether the tree is this checkout's own root.
        self.tree_is_root = tree_is_root
        #: The arguments of the git command, after `git`. The second rule
        #: reads them: whether the command writes, and where it writes.
        self.words = words
        #: The last line the command occupies — a Python call may span lines.
        self.end_lineno = end_lineno or lineno

    @property
    def is_read(self) -> bool:
        """Whether the command reports a fact about a tree's contents."""
        return self.subcommand not in WRITE_SUBCOMMANDS

    @property
    def is_another_tree(self) -> bool:
        """Whether the command is pointed at a tree that is not this checkout's root."""
        return bool(self.tree) and not self.tree_is_root

    def __repr__(self) -> str:  # pragma: no cover — a debugging aid
        return f'<Site {self.path.name}:{self.lineno} git {self.subcommand} in {self.tree!r}>'


def _is_root_expression(expression: str, parameters: frozenset[str] = frozenset()) -> bool:
    """Whether the expression names this checkout's own root.

    A parameter is never this checkout's root: a name the caller chose may
    name any tree, and a tree the caller chose is exactly what the rule is
    about. Everything else that reads like the root — a constant derived from
    ``__file__``, or a variable called ROOT / REPO_ROOT / repo / root — is
    the root.
    """
    if not expression:
        return False
    if '__file__' in expression:
        return True
    names = set(re.findall(r'[A-Za-z_][A-Za-z0-9_]*', expression))
    if names & parameters:
        return False
    return bool(names & ROOT_NAMES)


def _script_files(root: pathlib.Path) -> list[pathlib.Path]:
    """The scripts the rule is about: everything under `scripts/`.

    The tests are not in scope: they build throwaway repositories and drive the
    gates against them, and the environment they run git in is the environment
    the test itself chose.
    """
    scripts = root / 'scripts'
    if not scripts.is_dir():
        return []
    files = []
    for path in sorted(scripts.rglob('*')):
        if not path.is_file() or path.suffix not in ('.py', '.sh'):
            continue
        if '__pycache__' in path.parts:
            continue
        relative = path.relative_to(scripts)
        if relative.parts and relative.parts[0] == 'tests':
            continue
        files.append(path)
    return files


def _words(source: str, elements: list[ast.expr]) -> list[str]:
    """The text of each element of a command list.

    A literal string is its own value; anything else is the source that
    produced it, because that source is what says which tree it names.
    """
    words = []
    for element in elements:
        if isinstance(element, ast.Constant) and isinstance(element.value, str):
            words.append(element.value)
        else:
            segment = ast.get_source_segment(source, element)
            words.append(segment if segment is not None else '')
    return words


def _split_command(words: list[str]) -> tuple[str, str]:
    """The subcommand of a git command, and the tree it names.

    Returns `(subcommand, tree)`. The tree is empty when the command names
    none: it then runs in the directory the process was started in.
    """
    subcommand = ''
    tree = ''
    index = 0
    while index < len(words):
        word = words[index]
        if word in GIT_TREE_OPTIONS:
            if index + 1 < len(words):
                tree = words[index + 1]
            index += 2
            continue
        if any(word.startswith(option + '=') for option in GIT_TREE_OPTIONS):
            tree = word.split('=', 1)[1]
            index += 1
            continue
        if word in GIT_OPTIONS_WITH_VALUE:
            index += 2
            continue
        if word.startswith('-'):
            index += 1
            continue
        subcommand = word
        break
    return subcommand, tree


def _python_sites(path: pathlib.Path, source: str) -> list[Site]:
    """Every git command a Python script runs."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    sites: list[Site] = []

    def visit(node: ast.AST, parameters: frozenset[str]) -> None:
        local = parameters
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            arguments = node.args
            names = {argument.arg for argument in arguments.args}
            names |= {argument.arg for argument in arguments.posonlyargs}
            names |= {argument.arg for argument in arguments.kwonlyargs}
            if arguments.vararg:
                names.add(arguments.vararg.arg)
            if arguments.kwarg:
                names.add(arguments.kwarg.arg)
            local = frozenset(names)
        if isinstance(node, ast.Call):
            site = _site_of_call(node, source, path, local)
            if site is not None:
                sites.append(site)
        for child in ast.iter_child_nodes(node):
            visit(child, local)

    visit(tree, frozenset())
    return sites


def _site_of_call(
    node: ast.Call, source: str, path: pathlib.Path, parameters: frozenset[str]
) -> Site | None:
    """The git command a subprocess call runs, and the tree it reads."""
    function = node.func
    if not isinstance(function, ast.Attribute) or function.attr not in SUBPROCESS_FUNCS:
        return None
    if not (isinstance(function.value, ast.Name) and function.value.id == 'subprocess'):
        return None
    if not node.args:
        return None
    argument = node.args[0]
    if isinstance(argument, (ast.List, ast.Tuple)):
        words = _words(source, list(argument.elts))
    elif isinstance(argument, ast.Constant) and isinstance(argument.value, str):
        words = argument.value.split()
    else:
        # The command is built at run time. What it will be is not in the
        # source, so the rule does not read it.
        return None
    if 'git' not in words:
        return None
    tail = words[words.index('git') + 1:]
    subcommand, tree = _split_command(tail)
    if not subcommand:
        return None
    cwd = ''
    for keyword in node.keywords:
        if keyword.arg == 'cwd':
            segment = ast.get_source_segment(source, keyword.value)
            cwd = segment if segment is not None else ''
    pointed = cwd or tree
    return Site(
        path,
        node.lineno,
        subcommand,
        pointed,
        _is_root_expression(pointed, parameters),
        tuple(tail),
        node.end_lineno or node.lineno,
    )


def _shell_command_text(part: str) -> str:
    """The command a fragment of a shell line runs, without the prefixes.

    `env -u GIT_DIR git ls-files` and `if git diff --quiet` run the same
    command; the prefixes are what stand between the reader and the command.
    """
    text = part.strip()
    changed = True
    while changed:
        changed = False
        while text.startswith(('(', '`', '"', "'")):
            text = text[1:].lstrip()
            changed = True
        if text.startswith('$('):
            text = text[2:].lstrip()
            changed = True
        for prefix in SHELL_PREFIXES:
            if text.startswith(prefix + ' '):
                text = text[len(prefix) + 1:].lstrip()
                changed = True
        if text.startswith('env '):
            rest = text[4:].lstrip()
            while rest.startswith('-'):
                parts = rest.split(None, 1)
                option = parts[0]
                rest = parts[1].lstrip() if len(parts) > 1 else ''
                if option in ('-u', '--unset') and rest:
                    pieces = rest.split(None, 1)
                    rest = pieces[1].lstrip() if len(pieces) > 1 else ''
            text = rest
            changed = True
    return text


def _logical_lines(source: str) -> list[tuple[int, str]]:
    """The source's logical lines: a line a backslash continues is one line.

    A command written across ``\\`` lines is one command, and a reader that
    splits the text first sees two halves of it — ``git config \\`` names no
    key and no value, and the key sits on the next line. The number is the
    line the command STARTS on, so a report points at the place a reader has
    to change.
    """
    out: list[tuple[int, str]] = []
    pending = ''
    start = 0
    for number, line in enumerate(source.splitlines(), start=1):
        stripped = line.rstrip()
        if not pending:
            start = number
        if stripped.endswith('\\'):
            pending += stripped[:-1] + ' '
            continue
        out.append((start, pending + stripped))
        pending = ''
    if pending:
        out.append((start, pending.rstrip()))
    return out


def _shell_sites(path: pathlib.Path, source: str) -> list[Site]:
    """Every git command a shell script runs, and the directory it runs in."""
    sites = []
    tree = ''
    tree_is_root = False
    root_derived: set[str] = set()
    for number, line in _logical_lines(source):
        stripped = line.strip()
        if stripped.startswith('#'):
            continue
        assignment = SHELL_ASSIGN.match(line)
        if assignment:
            name, value = assignment.group(1), assignment.group(2)
            if 'BASH_SOURCE' in value or 'dirname' in value:
                root_derived.add(name)
        change = SHELL_CD.match(line)
        if change:
            target = change.group(1).strip().strip('"\'')
            tree = target
            tree_is_root = (
                'BASH_SOURCE' in target
                or 'dirname' in target
                or target.lstrip('$') in root_derived
                or target in ('.', '..', '~')
            )
            continue
        for part in line.split('|'):
            command = _shell_command_text(part)
            if not command.startswith('git '):
                continue
            words = command.split()
            subcommand = words[1] if len(words) > 1 else ''
            if not subcommand:
                continue
            sites.append(Site(path, number, subcommand, tree, tree_is_root, tuple(words[1:])))
        substitution = SHELL_SUBSTITUTION.search(line)
        if substitution:
            sites.append(
                Site(
                    path,
                    number,
                    substitution.group(1),
                    tree,
                    tree_is_root,
                    # The words of the command inside the substitution: the
                    # subcommand first, exactly as a command written out in
                    # full spells it. Without the subcommand the second rule
                    # could not tell `$(git config key value)` from a command
                    # that does not write anything.
                    (substitution.group(1),) + tuple(substitution.group(2).split()),
                )
            )
    return sites


#: The shared helpers that do the scrubbing. A script that calls one of them
#: controls its environment — the whole point of the helper is that the
#: answer is written down once (``sgate.gatekit.gitenv``).
SANCTIONED_HELPERS = ('tree_env', 'sandbox_env', 'git_sandbox')


def _python_neutralizes(source: str) -> bool:
    """Whether a Python script removes the git variables from the environment.

    Three shapes count: removing the name itself (``env.pop('GIT_DIR', None)``),
    removing the names of a collection that holds them — the shape the
    reference implementation uses — and calling the shared helper, which is
    where the answer lives now.
    """
    if any(helper in source for helper in SANCTIONED_HELPERS):
        return True
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return False
    holds_names = False
    removes = False
    for node in ast.walk(tree):
        if isinstance(node, (ast.Tuple, ast.List, ast.Set)):
            if any(
                isinstance(element, ast.Constant) and element.value in GIT_VARS
                for element in node.elts
            ):
                holds_names = True
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if node.func.attr in ('pop', 'remove', 'discard'):
                removes = True
                if (
                    node.args
                    and isinstance(node.args[0], ast.Constant)
                    and node.args[0].value in GIT_VARS
                ):
                    return True
        if isinstance(node, ast.Delete):
            for target in node.targets:
                if _is_variable_slot(target):
                    return True
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if _is_variable_slot(target):
                    return True
    return holds_names and removes


def _is_variable_slot(node: ast.expr) -> bool:
    """Whether the node assigns to or deletes an environment variable by name."""
    if not isinstance(node, ast.Subscript):
        return False
    key = node.slice
    return isinstance(key, ast.Constant) and key.value in GIT_VARS


def _shell_neutralizes(source: str) -> bool:
    """Whether a shell script removes the git variables from the environment."""
    return bool(SHELL_UNSET.search(source))


def _neutralizes(path: pathlib.Path, source: str) -> bool:
    """Whether the script controls the git variables its commands inherit."""
    if path.suffix == '.sh':
        return _shell_neutralizes(source)
    return _python_neutralizes(source)


def scan(root: pathlib.Path) -> tuple[list[str], list[Site]]:
    """The violations of both rules, and the sites the first rule is about."""
    violations, sensitive = scan_tree_reads(root)
    config_violations, _config_sites = scan_config_writes(root)
    return violations + config_violations, sensitive


def scan_tree_reads(root: pathlib.Path) -> tuple[list[str], list[Site]]:
    """The violations of the first rule, and the sites it is about."""
    violations: list[str] = []
    sensitive: list[Site] = []
    for path in _script_files(root):
        source = path.read_text(encoding='utf-8', errors='replace')
        if path.suffix == '.sh':
            sites = _shell_sites(path, source)
        else:
            sites = _python_sites(path, source)
        reads_another_tree = [site for site in sites if site.is_read and site.is_another_tree]
        if not reads_another_tree:
            continue
        sensitive.extend(reads_another_tree)
        if _neutralizes(path, source):
            continue
        relative = path.relative_to(root).as_posix()
        for site in reads_another_tree:
            violations.append(
                f'{relative}:{site.lineno}: `git {site.subcommand}` reads the tree at '
                f'`{site.tree}`, which is not this checkout\'s root, and the environment it '
                f'inherits is not controlled: git exports GIT_DIR, GIT_WORK_TREE, '
                f'GIT_INDEX_FILE and GIT_COMMON_DIR to a hook, and they name the tree the '
                f'hook runs in — so the command reads that tree, not this one'
            )
    return violations, sensitive


def _config_write(words: tuple[str, ...]) -> tuple[bool, str, bool]:
    """Whether a git command writes a config value, which key, and whether it is safe.

    A write SETS a value: the command carries a key AND a value. The forms
    that only read (``--get``, ``--list``) and the forms that remove
    (``--unset``, ``--remove-section``, ``--rename-section``) set nothing, so
    they are not writes here — ``git config --get key`` stays exactly as it
    was before this rule existed.

    A write is safe when it names its destination: ``--file <path>`` (exactly
    that file) or ``--worktree`` (this worktree's own config).
    """
    if 'config' not in words:
        return False, '', False
    args = words[words.index('config') + 1:]
    flags: set[str] = set()
    positionals: list[str] = []
    index = 0
    while index < len(args):
        word = args[index]
        if word.startswith('#'):
            break  # the rest of the line is a comment, not an argument
        if word.startswith('-'):
            name = word.split('=', 1)[0]
            flags.add(name)
            if '=' not in word and name in CONFIG_OPTIONS_WITH_VALUE:
                index += 2  # the option takes the next word as its value
                continue
            index += 1
            continue
        positionals.append(word)
        index += 1
    if flags & CONFIG_NON_WRITE_FLAGS:
        return False, '', False
    if flags & CONFIG_OTHER_SCOPE_FLAGS:
        # `--global` and `--system` write the user's own config and the
        # system's — not the shared config of this clone, which is what this
        # rule is about.
        return False, '', False
    if len(positionals) < 2:
        return False, '', False
    return True, positionals[0], bool(flags & CONFIG_SAFE_FLAGS)


def _marked_shared_write(lines: list[str], first: int, last: int) -> bool:
    """Whether the command says, in code, that it writes the shared config on purpose.

    The marker may sit on the command itself or in the comment block right
    above it: a decision somebody wrote down, next to the write it decides.
    """
    for number in range(first, last + 1):
        if SHARED_CONFIG_MARKER in lines[number - 1]:
            return True
    number = first - 1
    while number >= 1 and lines[number - 1].strip().startswith('#'):
        if SHARED_CONFIG_MARKER in lines[number - 1]:
            return True
        number -= 1
    return False


def scan_config_writes(root: pathlib.Path) -> tuple[list[str], list[Site]]:
    """The shared-config writes the second rule is about, and the sites they are."""
    violations: list[str] = []
    sites: list[Site] = []
    for path in _script_files(root):
        source = path.read_text(encoding='utf-8', errors='replace')
        lines = source.splitlines()
        if path.suffix == '.sh':
            file_sites = _shell_sites(path, source)
        else:
            file_sites = _python_sites(path, source)
        relative = path.relative_to(root).as_posix()
        for site in file_sites:
            writes, key, safe = _config_write(site.words)
            if not writes:
                continue
            sites.append(site)
            if safe:
                continue
            if _marked_shared_write(lines, site.lineno, site.end_lineno):
                continue
            violations.append(
                f'{relative}:{site.lineno}: `git config {key} <value>` writes the SHARED git '
                f'config — a plain `git config` always lands in the common directory\'s '
                f'`config`, which every worktree of this repository reads, so one write '
                f'changes all of them; write it with `--file <path>` or `--worktree`, or '
                f'say that the shared write is on purpose (a `# {SHARED_CONFIG_MARKER}:` '
                f'comment on the command or right above it)'
            )
    return violations, sites


#: The fewest sites the rule must find. A rule that finds nothing has proved
#: nothing, and a passing gate that checked nothing is the failure this
#: repository keeps paying for.
MIN_SITES = 1


@click.command()
@click.option('--quiet', is_flag=True)
@click.option(
    '--root',
    default=None,
    help='the tree to check (default: the repository this script lives in)',
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, root: str | None) -> None:
    """Fail when a script reads a tree in another tree without neutralising GIT_*,
    or when a script writes the shared git config without saying that it means to."""
    tree = pathlib.Path(root).resolve() if root else ROOT
    tree_violations, sensitive = scan_tree_reads(tree)
    config_violations, config_sites = scan_config_writes(tree)
    violations = tree_violations + config_violations

    for violation in violations:
        click.echo(f'ERROR: {violation}')

    if len(sensitive) < MIN_SITES:
        click.echo(
            f'ERROR: no script reads a tree in another tree — the rule found nothing to '
            f'check, and a rule that finds nothing proves nothing'
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if tree_violations:
        click.echo('')
        click.echo(
            'check-git-environment: FAIL — a git command reads a tree that is not its own '
            'checkout\'s root while inheriting the variables git exports to a hook'
        )
        click.echo(
            '  fix with — build the environment for the command, as '
            'scripts/pre-push-verdict.py does in tree_env()'
        )

    if config_violations:
        click.echo('')
        click.echo(
            'check-git-environment: FAIL — a `git config` write without `--file` or '
            '`--worktree` lands in the SHARED config, which every worktree of this '
            'repository reads'
        )
        click.echo(
            '  fix with — write the value with `git config --file <path>` or '
            '`git config --worktree`, or say that the shared write is on purpose (a '
            f'`# {SHARED_CONFIG_MARKER}:` comment next to the write)'
        )

    if violations:
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(
            f'check-git-environment: OK — {len(sensitive)} git command(s) read a tree that '
            f'is not their own checkout\'s root, and every script that runs one neutralises '
            f'GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE and GIT_COMMON_DIR'
        )
        click.echo(
            f'  and {len(config_sites)} git config write(s) land in a file of their own '
            f'choosing — `--file`, `--worktree`, or a shared write that says so'
        )
    raise ctx.exit(CLEAN)


if __name__ == '__main__':
    raise SystemExit(run(cli))
