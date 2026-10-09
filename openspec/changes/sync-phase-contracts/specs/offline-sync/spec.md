# offline-sync — amendment

## ADDED Requirements

### Requirement: REQ-OS-026a — Superseded push patch

When the server confirms a patch as applied (`ok: true`) but the local outbox no longer
holds a row for that patch — because a later local edit coalesced it away before the
response arrived — the device SHALL report the outcome as `superseded`, SHALL NOT delete
the outbox row (it is already gone), and SHALL NOT release the shadow marker (it is
already settled).

The `superseded` count is distinct from both `succeeded` and `failed`. Counting it as
`succeeded` would claim delivery for work the server accepted but this device no longer
holds. Counting it as `failed` would send an operator looking for a rejection that never
happened. The count answers "did this device lose any work today", which is a question
about user data rather than about the transport.

#### Scenario: A coalesced patch is confirmed by the server

- A device has a pending patch `P` for entity `E`
- A second local edit to `E` arrives before the push response
- The second edit builds patch `P2` and deletes `P` from the outbox
- The server confirms `P` as applied (`ok: true`)
- The push summary increments `superseded`
- The outbox row for `P` is not touched (it is already absent)
- The shadow for `E` is not touched (the coalescing delete already settled it)

#### Scenario: A superseded patch is not counted as failed

- A push response contains results for patches `P1`, `P2`
- `P2`'s outbox row was deleted before the response arrived
- The push summary has `succeeded = 1`, `failed = 0`, `superseded = 1`
- The summary distinguishes the two patches from a failed patch
