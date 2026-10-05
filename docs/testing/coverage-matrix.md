<!-- GENERATED — do not edit by hand. -->
<!-- Source: infra/kiwi/scenarios/** + @DisplayName / scenario: tags in code. -->
<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->

# Coverage matrix

Derived from `infra/kiwi/scenarios/**` and the `@DisplayName` / `scenario:`
tags in code. It changes only when code or specs change.

| Glyph | Meaning |
|---|---|
| — | target not claimed for this scenario |
| ○ | claimed, but no automated test exists — **a hole** |
| ● | automated |
| ⊘ | scenario is deprecated — retired deliberately, not an obligation |

**19 scenarios · 2/34 claimed cells automated · 32 holes**

## feature.auth

| Scenario | Title | android | desktop |
|---|---|---|---|
| `AUTH-ATTACH-01` | Turn an account-less session into a real account | ○ | ○ |
| `AUTH-FIRSTRUN-01` | Use the app on a first launch, with no account | ○ | ○ |
| `AUTH-SEED-01` | Sign in on a device that already holds local data | ○ | ○ |
| `AUTH-SESSION-01` | Carry on working after the session stops being valid | ○ | ○ |
| `AUTH-SIGNIN-01` | Sign in to an existing account | ○ | ○ |
| `AUTH-SIGNUP-01` | Create an account | ○ | ○ |
| `AUTH-SIGNUP-02` | A sign-up whose address still needs confirmation is not a completed sign-up | ○ | ○ |

## feature.calendar

| Scenario | Title | android | desktop |
|---|---|---|---|
| `CAL-FILT-01` | Filter the calendar by project, tag, priority or status | ⊘ | ⊘ |

## feature.sync

| Scenario | Title | android | desktop |
|---|---|---|---|
| `SYNC-FAILED-01` | Deal with a change that could not be sent | ○ | ○ |
| `SYNC-INCOMING-01` | Receive a change on the field being typed into | ○ | ○ |
| `SYNC-OFFLINE-01` | Work with no network and watch the queue drain | ○ | ○ |
| `SYNC-PROFILES-01` | Have a profile follow the account to another device | ○ | ○ |
| `SYNC-PROTO-01` | Meet a server the app is too old for | ○ | ○ |
| `SYNC-SETTINGS-01` | Change how often sync runs | ○ | ○ |
| `SYNC-SIGNOUT-01` | Sign out and sign back in as somebody else | ○ | ○ |
| `SYNC-STATUS-01` | See what sync is doing, and start it by hand | ○ | ○ |

## feature.tasks

| Scenario | Title | android | desktop |
|---|---|---|---|
| `TASK-CHECK-01` | Add a checklist item to a task | — | ○ |
| `TASK-REC-01` | Create a daily recurring task | ● | ● |
| `TASK-TIME-01` | Start a timer on a task from its detail view | ○ | — |

## Holes

Claimed but not automated:

- `AUTH-ATTACH-01` / android
- `AUTH-ATTACH-01` / desktop
- `AUTH-FIRSTRUN-01` / android
- `AUTH-FIRSTRUN-01` / desktop
- `AUTH-SEED-01` / android
- `AUTH-SEED-01` / desktop
- `AUTH-SESSION-01` / android
- `AUTH-SESSION-01` / desktop
- `AUTH-SIGNIN-01` / android
- `AUTH-SIGNIN-01` / desktop
- `AUTH-SIGNUP-01` / android
- `AUTH-SIGNUP-01` / desktop
- `AUTH-SIGNUP-02` / android
- `AUTH-SIGNUP-02` / desktop
- `SYNC-FAILED-01` / android
- `SYNC-FAILED-01` / desktop
- `SYNC-INCOMING-01` / android
- `SYNC-INCOMING-01` / desktop
- `SYNC-OFFLINE-01` / android
- `SYNC-OFFLINE-01` / desktop
- `SYNC-PROFILES-01` / android
- `SYNC-PROFILES-01` / desktop
- `SYNC-PROTO-01` / android
- `SYNC-PROTO-01` / desktop
- `SYNC-SETTINGS-01` / android
- `SYNC-SETTINGS-01` / desktop
- `SYNC-SIGNOUT-01` / android
- `SYNC-SIGNOUT-01` / desktop
- `SYNC-STATUS-01` / android
- `SYNC-STATUS-01` / desktop
- `TASK-CHECK-01` / desktop
- `TASK-TIME-01` / android
