# profile-isolation

## ADDED Requirements

### Requirement: REQ-PROF-001 The activated profile id is reported only when the switch succeeded

`ProfileBootstrapper.run` SHALL NOT return a `ProfileId` in
`ProfileBootstrapResult.activated` unless `ProfileRepository.switchTo` for that id
returned success. On failure it SHALL propagate the exception.

`run` SHALL continue to return `activated = null`, without throwing, in the two cases
that are not failures:

- `activateName` was `null` — no switch was requested;
- `activateName` named a profile that did not exist after seeding — there was never a
  switch to fail.

**Rationale:** the returned id is an input to a destructive migration, not a status
line. `mcp/Main.kt:164` reads it and, on a non-null id, runs
`retromigrateRowsToAgentScope`, which moves every row owned by the unscoped local user
id into a scope keyed on that profile. A switch that failed but still returned an id
therefore relocated the rows into a namespace the server is not running under, and out
of the one it was in — the migration is a move, so neither location has them.

Returning `null` instead of throwing would not fix that (the `activated?.let` would
skip the migration) but would destroy the distinction between "nothing asked for a
switch" and "the switch failed", because a successful `activateName = null` run already
returns `null`. The second of those is worth surfacing; the first is routine.

Propagating is safe because the only production caller already wraps the whole
bootstrap in a try/catch that logs and continues against the default profile
(`mcp/Main.kt:175-179`), so the exception reaches a handler that exists and states why.

#### Scenario: The switch fails

- **Given** a repository whose `switchTo` returns `Result.failure`
- **When** `run` is called with the name of a profile that exists
- **Then** the repository's exception propagates
- **And** no result carrying that profile id is returned
- **And** the repository's active profile is unchanged

#### Scenario: The switch succeeds

- **Given** a repository that accepts the switch
- **When** `run` is called with the name of a profile that exists
- **Then** the result carries that profile id
- **And** the repository agrees on the active profile

#### Scenario: No switch was requested

- **Given** `activateName = null`
- **When** `run` is called
- **Then** `activated` is null and nothing is thrown
- **And** the distinction from a failed switch is preserved by the throw, not by the value

#### Scenario: The name was not found

- **Given** a profile name that does not exist after seeding
- **When** `run` is called with it
- **Then** `activated` is null and nothing is thrown
- **And** no repository write was attempted, because there was no profile to switch to