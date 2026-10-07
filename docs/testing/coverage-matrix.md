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
| ◇ | claimed, no test, and **no automated carrier can reach that tier** |
| ● | automated |
| ⊘ | scenario is deprecated — retired deliberately, not an obligation |

**26 scenarios · 9/49 claimed cells automated · 40 holes**

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
| `CAL-SYNC-CONNECT-01` | Connect a Google account and choose a calendar to sync into | ○ | ● | The account section changes from an invitation to connect to a way to disconnect, a "Sync now" control appears only… |
| `CAL-SYNC-IMPORT-01` | Turn on importing Google events and see how far it reaches | ○ | ● | The toggle stays on across a reopen, and the sentence names the window the pass is actually configured with rather… |
| `CAL-SYNC-PROVIDER-01` | Choose which calendar the feature syncs to | ○ | ● | The system calendar's controls disappear when Google is selected and return when it is selected again, and neither… |
| `CAL-SYNC-RECUR-01` | A recurring Google event keeps the exact rule Google supplied | ◇ | ◇ | The series Google holds still carries the rule string it started with, and the series still occurs on its original… |
| `CAL-SYNC-RENEW-01` | A grant that cannot renew says so before sync silently stops | ○ | ● | The screen warns that the connection cannot be renewed in the background and that sync will stop when the current… |
| `CAL-SYNC-SYNCNOW-01` | A manual Google pass reports what it actually did | ○ | ● | The control is disabled and reads as in progress while the pass runs, and afterwards the panel reports the outcome of… |
| `CAL-SYNC-SYSTEM-01` | The system-calendar projection says so when the platform cannot do it | ◇ | ● | The panel states that system calendar sync needs Android and that the user's tasks are unaffected, rather than… |

## feature.sync

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `SYNC-FAILED-01` | Deal with a change that could not be sent | ○ | ○ | There is a visible way to reach the screen. It lists the item, its kind, why it failed, when, and how many times it… |
| `SYNC-INCOMING-01` | Receive a change on the field being typed into | ◇ | ◇ | Text being typed on A is not overwritten by the incoming change, and the due date still updates. When the same field… |
| `SYNC-OFFLINE-01` | Work with no network and watch the queue drain | ◇ | ◇ | The indicator shows how many changes are waiting, and the count survives a restart. Restoring the network drains it to… |
| `SYNC-PROFILES-01` | Have a profile follow the account to another device | ◇ | ◇ | The profile arrives on B under the same name with no duplicate. Switching profiles during a sync does not let the… |
| `SYNC-PROTO-01` | Meet a server the app is too old for | ○ | ○ | The user is told to update the app to keep syncing. Local work is not blocked and no data is damaged. Proposed rather… |
| `SYNC-SETTINGS-01` | Change how often sync runs | ○ | ○ | The new interval takes effect without a restart and survives one. |
| `SYNC-SIGNOUT-01` | Sign out and sign back in as somebody else | ○ | ○ | The first sign-out confirms briefly and leaves no way back into the signed-in state. With unsent changes the user is… |
| `SYNC-STATUS-01` | See what sync is doing, and start it by hand | ○ | ○ | Each state reads as what it is: no account and no network are calm states rather than red errors, and an error names… |

## feature.tasks

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `TASK-CHECK-01` | Add a checklist item to a task | — | ○ | The item persists with its text and its completed state, so the list survives closing and reopening the editor on the… |
| `TASK-REC-01` | Create a daily recurring task | ● | ● | The task list shows the next occurrence dated one day after the completion date, and the original instance is gone… |
| `TASK-TIME-01` | Start a timer on a task from its detail view | ○ | ○ | The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs. |

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
- `CAL-SYNC-CONNECT-01` / android
- `CAL-SYNC-IMPORT-01` / android
- `CAL-SYNC-PROVIDER-01` / android
- `CAL-SYNC-RECUR-01` / android
- `CAL-SYNC-RECUR-01` / desktop
- `CAL-SYNC-RENEW-01` / android
- `CAL-SYNC-SYNCNOW-01` / android
- `CAL-SYNC-SYSTEM-01` / android
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
- `TASK-TIME-01` / desktop

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

#### `CAL-SYNC-CONNECT-01` — Connect a Google account and choose a calendar to sync into

**confirmed** · P1 · `#CAL-SYNC-CONNECT`

**Given:** Settings → Calendar is open, the provider is set to Google Calendar, and no Google account is connected yet.

Steps:

