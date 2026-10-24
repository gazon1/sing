# ADR Review Manifest — the agent's surface says what it does

The sequence numbers below are the ordinal of the ADR inside this change; the
corpus in `docs/decisions/` is not numbered.

## ADRs Created

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| 1 | the-agent-surface-says-what-it-does | The agent's surface says what it does | accepted | The classification is published in the export and checked against the tools; the lifecycle is guarded and a close stays fast; the protocol's output is a value that owns the stream, and the logger is on standard error |

## ADRs Reviewed (No Action)

| Sequence | Slug | Title | Status | Notes |
|----------|------|-------|--------|-------|
| — | acp-client-and-agent-activity | External agent in the app (ACP client) and agent activity visibility | accepted | The decision behind the external agent; this change finishes the five items that decision's change left behind, and amends it in part — `create_tag` was one of its three direct-write exceptions, and it is a proposal-gated tool now |

## Decisions That Did Not Meet the Bar

- **A new version of the surface contract.** The export grew a field, but what an
  agent reads over the wire did not change: the classification was already in the
  catalogue, and it was the *publication* of it that was missing. The export's own
  version goes up; the contract's does not.
- **A gate over the ADR tools' writes.** `write_adr` and `update_adr` write through
  a storage port, and the scan cannot place that write on the user's own data — it is
  a decision file, not the user's tasks. So the two-sided check names them as the
  exceptions it cannot verify rather than failing them. A gate that cannot see the
  write would be a gate that lies.
- **A lock that makes the close wait.** Mutual exclusion of the four lifecycle
  fields and a fast disconnect are separate questions. A lock alone would make a
  close that lands during a handshake wait out the handshake — a minute, for an
  agent that is downloading its package. The close says so first and ends the
  handshake, and the lock keeps the fields whole.
