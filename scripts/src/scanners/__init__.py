"""scanners — repository scanners shared by the gate scripts.

Three members today, each answering one question about a file the gates read:

  * `source_text`   — read Kotlin source without a parser: comments, literals,
                      brace depth, class bodies. One character walk, one answer.
  * `test_classes`  — the runnable-test predicate.
  * `maestro_flow`  — the `tags:` block of a Maestro flow.

They live under `scripts/src/` because they are installed alongside `sgate` and
`traceability` rather than being standalone scripts that have to bootstrap their
own `sys.path`.

A question that more than one module answers belongs here. A gate that keeps
its own copy of a reader is a second answer waiting to drift — and the drift
is silent: both copies are plausible, and only one of them is right.
"""
