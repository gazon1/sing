<!-- GENERATED — do not edit by hand. -->
<!-- Source: infra/kiwi/scenarios/** + @DisplayName / scenario: tags in code. -->
<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->

# Coverage matrix

Derived from `infra/kiwi/scenarios/**` and the `@DisplayName` / `scenario:`
tags in code. It changes only when code or specs change.

Full steps and preconditions are in the [scenario details](#scenario-details)
below; the table carries the one-line version so a row is readable in a diff.

| Glyph | Meaning |
|---|---|
| — | target not claimed for this scenario |
| ○ | claimed, but no automated test exists — **a hole** |
| ● | automated |
| ⊘ | scenario is deprecated — retired deliberately, not an obligation |

**19 scenarios · 3/34 claimed cells automated · 31 holes**

## feature.auth

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `AUTH-ATTACH-01` | Turn an account-less session into a real account | ○ | ○ | The existing data stays visible throughout and ends up belonging to the new account. A failure part way through is… |
| `AUTH-FIRSTRUN-01` | Use the app on a first launch, with no account | ○ | ● | Tasks can be created without signing in, they are stored locally, and restarting offline shows the data with no error… |
| `AUTH-SEED-01` | Sign in on a device that already holds local data | ○ | ○ | The local data is uploaded, the application stays usable while it happens, and the upload survives being interrupted… |
| `AUTH-SESSION-01` | Carry on working after the session stops being valid | ○ | ○ | The app keeps working locally. An unobtrusive message says signing in again is needed, without blocking an editor.… |
| `AUTH-SIGNIN-01` | Sign in to an existing account | ○ | ○ | A wrong password gives one message that does not reveal whether the address exists, and the address stays in the form.… |
| `AUTH-SIGNUP-01` | Create an account | ○ | ○ | Invalid input is refused beside the field without a request leaving the device. A valid submission shows a pending… |
| `AUTH-SIGNUP-02` | A sign-up whose address still needs confirmation is not a completed sign-up | ○ | ○ | The user is told the account was created and to check their mail. They are NOT taken into the application as a… |

## feature.calendar

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `CAL-FILT-01` | Filter the calendar by project, tag, priority or status | ⊘ | ⊘ | Nothing happens, and nothing ever did: there is no `CalendarFilterPanel` in the tree, no filter state on… |

## feature.sync

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `SYNC-FAILED-01` | Deal with a change that could not be sent | ○ | ○ | There is a visible way to reach the screen. It lists the item, its kind, why it failed, when, and how many times it… |
| `SYNC-INCOMING-01` | Receive a change on the field being typed into | ○ | ○ | Text being typed on A is not overwritten by the incoming change, and the due date still updates. When the same field… |
| `SYNC-OFFLINE-01` | Work with no network and watch the queue drain | ○ | ○ | The indicator shows how many changes are waiting, and the count survives a restart. Restoring the network drains it to… |
| `SYNC-PROFILES-01` | Have a profile follow the account to another device | ○ | ○ | The profile arrives on B under the same name with no duplicate. Switching profiles during a sync does not let the… |
| `SYNC-PROTO-01` | Meet a server the app is too old for | ○ | ○ | The user is told to update the app to keep syncing. Local work is not blocked and no data is damaged. Proposed rather… |
| `SYNC-SETTINGS-01` | Change how often sync runs | ○ | ○ | The new interval takes effect without a restart and survives one. |
| `SYNC-SIGNOUT-01` | Sign out and sign back in as somebody else | ○ | ○ | The first sign-out confirms briefly and leaves no way back into the signed-in state. With unsent changes the user is… |
| `SYNC-STATUS-01` | See what sync is doing, and start it by hand | ○ | ○ | Each state reads as what it is: no account and no network are calm states rather than red errors, and an error names… |

## feature.tasks

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `TASK-CHECK-01` | Add a checklist item to a task | — | ○ | The item persists with its text and its completed state, so the list survives closing and reopening the editor on the… |
| `TASK-REC-01` | Create a daily recurring task | ● | ● | The task list shows the next occurrence dated one day after the completion date, and the original instance is gone… |
| `TASK-TIME-01` | Start a timer on a task from its detail view | ○ | — | The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs. |

## Holes

Claimed but not automated:

- `AUTH-ATTACH-01` / android
- `AUTH-ATTACH-01` / desktop
- `AUTH-FIRSTRUN-01` / android
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

## Scenario details

### feature.auth

#### `AUTH-ATTACH-01` — Turn an account-less session into a real account

**proposed** · P1 · `#AUTH-ATTACH`

**Given:** A device working without an account, holding data.

Steps:

1. Create an account from the account screen.
2. Wait for the attach to finish, including a case where it fails part way.

**Expected:** The existing data stays visible throughout and ends up belonging to the new account. A failure part way through is reported and can be retried without losing or duplicating data. Proposed rather than confirmed: the transfer's own shape depends on an open question in the plan, and the provider may attach the identity without moving any rows at all.

#### `AUTH-FIRSTRUN-01` — Use the app on a first launch, with no account

**confirmed** · P1 · `#AUTH-FIRSTRUN`

**Given:** A clean install with the network on, and no account configured yet.

Steps:

1. Start the app and do not sign in.
2. Create a task, a note and a project.
3. Turn the network off and restart the app.

**Expected:** Tasks can be created without signing in, they are stored locally, and restarting offline shows the data with no error and no dialog. The sync indicator shows a neutral state — having no account is not an error.

#### `AUTH-SEED-01` — Sign in on a device that already holds local data

**confirmed** · P1 · `#AUTH-SEED`

**Given:** The device holds 20 tasks, 3 projects and 2 profiles created without an account.

Steps:

1. Sign in to an account.
2. Minimize the app part way through, then bring it back.
3. Turn the network off part way through, then restore it.

**Expected:** The local data is uploaded, the application stays usable while it happens, and the upload survives being interrupted and resumes. No task is lost and none is duplicated. Signing in again on the same device does not upload a second time.

#### `AUTH-SESSION-01` — Carry on working after the session stops being valid

**confirmed** · P1 · `#AUTH-SESSION`

**Given:** A signed-in device whose session is no longer accepted by the server.

Steps:

1. Work in the app.
2. Wait for a sync attempt.
3. Sign in again, or choose to stay signed out.

**Expected:** The app keeps working locally. An unobtrusive message says signing in again is needed, without blocking an editor. Signing in again sends the queued changes, and the edits made in between are not lost.

#### `AUTH-SIGNIN-01` — Sign in to an existing account

**confirmed** · P1 · `#AUTH-SIGNIN`

**Given:** An account exists and the user is on the sign-in screen.

Steps:

1. Enter a wrong password.
2. Enter the correct password.

**Expected:** A wrong password gives one message that does not reveal whether the address exists, and the address stays in the form. A correct password shows a pending state and then enters the application, with the account's data arriving shortly afterwards.

#### `AUTH-SIGNUP-01` — Create an account

**confirmed** · P1 · `#AUTH-SIGNUP`

**Given:** The server is configured and the user is on the sign-in screen.

Steps:

1. Switch to creating an account.
2. Enter a malformed address, then a password that is too short.
3. Enter valid details and submit.

**Expected:** Invalid input is refused beside the field without a request leaving the device. A valid submission shows a pending state with the fields and the button disabled, and a second press does not create a second request.

#### `AUTH-SIGNUP-02` — A sign-up whose address still needs confirmation is not a completed sign-up

**confirmed** · P1 · `#AUTH-SIGNUP`

**Given:** The provider creates the account but requires the address to be confirmed before it can be used.

Steps:

1. Create an account with a valid address.
2. Wait for the outcome.

**Expected:** The user is told the account was created and to check their mail. They are NOT taken into the application as a signed-in user, and the outcome is not presented as a failure — a retry would be refused as an address already in use.

### feature.calendar

#### `CAL-FILT-01` — Filter the calendar by project, tag, priority or status

**deprecated** · P2 · `#CAL-FILT`

**Given:** The calendar screen, before 2026-09-30. Nine affordances were visible: a "More" icon in the calendar top bar, a "Filter" icon, five filter rows in the mini-calendar panel, and two "+N more" rows in the month and time grids.

Steps:

1. Open the calendar and look for a way to narrow what is shown.
2. Tap the "Filter" affordance, or a filter row, or "+N more".

**Expected:** Nothing happens, and nothing ever did: there is no `CalendarFilterPanel` in the tree, no filter state on `CalendarViewModel`, and nothing in the task query a filter could narrow. Every one of the nine affordances was a dead `onClick = {}`.

### feature.sync

#### `SYNC-FAILED-01` — Deal with a change that could not be sent

**proposed** · P2 · `#SYNC-FAILED`

**Given:** A signed-in device that has produced a change the server will never accept.

Steps:

1. Produce a change that cannot be sent, and let it exhaust its attempts.
2. Open the screen that lists problem changes.
3. Retry one, retry all, and delete one.

**Expected:** There is a visible way to reach the screen. It lists the item, its kind, why it failed, when, and how many times it was tried. Retrying one sends it and it disappears on success. Retrying all reports a result and leaves what still fails. Deleting confirms first, explains that the local copy then differs from the server, and does not touch the local data. Proposed rather than confirmed: the screen does not exist yet, and the plan introduces it as a condition of a fix rather than as shipped behaviour.

#### `SYNC-INCOMING-01` — Receive a change on the field being typed into

**confirmed** · P1 · `#SYNC-INCOMING`

**Given:** Two devices signed into the same account.

Steps:

1. On device A open a task and start typing a title without saving.
2. On device B change that task's due date and wait for the sync.
3. On device B change the title itself, and separately delete the task.

**Expected:** Text being typed on A is not overwritten by the incoming change, and the due date still updates. When the same field is changed remotely the outcome is defined and never discards in-progress typing without warning. A remote deletion of an open task reports itself as deleted, and saving does not resurrect it. A page of incoming changes does not jump the scroll or lose the selection.

#### `SYNC-OFFLINE-01` — Work with no network and watch the queue drain

**confirmed** · P1 · `#SYNC-OFFLINE`

**Given:** A signed-in device with a non-empty account.

Steps:

1. Turn the network off and create three tasks, change two, and delete one.
2. Restart the app while still offline.
3. Restore the network and repeat with a connection that comes and goes every few seconds.

**Expected:** The indicator shows how many changes are waiting, and the count survives a restart. Restoring the network drains it to zero. A flapping connection produces no flicker and no duplicated task on the second device.

#### `SYNC-PROFILES-01` — Have a profile follow the account to another device

**confirmed** · P2 · `#SYNC-PROFILES`

**Given:** Two devices signed into the same account.

Steps:

1. Create a profile on device A.
2. Switch to that profile on device B while a sync is running.
3. Switch back to the first profile and delete the second one with unsent changes.

**Expected:** The profile arrives on B under the same name with no duplicate. Switching profiles during a sync does not let the other profile's data appear. Each profile keeps its own sync position, so returning to one does not reload everything. Deleting a profile that has unsent changes warns first.

#### `SYNC-PROTO-01` — Meet a server the app is too old for

**proposed** · P2 · `#SYNC-PROTO`

**Given:** The server returns an event written by a newer version of the protocol.

Steps:

1. Open the app and let a sync run against that server.

**Expected:** The user is told to update the app to keep syncing. Local work is not blocked and no data is damaged. Proposed rather than confirmed: the client skips the event today, which is safe but silent — there is no such message yet.

#### `SYNC-SETTINGS-01` — Change how often sync runs

**confirmed** · P2 · `#SYNC-SETTINGS`

**Given:** A signed-in device with sync enabled.

Steps:

1. Change the sync interval.
2. Restart the app and check the setting.

**Expected:** The new interval takes effect without a restart and survives one.

#### `SYNC-SIGNOUT-01` — Sign out and sign back in as somebody else

**confirmed** · P1 · `#SYNC-SIGNOUT`

**Given:** A signed-in device, possibly holding unsent changes.

Steps:

1. Sign out with nothing pending, then sign out with unsent changes.
2. Sign out while offline, and sign out while a sync is running.
3. Sign back in as a different user.

**Expected:** The first sign-out confirms briefly and leaves no way back into the signed-in state. With unsent changes the user is told how many, and chooses. Signing out offline still completes locally. Signing out mid-sync stops the cycle cleanly. Signing in as another user shows none of the previous user's data.

#### `SYNC-STATUS-01` — See what sync is doing, and start it by hand

**confirmed** · P1 · `#SYNC-STATUS`

**Given:** A signed-in device with a non-empty account.

Steps:

1. Look at the sync indicator in each of its states.
2. Press the sync action ten times in a row.
3. Press it with the network off, and with no account signed in.

**Expected:** Each state reads as what it is: no account and no network are calm states rather than red errors, and an error names its cause and offers a retry without showing a token. Ten presses run one cycle and at most one follow-up. Pressing with no network or no account explains the situation rather than failing.

### feature.tasks

#### `TASK-CHECK-01` — Add a checklist item to a task

**confirmed** · P1 · `#TASK-CHECK`

**Given:** A task exists and its detail view is open on desktop, where the checklist editor is reachable from the task editor.

Steps:

1. Open the checklist editor for the task.
2. Add an item and give it text.
3. Complete the item, close the editor and reopen it.

**Expected:** The item persists with its text and its completed state, so the list survives closing and reopening the editor on the same task.

#### `TASK-REC-01` — Create a daily recurring task

**confirmed** · P1 · `#TASK-REC`

**Given:** An empty task database on a signed-in device, with the app in the foreground and notifications permitted.

Steps:

1. Open the task composer and give the task a title.
2. Enable recurrence and choose the Daily interval.
3. Save the task and complete it once.

**Expected:** The task list shows the next occurrence dated one day after the completion date, and the original instance is gone from Today.

#### `TASK-TIME-01` — Start a timer on a task from its detail view

**confirmed** · P1 · `#TASK-TIME`

**Given:** A task exists, the app is signed in, and the task's detail view is open on Android, where the time-tracking section is part of the detail screen.

Steps:

1. Open a task and scroll to the time-tracking section.
2. Start the timer.
3. Stop it again.

**Expected:** The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs.
