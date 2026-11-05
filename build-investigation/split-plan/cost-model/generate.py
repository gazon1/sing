#!/usr/bin/env python3
"""Generate the synthetic module for the cost model: N files of realistic size, plus one body-only edit.

Sources go under `module/build/generated/kotlin`, which `**/build/` keeps out of `git status`. The size
of a file matters as much as the count — a stand of 5-line stubs would measure the compiler's per-file
overhead and call it a per-file cost — so the generator reproduces the tree's average, computed from
`shared/src` at run time rather than hardcoded here.
"""

import argparse
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODULES = {"a": ("module", "stand"), "b": ("module-b", "standb")}
EDITED = os.path.join(HERE, "module", "build", "generated", ".edit")


def out_dir(key):
    directory, package = MODULES[key]
    return os.path.join(HERE, directory, "build", "generated", "kotlin", package)


def average_file_lines(repo_root):
    total_files = total_lines = 0
    for source_set in ("commonMain", "jvmMain"):
        base = os.path.join(repo_root, "shared", "src", source_set)
        for dirpath, _, names in os.walk(base):
            for name in names:
                if name.endswith(".kt"):
                    total_files += 1
                    with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as handle:
                        total_lines += sum(1 for _ in handle)
    return max(60, total_lines // max(total_files, 1))


def file_text(index, target_lines, compose, module="a"):
    """One class of ordinary Kotlin, about `target_lines` long, deterministic for a given index."""
    lines = ["package standb", "import stand.Holder0", ""] if module == "b" else ["package stand", ""]
    if compose and module == "a":
        lines.append("import androidx.compose.runtime.Composable")
        lines.append("")
    lines.append(f"class Holder{index}(private val seed: Int) {{")
    lines.append("")
    methods = max(4, (target_lines - 12) // 9)
    for method in range(methods):
        lines += [
            f"    fun value{method}(input: List<Int>): Int {{",
            "        var total = seed",
            "        for (item in input) {",
            f"            total += item * {method + 1}",
            "            if (total % 7 == 0) {",
            "                total -= 1",
            "            }",
            "        }",
            "        return total",
            "    }",
            "",
        ]
    if module == "b":
        lines.append(f"    fun usesModuleA(): String = Holder0(seed).describe() + \"-b{index}\"")
    else:
        lines.append(f"    fun describe(): String = \"holder-{index}\"")
    lines.append("}")
    lines.append("")
    if compose and module == "a":
        lines += [
            "@Composable",
            f"fun label{index}(seed: Int): String = \"label{index}-\" + seed.toString()",
            "",
        ]
    return "\n".join(lines) + "\n"


def generate(size, target_lines, compose, module="a"):
    OUT = out_dir(module)
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    written = 0
    for index in range(size):
        path = os.path.join(OUT, f"Holder{index}.kt")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(file_text(index, target_lines, compose, module))
        written += 1
    actual = sorted(name for name in os.listdir(OUT) if name.endswith(".kt"))
    if len(actual) != size or written != size:
        raise SystemExit(f"self-check failed: wrote {written}, found {len(actual)}, asked for {size}")
    return actual


def edit(apply_it):
    """Swap one expression inside a function body, in every generated module.

    No signature, visibility or call site changes — the same body-only edit the :shared cycles use.
    """
    for key in MODULES:
        if os.path.isdir(out_dir(key)):
            edit_one(apply_it, out_dir(key))


def edit_one(apply_it, out):
    target = os.path.join(out, "Holder0.kt")
    key = os.path.basename(out)
    original = os.path.join(EDITED, f"{key}.original.txt")
    edited = os.path.join(EDITED, f"{key}.edited.txt")
    if apply_it:
        before = open(target, encoding="utf-8").read()
        after = before.replace("            total -= 1", "            total -= 2", 1)
        if after == before:
            raise SystemExit("self-check failed: the body-only edit matched nothing")
        os.makedirs(EDITED, exist_ok=True)
        with open(original, "w", encoding="utf-8") as handle:
            handle.write(before)
        with open(edited, "w", encoding="utf-8") as handle:
            handle.write(after)
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(after)
    else:
        with open(original, encoding="utf-8") as handle:
            before = handle.read()
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(before)
        with open(target, encoding="utf-8") as handle:
            if handle.read() != before:
                raise SystemExit("self-check failed: revert did not restore the file")


def signature(apply_it):
    """An ABI-only edit: a new public function in module a. Callers keep compiling, the ABI changes."""
    out = out_dir("a")
    target = os.path.join(out, "Holder0.kt")
    original = os.path.join(EDITED, "a.signature.original.txt")
    os.makedirs(EDITED, exist_ok=True)
    if apply_it:
        text = open(target, encoding="utf-8").read()
        with open(original, "w", encoding="utf-8") as handle:
            handle.write(text)
        edited = text.replace("    fun describe(): String",
                              "    fun extraValue(): Int = seed\n\n    fun describe(): String", 1)
        if edited == text:
            raise SystemExit("self-check failed: the signature edit matched nothing")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(edited)
    else:
        with open(target, "w", encoding="utf-8") as handle:
            handle.write(open(original, encoding="utf-8").read())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int)
    parser.add_argument("--edit", action="store_true")
    parser.add_argument("--revert", action="store_true")
    parser.add_argument("--signature", action="store_true")
    parser.add_argument("--signature-revert", action="store_true")
    parser.add_argument("--compose", action="store_true")
    parser.add_argument("--module", choices=["a", "b"], default="a")
    parser.add_argument("--repo-root", default=os.path.abspath(os.path.join(HERE, "..", "..", "..")))
    args = parser.parse_args()

    if args.edit:
        edit(True)
        return 0
    if args.revert:
        edit(False)
        return 0
    if args.signature:
        signature(True)
        return 0
    if args.signature_revert:
        signature(False)
        return 0
    if args.size is None:
        parser.error("--size is required unless --edit/--revert")
    target = average_file_lines(args.repo_root)
    names = generate(args.size, target, args.compose, args.module)
    print(f"generated {len(names)} files of ~{target} lines under {out_dir(args.module)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
