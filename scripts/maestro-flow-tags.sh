#!/usr/bin/env bash
#
# Maestro flow tag matching — pure text, no device, no adb.
#
# Sourced by scripts/run-maestro.sh. Split out because a `TAGS=` filter decides
# which UI flows execute, and a filter that quietly drops a flow is the same
# defect class as a test class that is never selected: the suite stays green and
# the coverage disappears. That is exactly how two recurrence classes sat
# untagged for months while CI reported success — so the matching here is unit
# tested (scripts/tests/test_maestro_flow_tags.py) against real flow files and
# synthetic ones, rather than only exercised on a run that happens to be red.
#
# ## The bug this fixes
#
# The first version compared with awk string equality:
#
#     if (line == want) { found = 1; exit }
#
# `sub()` removed the leading "- ", but nothing removed what followed. A flow
# header written as
#
#     tags:
#       - smoke␠␠␠          # one trailing space
#
# therefore did not match `TAGS=smoke` and was dropped from the run with no
# message. Nothing asserted that every flow is reachable by some tag, so such a
# flow then vanished from every suite. Tracked as
# docs/decisions/deferred-backlog.md#flow-has-tag-drops-a-flow-whose-tag-has-a-trailing-space
# (#148).
#
# Both ends are now trimmed, so the comparison is on the tag itself rather than
# on how the YAML happens to be spaced.

# Print the tags declared in a flow's `tags:` block, one per line, trimmed.
#
# The block is bounded on both sides. `---` because a flow is multi-document
# YAML and a later document may declare its own tags — those count, the file is
# still one flow. And any line that is not a list item, blank, or comment ends
# it, because the next key (`commands:`) is where the steps begin: without that
# bound `- tapOn: 'x'` reads as a tag and `TAGS=tapOn:` would select every flow.
flow_tags() {
    local file="$1"
    awk '
        function trim(s) {
            sub(/^[ \t]+/, "", s)
            sub(/[ \t\r]+$/, "", s)
            return s
        }
        /^---[ \t]*$/ { in_tags = 0; next }
        /^tags:/ { in_tags = 1; next }
        in_tags {
            line = $0
            if (line ~ /^[ \t]*-[ \t]*/) {
                sub(/^[ \t]*-[ \t]*/, "", line)
                # A YAML comment may follow the value on the same line.
                sub(/[ \t]+#.*$/, "", line)
                value = trim(line)
                sub(/^["'"'"']/, "", value)
                sub(/["'"'"']$/, "", value)
                if (value != "") print value
                next
            }
            # Blank lines and comments inside the block do not end it.
            if (line ~ /^[ \t]*$/ || line ~ /^[ \t]*#/) next
            in_tags = 0
        }
    ' "$file"
}

# True when the flow declares the given tag.
#
# String equality on the trimmed value: two spellings of the same tag are one
# tag, and a filter that treats trailing whitespace as meaning is a filter that
# deletes coverage.
flow_has_tag() {
    local file="$1" tag="$2"
    flow_tags "$file" | grep -qxF -- "$tag"
}
