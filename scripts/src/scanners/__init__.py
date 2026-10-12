"""scanners — repository scanners shared by the gate scripts.

Only one member today: `test_classes`, the runnable-test predicate. It lives
under `scripts/src/` because it is installed alongside `sgate` and
`traceability` rather than being a standalone script that has to bootstrap its
own `sys.path`.
"""