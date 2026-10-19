# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-19 A cross-job transport names a value that resolves

A workflow that carries state to another job — a cache path, a cache key, a
directory, a name — SHALL name that state in a way that resolves on the runner.
CI SHALL fail when a workflow references a variable that resolves to nothing.

In particular, a workflow SHALL NOT rely on the `env` context to reach the
runner's own environment: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `RUNNER_TEMP` and
`HOME` are process variables, not context entries, and `${{ env.NAME }}` for
one of them expands to the empty string — silently, with no warning and no
error. A value published by one job and consumed by another SHALL travel
through that job's outputs, so the producer is the single place that decides
what the name is.

**Rationale:** measured at run `39253664636`, all four Maestro shards failed
within a second of each other with `'emulator' binary not found in PATH`, on
runners where the producer job had executed that exact binary ninety seconds
earlier. The cache paths were written `${{ env.ANDROID_HOME }}/…`, so the list
expanded to `/emulator` and the rest at the filesystem root; the cache carried
nothing, and the entry it left behind (1659 bytes) was a *hit* on every later
run — and a cache hit means "do not save", so the empty entry was permanent.
The failure named the emulator, because that is where the missing path ended
up.

#### Scenario: A workflow references a name the file does not define

- **Given** a workflow whose cache path is written `${{ env.ANDROID_HOME }}/emulator`
- **And** no `env:` block in that workflow defines `ANDROID_HOME`
- **When** the env-context gate runs
- **Then** it reports the reference and fails the build
- **And** it names the file and the variable

#### Scenario: A producer and a consumer agree on one key

- **Given** a producer job that publishes a cache key as an output
- **And** a consumer job that restores that key
- **When** the transport contract is checked
- **Then** the consumer restores the producer's output verbatim
- **And** neither side re-spells the key

### Requirement: REQ-20 A missing optimisation is not a failed build

A job that needs state another job produced SHALL be able to produce that state
itself. A cache, a warm directory, a restored artefact — these are
optimisations: they make the common case fast. CI SHALL NOT fail a build
because an optimisation delivered nothing, where the job could have produced
the state it needed.

Where a job can produce the state, the definition of "produced" SHALL live in
one place, shared by the producer and the consumers, so the two sides cannot
disagree about it.

**Rationale:** the previous ADR for this transport named the gap itself — "a
cache miss on the consumer is fatal, and there is no fallback" — and a cache
entry can be evicted, a system image bumped, or a cached layout changed. A
dependency on an optimisation is a dependency on an eviction policy that
belongs to somebody else, and its failure mode is a red build that looks like a
flaky test: the most expensive kind of failure to investigate.

#### Scenario: The cache delivers the state

- **Given** a job whose cache restore produced the emulator, the system image and the AVD
- **When** the provisioning step runs
- **Then** it changes nothing and the job proceeds

#### Scenario: The cache delivers nothing

- **Given** a job whose cache restore produced an empty directory
- **When** the provisioning step runs
- **Then** it installs the missing state and creates the AVD
- **And** the build continues instead of failing on the missing cache
