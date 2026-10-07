# Tasks — a-cycle-drains-the-feed-or-says-it-could-not

Issue: #176. Spec: `offline-sync` REQ-OS-015, REQ-OS-016.

- [x] `shared/` Read pages until a short page, rather than one request per cycle.
      **Test:** a 250-change backlog is applied in one cycle and the stored position is
      the end of the last page.
- [x] `shared/` Keep the read position and the stored position apart: the next request
      resumes after the last change read, the database keeps the last change dealt with.
      **Test:** a cycle that stops early stores the earlier one and the rest is re-read.
- [x] `shared/` Stop when a page contains nothing past the position asked from, so a
      server that ignores the cursor cannot loop forever. **Test:** the fake is given a
      feed that ignores its position; the cycle ends and still reports what it read.
- [x] `shared/` Say out loud that a full page is not proof either way, instead of
      treating it as the end or inventing a certainty.
- [x] `shared/` Do not ask twice for a short page, so an idle account does not pay for
      a second round trip forever. **Test:** a ten-change backlog asks exactly once.
- [x] `shared/test` Let the transport fake ignore the position it is given, so the loop
      is shown to terminate against a feed that misbehaves.
- [x] `shared/` Report the total received across every page, not the last page's size.

## Verification

- [x] `shared/` `./gw :shared:jvmTest`.
- [x] `shared/` `./gw detekt`.
- [x] `openspec validate --all --strict`.
- [x] Stopping after the first page regardless of its size fails the backlog test.
