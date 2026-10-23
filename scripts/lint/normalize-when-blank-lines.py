#!/usr/bin/env python3
"""Normalise the blank lines between `when` branches.

The ktlint rule `BlankLineBetweenWhenConditions` says: if at least one branch of a
`when` is multi-line, there is a blank line between *all* the branches; if every
branch is single-line, there is no blank line at all.

This script applies exactly that, so the rule stops being something an agent has to
re-derive from the ktlint bytecode.
"""
import re
import sys


def branch_lines(lines, start, end):
    """The indices of the branch lines of the `when` that spans [start, end]."""
    indent = len(lines[start]) - len(lines[start].lstrip())
    out = []
    for i in range(start + 1, end):
        line = lines[i]
        if not line.strip():
            continue
        cur = len(line) - len(line.lstrip())
        if cur > indent and "->" in line:
            out.append(i)
    return out


def has_multiline_branch(lines, branches):
    """Whether any branch of the `when` takes more than one line of code."""
    for position in range(len(branches) - 1):
        start = branches[position]
        end = branches[position + 1]
        code = [line for line in lines[start + 1:end] if line.strip()]
        if code:
            return True
    return False


def normalise(path):
    with open(path) as handle:
        lines = handle.read().split("\n")

    changed = False
    index = 0
    while index < len(lines):
        line = lines[index]
        if re.search(r"(^|\s)when\s*\(", line) and line.rstrip().endswith("{"):
            indent = len(line) - len(line.lstrip())
            end = index + 1
            while end < len(lines):
                candidate = lines[end]
                if candidate.strip() == "}" and (len(candidate) - len(candidate.lstrip())) == indent:
                    break
                end += 1
            branches = branch_lines(lines, index, end)
            multiline = has_multiline_branch(lines, branches)
            # Walk the branches from the last to the first, so the earlier indices
            # stay valid while the later ones move.
            for position in range(len(branches) - 1, 0, -1):
                current = branches[position]
                previous = branches[position - 1]
                gap = range(previous + 1, current)
                blanks = [i for i in gap if not lines[i].strip()]
                if multiline and not blanks:
                    lines.insert(current, "")
                    changed = True
                elif not multiline and blanks:
                    for offset in reversed(blanks):
                        del lines[previous + 1 + offset]
                    changed = True
            index = end
        index += 1

    if changed:
        with open(path, "w") as handle:
            handle.write("\n".join(lines))
    return changed


if __name__ == "__main__":
    for target in sys.argv[1:]:
        print(f"{target}: {'changed' if normalise(target) else 'unchanged'}")
