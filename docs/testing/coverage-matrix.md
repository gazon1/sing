<!-- GENERATED — do not edit by hand. -->
<!-- Source: scripts/src/traceability/scenarios/** + @DisplayName / scenario: tags in code. -->
<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->

# Coverage matrix

Derived from `scripts/src/traceability/scenarios/**` and the `@DisplayName` / `scenario:`
tags in code. It changes only when code or specs change.

Full steps and preconditions are in [scenario-details.md](./scenario-details.md);
the table carries the one-line version so a row is readable in a diff.

| Glyph | Meaning |
|---|---|
| — | target not claimed for this scenario |
| ○ | claimed, but no automated test exists — **a hole** |
| ◇ | claimed, no test, and **no automated carrier can reach that tier** |
| ● | automated |
| ⊘ | scenario is deprecated — retired deliberately, not an obligation |

**98 scenarios · 28/121 claimed cells automated · 93 holes**

## feature.agenda

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `AGENDA-01` | A dated task moves between Inbox date buckets | ○ | — | The task leaves the No Date bucket, whose header count drops, and appears under the section for its date — it stays in… |
| `AGENDA-02` | The saved-views list shows its empty state | ○ | — | The list says that there are no saved views yet, rather than rendering as a blank screen or an error. A user who has… |
| `AGENDA-03` | Saving the current agenda as a named view, then deleting it | ○ | — | Saving returns to the agenda — not to the list — so the view is created without stranding the user. The list then… |
| `AGENDA-04` | Opening a saved view lists the tasks matching its definition | ○ | — | A results screen appears listing the tasks matching the view's definition, so the view is a stored filter rather than… |
| `AGENDA-05` | Saved views are reachable from every agenda bottom tab | ○ | — | The agenda and its saved-views entry are reachable from all three agenda-carrying tabs, so the feature does not depend… |
| `AGENDA-06` | A saved view survives create, rename, reopen and delete | ○ | — | The list ends where it started — empty — with every intermediate step observable along the way: the rename is what the… |
| `AGENDA-07` | A saved view can be copied to another profile | ○ | — | The copy appears in the destination profile's saved-views list as a distinct entry from the source, so a view can be… |
| `AGENDA-08` | A saved view survives an app restart | ○ | — | The saved view is still listed and still opens, so it was persisted rather than held in memory. Restarting without… |

## feature.archive

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `ARCHIVE-01` | An archived task is restored to Inbox | ○ | — | The task leaves the Archive and is back in Inbox, so archiving is reversible rather than a one-way move. Archive is… |

## feature.attachments

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `ATTACH-01` | Adding a URL attachment makes it appear on the task | ● | — | The URL attachment appears in the task's attachment list with the given title and a link icon, so the user can revisit… |
| `ATTACH-02` | Deleting an attachment removes it from the list | ● | — | The attachment is removed from the list. If it was the last attachment, the section returns to the empty state showing… |
| `ATTACH-03` | Submitting an empty URL field does not add an attachment | ● | — | No attachment is added. The "Add URL" button is disabled or the submit is rejected, so a blank entry cannot create a… |
| `ATTACH-04` | An attachment persists after app restart | ● | — | The attachment is still in the list after restart, so the user's attached links are not lost when the process is killed. |
| `ATTACH-05` | A URL attachment shows title, URL text and a link icon | ● | — | The row shows a link icon, the attachment's title, and the URL text below the title in a smaller font. The row has a… |

## feature.auth

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `AUTH-ATTACH-01` | Turn an account-less session into a real account | ○ | ○ | The existing data stays visible throughout and ends up belonging to the new account. A failure part way through is… |
| `AUTH-FIRSTRUN-01` | Use the app on a first launch, with no account | ○ | ● | Tasks can be created without signing in, they are stored locally, and restarting offline shows the data with no error… |
| `AUTH-FIRSTRUN-04` | The login screen appears on a cold, empty install | ○ | — | The login screen appears within the restore budget, with "Continue Offline" present, and the loading indicator gone. A… |
| `AUTH-FIRSTRUN-05` | The app starts when the session store cannot be read | ○ | — | The login screen appears within the restore budget, with the loading indicator gone, and the store is left in a state… |
| `AUTH-SEED-01` | Sign in on a device that already holds local data | ● | ○ | The local data is uploaded, the application stays usable while it happens, and the upload survives being interrupted… |
| `AUTH-SESSION-01` | Carry on working after the session stops being valid | ● | ○ | The app keeps working locally. An unobtrusive message says signing in again is needed, without blocking an editor.… |
| `AUTH-SIGNIN-01` | Sign in to an existing account | ○ | ○ | A wrong password gives one message that does not reveal whether the address exists, and the address stays in the form.… |
| `AUTH-SIGNUP-01` | Create an account | ○ | ○ | Invalid input is refused beside the field without a request leaving the device. A valid submission shows a pending… |
| `AUTH-SIGNUP-02` | A sign-up whose address still needs confirmation is not a completed sign-up | ● | ○ | The user is told the account was created and to check their mail. They are NOT taken into the application as a… |

## feature.backup

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `BACKUP-01` | A task survives a create-backup, wipe and restore round trip | ○ | — | The task is present again after the wipe, so the backup carried the data outside the process rather than the restore… |

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
| `CAL-SYNC-SYSTEM-02` | System calendar sync can be switched on where a provider exists | ○ | — | When a provider exists the panel offers a working enable switch rather than the unavailable message, and the status… |
| `CALENDAR-01` | The Month view renders the current month with weekday columns | ○ | — | The current month renders as weekday columns of numbered days, from the first of the month to the last — so the grid… |
| `CALENDAR-02` | The view-mode control switches modes and reports the selection | ○ | — | The view changes and the control reports the mode now selected, so a user can tell where they are without counting the… |
| `CALENDAR-03` | Tapping a day in Month view opens that day's view | ○ | — | That day's view opens and the date header names the day that was tapped, so the user lands on the day they chose. The… |

## feature.lifecycle

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `LIFECYCLE-01` | Data survives a process kill and a relaunch that does not clear state | ○ | — | The data is still present, so it was written to storage rather than held in memory. Reopening after a kill looks the… |
| `LIFECYCLE-02` | The task editor stays open and keeps its content through rotation | ○ | — | The editor is still open and still usable, and the title is still in it — so a configuration change did not discard… |

## feature.negative

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `NEGATIVE-01` | The Inbox empty state names what is missing | ○ | — | An empty state is shown saying what the screen is empty of, rather than a blank list that is indistinguishable from a… |
| `NEGATIVE-02` | The notes list shows an empty state | ○ | — | The list shows its empty state instead of rendering as an empty area with no message. |
| `NEGATIVE-03` | The Plans tab shows an empty state with no projects | ○ | — | The tab shows its empty state rather than an unexplained blank area — a tab with nothing to show still has to say so. |
| `NEGATIVE-04` | The Archive screen shows an empty state | ○ | — | The screen shows an empty state rather than being blank, so a user who arrives there by accident is told the archive… |
| `NEGATIVE-05` | Search distinguishes an idle prompt from no matches | ○ | — | The empty query shows a prompt asking the user to search, and the failed search says that nothing matched — so the… |
| `NEGATIVE-06` | The Tags tab shows an empty state | ○ | — | The tab shows its empty state. The assertion is on the screen rather than on the list container, because the… |

## feature.notes

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `NOTES-01` | A note is created from the notes list | ○ | — | The note exists in the list and can be opened, so creation is reachable from the notes list itself — a user is never… |
| `NOTES-02` | An edited note body survives reopening the note | ○ | — | The edited body is still there, so the autosave happened and the text is not lost on the way out. Quick-add lands on… |
| `NOTES-03` | The All, Pinned and Archived chips show different sets | ○ | — | All shows both notes and the other two chips show neither, so each filter narrows the list to its own set rather than… |

## feature.pomodoro

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `POMODORO-01` | A fresh Pomodoro timer sits paused on work cycle 1 | ○ | — | The phase reads Work, the cycle counter reads Cycle 1, and the remaining duration shows the full work block — the… |
| `POMODORO-02` | Selecting a task chip starts the timer on that task | ○ | — | The timer names the selected task as the focus, and the work phase is already running — selection starts the timer, so… |
| `POMODORO-03` | Pause and resume survive the phase and cycle state | ○ | — | The timer and the Work phase are still shown after both transitions, so a pause suspends the countdown rather than… |
| `POMODORO-04` | Skipping a work block starts a break and advances the cycle | ○ | — | A break phase begins, the cycle counter advances from Cycle 1 to Cycle 2, and the completed-work count reads one —… |
| `POMODORO-05` | Stopping the session releases the focus task | ○ | — | The timer returns to an idle, paused state with the play control showing again, and the previously focused task is no… |

## feature.profile

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `PROFILE-01` | A second profile is created and listed | ○ | — | The new profile is listed alongside the default, so a user can reach the second profile the moment they have made it. |
| `PROFILE-02` | Tasks in one profile are invisible in another | ○ | — | The seeded task is not visible from the default profile and is visible again after switching back, so switching… |

## feature.projects

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `PROJECTS-01` | A project's detail screen shows its own task list | ○ | — | The project's detail screen opens and shows a task list belonging to that project, initially empty. |
| `PROJECTS-02` | A task added from a project's detail is listed under it | ○ | — | The empty state is left and the new task appears under that project, addressed by its own title — so a task added here… |

## feature.search

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `SEARCH-01` | Searching for a task's title finds the task | ○ | — | The seeded task appears in the results, so the user can get back to something they stopped thinking about weeks ago… |
| `SEARCH-02` | Cyrillic query text is rendered and matched correctly | ● | — | The task with the Cyrillic title appears correctly in the results with its Cyrillic characters rendered as Cyrillic,… |
| `SEARCH-03` | Clearing the search query returns to the idle prompt | ● | — | The results disappear and the idle prompt "Enter a search query" returns. The idle prompt is shown when the query is… |
| `SEARCH-04` | Filter by has-description toggles task results | ● | — | Only the task that has a description is shown; the task without a description is filtered out. Toggling the filter off… |
| `SEARCH-05` | Filter by pinned toggles task results | ● | — | Only the pinned task is shown; the unpinned task is filtered out. Toggling the filter off restores all results. |
| `SEARCH-06` | A query with no matches shows an explicit no-results state | ● | — | An explicit "No results found" state is shown. This is distinct from the idle prompt (which is shown when the query is… |
| `SEARCH-07` | A saved search persists and can be loaded | ● | — | The saved search persists across navigation. Tapping it restores the query text and the matching results, so the user… |
| `SEARCH-08` | A term matches the beginning of a word, and not its middle | ● | — | The first search finds the task titled "architecture": a term matches the beginning of a word. The second does not… |
| `SEARCH-09` | The result shows the word that was matched | — | ● | The result shows the word in the title as the match: the index says where the word is, and the search draws it there.… |

## feature.settings

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `SETTINGS-01` | Changing the theme mode leaves Settings intact and navigable | ○ | — | Settings stays intact and navigable after the change, so selecting a theme cannot strand the user on a broken screen.… |
| `SETTINGS-02` | The notification surface renders its controls | ○ | — | Its controls are present and the screen is usable — the notification settings are reachable from Settings. Whether a… |
| `SETTINGS-03` | Every tab in the Settings rail opens without crashing | ○ | — | Each tab opens without a crash, so every Settings section is reachable. A crash on any one tab would make the rest of… |

## feature.sync

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `SYNC-FAILED-01` | Deal with a change that could not be sent | ○ | ○ | There is a visible way to reach the screen. It lists the item, its kind, why it failed, when, and how many times it… |
| `SYNC-INCOMING-01` | Receive a change on the field being typed into | ◇ | ◇ | Text being typed on A is not overwritten by the incoming change, and the due date still updates. When the same field… |
| `SYNC-LOCAL-01` | A task created with no connectivity is kept and appears once back online | ○ | — | The task is still there, so work done without a network was written to local storage rather than queued in memory and… |
| `SYNC-OFFLINE-01` | Work with no network and watch the queue drain | ◇ | ◇ | The indicator shows how many changes are waiting, and the count survives a restart. Restoring the network drains it to… |
| `SYNC-PROFILES-01` | Have a profile follow the account to another device | ◇ | ◇ | The profile arrives on B under the same name with no duplicate. Switching profiles during a sync does not let the… |
| `SYNC-PROTO-01` | Meet a server the app is too old for | ○ | ○ | The user is told to update the app to keep syncing. Local work is not blocked and no data is damaged. Proposed rather… |
| `SYNC-SETTINGS-01` | Change how often sync runs | ○ | ○ | The new interval takes effect without a restart and survives one. |
| `SYNC-SIGNOUT-01` | Sign out and sign back in as somebody else | ○ | ○ | The first sign-out confirms briefly and leaves no way back into the signed-in state. With unsent changes the user is… |
| `SYNC-STATUS-01` | See what sync is doing, and start it by hand | ○ | ○ | Each state reads as what it is: no account and no network are calm states rather than red errors, and an error names… |

## feature.tasks

| Scenario | Title | android | desktop | What we verify |
|---|---|---|---|---|
| `TASK-01` | Create a task from the FAB and see it in the Inbox | ○ | — | The task appears in the Inbox list. A task saved without a due date lands in the Inbox (No Date section), not in any… |
| `TASK-02` | Tapping the checkbox toggles the task's completion state | ○ | — | The checkbox appears checked. The row shows the completed visual state. Tapping again toggles it back to unchecked. |
| `TASK-03` | Tapping a task row opens its detail screen with the correct title | ○ | — | The editor opens for the correct task. After navigating back, the task row is still present and the agenda is displayed. |
| `TASK-04` | Delete a task from its detail screen overflow menu | ○ | — | The task row is no longer present in the list. No confirmation dialog is shown — deletion is immediate and reversible… |
| `TASK-05` | Deleting a task shows an Undo snackbar and Undo restores it | ○ | — | The Undo snackbar appears immediately after deletion. Tapping Undo restores the task to its original position in the… |
| `TASK-06` | Long-press opens context menu; archive removes the task | ○ | — | The task is removed from the Inbox list. The context menu does not open the editor; it only dispatches the selected… |
| `TASK-07` | Set task priority from the editor attribute row | ○ | — | The row label changes to "High priority" and the task row in the agenda shows the high-priority badge. The change… |
| `TASK-08` | Set task due date from the editor attribute row | ○ | — | The row label changes from "Add date" to the ISO date string of the selected day. The task moves to the matching date… |
| `TASK-09` | Cancelling the date picker leaves the task undated | ○ | — | The row label stays as "Add date". No date is saved and the task remains undated. |
| `TASK-10` | Empty title is rejected; original title remains intact | ○ | — | The editor stays open with the title field empty. The task retains its original title and reappears in the list with… |
| `TASK-11` | Cyrillic task title is created, saved, and displayed correctly | ○ | — | The task appears with its Cyrillic title intact (no mojibake or replacement character) and in the correct agenda… |
| `TASK-12` | Create a task with all attribute fields filled | ○ | — | The task appears in the Today section with the correct title and the high-priority badge. All attribute rows are… |
| `TASK-13` | Tasks appear in the correct agenda date-bucket sections | ○ | — | The Overdue section contains the yesterday task, the Today section contains today's task, and the Upcoming section… |
| `TASK-14` | FAB from Today tab pre-fills the due date to Today | ○ | — | The task is saved with today's date and appears in the Today section. After saving, the user is returned to the Today… |
| `TASK-15` | FAB from Inbox tab does NOT pre-fill a due date | ○ | — | The task is saved with no due date and appears in the No Date section. After saving, the user is returned to the Inbox… |
| `TASK-16` | Creating from a tab returns to that tab after save | ○ | — | The save operation returns to the originating tab. The Today task is in the Today section and the Inbox task is in the… |
| `TASK-17` | Start and stop a time-tracking session on a task | ○ | — | The chip swaps from Start to Stop in place while the timer is running, and returns to Start when stopped. No crash… |
| `TASK-CHECK-01` | Add a checklist item to a task | ● | — | The item persists with its text and its completed state, so the list survives closing and reopening the editor on the… |
| `TASK-REC-01` | Create a daily recurring task | ● | ● | The task list shows the next occurrence dated one day after the completion date, and the original instance is gone… |
| `TASK-SUB-01` | Subtask lifecycle — add, toggle, delete | ● | — | The subtask survives add → toggle → delete. Its parentTaskId is set correctly so it appears as a child in the… |
| `TASK-TIME-01` | Start a timer on a task from its detail view | ○ | ● | The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs. |

## Holes

Claimed but not automated:

- `AGENDA-01` / android
- `AGENDA-02` / android
- `AGENDA-03` / android
- `AGENDA-04` / android
- `AGENDA-05` / android
- `AGENDA-06` / android
- `AGENDA-07` / android
- `AGENDA-08` / android
- `ARCHIVE-01` / android
- `AUTH-ATTACH-01` / android
- `AUTH-ATTACH-01` / desktop
- `AUTH-FIRSTRUN-01` / android
- `AUTH-FIRSTRUN-04` / android
- `AUTH-FIRSTRUN-05` / android
- `AUTH-SEED-01` / desktop
- `AUTH-SESSION-01` / desktop
- `AUTH-SIGNIN-01` / android
- `AUTH-SIGNIN-01` / desktop
- `AUTH-SIGNUP-01` / android
- `AUTH-SIGNUP-01` / desktop
- `AUTH-SIGNUP-02` / desktop
- `BACKUP-01` / android
- `CAL-SYNC-CONNECT-01` / android
- `CAL-SYNC-IMPORT-01` / android
- `CAL-SYNC-PROVIDER-01` / android
- `CAL-SYNC-RECUR-01` / android
- `CAL-SYNC-RECUR-01` / desktop
- `CAL-SYNC-RENEW-01` / android
- `CAL-SYNC-SYNCNOW-01` / android
- `CAL-SYNC-SYSTEM-01` / android
- `CAL-SYNC-SYSTEM-02` / android
- `CALENDAR-01` / android
- `CALENDAR-02` / android
- `CALENDAR-03` / android
- `LIFECYCLE-01` / android
- `LIFECYCLE-02` / android
- `NEGATIVE-01` / android
- `NEGATIVE-02` / android
- `NEGATIVE-03` / android
- `NEGATIVE-04` / android
- `NEGATIVE-05` / android
- `NEGATIVE-06` / android
- `NOTES-01` / android
- `NOTES-02` / android
- `NOTES-03` / android
- `POMODORO-01` / android
- `POMODORO-02` / android
- `POMODORO-03` / android
- `POMODORO-04` / android
- `POMODORO-05` / android
- `PROFILE-01` / android
- `PROFILE-02` / android
- `PROJECTS-01` / android
- `PROJECTS-02` / android
- `SEARCH-01` / android
- `SETTINGS-01` / android
- `SETTINGS-02` / android
- `SETTINGS-03` / android
- `SYNC-FAILED-01` / android
- `SYNC-FAILED-01` / desktop
- `SYNC-INCOMING-01` / android
- `SYNC-INCOMING-01` / desktop
- `SYNC-LOCAL-01` / android
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
- `TASK-01` / android
- `TASK-02` / android
- `TASK-03` / android
- `TASK-04` / android
- `TASK-05` / android
- `TASK-06` / android
- `TASK-07` / android
- `TASK-08` / android
- `TASK-09` / android
- `TASK-10` / android
- `TASK-11` / android
- `TASK-12` / android
- `TASK-13` / android
- `TASK-14` / android
- `TASK-15` / android
- `TASK-16` / android
- `TASK-17` / android
- `TASK-TIME-01` / android
