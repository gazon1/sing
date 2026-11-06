#!/usr/bin/env python3
"""Replay the six months of `shared/src` history against a cost model and hypothetical module splits.

Answers one question: if the module were split this way, what would a typical commit have cost — and is
that at least 30 % better than the single module it is today? Three inputs:

  1. the measured cost curves, results/cost-model/SUMMARY.tsv, fitted to T = a + b·N per plugin mix;
  2. the commit history, with rename chains resolved so that a path that moved still maps to a cluster;
  3. a module assignment per cluster (S0 single module, S1 a realistic post-refactor split, S2 every
     cluster its own module).

ABI edits cascade — a signature change rebuilds every dependent module — so each commit is classified
from its diff. The regex cannot tell a local `val` from a property, so every scenario is reported under
three brackets: all body-only (optimistic), all ABI (pessimistic), and the classification itself.

Numbers are printed and written to results/cost-model/replay-data.tsv; the prose lives in replay.md.
"""

import argparse
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from analyse import SRC, cluster_of, resolve, scan_sources  # noqa: E402  (one parser, not two)

BASELINE_MEDIAN = 60.055  # measured, results/SUMMARY.tsv, p0-in-process-2..6
JVM_SOURCE_SETS = ("commonMain", "jvmMain")
MODIFIERS = (r"(?:(?:open|abstract|sealed|data|inline|suspend|operator|infix|tailrec|const|override|"
              r"expect|actual|lateinit|external|annotation|value|internal)\s+)*")
# A declaration a dependent module can see. Indentation is the only depth signal available from a
# diff without parsing: members sit at <=4 spaces, locals deeper. `private` is excluded because it
# cannot appear in a dependent's compile classpath — the spot check in replay.md shows both rules.
TYPE_DECL = re.compile(r"^[+-]( {0,4})" + MODIFIERS + r"(?:fun|class|interface|object|typealias)\b")
PROP_DECL = re.compile(r"^[+-]" + MODIFIERS + r"(?:val|var)\b")
PRIVATE_DECL = re.compile(r"^[+-]\s*private\b")


ROOT = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True,
                      check=True, cwd=HERE).stdout.strip()


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True, cwd=ROOT).stdout


# ── cost curves ─────────────────────────────────────────────────────────────────────────────────────

def read_cost_results(path):
    """{mix: {size: [task_s, ...]}} from the cost-model SUMMARY.tsv, warm-up (cycle 0) excluded."""
    samples = defaultdict(lambda: defaultdict(list))
    with open(path, encoding="utf-8") as handle:
        next(handle, None)
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 6:
                continue
            run, _strategy, _commit, task_s, _wall, exit_code = parts[:6]
            if exit_code != "0" or not task_s.replace(".", "", 1).isdigit():
                continue
            mix, size, cycle = run.rsplit("-", 2)
            if cycle == "0":  # warm-up
                continue
            samples[mix][int(size)].append(float(task_s))
    return samples


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def fit(points):
    """Least squares for T = a + b·N. Returns (a, b)."""
    count = len(points)
    sx = sum(x for x, _ in points)
    sy = sum(y for _, y in points)
    sxx = sum(x * x for x, _ in points)
    sxy = sum(x * y for x, y in points)
    denominator = count * sxx - sx * sx
    if denominator == 0:
        raise SystemExit("cannot fit: fewer than two distinct sizes")
    b = (count * sxy - sx * sy) / denominator
    return (sy - b * sx) / count, b


def curves(samples):
    """{mix: (a, b, r2, residuals)} plus leave-one-out residuals for the largest size."""
    out = {}
    for mix, by_size in samples.items():
        points = [(size, median(values)) for size, values in sorted(by_size.items())]
        if len(points) < 2:
            continue
        a, b = fit(points)
        mean = sum(y for _, y in points) / len(points)
        ss_tot = sum((y - mean) ** 2 for _, y in points)
        ss_res = sum((y - (a + b * x)) ** 2 for x, y in points)
        r2 = 1 - ss_res / ss_tot if ss_tot else 1.0
        residuals = [(x, y, a + b * x, (a + b * x - y) / y * 100) for x, y in points]
        loo = []
        for x, y in points:
            rest = [(px, py) for px, py in points if px != x]
            if len(rest) < 2:
                loo.append((x, None, None))
                continue
            la, lb = fit(rest)
            loo.append((x, y, (la + lb * x - y) / y * 100))
        out[mix] = {"a": a, "b": b, "r2": r2, "residuals": residuals, "loo": loo,
                    "points": points, "max": max(points)[0]}
    return out


# ── history ────────────────────────────────────────────────────────────────────────────────────────

def commits_since(since):
    raw = sh("git", "log", f"--since={since}", "--pretty=format:COMMIT %H", "--name-only", "--", SRC)
    commits, current = [], None
    for line in raw.splitlines():
        if line.startswith("COMMIT "):
            current = (line.split()[1], [])
            commits.append(current)
        elif line.strip() and current is not None and line.strip().endswith(".kt"):
            current[1].append(line.strip())
    return [entry for entry in commits if entry[1]]


