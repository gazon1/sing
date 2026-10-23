# The tool surface

The repository is checked by more than forty gate scripts, and the surface
around them — the registry, the controls, the sabotage harness — is larger
than the gates themselves. This page says what the parts are and how to work
with them. The full list of gate scripts is generated:
[`catalog.md`](catalog.md).

## The three commands

```bash
sgate run-all                 # every gate, in parallel, with the verdicts
bash scripts/ci/static-gates.sh   # the same registry, run as a shell script
python3 scripts/check-gate-wiring.py --dry-run   # what the sabotage phase would touch
```

`sgate run-all` reads `scripts/ci/static-gates.sh` and dispatches each gate to
its own command. The shell file is **generated** — the declarations live in
`scripts/src/sgate/gate_manifest.py`, and the two commands that render it are:

```bash
python3 -m sgate render-static-gates --write    # the shell registry
python3 -m sgate render-registry                # docs/scripts/gate-registry.md — the gates
python3 scripts/check-tools-catalog.py --write  # docs/tools/catalog.md — the suite around them
```

## Whose code runs

On a machine with several checkouts of this repository, the `sgate` on PATH is
an editable install of *one* of them. The gate suite therefore runs **this
checkout's module**, not the binary on PATH (`sgate.commands.run_all`), and
the preflight says so when the installed one belongs elsewhere:

```bash
bash scripts/ci/setup-local-tools.sh     # uv venv + editable install of scripts/src
export PATH="$HOME/.venvs/singularity-todo/bin:$PATH"
sgate whereami                            # the checkout the sgate belongs to
```

A manual `PYTHONPATH` is not the way: the gate scripts' own unit tests run
them with a minimal environment, and a package that is only on `PYTHONPATH` is
invisible to them.

## The pieces of a gate

A gate is five things, and they used to be five files and four names:

| Piece | Where | What it says |
|---|---|---|
| the script | `scripts/check-<name>.py` | what the gate checks |
| the spec | `_registry.py` | how it is invoked, and the change that must make it fail |
| the control | `gate_controls.py` | which file the harness sabotages, and why that change |
| the placement | `gate_manifest.py` | where it runs, and the prose above its line |
| the test | `scripts/tests/test_check_<name>.py` | that the gate is a gate |

`python3 scripts/ci/new-gate.py --display-name "…" --script scripts/check-….py`
writes all of them, derives the names, and checks that the registry still
imports.

## The document-against-the-code family

Several gates compare a table in a document with the code it names. They share
a library and a rule format:

* `sgate.gatekit.markdown` — the tables a document claims things in;
* `sgate.gatekit.kotlin` — what the sources declare and mention;
* `sgate.gatekit.findings` — a finding, and how sure the gate is;
* `sgate.gatekit.cli` — the flags and the exit code every gate has;
* `sgate.gatekit.rules` — the comparison of a deep-link table with the code;
* `sgate.gatekit.tables` — the table a document claims things in: where it
  starts, what its rows name, the verdict vocabulary, and the comparison in
  both directions;
* `sgate.gatekit.paths` — where a gate is allowed to write.

The **rule** is data: `config/gates/navigation-doc-sync.yaml` says which
table of which document, which columns, and the naming convention that ties a
row to the code; `config/gates/platform-reference-ports.yaml` and
`config/gates/text-field-transformations.yaml` say the shape of the tables of
the ports reference and of the text-field skill. A second table that follows
the same convention needs a YAML file and nothing else.

What stays in the gate is what is the gate's own business: how the code is
read. The ports gate knows that a port is an interface the platform module
binds; the transformation gate knows that a transformation is a function that
returns one.

A finding is either **blocking** (a fact the code contradicts) or
**advisory** (a claim read out of prose). Only a blocking finding fails a
`--check` run — a gate that blocks on a guess about a sentence blocks on the
wrong thing.

## The sabotage harness

`scripts/check-gate-wiring.py` proves that a gate *can fail*: it changes one
input and requires the gate to notice. The phase edits files in the working
tree and puts them back — the restore is atomic, the original is kept until
the restore succeeded, and an interrupted run is repaired by the next one.

```bash
python3 scripts/check-gate-wiring.py --dry-run   # what it would touch
python3 scripts/check-gate-wiring.py --can-fail  # the mutating phase
```

Do not edit the tree while the phase runs.

## The reports

```bash
python3 scripts/detekt-summary.py    # detekt findings by rule and by place
python3 scripts/check-tools-catalog.py --check   # is the catalogue current?
```