1. Press "Connect Google account".
2. Once the account is connected, choose one of the calendars it reported.

**Expected:** The account section changes from an invitation to connect to a way to disconnect, a "Sync now" control appears only after a calendar is chosen, and choosing a calendar persists — reopening Settings still shows it selected. Before a calendar is chosen the panel says what is missing rather than offering a control that does nothing.

#### `CAL-SYNC-IMPORT-01` — Turn on importing Google events and see how far it reaches

**confirmed** · P2 · `#CAL-SYNC-IMPORT`

**Given:** Settings → Calendar is open with Google selected and an account connected, so the import section is on screen.

Steps:

1. Turn on "Import events from Google".
2. Read the sentence describing how far the listing reaches.
3. Reopen Settings and return to the tab.

**Expected:** The toggle stays on across a reopen, and the sentence names the window the pass is actually configured with rather than a default that could disagree with it. Turning the toggle off leaves the user's own tasks still syncing to Google.

#### `CAL-SYNC-PROVIDER-01` — Choose which calendar the feature syncs to

**confirmed** · P1 · `#CAL-SYNC-PROVIDER`

**Given:** The app is signed in on a fresh profile with no calendar sync configured, and Settings is open.

Steps:

1. Open Settings and choose the Calendar tab.
2. Switch the provider selector from the system calendar to Google Calendar.
3. Switch it back to the system calendar.

**Expected:** The system calendar's controls disappear when Google is selected and return when it is selected again, and neither half leaves the other's choices on screen. On a platform with no calendar provider, selecting the system calendar says so plainly and still leaves Google selectable.

#### `CAL-SYNC-RECUR-01` — A recurring Google event keeps the exact rule Google supplied

**confirmed** · P1 · `#CAL-SYNC-RECUR`

**Given:** A Google calendar contains a recurring event whose rule uses syntax the app's own parser does not fully model — a weekly interval count, a BYDAY with an ordinal, or an UNTIL expressed in UTC.

Steps:

1. The event is imported into the app as an editable task.
2. The user changes the time of one occurrence.
3. The change is pushed back to Google.

**Expected:** The series Google holds still carries the rule string it started with, and the series still occurs on its original recurrence. The rule is opaque to this feature and must never be re-derived from parsed dates.

#### `CAL-SYNC-RENEW-01` — A grant that cannot renew says so before sync silently stops

**confirmed** · P1 · `#CAL-SYNC-RENEW`

**Given:** Settings → Calendar is open with Google selected, and the profile holds a credential whose grant carries no refresh token — the shape the hybrid OAuth flow produces, because consent happens in KMPAuth while the durable credential is owned here.

Steps:

1. The screen loads the account.
2. The user looks at the Google Account section.

**Expected:** The screen warns that the connection cannot be renewed in the background and that sync will stop when the current permission expires, and says to reconnect. The warning appears while the account still looks connected in every other respect — the disconnect control is present and nothing else reports a problem — because that is the state a user cannot otherwise distinguish from working sync.

#### `CAL-SYNC-SYNCNOW-01` — A manual Google pass reports what it actually did

**confirmed** · P1 · `#CAL-SYNC-SYNCNOW`

**Given:** Settings → Calendar is open, Google is the selected provider, an account is connected, and a writable calendar is chosen.

Steps:

1. Press "Sync now" in the Google half.
2. Let the pass finish.

**Expected:** The control is disabled and reads as in progress while the pass runs, and afterwards the panel reports the outcome of that pass: either a failure with its reason, or the time the pass completed. A failed pass never leaves the panel looking unchanged, and never advances the "last synced" time. The system-calendar half's own status is not moved by a Google pass.

#### `CAL-SYNC-SYSTEM-01` — The system-calendar projection says so when the platform cannot do it

**confirmed** · P2 · `#CAL-SYNC-SYSTEM`

**Given:** Settings → Calendar is open and the provider is the system calendar. On desktop, or any platform with no calendar provider the app can reach.

Steps:

1. Select the system calendar in the provider selector.

**Expected:** The panel states that system calendar sync needs Android and that the user's tasks are unaffected, rather than rendering an enable switch and an empty calendar list over providers that were never asked. The Google half stays reachable, because the two are alternatives and one being impossible must not hide the other.

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

**Given:** A task exists, the app is signed in, and the task's detail view is open.

Steps:

1. Open a task and scroll to the time-tracking section.
2. Start the timer.
3. Stop it again.

**Expected:** The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs.
