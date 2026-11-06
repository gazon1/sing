#!/usr/bin/env python3
"""Phase 1 of build-investigation/split-plan: which code is hot, and what it depends on.

Three questions, one pass over the tree and one over the git history:

  1. which files, packages and clusters change most often (hot-files / hot-packages);
  2. which files change in the same commit (co-changes) — the connectivity a split must not cut;
  3. which packages import which, and where the cycles are (package-graph).

Production (`commonMain`, `jvmMain`, `androidMain`) and test (`commonTest`, `jvmTest`,
`androidHostTest`) sources are counted apart: a hot test file says where the tests are, not where a
module boundary should go.

The import graph is an upper bound on coupling: Koin aggregation, KSP/Room generated code and
reflection are invisible to it, so a candidate chosen here is checked by hand before any split.

Output is deterministic (every list is sorted by count desc, then name asc), so a re-run produces
byte-identical files and a diff is reviewable.
"""

import argparse
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict, deque

SRC = "shared/src"
PRODUCTION = ("commonMain", "jvmMain", "androidMain")
TESTS = ("commonTest", "jvmTest", "androidHostTest")
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)")
IMPORT_RE = re.compile(r"^import\s+([\w.]+)")
EXPECT_RE = re.compile(r"^\s*(?:expect|actual)\s+(?:fun|class|object|interface|val|var)\b")
TOP_PAIRS = 100
HOT_FILES_FOR_PAIRS = 60
TOP_FILES = 200
TOP_GONE = 100
COLUMNS = ["cluster", "files", "tests", "lines", "commits", "common", "platform", "expects", "in", "out",
           "score", "cycle"]


def git_commits(since, root):
    """[[path, ...], ...] — one list of changed .kt files per commit, newest first.

    Run from the repository root, not from the caller's directory: the pathspec is root-relative, and
    a run started inside this directory would silently see no commits at all.
    """
    out = subprocess.run(
        ["git", "log", f"--since={since}", "--pretty=format:COMMIT %H", "--name-only", "--", SRC],
        capture_output=True, text=True, check=True, cwd=root,
    ).stdout
    commits, current = [], None
    for line in out.splitlines():
        if line.startswith("COMMIT "):
            current = []
            commits.append(current)
        elif line.strip() and current is not None:
            path = line.strip()
            if path.endswith(".kt"):
                current.append(path)
    return [commit for commit in commits if commit]


def scan_sources(root):
    """path -> {package, imports, lines, source_set, production, expects}."""
    files = {}
    for dirpath, _, names in os.walk(os.path.join(root, SRC)):
        for name in sorted(names):
            if not name.endswith(".kt"):
                continue
            path = os.path.relpath(os.path.join(dirpath, name), root)
            package, imports, expects, lines = None, [], 0, 0
            with open(os.path.join(root, path), encoding="utf-8") as handle:
                for line in handle:
                    lines += 1
                    match = PACKAGE_RE.match(line)
                    if match and package is None:
                        package = match.group(1)
                    match = IMPORT_RE.match(line)
                    if match:
                        imports.append(match.group(1).rstrip("."))
                    if EXPECT_RE.match(line):
                        expects += 1
            source_set = next((s for s in PRODUCTION + TESTS if f"/{s}/" in path), "other")
            files[path] = {
                "package": package,
                "imports": imports,
                "lines": lines,
                "source_set": source_set,
                "production": source_set in PRODUCTION,
                "expects": expects,
            }
    return files


def resolve(fqn, packages):
    """Longest declared package that prefixes the import; None when the import is external.

    The remainder must look like a member of that package — `Type` or `Type.member`. Without that
    check, `com.singularity.todo.core.format.formatPadded` (a package that lives in another Gradle
    module) falls back to the root package `com.singularity.todo` and invents an edge through it.
    `shortcut: nested classes deeper than Class.Nested.member resolve to the shorter prefix, none
    live here`.
    """
    parts = fqn.split(".")
    for end in range(len(parts), 0, -1):
        candidate = ".".join(parts[:end])
        if candidate in packages:
            return candidate if len(parts) - end <= 2 else None
    return None


