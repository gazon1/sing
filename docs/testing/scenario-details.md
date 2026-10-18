<!-- GENERATED — do not edit by hand. -->
<!-- Source: scripts/src/traceability/scenarios/** + @DisplayName / scenario: tags in code. -->
<!-- Regenerate: just trace-coverage   Verify: just trace-coverage-check -->

## Scenario details

Full preconditions, steps and expected result for every scenario, with stable anchors so the index can link into it.

### feature.agenda

#### `AGENDA-01` — A dated task moves between Inbox date buckets

**confirmed** · P2 · `#AGENDA`

**Given:** One task exists in Inbox with no date, so the No Date bucket has content to move.

Steps:

1. Open Inbox and note which bucket the task is under.
2. Give the task a date.
3. Open Inbox again and look at the bucket headers.

**Expected:** The task leaves the No Date bucket, whose header count drops, and appears under the section for its date — it stays in Inbox rather than disappearing from the screen. Section headers carry their own count, so the move stays visible even without scrolling to the row.

#### `AGENDA-02` — The saved-views list shows its empty state

**confirmed** · P1 · `#AGENDA`

**Given:** No saved view exists yet — the first-run state of the feature.

Steps:

1. Open the agenda and navigate to the saved-views list.

**Expected:** The list says that there are no saved views yet, rather than rendering as a blank screen or an error. A user who has never saved anything is told what the feature is for and where to start.

#### `AGENDA-03` — Saving the current agenda as a named view, then deleting it

**confirmed** · P1 · `#AGENDA`

**Given:** At least one task matches the agenda filter being saved, so the saved view has a non-empty definition to carry.

Steps:

1. From the agenda, choose to save the current view.
2. Clear the pre-filled name and type a name of your own.
3. Save, and observe where you land.
4. Reopen the saved-views list and find the new view.
5. Enter edit mode and delete it.

**Expected:** Saving returns to the agenda — not to the list — so the view is created without stranding the user. The list then shows the view under the name typed, and deleting it removes it, leaving the rest of the list untouched. The name is the one typed rather than the pre-fill, because the field is cleared first; which name is "correct" is not something this scenario claims.

#### `AGENDA-04` — Opening a saved view lists the tasks matching its definition

**confirmed** · P1 · `#AGENDA`

**Given:** An active task exists, so the view's definition has at least one matching task and an empty results screen cannot pass as correct.

Steps:

1. Create a saved view over active tasks, named "My Active Tasks".
2. From the saved-views list, open that view.

**Expected:** A results screen appears listing the tasks matching the view's definition, so the view is a stored filter rather than a stored label — the seeded active task is present in the results.

#### `AGENDA-05` — Saved views are reachable from every agenda bottom tab

**confirmed** · P2 · `#AGENDA`

**Given:** The app is running with the bottom navigation bar visible.

Steps:

1. From each of the Inbox, Today and Upcoming tabs in turn, open the agenda.
2. Reach the saved-views entry.

**Expected:** The agenda and its saved-views entry are reachable from all three agenda-carrying tabs, so the feature does not depend on which tab the user happened to be on. The non-agenda tabs are not exercised and make no claim.

#### `AGENDA-06` — A saved view survives create, rename, reopen and delete

**confirmed** · P1 · `#AGENDA`

**Given:** No saved view exists yet, so the lifecycle starts from the same empty state a new user's account would be in.

Steps:

1. Open the saved-views list and confirm it is empty.
2. Create a view named "Journey View" from the current agenda.
3. Open the view and confirm its results render.
4. Edit the view and rename it.
5. Reopen the renamed view.
6. Delete it from the list.

**Expected:** The list ends where it started — empty — with every intermediate step observable along the way: the rename is what the list shows afterwards, a delete at list level takes effect immediately without a confirmation dialog that belongs only to edit mode, and no step leaves the user stranded. A build that returned to the empty state anywhere before the final delete fails this scenario even though every individual step would pass on its own.

#### `AGENDA-07` — A saved view can be copied to another profile

**confirmed** · P2 · `#AGENDA`

**Given:** A saved view exists, and a freshly launched app whose default profile ("Personal") is therefore always present.

Steps:

1. Open the saved view's overflow menu and choose to copy it.
2. Select a profile in the picker.

**Expected:** The copy appears in the destination profile's saved-views list as a distinct entry from the source, so a view can be handed to a profile without being moved out of the one it came from. The destination here is the default profile, so this verifies the copy path rather than isolation between two distinct profiles.

#### `AGENDA-08` — A saved view survives an app restart

**confirmed** · P1 · `#AGENDA`

**Given:** A saved view named "Persistent View" exists.

Steps:

1. Stop the app without clearing its data.
2. Launch it again and open the saved-views list.

**Expected:** The saved view is still listed and still opens, so it was persisted rather than held in memory. Restarting without clearing data is what makes this a persistence check. Backup and restore are not exercised here.

### feature.archive

#### `ARCHIVE-01` — An archived task is restored to Inbox

**confirmed** · P2 · `#ARCHIVE`

**Given:** A task exists in Inbox. The restore only has something to do if there is something to archive.

Steps:

1. Archive the task from its detail overflow menu.
2. Open the Archive screen and open the archived task.
3. Restore it.

**Expected:** The task leaves the Archive and is back in Inbox, so archiving is reversible rather than a one-way move. Archive is the action a user takes when they are unsure, and a task they cannot get back is lost.

### feature.attachments

#### `ATTACH-01` — Adding a URL attachment makes it appear on the task

**confirmed** · P1 · `#ATTACH`

**Given:** A saved task exists, its detail screen is reachable.

Steps:

1. Open the task's detail screen.
2. Locate the Attachments section and tap "+ Add file" to open the sheet.
3. In the URL field, enter a valid URL.
4. In the Title field, enter a display title.
5. Tap "Add URL".
6. Verify the attachment appears in the list.

**Expected:** The URL attachment appears in the task's attachment list with the given title and a link icon, so the user can revisit the URL later without re-typing it.

#### `ATTACH-02` — Deleting an attachment removes it from the list

**confirmed** · P1 · `#ATTACH`

**Given:** A saved task exists with one URL attachment already added.

Steps:

1. Open the task's detail screen.
2. Locate the attachment in the Attachments section.
3. Tap the delete icon on the attachment row.
4. Observe the attachment list.

**Expected:** The attachment is removed from the list. If it was the last attachment, the section returns to the empty state showing "+ Add file".

#### `ATTACH-03` — Submitting an empty URL field does not add an attachment

**confirmed** · P1 · `#ATTACH`

**Given:** A saved task exists, its detail screen is reachable.

Steps:

1. Open the task's detail screen.
2. Open the attachments sheet.
3. Leave the URL field blank.
4. Attempt to tap "Add URL".
5. Observe the list of attachments.

**Expected:** No attachment is added. The "Add URL" button is disabled or the submit is rejected, so a blank entry cannot create a broken attachment in the list.

#### `ATTACH-04` — An attachment persists after app restart

**confirmed** · P1 · `#ATTACH`

**Given:** A saved task with one URL attachment already exists.

Steps:

1. Open the task's detail screen and note the attachment is present.
2. Force-stop the app and relaunch it.
3. Navigate to the same task's detail screen.
4. Observe the attachment list.

**Expected:** The attachment is still in the list after restart, so the user's attached links are not lost when the process is killed.

#### `ATTACH-05` — A URL attachment shows title, URL text and a link icon

**confirmed** · P2 · `#ATTACH`

**Given:** A saved task with one URL attachment already exists.

Steps:

1. Open the task's detail screen.
2. Locate the attachment in the Attachments section.
3. Observe the rendering of the attachment row.

**Expected:** The row shows a link icon, the attachment's title, and the URL text below the title in a smaller font. The row has a delete affordance and no open affordance (URL attachments have no file behind them to open).

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

#### `AUTH-FIRSTRUN-04` — The login screen appears on a cold, empty install

**confirmed** · P1 · `#AUTH-FIRSTRUN`

**Given:** A clean install — no account, no previous app data, and an empty session store — with the network on.

Steps:

1. Install the app over a clean state, discarding whatever the previous run left.
2. Launch it and touch nothing.
3. Wait for the restore to settle.

**Expected:** The login screen appears within the restore budget, with "Continue Offline" present, and the loading indicator gone. A session store that hangs or cannot be read ends at this screen rather than at a spinner the user cannot dismiss. This is REQ-UA-015's user-visible half: whatever the store is doing, the first screen arrives and offers a way in.

#### `AUTH-FIRSTRUN-05` — The app starts when the session store cannot be read

**confirmed** · P1 · `#AUTH-FIRSTRUN`

**Given:** An installed app whose session store has been written at least once, so there is a real encrypted file to damage.

Steps:

1. Launch the app once so the store is created, then stop it.
2. Damage the store file so it can no longer be decrypted.
3. Launch the app again and wait for the restore to settle.

**Expected:** The login screen appears within the restore budget, with the loading indicator gone, and the store is left in a state a later launch can read. The app is usable afterwards, not merely open: a recovery that ends in a spinner has not recovered anything. This is REQ-UA-015's half for a store that cannot be read.

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

### feature.backup

#### `BACKUP-01` — A task survives a create-backup, wipe and restore round trip

**confirmed** · P1 · `#BACKUP`

**Given:** A task exists, so the restore has something to bring back and an empty result cannot pass as success.

Steps:

1. Create a backup from Settings.
2. Clear the app's data.
3. Restore from the backup.

**Expected:** The task is present again after the wipe, so the backup carried the data outside the process rather than the restore re-reading live state.

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

#### `CAL-SYNC-SYSTEM-02` — System calendar sync can be switched on where a provider exists

**confirmed** · P1 · `#CAL-SYNC-SYSTEM`

**Given:** Settings → Calendar is open and the system calendar is the selected provider, on a platform where a system calendar provider is actually reachable. This is the complement of CAL-SYNC-SYSTEM-01, which covers the case where it is not.

Steps:

1. Look at the system-calendar panel.
2. Switch system calendar sync on and read the status.
3. Switch it off and read the status again.

**Expected:** When a provider exists the panel offers a working enable switch rather than the unavailable message, and the status follows the switch in both directions — so turning sync on actually turns it on. The Google half stays usable throughout; the two providers are alternatives, and neither may hide the other.

#### `CALENDAR-01` — The Month view renders the current month with weekday columns

**confirmed** · P1 · `#CALENDAR`

**Given:** The Calendar tab is open.

Steps:

1. Open the Calendar tab.

**Expected:** The current month renders as weekday columns of numbered days, from the first of the month to the last — so the grid is complete rather than showing a partial page. The mode control reads Month.

#### `CALENDAR-02` — The view-mode control switches modes and reports the selection

**confirmed** · P2 · `#CALENDAR`

**Given:** The Calendar tab is open in Month view.

Steps:

1. Open the mode control.
2. Choose a different mode.

**Expected:** The view changes and the control reports the mode now selected, so a user can tell where they are without counting the shape of the grid. The control carries the mode label — the screen title does not, and does not reliably do so in every mode.

#### `CALENDAR-03` — Tapping a day in Month view opens that day's view

**confirmed** · P1 · `#CALENDAR`

**Given:** The Calendar tab is open in Month view.

Steps:

1. Tap a numbered day in the grid.

**Expected:** That day's view opens and the date header names the day that was tapped, so the user lands on the day they chose. The mode control follows the navigation into the single-day view rather than still reporting Month.

### feature.lifecycle

#### `LIFECYCLE-01` — Data survives a process kill and a relaunch that does not clear state

**confirmed** · P1 · `#LIFECYCLE`

**Given:** Data has been created in the app.

Steps:

1. Kill the app's process.
2. Relaunch it without clearing state.
3. Look for the data that was there before the kill.

**Expected:** The data is still present, so it was written to storage rather than held in memory. Reopening after a kill looks the same as reopening after a normal exit — an app that saved only on clean exit would show an empty screen here and no other flow in this repository would notice.

#### `LIFECYCLE-02` — The task editor stays open and keeps its content through rotation

**confirmed** · P2 · `#LIFECYCLE`

**Given:** A task editor is open with a title entered.

Steps:

1. Rotate the device while the editor is open.
2. Look at the editor afterwards.

**Expected:** The editor is still open and still usable, and the title is still in it — so a configuration change did not discard what the user was typing.

### feature.negative

#### `NEGATIVE-01` — The Inbox empty state names what is missing

**confirmed** · P1 · `#NEGATIVE`

**Given:** No tasks exist. A clean launch, not a filtered list that happens to be empty — the two are different states and only the first is this scenario.

Steps:

1. Open Inbox with no tasks.

**Expected:** An empty state is shown saying what the screen is empty of, rather than a blank list that is indistinguishable from a screen that failed to load.

#### `NEGATIVE-02` — The notes list shows an empty state

**confirmed** · P1 · `#NEGATIVE`

**Given:** No notes exist.

Steps:

1. Open the notes list with nothing in it.

**Expected:** The list shows its empty state instead of rendering as an empty area with no message.

#### `NEGATIVE-03` — The Plans tab shows an empty state with no projects

**confirmed** · P1 · `#NEGATIVE`

**Given:** No projects exist.

Steps:

1. Open the Plans tab with nothing in it.

**Expected:** The tab shows its empty state rather than an unexplained blank area — a tab with nothing to show still has to say so.

#### `NEGATIVE-04` — The Archive screen shows an empty state

**confirmed** · P1 · `#NEGATIVE`

**Given:** Nothing has been archived.

Steps:

1. Open the Archive screen with nothing in it.

**Expected:** The screen shows an empty state rather than being blank, so a user who arrives there by accident is told the archive is empty instead of being left to guess whether the screen works.

#### `NEGATIVE-05` — Search distinguishes an idle prompt from no matches

**confirmed** · P2 · `#NEGATIVE`

**Given:** The Search screen is open with no query entered.

Steps:

1. Open Search with nothing typed.
2. Search for a string that matches nothing.

**Expected:** The empty query shows a prompt asking the user to search, and the failed search says that nothing matched — so the second state does not invite the user to type again when they already have.

#### `NEGATIVE-06` — The Tags tab shows an empty state

**confirmed** · P1 · `#NEGATIVE`

**Given:** No tags exist.

Steps:

1. Open the Tags tab of Settings with nothing in it.

**Expected:** The tab shows its empty state. The assertion is on the screen rather than on the list container, because the container's test tag does not surface where a selector-based assertion would look for it.

### feature.notes

#### `NOTES-01` — A note is created from the notes list

**confirmed** · P1 · `#NOTES`

**Given:** No notes exist yet, so the created note is unambiguously the one that was just made.

Steps:

1. Open the Menu sheet and go to Notes.
2. Type into the quick-add row and submit it with the keyboard's Done action.

**Expected:** The note exists in the list and can be opened, so creation is reachable from the notes list itself — a user is never asked to leave the screen to write something down.

#### `NOTES-02` — An edited note body survives reopening the note

**confirmed** · P1 · `#NOTES`

**Given:** A note exists.

Steps:

1. Open the note and go to its editor via the Edit action on the preview.
2. Change the body.
3. Leave the note and reopen it.

**Expected:** The edited body is still there, so the autosave happened and the text is not lost on the way out. Quick-add lands on the preview rather than the editor, which is why the edit is a separate action here.

#### `NOTES-03` — The All, Pinned and Archived chips show different sets

**confirmed** · P2 · `#NOTES`

**Given:** Two notes exist, neither pinned and neither archived.

Steps:

1. Open the notes list on the All chip.
2. Switch to Pinned.
3. Switch to Archived.

**Expected:** All shows both notes and the other two chips show neither, so each filter narrows the list to its own set rather than leaving it unchanged.

### feature.pomodoro

#### `POMODORO-01` — A fresh Pomodoro timer sits paused on work cycle 1

**confirmed** · P2 · `#POMODORO`

**Given:** A clean install with an empty task list. The chips are populated by the task provider, so no chip exists here — this scenario deliberately covers the no-task case, which is the first thing a new user sees.

Steps:

1. Open the Pomodoro tab.
2. Look at the timer and its controls without touching them.

**Expected:** The phase reads Work, the cycle counter reads Cycle 1, and the remaining duration shows the full work block — the timer starts paused, not running. Stop, play and skip are all present, so every control is reachable before the user has decided anything.

#### `POMODORO-02` — Selecting a task chip starts the timer on that task

**confirmed** · P1 · `#POMODORO`

**Given:** At least one task exists, so the chip list is non-empty. The flow seeds one task via `Maestro/helpers/seed-task.yaml`; without a seed the tab renders and this scenario would pass for the wrong reason, asserting nothing about selection at all.

Steps:

1. Open the Pomodoro tab.
2. Select the chip labelled with the seeded task.

**Expected:** The timer names the selected task as the focus, and the work phase is already running — selection starts the timer, so no separate play action is needed or offered for this path.

#### `POMODORO-03` — Pause and resume survive the phase and cycle state

**confirmed** · P1 · `#POMODORO`

**Given:** At least one task exists and the Pomodoro tab is open.

Steps:

1. Select the seeded task's chip to start the timer.
2. Pause the running timer.
3. Resume it.

**Expected:** The timer and the Work phase are still shown after both transitions, so a pause suspends the countdown rather than discarding it or resetting the phase.

#### `POMODORO-04` — Skipping a work block starts a break and advances the cycle

**confirmed** · P1 · `#POMODORO`

**Given:** At least one task exists and the Pomodoro tab is open, on cycle 1.

Steps:

1. Select the seeded task's chip to start the work block.
2. Skip the running work block.

**Expected:** A break phase begins, the cycle counter advances from Cycle 1 to Cycle 2, and the completed-work count reads one — skipping counts as a completed work block, not as an abandoned one.

#### `POMODORO-05` — Stopping the session releases the focus task

**confirmed** · P1 · `#POMODORO`

**Given:** At least one task exists. The flow seeds two and starts a session on one of them, because "stop" only has something to release once a chip is bound.

Steps:

1. Select a task's chip and skip past the work block into a break.
2. Stop the session.

**Expected:** The timer returns to an idle, paused state with the play control showing again, and the previously focused task is no longer named as the focus — stopping ends the session rather than just pausing it.

### feature.profile

#### `PROFILE-01` — A second profile is created and listed

**confirmed** · P1 · `#PROFILE`

**Given:** The Menu sheet is open and only the default profile exists.

Steps:

1. Choose to create a profile from the Menu sheet.
2. Supply a name and confirm.

**Expected:** The new profile is listed alongside the default, so a user can reach the second profile the moment they have made it.

#### `PROFILE-02` — Tasks in one profile are invisible in another

**confirmed** · P1 · `#PROFILE`

**Given:** A second profile exists.

Steps:

1. Switch to the second profile and seed a task into it.
2. Switch to the default profile.
3. Look for the seeded task.
4. Switch back to the second profile.

**Expected:** The seeded task is not visible from the default profile and is visible again after switching back, so switching changes what is shown without destroying what was there.

### feature.projects

#### `PROJECTS-01` — A project's detail screen shows its own task list

**confirmed** · P1 · `#PROJECTS`

**Given:** A project exists.

Steps:

1. Open the project from the Plans tab.

**Expected:** The project's detail screen opens and shows a task list belonging to that project, initially empty.

#### `PROJECTS-02` — A task added from a project's detail is listed under it

**confirmed** · P1 · `#PROJECTS`

**Given:** A project's detail screen is open with no tasks.

Steps:

1. Type into the quick-add field and submit it with the keyboard's Done action.
2. Look at the list.

**Expected:** The empty state is left and the new task appears under that project, addressed by its own title — so a task added here belongs to this project rather than landing in an ungrouped list.

### feature.search

#### `SEARCH-01` — Searching for a task's title finds the task

**confirmed** · P1 · `#SEARCH`

**Given:** A task exists with a distinctive title nothing else shares, so the result is unambiguously the seeded task.

Steps:

1. Open the quick search from the menu sheet.
2. Type the task's title.

**Expected:** The seeded task appears in the results, so the user can get back to something they stopped thinking about weeks ago without opening every list in the app.

#### `SEARCH-02` — Cyrillic query text is rendered and matched correctly

**confirmed** · P2 · `#SEARCH`

**Given:** A task with a Cyrillic title exists in the database.

Steps:

1. Open the quick search from the menu sheet.
2. Type the task's Cyrillic title using the ASCII-equivalent keystrokes.
3. Observe the task with the Cyrillic title in the results.

**Expected:** The task with the Cyrillic title appears correctly in the results with its Cyrillic characters rendered as Cyrillic, not as replacement characters or garbled text.

#### `SEARCH-03` — Clearing the search query returns to the idle prompt

**confirmed** · P1 · `#SEARCH`

**Given:** The search screen is open with a non-empty query that produced results.

Steps:

1. With a query that produced results visible, erase the query text.
2. Observe the results area and the prompt.

**Expected:** The results disappear and the idle prompt "Enter a search query" returns. The idle prompt is shown when the query is empty; it is not shown when a non-empty query produced zero results.

#### `SEARCH-04` — Filter by has-description toggles task results

**confirmed** · P1 · `#SEARCH`

**Given:** Two tasks exist: one with a description field set, one without.

Steps:

1. Open quick search and type a query that matches both tasks.
2. Both tasks appear in results.
3. Open the filter sheet and enable the "Has description" filter.
4. Apply the filter.
5. Observe the results.

**Expected:** Only the task that has a description is shown; the task without a description is filtered out. Toggling the filter off restores all results.

#### `SEARCH-05` — Filter by pinned toggles task results

**confirmed** · P1 · `#SEARCH`

**Given:** Two tasks exist: one pinned, one not.

Steps:

1. Open quick search and type a query that matches both tasks.
2. Both tasks appear in results.
3. Open the filter sheet and enable the "Pinned" filter.
4. Apply the filter.
5. Observe the results.

**Expected:** Only the pinned task is shown; the unpinned task is filtered out. Toggling the filter off restores all results.

#### `SEARCH-06` — A query with no matches shows an explicit no-results state

**confirmed** · P1 · `#SEARCH`

**Given:** The app is on the Search screen with the input focused and empty.

Steps:

1. Verify the idle prompt "Enter a search query" is shown.
2. Type a query that cannot possibly match any content in the database.
3. Observe the state after results are computed.

**Expected:** An explicit "No results found" state is shown. This is distinct from the idle prompt (which is shown when the query is empty). The no-results state invites the user to try a different query, not to type more of the same one.

#### `SEARCH-07` — A saved search persists and can be loaded

**confirmed** · P2 · `#SEARCH`

**Given:** The Search screen is open with a query that has produced results.

Steps:

1. With results visible, tap the save/search icon in the top bar.
2. Name the saved search and confirm.
3. Verify the "Search saved" snackbar appears.
4. Verify the saved search chip appears in the saved searches row.
5. Navigate away to the Today tab.
6. Open quick search again.
7. Tap the saved search chip.
8. Observe the restored query and results.

**Expected:** The saved search persists across navigation. Tapping it restores the query text and the matching results, so the user does not have to re-type a frequently-used search.

### feature.settings

#### `SETTINGS-01` — Changing the theme mode leaves Settings intact and navigable

**confirmed** · P2 · `#SETTINGS`

**Given:** Settings > Interface is open with the three-way System / Light / Dark selector visible.

Steps:

1. Change the theme mode.
2. Continue using the screen.

**Expected:** Settings stays intact and navigable after the change, so selecting a theme cannot strand the user on a broken screen. The applied theme itself is not asserted — see the note above.

#### `SETTINGS-02` — The notification surface renders its controls

**confirmed** · P2 · `#SETTINGS`

**Given:** Settings is open.

Steps:

1. Open the Notifications tab.

**Expected:** Its controls are present and the screen is usable — the notification settings are reachable from Settings. Whether a given toggle changes what the app actually sends is not asserted.

#### `SETTINGS-03` — Every tab in the Settings rail opens without crashing

**confirmed** · P2 · `#SETTINGS`

**Given:** The app is running; Settings is reached from the Menu sheet.

Steps:

1. Open Settings from the menu sheet.
2. Visit every tab in the rail.

**Expected:** Each tab opens without a crash, so every Settings section is reachable. A crash on any one tab would make the rest of the screen unreachable too.

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

#### `SYNC-LOCAL-01` — A task created with no connectivity is kept and appears once back online

**confirmed** · P1 · `#SYNC-LOCAL`

**Given:** The device has no connectivity.

Steps:

1. Create a task while offline.
2. Restore connectivity.
3. Look for the task.

**Expected:** The task is still there, so work done without a network was written to local storage rather than queued in memory and lost. This claims the local guarantee only — nothing here says the task reached a server, which is SYNC-OFFLINE-01's separate subject.

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

#### `TASK-01` — Create a task from the FAB and see it in the Inbox

**confirmed** · P1 · `#TASK`

**Given:** An empty task database, app on the Inbox tab.

Steps:

1. Tap the FAB to open the task composer.
2. Enter a title and save.

**Expected:** The task appears in the Inbox list. A task saved without a due date lands in the Inbox (No Date section), not in any date-based section.

#### `TASK-02` — Tapping the checkbox toggles the task's completion state

**confirmed** · P1 · `#TASK`

**Given:** A task exists in the Inbox.

Steps:

1. Tap the task's checkbox.

**Expected:** The checkbox appears checked. The row shows the completed visual state. Tapping again toggles it back to unchecked.

#### `TASK-03` — Tapping a task row opens its detail screen with the correct title

**confirmed** · P1 · `#TASK`

**Given:** A task with a known title exists in the Inbox.

Steps:

1. Tap the task row.
2. Verify the editor shows the task's title (proving the correct task opened).
3. Press back.

**Expected:** The editor opens for the correct task. After navigating back, the task row is still present and the agenda is displayed.

#### `TASK-04` — Delete a task from its detail screen overflow menu

**confirmed** · P1 · `#TASK`

**Given:** A task exists in the Inbox.

Steps:

1. Tap the task to open its detail screen.
2. Open the overflow menu and tap Delete.
3. Navigate back to the agenda.

**Expected:** The task row is no longer present in the list. No confirmation dialog is shown — deletion is immediate and reversible via Undo.

#### `TASK-05` — Deleting a task shows an Undo snackbar and Undo restores it

**confirmed** · P1 · `#TASK`

**Given:** A task exists in the Inbox.

Steps:

1. Tap the task to open its detail screen.
2. Open the overflow menu and tap Delete.
3. Tap the Undo action on the snackbar.
4. Navigate back to the agenda.

**Expected:** The Undo snackbar appears immediately after deletion. Tapping Undo restores the task to its original position in the list.

#### `TASK-06` — Long-press opens context menu; archive removes the task

**confirmed** · P1 · `#TASK`

**Given:** A task exists in the Inbox.

Steps:

1. Long-press the task row to open the context menu sheet.
2. Verify the sheet is the context menu (editor is not visible).
3. Verify Archive, Open, and Mark as completed actions are present.
4. Tap Mark as completed to mark the task done.
5. Long-press the row again and tap Archive.

**Expected:** The task is removed from the Inbox list. The context menu does not open the editor; it only dispatches the selected action.

#### `TASK-07` — Set task priority from the editor attribute row

**confirmed** · P1 · `#TASK`

**Given:** A task exists and its detail/editor screen is open.

Steps:

1. Scroll to and tap the Priority row.
2. Choose High priority from the picker.

**Expected:** The row label changes to "High priority" and the task row in the agenda shows the high-priority badge. The change persists after closing the editor.

#### `TASK-08` — Set task due date from the editor attribute row

**confirmed** · P1 · `#TASK`

**Given:** A task exists and its detail/editor screen is open.

Steps:

1. Scroll to and tap the Due date row.
2. Pick a day from the date picker and confirm.

**Expected:** The row label changes from "Add date" to the ISO date string of the selected day. The task moves to the matching date bucket in the agenda.

#### `TASK-09` — Cancelling the date picker leaves the task undated

**confirmed** · P1 · `#TASK`

**Given:** A task exists and its detail/editor screen is open.

Steps:

1. Tap the Due date row to open the date picker.
2. Select a day but tap Cancel instead of OK.

**Expected:** The row label stays as "Add date". No date is saved and the task remains undated.

#### `TASK-10` — Empty title is rejected; original title remains intact

**confirmed** · P1 · `#TASK`

**Given:** A task with a known title exists.

Steps:

1. Open the task in the editor.
2. Select all text in the title field and erase it.
3. Attempt to save by navigating back.

**Expected:** The editor stays open with the title field empty. The task retains its original title and reappears in the list with that title unchanged. The domain layer rejects the empty string at save time.

#### `TASK-11` — Cyrillic task title is created, saved, and displayed correctly

**confirmed** · P2 · `#TASK`

**Given:** An empty task database.

Steps:

1. Seed a task with a Cyrillic title and a due date of today.
2. Navigate to the Today agenda tab.

**Expected:** The task appears with its Cyrillic title intact (no mojibake or replacement character) and in the correct agenda section.

#### `TASK-12` — Create a task with all attribute fields filled

**confirmed** · P1 · `#TASK`

**Given:** The app is on the Inbox tab with a clean database.

Steps:

1. Tap the FAB to open the composer.
2. Verify all attribute rows are visible (priority, due date, start date, project, tags, recurrence, and pin).
3. Fill in a title, set priority to High, and set due date to Today.
4. Save the task.

**Expected:** The task appears in the Today section with the correct title and the high-priority badge. All attribute rows are present and settable.

#### `TASK-13` — Tasks appear in the correct agenda date-bucket sections

**confirmed** · P1 · `#TASK`

**Given:** An empty task database.

Steps:

1. Seed three tasks: one due yesterday, one due today, one due tomorrow.
2. Navigate to the Today agenda tab.

**Expected:** The Overdue section contains the yesterday task, the Today section contains today's task, and the Upcoming section contains the tomorrow task. Each section renders its section header testTag.

#### `TASK-14` — FAB from Today tab pre-fills the due date to Today

**confirmed** · P1 · `#TASK`

**Given:** The app is on the Today agenda tab.

Steps:

1. Tap the FAB to open the task composer.
2. Verify the Due date row shows today's date pre-filled.
3. Enter a title and save.

**Expected:** The task is saved with today's date and appears in the Today section. After saving, the user is returned to the Today tab.

#### `TASK-15` — FAB from Inbox tab does NOT pre-fill a due date

**confirmed** · P1 · `#TASK`

**Given:** The app is on the Inbox agenda tab.

Steps:

1. Tap the FAB to open the task composer.
2. Verify the Due date row shows no date (placeholder "Add date").
3. Enter a title and save.

**Expected:** The task is saved with no due date and appears in the No Date section. After saving, the user is returned to the Inbox tab.

#### `TASK-16` — Creating from a tab returns to that tab after save

**confirmed** · P1 · `#TASK`

**Given:** An empty task database.

Steps:

1. From the Today tab, open the FAB, enter a title, and save.
2. Verify the task appears in Today and the Today tab is still active.
3. Switch to the Inbox tab.
4. Open the FAB, enter a different title, and save.
5. Verify the task appears in No Date and the Inbox tab is still active.

**Expected:** The save operation returns to the originating tab. The Today task is in the Today section and the Inbox task is in the No Date section.

#### `TASK-17` — Start and stop a time-tracking session on a task

**confirmed** · P2 · `#TASK`

**Given:** A task exists and the app is signed in. The task's detail screen is open and scrolled to the time-tracking section.

Steps:

1. Tap the Start chip.
2. Verify the chip changes to Stop and the elapsed timer is visible.
3. Tap the Stop chip.

**Expected:** The chip swaps from Start to Stop in place while the timer is running, and returns to Start when stopped. No crash occurs across the state transitions. (A signed-in session is required; anonymous or dev sessions show an error state instead of the Start chip.)

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

#### `TASK-SUB-01` — Subtask lifecycle — add, toggle, delete

**confirmed** · P1 · `#TASK-SUB`

**Given:** A task exists and its detail view is open. The SubtasksSection is shown even when the task has no subtasks (the "add subtask" row is always present when the section is visible).

Steps:

1. In the SubtasksSection, type a subtask title in the "Add subtask" input.
2. Submit the input (press Enter or tap Add).
3. Verify the subtask appears in the list.
4. Tap the subtask row to toggle its completed state.
5. Verify the subtask's completed state changes.
6. Tap the delete (×) button on the subtask row.
7. Verify the subtask is removed from the list.

**Expected:** The subtask survives add → toggle → delete. Its parentTaskId is set correctly so it appears as a child in the SubtasksSection and nowhere else in the agenda unless "Show subtasks" is enabled.

#### `TASK-TIME-01` — Start a timer on a task from its detail view

**confirmed** · P1 · `#TASK-TIME`

**Given:** A task exists, the app is signed in, and the task's detail view is open.

Steps:

1. Open a task and scroll to the time-tracking section.
2. Start the timer.
3. Stop it again.

**Expected:** The chip swaps between "Start" and "Stop" in place, and the elapsed total for that task grows while the timer runs.
