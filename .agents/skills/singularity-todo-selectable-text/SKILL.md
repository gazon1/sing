---
name: singularity-todo-selectable-text
description: How to render text a person can copy, and how to make the links in it tappable, in Singularity Todo. Covers the two widgets (SelectableText, LinkifiedText), the selection groups and the exceptions, the app's link vocabulary in core/links, the deep-link versus internal-link distinction, and the inventory table that classifies every text surface.
---

# Selectable Text and Tappable Links

Text a person reads should be **selectable** — they should be able to copy an error
message, a backup id, a project name. And when the text carries a link, the link
should be **tappable**. These two are the same decision in two parts, and this skill
is the record of which surfaces get which.

## 1. The decision a text surface makes

| Treatment | What the person gets | Widget |
|---|---|---|
| **selectable** | the text can be selected and copied | `SelectableText` |
| **linkified** | the text's links can be tapped, and it can be copied | `LinkifiedText` |
| **excluded** | neither — and the reason is written down in the inventory | — |

The third row is not a shrug. A row whose long press already opens a menu cannot also
start a text selection: the gesture is spoken for. That is a decision somebody makes on
purpose, and it is written into the inventory below with its reason.

**The inventory is the truth.** `SelectableTextConventionTest` reads the table in this
file and holds it against the code: a file that uses a widget and is not in the table
fails the build, and a row that does not match the code fails too. The table is not
derived from the code — writing it down is the point.

## 2. The widgets

Both live in `core/ui/text/`.

```kotlin
SelectableText(text, modifier, style, color, maxLines, overflow)   // one text, selectable
SelectableTextGroup(modifier) { … }                                // a block, selectable as one
UnselectableContent { … }                                          // an interactive child, excluded
LinkifiedText(text, modifier, style, color, onInternalLink)        // one text, with tappable links
```

### SelectableText is one text, not a list

`SelectableText` creates a `SelectionContainer`, and a selection container owns a
selection state. Putting one around every row of a list gives every row its own
selection manager, and the selection does not survive the row scrolling out of view.

For a block of text that belongs together — a heading and the lines under it — use
`SelectableTextGroup`: one container for the block, so the person can select across it.

### UnselectableContent is for the interactive child

A button inside a selectable block would be swallowed by a long press that was meant to
start a selection. Wrap it in `UnselectableContent` and it keeps its own gesture.

A text that carries its own selection container keeps it: a nested container has its own
registrar, so wrapping *that* in `UnselectableContent` would not take its selection away.

### LinkifiedText is selectable too

`LinkifiedText` wraps its text in a selection container as well: a person who wants to
copy a sentence that contains a link should not have to give up the link to do it.

## 3. The links

### Two vocabulararies, not one

| Vocabulary | Shape | Opens | Owner |
|---|---|---|---|
| **deep link** | `singularity://view/{id}` | a screen, in a tab | the feature that owns the screen |
| **internal link** | `note://{id}`, `task://{id}` | an entity | `core/links` |

A deep link arrives from outside the app — a notification, a QR code, a browser. An
internal link is written by a person into a note or a task.

### One owner for the internal vocabulary

`shared/src/commonMain/kotlin/com/singularity/todo/core/links/InternalLinks.kt` is the only place that knows the schemes and what an
identifier may contain. Everything else reads them from there:

- `InternalLinks.parse(url)` — reads a link, or null if it is not ours
- `InternalLinks.findIn(text)` — finds the internal links in a piece of text
- `InternalLinks.urlFor(scheme, id)` — builds one

A scheme that is not ours is not ours: `xnote://id` is a link to somebody else's thing,
and the app must not open it as its own.

### How a link is opened

`rememberAppUriHandler` wraps the platform's `UriHandler`: an internal link stays in
the app, everything else goes out to the browser. A screen that wants its own behaviour
for internal links passes its own callback:

```kotlin
val uriHandler = rememberAppUriHandler { link ->
    when (link.target.scheme) {
        InternalLinkSchemes.NOTE -> onNavigateToNote(NoteId.fromString(link.target.id))
        InternalLinkSchemes.TASK -> onNavigateToTask(TaskId.fromString(link.target.id))
    }
}
```

The app-wide default comes from `LocalInternalLinkHandler`, which the app's root
provides. The note preview does *not* use it: a note links to another note, and moving
between the two inside the notes graph is that screen's business.

## 4. The inventory

One row per file. The treatment is what the file's texts have; the reason is filled in
when the answer is "excluded".