def cluster_of(package):
    """feature/tasks, core/di, test/fakes — the two package segments after com/singularity/todo.

    Derived from the package, never from the file path: the root package `com.singularity.todo`
    is declared by App.kt in three source sets, and a path-derived cluster would split it into
    one cluster per file name and invent cycles between them.
    """
    parts = package.split(".")
    if parts[:3] != ["com", "singularity", "todo"]:
        return "(external)"
    tail = parts[3:]
    if len(tail) > 1 and tail[0] in ("feature", "core", "test"):
        return f"{tail[0]}/{tail[1]}"
    return tail[0] if tail else "(root)"


def strongly_connected(adjacency, nodes):
    """Groups of two or more packages that can all reach each other (mutual reachability)."""
    reach = {}
    for node in nodes:
        seen, stack = set(), [node]
        while stack:
            for nxt in adjacency[stack.pop()]:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        reach[node] = seen
    groups, done = [], set()
    for node in nodes:
        if node in done:
            continue
        component = {other for other in nodes if other == node or (other in reach[node] and node in reach[other])}
        if len(component) > 1:
            groups.append(sorted(component))
        done |= component
    return sorted(groups, key=lambda group: (-len(group), group))


def shortest_cycle(adjacency, node):
    """Shortest path from a neighbour of `node` back to `node`; None when `node` is in no cycle.

    This is the smallest set of edges that has to be broken before `node` can become its own module.
    """
    best = None
    for start in sorted(adjacency[node]):
        queue, seen = deque([[start]]), {start}
        while queue:
            path = queue.popleft()
            if node in adjacency[path[-1]]:
                candidate = [node] + path + [node]
                if best is None or len(candidate) < len(best):
                    best = candidate
                break
            for nxt in sorted(adjacency[path[-1]]):
                if nxt not in seen:
                    seen.add(nxt)
                    queue.append(path + [nxt])
    return best


