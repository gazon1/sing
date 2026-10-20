# Tasks — version-gate-read-timeout

- [x] Give the remote-config read a deadline (`readTimeoutMs`, 5 s by default), placed so
      that `scope` stays last in the constructor.
- [x] Route a missed deadline through the same funnel as a thrown read: reported as
      `AppError.Network(code = "gate.remote_config_read_timeout")`, breadcrumbed, admitted
      on the defaults.
- [x] Name the reason in the breadcrumb: a read that *threw* and a read that *timed out*
      are two different investigations, and the wording of the thrown case stays
      byte-identical to before.
- [x] Give the `Check again` path the same deadline as the first check.
- [x] Cover it with tests: admitted on the defaults after the deadline, the injected
      deadline is the one honoured, the timeout is reported under its own key, the
      breadcrumb is left and names the reason, the breadcrumb precedes the report, a
      thrown read is still recorded as an unreadable config, and a hanging refresh is
      admitted on the defaults.
- [x] Verify the tests fail without the deadline: with it removed, seven of the new
      cases fail (the gate stays on `Checking`, no report, no breadcrumb).
- [x] Record the decision: ADR `2026-10-20-version-gate-read-timeout.md`.
- [x] State the capability: `specs/version-gate/spec.md`.