| File | Treatment | Reason |
|---|---|---|
| `core/ui/components/MessageBubble.kt` | linkified | the assistant's answer is prose, and prose is where links turn up |
| `feature/genui/render/material3/atoms/TextRenderer.kt` | linkified | a generated screen's text; the model's own words may carry links |
| `feature/tasks/presentation/components/detail/TaskDetailProposalSection.kt` | linkified | the model's summary of what it proposes |
| `core/ui/components/EmptyState.kt` | selectable | the empty state's title and subtitle are a message; the action keeps its own gesture |
| `feature/agenda/presentation/screen/SavedAgendaContent.kt` | selectable | the missing-view report is a message the person reads |
| `feature/agenda/presentation/screen/SavedAgendaScreen.kt` | selectable | the dialog explains the pattern it matches; the mode names stay labels |
| `feature/ai/usage/AiUsageScreen.kt` | selectable | the model id and the tool name; the token counts are not |
| `feature/attachments/annotation/AttachmentAnnotationPanel.kt` | selectable | the quote and the annotation's text; the input fields are not touched |
| `feature/attachments/components/AttachmentsSheet.kt` | selectable | the empty state tells the person what is missing |
| `feature/attachments/viewer/AttachmentViewerHost.kt` | selectable | the failure message names the type it cannot open |
| `feature/attachments/viewer/AttachmentViewerRoute.kt` | selectable | the failure message |
| `feature/attachments/viewer/TextViewerScreen.kt` | selectable | it already selects the text — it wraps the viewer in a selection container of its own |
| `feature/auth/ConfirmationPendingContent.kt` | selectable | the instruction the person has to follow |
| `feature/auth/LoginScreen.kt` | selectable | the body explains where the key comes from; the headline stays a label |
| `feature/auth/presentation/AttachAccountScreen.kt` | selectable | the explanation of what the account is for |
| `feature/auth/presentation/MagicLinkConfirmScreen.kt` | selectable | the instruction that says what to do next |
| `feature/backup/BackupScreen.kt` | selectable | the backup id, which a person has to write down somewhere |
| `feature/calendar_sync/presentation/CalendarAppPicker.kt` | selectable | the message that reports the fallback |
| `feature/calendar_sync/presentation/CalendarSyncSettingsScreen.kt` | selectable | the failure messages — the ones a person has to read out to support |
| `feature/checklist/components/ChecklistEditorSheet.kt` | selectable | the empty state tells the person what is missing |
| `feature/notes/presentation/screen/NotePreviewScreen.kt` | selectable | the note body; its links are the note's own, handled by the screen |
| `feature/pomodoro/PomodoroScreen.kt` | selectable | the message that reports the capability is missing |
| `feature/profile/presentation/AccountSettingsScreen.kt` | selectable | the explanation of what the account keeps |
| `feature/profile/presentation/ChangePasswordScreen.kt` | selectable | the instruction the person has to follow |
| `feature/projects/presentation/components/ConfirmCloseSheet.kt` | selectable | the dialog body reports what is unfinished; the title and the buttons stay labels |
| `feature/projects/presentation/components/ConfirmDeleteSheet.kt` | selectable | the dialog body names the project; the title and the buttons stay labels |
| `feature/search/SearchScreen.kt` | selectable | the titles of the note, project and tag results |
| `feature/settings/screens/AgendaSettingsScreen.kt` | selectable | the empty state says where to create a view |
| `feature/settings/screens/AiProviderSettingsScreen.kt` | selectable | the provider and model ids; the key and the endpoint are input fields and stay as they are |
| `feature/settings/screens/FilesSettingsScreen.kt` | selectable | the explanation of where attachments are stored |
| `feature/statistics/StatisticsScreen.kt` | selectable | the project names; the numbers and the percentages are not |
| `feature/tags/components/TagsPickerSheet.kt` | selectable | the empty state tells the person what is missing |
| `feature/tags/presentation/screen/TagGroupsScreen.kt` | selectable | the dialog body says what happens to the tags; the title and the buttons stay labels |
| `feature/tasks/presentation/components/detail/DependencyPickerSheet.kt` | selectable | the empty state tells the person what is missing |
| `feature/tasks/presentation/components/detail/LinkedBacklinksCard.kt` | selectable | the titles of the notes that link back |
| `feature/tasks/presentation/components/detail/LogbookSection.kt` | selectable | note titles and time rows — the "add note" button is excluded from the selection |
| `feature/tasks/presentation/components/ProjectPickerSheet.kt` | selectable | the empty state tells the person what is missing |
| `feature/timetracking/presentation/components/TimeTrackingSection.kt` | selectable | the section's message |
| `feature/whatsnew/presentation/screen/WhatsNewScreen.kt` | selectable | the message that reports the dropped lines |
| `feature/agenda/presentation/components/SavedAgendaCard.kt` | excluded | the overflow menu's item labels |
| `feature/agenda/presentation/screen/AgendaContent.kt` | excluded | its rows are swipeable task rows with a long press |
| `feature/agenda/presentation/screen/SelectorParameterSheet.kt` | excluded | the section heading over the tag chips |
| `feature/archive/ArchiveScreen.kt` | excluded | its rows are task cards, and the gesture is theirs |
| `feature/checklist/components/ChecklistItemRow.kt` | excluded | the overflow menu's item label |
| `feature/notes/components/InternalLinkPickerSheet.kt` | excluded | the search field's placeholder |
| `feature/notes/components/NoteCard.kt` | excluded | the row swipes — the gesture belongs to the card |
| `feature/notes/presentation/screen/NotesListScreen.kt` | excluded | its empty state is rendered by the shared EmptyState component, which is selectable |
| `feature/projects/presentation/components/EditorParentPickerSheet.kt` | excluded | the picker's option label |
| `feature/projects/presentation/components/InheritedTagGroupsSheet.kt` | excluded | the sheet's heading |
| `feature/projects/presentation/screen/ProjectDetailBody.kt` | excluded | the quick-add field's placeholder |
| `feature/settings/screens/InterfaceSettingsScreen.kt` | excluded | the settings preview's sample text |
| `feature/settings/screens/WorkScheduleSettingsScreen.kt` | excluded | the settings preview's sample text |
| `feature/tasks/presentation/components/detail/RecurrencePickerSheet.kt` | excluded | the picker's option labels |
| `feature/tasks/presentation/components/detail/TaskDescriptionField.kt` | excluded | the description field's placeholder |
| `feature/tasks/presentation/components/detail/TaskDetailExtraSections.kt` | excluded | the action's label |
| `feature/tasks/presentation/components/detail/TaskEditorEstimateSheet.kt` | excluded | the picker's option labels |
| `feature/tasks/presentation/components/TaskCard.kt` | excluded | the row's long press opens the task's menu |