def write(path, lines):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", default="6 months ago")
    parser.add_argument("--out", default=".")
    args = parser.parse_args()

    root = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                          check=True).stdout.strip()
    out = args.out

    commits = git_commits(args.since, root)
    files = scan_sources(root)
    production = {path: info for path, info in files.items() if info["production"]}
    # Graph nodes are packages that have production code in THIS module. A package that only test
    # sources declare (e.g. com.singularity.todo.core.database, whose code lives in :core-database)
    # would otherwise become a node whose imports come from another Gradle module.
    packages = {info["package"] for info in production.values() if info["package"]}
    cluster_of_path = {path: cluster_of(info["package"] or "(no package)") for path, info in files.items()}
    package_cluster = {package: cluster_of(package) for package in packages}

    # ── 1. hot files, packages, clusters ─────────────────────────────────────────────────────────
    per_file = Counter(path for commit in commits for path in commit)
    ranked_files = sorted(per_file.items(), key=lambda item: (-item[1], item[0]))
    # A file moved out of :shared during the window still appears in the history; it is listed
    # apart, because it is not a candidate and would inflate the ranking.
    hot_files = [(path, count) for path, count in ranked_files if path in files]
    gone_files = [(path, count) for path, count in ranked_files if path not in files]

    package_commits = Counter()
    cluster_commits = Counter()
    for commit in commits:
        touched = [path for path in commit if path in files]
        for name in {files[path]["package"] for path in touched if files[path]["package"]}:
            package_commits[name] += 1
        for cluster in {cluster_of_path[path] for path in touched}:
            cluster_commits[cluster] += 1
    hot_packages = sorted(package_commits.items(), key=lambda item: (-item[1], item[0]))

    cluster_files = Counter(cluster_of_path[path] for path in production)
    cluster_tests = Counter(cluster_of_path[path] for path in files if not files[path]["production"])
    cluster_lines = Counter()
    cluster_source_sets = defaultdict(Counter)
    cluster_expects = Counter()
    for path, info in production.items():
        cluster = cluster_of_path[path]
        cluster_lines[cluster] += info["lines"]
        cluster_source_sets[cluster][info["source_set"]] += 1
        cluster_expects[cluster] += info["expects"]

    hot_package_lines = []
    for name in hot_packages:
        name = name[0]
        members = [info for info in production.values() if info["package"] == name]
        tests = sum(1 for info in files.values() if info["package"] == name and not info["production"])
        hot_package_lines.append(
            f"{package_commits[name]}\t{len(members)}\t{tests}\t{sum(i['lines'] for i in members)}\t{name}")

    write(os.path.join(out, "hot-files.txt"), [
        f"# {len(commits)} commits since '{args.since}', .kt files under {SRC}",
        f"# {len(hot_files)} present in the tree, {len(gone_files)} moved out during the window",
        f"# capped at the top {TOP_FILES} and {TOP_GONE}: a full list is one `analyse.py` run away",
        "",
        "# commits\tset\tpath",
        *(f"{count}\t{files[path]['source_set']}\t{path}" for path, count in hot_files[:TOP_FILES]),
        "",
        "# moved out of the tree during the window — history only, not candidates",
        "# commits\tpath",
        *(f"{count}\t{path}" for path, count in gone_files[:TOP_GONE]),
    ])
    write(os.path.join(out, "hot-packages.txt"), [
        f"# {len(commits)} commits since '{args.since}'",
        "",
        "# commits\tfiles\ttests\tlines\tpackage   (files and lines count production sources only)",
        *hot_package_lines,
        "",
        "# commits\tfiles\ttests\tcluster",
        *(
            f"{cluster_commits[cluster]}\t{cluster_files[cluster]}\t{cluster_tests[cluster]}\t{cluster}"
            for cluster in sorted(set(cluster_files) | set(cluster_tests),
                                  key=lambda c: (-cluster_commits[c], c))
        ),
    ])

    # ── 2. co-changes ────────────────────────────────────────────────────────────────────────────
    top = [path for path, _ in hot_files[:HOT_FILES_FOR_PAIRS]]
    pairs = Counter()
    for commit in commits:
        touched = sorted(set(commit) & set(top))
        for index, first in enumerate(touched):
            for second in touched[index + 1:]:
                pairs[(first, second)] += 1
    cluster_pairs = Counter()
    for commit in commits:
        clusters = sorted({cluster_of_path[path] for path in commit if path in files})
        for index, first in enumerate(clusters):
            for second in clusters[index + 1:]:
                cluster_pairs[(first, second)] += 1

    write(os.path.join(out, "co-changes.txt"), [
        f"# files changed in the same commit, among the {HOT_FILES_FOR_PAIRS} hottest files, count >= 2",
        "# count\tfile_a\tfile_b",
        *(f"{count}\t{first}\t{second}" for (first, second), count in
          sorted(pairs.items(), key=lambda item: (-item[1], item[0]))[:TOP_PAIRS] if count >= 2),
        "",
        "# clusters changed in the same commit, count >= 2",
        "# count\tcluster_a\tcluster_b",
        *(f"{count}\t{first}\t{second}" for (first, second), count in
          sorted(cluster_pairs.items(), key=lambda item: (-item[1], item[0]))[:TOP_PAIRS] if count >= 2),
    ])

    # ── 3. package graph ─────────────────────────────────────────────────────────────────────────
    adjacency = defaultdict(set)
    package_edges = set()
    for info in production.values():
        source = info["package"]
        if not source:
            continue
        for fqn in info["imports"]:
            target = resolve(fqn, packages)
            if target and target != source:
                adjacency[source].add(target)
                package_edges.add((source, target))
    in_degree = Counter(target for _, target in package_edges)
    out_degree = Counter(source for source, _ in package_edges)
    cycles = strongly_connected(adjacency, sorted(packages))

    # ── cluster table: degrees counted between clusters, not packages ────────────────────────────
    cluster_out = defaultdict(set)
    cluster_in = defaultdict(set)
    for info in production.values():
        source = info["package"]
        if not source:
            continue
        source_cluster = package_cluster[source]
        for fqn in info["imports"]:
            target = resolve(fqn, packages)
            if not target:
                continue
            target_cluster = package_cluster[target]
            if target_cluster != source_cluster:
                cluster_out[source_cluster].add(target_cluster)
                cluster_in[target_cluster].add(source_cluster)

    cluster_nodes = sorted(set(cluster_files) | set(cluster_tests))
    cluster_cycles = strongly_connected(cluster_out, cluster_nodes)

    write(os.path.join(out, "package-graph.txt"), [
        f"# packages={len(packages)} files={len(files)} edges={len(package_edges)} cycles={len(cycles)}",
        "# edges come from production imports only",
        "",
        "# in\tout\tfiles\tlines\tpackage",
        *(
            f"{in_degree[name]}\t{out_degree[name]}\t"
            f"{sum(1 for i in production.values() if i['package'] == name)}\t"
            f"{sum(i['lines'] for i in production.values() if i['package'] == name)}\t{name}"
            for name in sorted(packages, key=lambda n: (-in_degree[n], n))
        ),
        "",
        "# cycles (packages that can all reach each other)",
        *(f"[{len(group)}] {' '.join(group)}" for group in cycles),
        "",
        "# cluster cycles — the clusters a split cannot separate without breaking the cycle",
        *(f"[{len(group)}] {' '.join(group)}" for group in cluster_cycles),
        "",
        "# cluster edges: importing cluster -> imported cluster",
        *(f"{source} -> {target}" for source, targets in sorted(cluster_out.items())
          for target in sorted(targets)),
    ])

    rows = []
    for cluster in cluster_nodes:
        rows.append({
            "cluster": cluster,
            "files": cluster_files[cluster],
            "tests": cluster_tests[cluster],
            "lines": cluster_lines[cluster],
            "commits": cluster_commits[cluster],
            "common": cluster_source_sets[cluster]["commonMain"],
            "platform": cluster_source_sets[cluster]["jvmMain"] + cluster_source_sets[cluster]["androidMain"],
            "expects": cluster_expects[cluster],
            "in": len(cluster_in[cluster]),
            "out": len(cluster_out[cluster]),
            "score": cluster_commits[cluster] * cluster_files[cluster],
            "cycle": " -> ".join(shortest_cycle(cluster_out, cluster) or ["-"]),
        })

    ranked = sorted(rows, key=lambda row: (-row["score"], row["cluster"]))
    pilot = [
        row for row in ranked
        if row["files"] >= 100
        and row["common"] > 0
        and not row["cluster"].startswith("test/")
        and row["cluster"] != "core/di"
    ]
    write(os.path.join(out, "cluster-data.tsv"), [
        "# generated by analyse.py — every number quoted in cluster-map.md comes from here",
        "# files/lines/common/platform/expects count production sources; tests are listed apart",
        "# all clusters",
        "\t".join(COLUMNS),
        *("\t".join(str(row[column]) for column in COLUMNS) for row in ranked),
        "",
        "# pilot candidates: production files >=100, a commonMain footprint, not DI assembly, not test support",
        "\t".join(COLUMNS),
        *("\t".join(str(row[column]) for column in COLUMNS) for row in pilot),
    ])

    # ── what lifting a cluster actually costs ────────────────────────────────────────────────────
    # A cluster X can only move if nothing it can reach imports it back: every edge (Y -> X) with Y
    # reachable from X has to go. That is exact, and it is much larger than the shortest cycle
    # through X — one cycle is not the whole bill.
    cluster_edges = {(source, target) for source in cluster_out for target in cluster_out[source]}
    import_lines = Counter()
    for path, info in production.items():
        source_cluster = cluster_of_path[path]
        for fqn in info["imports"]:
            target = resolve(fqn, packages)
            if target:
                target_cluster = package_cluster[target]
                if target_cluster != source_cluster:
                    import_lines[(source_cluster, target_cluster)] += 1

    def reachable_from(nodes):
        seen, stack = set(), list(nodes)
        while stack:
            for nxt in cluster_out[stack.pop()]:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        return seen

    def reach_back(nodes):
        seen, stack = set(), list(nodes)
        while stack:
            for nxt in cluster_in[stack.pop()]:
                if nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        return seen

    # Two valid cuts for the same lift, from opposite sides: as a top layer (nothing it can reach may
    # import it back) and as a bottom layer (nothing it depends on may reach it). The cheaper one is
    # what a refactor would cost; the true minimum is a max-flow, so the min of two is an upper bound.
    def cuts_as_top(group):
        back = reachable_from(group) - set(group)
        return sorted((source, node) for node in group for source in cluster_in[node] if source in back)

    def cuts_as_bottom(group):
        back = reach_back(group) - set(group)
        return sorted((node, target) for node in group for target in cluster_out[node] if target in back)

    def lines_in(cuts):
        return sum(import_lines[cut] for cut in cuts)

    def greedy_back_edges():
        """Eades-Lin-Smyth: an ordering whose back edges break every cycle. An upper bound, not the minimum."""
        remaining = {node: {m for m in cluster_out[node] if m in cluster_nodes} for node in cluster_nodes}
        front, back = [], []
        while remaining:
            indegree = Counter()
            for targets in remaining.values():
                for target in targets & remaining.keys():
                    indegree[target] += 1
            sinks = sorted(node for node in remaining if not (remaining[node] & remaining.keys()))
            if sinks:
                back.append(sinks[0])
                del remaining[sinks[0]]
                continue
            sources = sorted(node for node in remaining if indegree[node] == 0)
            if sources:
                front.append(sources[0])
                del remaining[sources[0]]
                continue
            pick = max(sorted(remaining),
                       key=lambda node: len(remaining[node] & remaining.keys()) - indegree[node])
            front.append(pick)
            del remaining[pick]
        position = {node: index for index, node in enumerate(front + list(reversed(back)))}
        return sorted((source, target) for source, target in cluster_edges
                      if source in position and target in position and position[source] >= position[target])

    in_scc = set(cluster_cycles[0]) if cluster_cycles else set()
    scenarios = [
        ("core/ui", {"core/ui"}),
        ("core/backup", {"core/backup"}),
        ("feature/tasks", {"feature/tasks"}),
        ("test/fakes", {"test/fakes"}),
        ("core/observability", {"core/observability"}),
        ("(root)", {"(root)"}),
        ("every core/* cluster", {node for node in cluster_nodes if node.startswith("core/")}),
    ]
    def without(cut):
        source, target = cut
        return {node: (targets - {target} if node == source else targets)
                for node, targets in cluster_out.items()}

    def largest_scc(adjacency):
        groups = strongly_connected(adjacency, cluster_nodes)
        return set(groups[0]) if groups else set()

    back_edges = greedy_back_edges()
    named_cut = largest_scc(without(("core/search", "feature/notes")))
    di_lifted = largest_scc({node: (targets - {"core/di"}) for node, targets in cluster_out.items()})
    aggregated = largest_scc({node: (targets - {"core/di"} if node == "core/search" else targets)
                              for node, targets in cluster_out.items()})

    cost_rows = []
    for name, group in scenarios:
        group = group & set(cluster_nodes)
        if not group:
            continue
        top, bottom = cuts_as_top(group), cuts_as_bottom(group)
        cheaper = min((top, bottom), key=len)
        cost_rows.append(
            f"{name}\t{len(group)}\t{len(top)}\t{lines_in(top)}\t{len(bottom)}\t{lines_in(bottom)}\t"
            f"{len(cheaper)}\t{lines_in(cheaper)}")

    write(os.path.join(out, "cut-plan.txt"), [
        f"# clusters={len(cluster_nodes)} largest SCC={len(in_scc)}",
        "# A lift is costed from both sides; a refactor takes the cheaper one. The exact minimum is a",
        "# max-flow, so min(top, bottom) is an upper bound on it, not the minimum.",
        "#   top    = cut every edge into the group from a cluster the group can reach",
        "#   bottom = cut every edge out of the group into a cluster that can reach the group",
        "#   imports = import statements on those edges — the unit of refactor work (edges are not)",
        "",
        "# cluster\tclusters\tcuts_top\timports_top\tcuts_bottom\timports_bottom\tmin_cuts\tmin_imports",
        *cost_rows,
        "",
        "# the cheaper cut, edge by edge (imports in brackets)",
        *(f"{name}: " + ", ".join(f"{source} -> {target} ({import_lines[(source, target)]})"
                                  for source, target in cheaper)
          for name, group in scenarios
          for group in [group & set(cluster_nodes)] if group
          for top, bottom in [(cuts_as_top(group), cuts_as_bottom(group))]
          for cheaper in [min((top, bottom), key=len)] if 0 < len(cheaper) <= 30),
        "",
        "# checks on the single cut an earlier draft claimed was enough for core/ui",
        f"core/ui still in a cycle after cutting core/search -> feature/notes: {'yes' if 'core/ui' in named_cut else 'no'}",
        f"largest SCC after every edge into core/di is removed: {len(di_lifted)}",
        f"largest SCC after both of the above: {len(aggregated)}",
        "",
        f"# greedy feedback arc set (Eades-Lin-Smyth, an upper bound): {len(back_edges)} edges",
        *(f"{source} -> {target}" for source, target in back_edges),
    ])

    # ── what share of the history a split could actually shorten ─────────────────────────────────
    liftable = {cluster_of(package) for package in packages} - in_scc
    total_commits = len(commits)
    moved_paths = sum(1 for commit in commits if any(path not in files for path in commit))
    test_only = sum(1 for commit in commits if not any(path in production for path in commit))
    liftable_only = sum(
        1 for commit in commits
        if all(path in files for path in commit)
        and {cluster_of_path[path] for path in commit} <= liftable
        and any(path in production for path in commit))
    fakes_only = sum(1 for commit in commits
                     if all(path in files and cluster_of_path[path] == "test/fakes" for path in commit))
    fakes_any = sum(1 for commit in commits if any(cluster_of_path.get(path) == "test/fakes" for path in commit))

    renames = subprocess.run(
        ["git", "log", f"--since={args.since}", "-M", "--name-status", "--pretty=format:COMMIT %H", "--", SRC],
        capture_output=True, text=True, check=True, cwd=root).stdout
    renamed_into = Counter()
    for line in renames.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0].startswith("R") and parts[1].startswith(SRC) and parts[2].startswith(SRC):
            renamed_into[cluster_of_path.get(parts[2], "(unknown)")] += 1
    known_paths = total_commits - moved_paths
    write(os.path.join(out, "commit-profile.txt"), [
        f"# {total_commits} commits since '{args.since}' touching {SRC}",
        "# Two denominators, because a commit touching a path that no longer exists cannot be classified:",
        f"total\t{total_commits}\tall commits in the window",
        f"known-paths\t{known_paths}\tcommits whose every path still exists — the honest denominator",
        f"moved-path\t{moved_paths}\ttouches at least one path that no longer exists (excluded from shares below)",
        "",
        f"# liftable = clusters outside the largest SCC ({len(liftable)} production clusters)",
        f"tests-only\t{test_only}\t{test_only * 100 / total_commits:.1f}% of all commits — touches no production file",
        f"liftable-only\t{liftable_only}\ttouches only liftable production clusters — the upper bound of a split",
        f"liftable-of-known\t{liftable_only * 100 / max(known_paths, 1):.1f}%\tliftable-only / known-paths",
        f"liftable-of-all\t{liftable_only * 100 / total_commits:.1f}%\tliftable-only / all commits",
        f"fakes-only\t{fakes_only}\ttouches only test/fakes",
        f"fakes-any\t{fakes_any}\ttouches test/fakes at all",
        "",
        "# renames inside the tree: history of the new path starts at the rename, so its cluster's",
        "# commit count is a lower bound",
        f"renames\t{sum(renamed_into.values())}\tfiles renamed within the tree during the window",
        "# renames into\tcluster",
        *(f"{count}\t{cluster}" for cluster, count in
          sorted(renamed_into.items(), key=lambda item: (-item[1], item[0])) if cluster != "(unknown)"),
    ])

    # ── self-checks ──────────────────────────────────────────────────────────────────────────────
    problems = []
    if len(packages) < 100:
        problems.append(f"only {len(packages)} packages parsed")
    if not production:
        problems.append("no production sources found")
    if not hot_files or hot_files[0][1] < 10:
        problems.append("hot-file counts look empty — did git log return anything?")
    for path, _ in hot_files:
        if not path.endswith(".kt") or not os.path.isfile(os.path.join(root, path)):
            problems.append(f"hot path missing or not Kotlin: {path}")
            break
    if sum(out_degree.values()) != len(package_edges):
        problems.append(f"edge total {len(package_edges)} != sum of out-degrees {sum(out_degree.values())}")
    for _, target in package_edges:
        if target not in packages:
            problems.append(f"edge target is not a declared package: {target}")
            break
    for group in cycles:
        for node in group:
            seen, stack = set(), [node]
            while stack:
                for nxt in adjacency[stack.pop()]:
                    if nxt not in seen:
                        seen.add(nxt)
                        stack.append(nxt)
            missing = set(group) - seen - {node}
            if missing:
                problems.append(f"cycle of {len(group)} is not closed: {node} cannot reach {sorted(missing)[:3]}")
                break

    if problems:
        for problem in problems:
            print(f"self-check failed: {problem}", file=sys.stderr)
        return 1

    print(f"commits={len(commits)} files={len(files)} production={len(production)} packages={len(packages)} "
          f"edges={len(package_edges)} cycles={len(cycles)} clusters={len(rows)}")
    print("top clusters by commits x production files:")
    for row in ranked[:10]:
        print(f"  {row['score']:7d}  {row['cluster']:24s} files={row['files']:4d} tests={row['tests']:4d} "
              f"commits={row['commits']:4d} commonMain={row['common']:4d} platform={row['platform']:4d}")
    print("pilot candidates:")
    for row in pilot[:10]:
        print(f"  {row['score']:7d}  {row['cluster']:24s} files={row['files']:4d} commits={row['commits']:4d} "
              f"in={row['in']:3d} out={row['out']:3d}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