def rename_map(since):
    """old path -> new path, resolved to a fixpoint so a->b->c becomes a->c."""
    raw = sh("git", "log", f"--since={since}", "-M", "--name-status", "--pretty=format:COMMIT", "--", SRC)
    direct = {}
    for line in raw.splitlines():
        parts = line.split("\t")
        if len(parts) == 3 and parts[0].startswith("R") and parts[1].startswith(SRC) and parts[2].startswith(SRC):
            direct[parts[1]] = parts[2]
    changed = True
    while changed:
        changed = False
        for old, new in list(direct.items()):
            if new in direct and direct[old] != direct[new]:
                direct[old] = direct[new]
                changed = True
    return direct


def abi_commits(commits, jvm_files):
    """Commits whose diff touches a declaration a dependent module could see.

    Test sources are not in a dependent's classpath and are skipped by path; `private` declarations
    are skipped by line. Bounded by the brackets in the report, not trusted on its own.
    """
    flagged = set()
    for sha, paths in commits:
        interesting = [path for path in paths if path in jvm_files]
        if not interesting:
            continue
        patch = sh("git", "show", "--format=", "--unified=0", "-M", sha, "--", *interesting)
        for line in patch.splitlines():
            if PRIVATE_DECL.match(line):
                continue
            if TYPE_DECL.match(line) or PROP_DECL.match(line):
                flagged.add(sha)
                break
    return flagged


# ── scenarios ─────────────────────────────────────────────────────────────────────────────────────

def module_of(cluster, scenario):
    if scenario == "S0":
        return "all"
    if scenario == "S2":
        return cluster
    # S1: the realistic post-refactor state — one module per feature, core grouped into layers.
    if cluster.startswith("feature/"):
        return cluster
    groups = {
        "ui": {"core/ui"}, "platform": {"core/platform", "core/files", "core/mcp", "core/process"},
        "text": {"core/text", "core/format", "core/links", "core/ids", "core/version"},
        "data": {"core/database", "core/sync", "core/backup", "core/tree", "core/attachments"},
        "kernel": {"core/coroutines", "core/error", "core/observability", "core/log", "core/security"},
        "settings": {"core/settings", "core/auth", "core/config", "core/appearance", "core/draft"},
        "di": {"core/di"},
    }
    for name, members in groups.items():
        if cluster in members:
            return f"core/{name}"
    if cluster == "test/fakes":
        return "test/support"
    if cluster in {"(root)", "preview", "shell", "mcp", "arch"}:
        return "app/shell"
    return "core/misc"


def p80(values):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * 0.8))]