## 5. The rules

| Category | Rule |
|---|---|
| **A** | prose — an answer, a summary, a generated screen — is `linkified` |
| **B** | a value a person may need to copy — a title, an id, an error message — is `selectable` |
| **C** | a message that reports a failure is `selectable`: it is the one a person reads out to support |
| **D** | an identifier is `selectable`; the numbers around it are not |
| **E** | a text field is never `selectable` — see `singularity-todo-text-field-states` |
| **F** | a list row whose long press or swipe is spoken for is `excluded`, and the reason is written down |
| **G** | a label that is not content — a section heading, a counter — stays a plain `Text` |

## 6. Common mistakes

- **A `SelectionContainer` around every row of a list.** Every row then owns its own
  selection state, and the selection dies when the row scrolls away. Use
  `SelectableTextGroup` for the block.
- **A button inside a selectable block without `UnselectableContent`.** A long press
  meant for the button starts a selection instead.
- **`LinkifiedText` for a value that only has to be copied.** `LinkifiedText` also
  scans the text for links, which a task title does not need.
- **Parsing a link by hand.** `parseLinkUrl`, a local `when (prefix)`, a regex over
  `note://` — every one of those is a second copy of the vocabulary. Use
  `InternalLinks.parse`.
- **Treating a foreign scheme as ours.** `xnote://id` is not a note. The scheme has to
  be one of `InternalLinkSchemes.ALL`.
- **Making an input field selectable.** A field's text belongs to the field; see the
  text-field skill.

## 7. Where it is tested

| What | Where |
|---|---|
| the link vocabulary — schemes, identifiers, reading | `shared/src/commonTest/kotlin/com/singularity/todo/core/links/InternalLinksTest.kt` |
| that the two surfaces that read links agree | `shared/src/commonTest/kotlin/com/singularity/todo/core/links/InternalLinksAgreementTest.kt` |
| what counts as a link in a piece of text | `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/text/TextLinkDetectorTest.kt` |
| where an internal link points | `shared/src/commonTest/kotlin/com/singularity/todo/feature/nav/InternalLinkNavigationTest.kt` |
| that the inventory matches the code | `shared/src/jvmTest/kotlin/com/singularity/todo/arch/SelectableTextConventionTest.kt` |
| the prose that no row covers (advisory) | `scripts/check-selectable-text-coverage.py` |

ADR: `docs/decisions/2026-10-21-text-selection-and-link-dispatch.md`