def makespan(durations, workers=2):
    """LPT: assign each module to the currently least loaded worker."""
    loads = [0.0] * workers
    for duration in sorted(durations, reverse=True):
        loads[loads.index(min(loads))] += duration
    return max(loads)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", default="2026-05-05")
    parser.add_argument("--results", default=os.path.join(HERE, "results"))
    parser.add_argument("--skip-abi", action="store_true", help="reuse the classification if it is slow")
    args = parser.parse_args()

    samples = read_cost_results(os.path.join(args.results, "cost-model", "SUMMARY.tsv"))
    fitted = curves(samples)
    if "compose-koin" not in fitted or "bare" not in fitted:
        raise SystemExit("need both a bare and a compose-koin curve — run run-cost-model.sh first")

    print("# curves: " + ", ".join(
        f"{m}: a={v['a']:.2f} b={v['b']:.5f} R2={v['r2']:.3f}" for m, v in fitted.items()))

    # Gate B is a shape check against a *measured* point: fit the smaller sizes, predict the largest,
    # compare with what it actually took. Fitting all points and predicting 1238 would be in-sample and
    # would say nothing.
    control = fitted["compose-koin"]
    top = control["max"]
    smaller = [(size, value) for size, value in control["points"] if size != top]
    if len(smaller) < 2:
        raise SystemExit("Gate B needs at least three measured sizes on the compose-koin curve")
    lo_a, lo_b = fit(smaller)
    measured_top = dict(control["points"])[top]
    predicted_top = lo_a + lo_b * top
    loo = (predicted_top - measured_top) / measured_top * 100
    print(f"# Gate B: fit on N={[x for x, _ in smaller]}, predict N={top} "
          f"(x{top / max(x for x, _ in smaller):.1f} beyond the fit) -> {predicted_top:.1f}s "
          f"vs measured {measured_top:.1f}s ({loo:+.1f}%)")
    if abs(loo) > 20:
        raise SystemExit(f"Gate B failed: {loo:+.1f}% (> ±20%)")

    # Parity, reported not gated: can this stand stand in for :shared at all?
    predicted_shared = control["a"] + control["b"] * 1238
    parity = (predicted_shared - BASELINE_MEDIAN) / BASELINE_MEDIAN * 100
    print(f"# parity: compose-koin curve at N=1238 -> {predicted_shared:.1f}s vs :shared "
          f"{BASELINE_MEDIAN:.3f}s ({parity:+.1f}%)")

    files = scan_sources(ROOT)
    cluster_of_path = {path: cluster_of(info["package"] or "(no package)") for path, info in files.items()}
    jvm_files = {path for path, info in files.items() if info["source_set"] in JVM_SOURCE_SETS}
    renames = rename_map(args.since)
    commits = commits_since(args.since)

    def resolve_path(path):
        seen = set()
        while path not in files and path in renames and path not in seen:
            seen.add(path)
            path = renames[path]
        return path if path in files else None

    resolved, unresolved = [], 0
    for sha, paths in commits:
        mapped = [resolve_path(path) for path in paths]
        if any(path is None for path in mapped):
            unresolved += 1
            continue
        touched = {cluster_of_path[path] for path in mapped}
        if not touched & {cluster_of_path[path] for path in jvm_files}:
            continue  # costs nothing in compileKotlinJvm: tests, androidMain only
        resolved.append((sha, touched))

    print(f"# history: {len(commits)} commits, {len(resolved)} usable, {unresolved} with an unresolvable path")

    flagged = set() if args.skip_abi else abi_commits([(sha, [p for p in paths if p in files]) for sha, paths in commits], jvm_files)
    resolved_shas = {sha for sha, _ in resolved}
    abi_share = len(flagged & resolved_shas) / max(len(resolved), 1) * 100
    print(f"# ABI-classified: {len(flagged & resolved_shas)} of {len(resolved)} usable commits ({abi_share:.0f}%) touch a declaration line")


    def scenario_rows(scale):
        out = []
        for scenario in ("S0", "S1", "S2"):
            modules = {cluster: module_of(cluster, scenario) for cluster in set(cluster_of_path.values())}
            sizes = Counter(modules[cluster_of_path[path]] for path in jvm_files)
            mix_of = {}
            for module in sizes:
                clusters = {cluster_of_path[path] for path in jvm_files
                            if modules[cluster_of_path[path]] == module}
                ui = any(c.startswith("feature/") or c == "core/ui" for c in clusters)
                mix_of[module] = "compose-koin" if ui else "bare"

            # The module graph covers JVM-compiled code only: a module with no files in the compile
            # iteration is not a dependency of anything here.
            packages = {files[path]["package"] for path in jvm_files if files[path]["package"]}
            package_module = {}
            for path in jvm_files:
                if files[path]["package"]:
                    package_module.setdefault(files[path]["package"], modules[cluster_of_path[path]])
            depends = defaultdict(set)
            for path in jvm_files:
                source_module = modules[cluster_of_path[path]]
                for fqn in files[path]["imports"]:
                    target = resolve(fqn, packages)
                    target_module = package_module.get(target) if target else None
                    if target_module and target_module != source_module:
                        depends[source_module].add(target_module)

            # An ABI change in a module rebuilds whatever depends on it, so the cascade follows the
            # reversed edges — `depends` would add the module's own dependencies, the smaller set.
            dependents = defaultdict(set)
            for source_module, targets in depends.items():
                for target_module in targets:
                    dependents[target_module].add(source_module)

            def duration(module):
                curve = fitted[mix_of[module]]
                return (curve["a"] + curve["b"] * sizes[module]) * scale

            for bucket, abi_set in (("body-only", set()), ("all-abi", {c for c, _ in resolved}),
                                    ("classified", flagged)):
                serial, parallel = [], []
                for sha, touched in resolved:
                    # A cluster with no JVM-compiled file (tests, androidMain-only) is in no module that
                    # compileKotlinJvm builds, so it costs nothing here — counted, not billed as free.
                    touched_modules = {modules[c] for c in touched if sizes.get(modules[c], 0) > 0}
                    if sha in abi_set:
                        touched_modules |= set().union(*(dependents[m] for m in touched_modules))
                    durations = [duration(m) for m in touched_modules]
                    serial.append(sum(durations))
                    # Within one commit the modules compile in parallel over workers.max=2 — the
                    # optimistic aggregation. Across commits they are not summed: one edit, one build.
                    parallel.append(makespan(durations))
                out.append((scenario, bucket, median(serial), p80(serial), sum(serial) / len(serial),
                            median(parallel), p80(parallel), len(sizes)))
        return out

    def report(rows, label):
        base_p80 = next(r[3] for r in rows if r[0] == "S0")
        base_mean = next(r[4] for r in rows if r[0] == "S0")
        print(f"\n# {label}")
        print("scenario\tbracket\tserial_med\tserial_p80\tserial_mean\tpar_med\tpar_p80\t"
              "vs_S0_p80\tvs_S0_mean\tmodules")
        for scenario, bucket, med, p80s, mean, pmed, pp80, count in rows:
            print(f"{scenario}\t{bucket}\t{med:.1f}\t{p80s:.1f}\t{mean:.1f}\t{pmed:.1f}\t{pp80:.1f}\t"
                  f"{(p80s - base_p80) / base_p80 * 100:+.0f}%\t"
                  f"{(mean - base_mean) / base_mean * 100:+.0f}%\t{count}")

    report(scenario_rows(1.0), "raw model (parity is off, see above — ratios are the comparable part)")
    calibrated = BASELINE_MEDIAN / predicted_shared
    report(scenario_rows(calibrated), f"model calibrated to the measured baseline (x{calibrated:.1f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
