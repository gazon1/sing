# The text fields of the app: the inventory and the rewrite contract

The method lives in [`SKILL.md`](SKILL.md): how to pick a field's shape, how to
write it, how to filter at the point of entry, how to react to an edit, what the
bridge guarantees and does not, and how to test it. This file holds the per-field
data — which shape every text field in the app has, and who may rewrite it.

## The inventory: every field in the app

Who owns the text is the decision; this is where each field's answer lives. A field that
is not in this table has not been classified yet — classify it before you touch it.

**This table is the registry.** `TextFieldShapeConventionTest`
(`shared/src/jvmTest/.../arch/TextFieldShapeConventionTest.kt`) reads *this* table — there is
no second copy of it in the test — and compares it with the code: a file that owns a field
and is missing from this table fails the build, a row whose shape does not match the helper
the file uses fails the build, and a row whose file no longer exists fails the build.
Adding a field means adding a row here.

**The shape is derived from the code, not from its text.** The test reads the *imports*
of every file: a file that uses the bridged helper imports
`com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState`, because the
helper is in another package. A mention of a helper in a comment imports nothing, so a
comment cannot classify a file that owns no field — and a field cannot hide behind one.

Paths are relative to `shared/src/commonMain/kotlin/com/singularity/todo/`.

| File | Shape | Field |
|---|---|---|
| `core/ui/components/ChatInputBar.kt` | local | the input bar's state — the bar takes the state, the screen creates it |
| `feature/agenda/presentation/screen/SavedAgendaContent.kt` | bridged | the saved view's name |
| `feature/agenda/presentation/screen/SavedAgendaScreen.kt` | local | the regexp sheet's pattern |
| `feature/ai/chat/ChatScreen.kt` | local, bridged | the chat input (bridged) and the `ChatInputBar` states (local) |
| `feature/attachments/annotation/AttachmentAnnotationPanel.kt` | bridged | start, end, quote, note |
| `feature/attachments/components/AttachmentsSheet.kt` | local | the attachment's caption |
| `feature/auth/AuthFormFields.kt` | local | the shared email and password fields |
| `feature/auth/LoginScreen.kt` | local | the email and password fields |
| `feature/auth/presentation/AttachAccountScreen.kt` | local | the email field |
| `feature/auth/presentation/MagicLinkConfirmScreen.kt` | local | the code field |
| `feature/backup/BackupScreen.kt` | local | the archive's password |
| `feature/calendarSync/presentation/CalendarSyncSettingsScreen.kt` | bridged | the Google client id |
| `feature/checklist/components/ChecklistEditorSheet.kt` | local | the item's text |
| `feature/genui/render/material3/input/TextFieldRenderer.kt` | bridged | the GenUI input — the server can rewrite it (see the owned source) |
| `feature/notes/components/InternalLinkPickerSheet.kt` | local | the link's text |
| `feature/notes/presentation/screen/LinkUrlDialog.kt` | local | the URL |
| `feature/notes/presentation/screen/NoteEditorScreen.kt` | bridged | the title — the AI rewrites it |
| `feature/notes/presentation/screen/NotesListScreen.kt` | local, bridged | the quick-add row (local) and the search query (bridged) |
| `feature/profile/ProfileSwitcherScreen.kt` | local | the profile's name |
| `feature/profile/presentation/ChangePasswordScreen.kt` | local | the password field |
| `feature/projects/presentation/components/IconPickerSheet.kt` | local | the custom emoji |
| `feature/projects/presentation/screen/ProjectDetailBody.kt` | local | the two fields of the body |
| `feature/projects/presentation/screen/ProjectDetailHero.kt` | bridged | the name, the description |
| `feature/projects/presentation/screen/ProjectEditorScreen.kt` | bridged | the name, the description |
| `feature/projects/presentation/screen/ProjectsScreen.kt` | bridged | the search query |
| `feature/search/SearchScreen.kt` | bridged | the query |
| `feature/search/presentation/SaveSearchDialog.kt` | local | the saved search's name, its query |
| `feature/search/presentation/SavedSearchesRow.kt` | anchored | the rename field of a saved search |
| `feature/settings/screens/AiProviderSettingsScreen.kt` | local, bridged, anchored | the API key (local), the base URL (bridged), the provider and the model (anchored) |
| `feature/settings/screens/WorkScheduleSettingsScreen.kt` | anchored | the time |
| `feature/tags/components/AddTagDialog.kt` | local | the tag's name |
| `feature/tasks/presentation/components/detail/RecurrencePickerSheet.kt` | anchored | the interval amount, the day of month |
| `feature/tasks/presentation/components/detail/SubtasksSection.kt` | local | the new subtask |
| `feature/tasks/presentation/components/detail/TaskDetailExtraSections.kt` | local | the new checklist item |
| `feature/proposals/presentation/components/ProposalCard.kt` | local | the rejection reason |
| `feature/tasks/presentation/components/detail/TaskEditorContent.kt` | bridged | the title, the description — the detail editor's are owned, the create editor's are static |
| `feature/tasks/presentation/components/detail/TaskEditorEstimateSheet.kt` | local | the estimate |
| `feature/timetracking/presentation/components/TimeEntryEditorSheet.kt` | local | the note |

A local field is still *seeded* from a parameter — the rename dialog's current name, the
regexp sheet's pattern, the tag dialog's name. That is safe only because those
composables are created fresh each time they open (`if (showDialog) { … }`, or a `let` on
a nullable). A field that lives as long as the list does must not be seeded: the
rename field of a saved search is the worked example, and it is anchored for that reason.

## Who may rewrite this field?

The shape says who *owns* a field's text. It does not say what happens when somebody
*else* writes the value — a proposal applied to the note, a sync from another device, a
server writing a GenUI surface. That is where the loss happens: the field owns the
content it shows, so a rewrite it did not make reaches it only through the upstream's
value, and the bridge's echo guard drops a rewrite that lands on a value the field
*produced earlier* — it looks exactly like a lagging upstream.

**The answer is a type, and it is the source's type.** Every bridged field is handed a
source, and the source says who may replace the value:

- **`StaticText`** — the upstream replaces the value only with one the field never
  produced: it loads the value once, or it clears it. The echo guard is enough, and there
  is no counter.
- **`OwnedText`** — the upstream owns the value and may replace it with anything,
  including a value the field produced earlier. The source carries the count of the
  replacements, and the guard is lifted for the value that arrives with a new count.

**The kind is a property of the parameter, not a question the receiver answers.** A
component that serves both kinds — the task editor serves the create draft and the detail
draft — cannot say which kind of upstream it holds, so it is split: `TaskEditorContent`
takes an `OwnedText`, `TaskCreateEditorContent` takes a `StaticText`, and the shared body
takes the bridged states and never asks. The bridge has the same shape: two overloads, one
per kind, and **no overload that takes the sealed parent and dispatches on it**. A caller
holding the wrong kind does not compile, and a reader of the call site sees the kind
without opening the type.

**The sealed parent is nobody's parameter, anywhere in the tree.** The contract test says
so over every function of `commonMain`, not only over the bridge: a parameter of the
sealed parent is a promise the compiler cannot keep — anyone holding one may pass it on,
and the receiver has to decide what to do with it.

**The static promise lives in the owner's type.** `StaticText` says *what the bridge does*
with a value; it does not say *what the owner may write*. A `String` in the owner's state
says nothing at all: a reviewer cannot tell a seed from a replacement, and the compiler
cannot either. So a static owner does not hold a `String`. It holds a `FieldOwnedText`,
whose writes are named:

| Write | Means |
|---|---|
| `seeded(value)` | the owner loads the value from outside — once, on open |
| `echoed(fieldText)` | the owner hands back what the field produced — a keystroke |
| `cleared()` / `empty()` | the owner empties the field — the search query is cleared |

The names are the guarantee, not the types: `seeded` and `echoed` both take a `String`, and
nothing stops an owner from seeding a value the field produced. What the type buys is that
every write *says which kind it is*, so the honest claim is "every write is named" and not
"the owner cannot lie". A `FieldOwnedText` is also the thing a static row is checked
against: the contract test asks the owner for a property of that type, so an owner that
still holds a plain `String` fails the build.

**One shape of the lie is closed.** A seed may not carry the field's own text:
`FieldOwnedTextSeedContractTest` fails the build when a `seeded(...)` call reads `.text` out
of a field. A value read out of a field is a value the field produced, and the bridge's echo
guard drops it — the owner replaces the field and the field keeps the old text. A value the
field produced *earlier* and that the owner now holds as a plain `String` is still invisible
to a text scan; that one is `echoed`.

**The write is named where the provenance is known.** Most owners see the field's own text
at the place where the field's callback lands, and the value that arrives from outside at
the place where the upstream loads it — both in the owner. The AI provider settings are the
exception: their value lives in a persisted store, so the store hands the field's own text
back after the user typed it, and the store cannot tell that text from a value it loaded.
Their intent therefore carries a `FieldOwnedText` rather than a string, and the screen —
which knows — names the write.

`Revisioned<T>` is the other half of the same idea, and it is a different kind of
counter. `FieldOwnedText` has no count: a static upstream never replaces a value with one
the field produced, so the echo guard is enough. `Revisioned<T>` carries the count of the
replacements, because an owned upstream may replace a value the field produced earlier.

**The owner builds the source; the field passes it through.** A field that built its own
source would be answering a question it cannot know the answer to. The owner is the
component that holds the value and knows who else writes it — a ViewModel's state, a
draft.

**The read carries the count.** A field bound to a path in a surface's data model reads
it through `textSource(path, fallback)` — the value *and* the count of the server's writes
to that path, in one object. The context's other read of a path is
`valueWithoutCount(path)`, and it is named for what it does not carry: it is for values
that are not text and are not rewritten from outside — a checkbox's flag, a due date on a
card. A text field that reads through it gets a value with no count, and a rewrite that
lands on a value the field had already sent is then dropped as an echo. The two reads
look alike when the second one is called `value`, so it is not called `value`.
`TextFieldSourceContractTest` holds the line: a field that bridges a surface path reads
it through the source.

**The bridge has no `revision` parameter**, and that is the point. The count used to be an
argument with a default, and three failures came out of that: an upstream that owned the
value passed no count, an upstream passed a count that never moved (`revision = 0`), and a
field forgot the question altogether because the argument had a default. All three were
silent. A count that lives in the source cannot be frozen at the call site, and the
question cannot be left out — the source is required, and its type says what it means.

**This table is the registry.** `TextFieldRewriteContractTest`
(`shared/src/jvmTest/kotlin/com/singularity/todo/arch/TextFieldRewriteContractTest.kt`) reads *this* table —
there is no second copy of it in the test — and compares it with the
code: a file that owns a bridged field and is missing from this table fails the build, a
row whose owner does not declare a source of the claimed kind fails the build, a static row
whose owner does not hold a `FieldOwnedText` fails the build, an owned row
whose counter is never declared or never advanced fails the build, a file that builds its
own source fails the build, and a row whose file no longer exists fails the build. Adding
a bridged field means adding a row here.

**The evidence is the code's structure, not its text.** The bridged sites are the files
that *import* the bridged helper; the owner's source is a property with a type; the
counter is a property with a name; the bridge's parameters are a list of names. The two
checks that Konsist cannot express — a counter that *moves*, and a site that *constructs*
a source — are read from the declarations of the file with the comments stripped, so a
comment outside a declaration cannot make a counter move.

Paths are relative to `shared/src/commonMain/kotlin/com/singularity/todo/`.

| File | Field | Source | Owner | Counter | Advances |
|---|---|---|---|---|---|
| `feature/agenda/presentation/screen/SavedAgendaContent.kt` | the saved agenda's name | `StaticText` | `feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` | - | - |
| `feature/ai/chat/ChatScreen.kt` | the chat input | `StaticText` | `feature/ai/chat/ChatViewModel.kt` | - | - |
| `feature/attachments/annotation/AttachmentAnnotationPanel.kt` | the annotation draft (start, end, quote, note) | `StaticText` | `feature/attachments/annotation/AttachmentAnnotationViewModel.kt` | - | - |
| `feature/genui/render/material3/input/TextFieldRenderer.kt` | the bound text field | `OwnedText` | `feature/genui/render/DefaultDataContext.kt` | `feature/genui/schema/DataModel.kt` | `feature/genui/schema/DataModel.kt` |
| `feature/calendarSync/presentation/CalendarSyncSettingsScreen.kt` | the Google client id | `StaticText` | `feature/calendarSync/presentation/CalendarSyncViewModel.kt` | - | - |
| `feature/notes/presentation/screen/NoteEditorScreen.kt` | the note's title | `OwnedText` | `feature/notes/Ids.kt` | `feature/notes/Ids.kt` | `feature/notes/presentation/viewmodel/NoteEditor.kt` |
| `feature/notes/presentation/screen/NoteEditorScreen.kt` | the note's body | `OwnedText` | `feature/notes/Ids.kt` | `feature/notes/Ids.kt` | `feature/notes/presentation/viewmodel/NoteEditor.kt` |
| `feature/notes/presentation/screen/NotesListScreen.kt` | the notes search query | `StaticText` | `feature/notes/Ids.kt` | - | - |
| `feature/projects/presentation/screen/ProjectDetailHero.kt` | the project's name, the project's description | `OwnedText` | `feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt` | `feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt` | `feature/projects/presentation/viewmodel/ProjectDetailDraftState.kt` |
| `feature/projects/presentation/screen/ProjectEditorScreen.kt` | the project's name, the project's description | `StaticText` | `feature/projects/domain/state/ProjectEditorUiState.kt` | - | - |
| `feature/projects/presentation/screen/ProjectsScreen.kt` | the projects search query | `StaticText` | `feature/projects/domain/state/ProjectsUiState.kt` | - | - |
| `feature/search/SearchScreen.kt` | the search query | `StaticText` | `feature/search/SearchViewModel.kt` | - | - |
| `feature/settings/screens/AiProviderSettingsScreen.kt` | the base URL, the model, the system prompt | `StaticText` | `feature/settings/SettingsUiState.kt` | - | - |
| `feature/tasks/presentation/components/detail/TaskEditorContent.kt` | the task's title, the task's description (detail) | `OwnedText` | `feature/tasks/presentation/state/TaskDetailUiState.kt` | `feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt` | `feature/tasks/presentation/viewmodel/TaskDetailDraftState.kt` |
| `feature/tasks/presentation/components/detail/TaskEditorContent.kt` | the task's title, the task's description (create) | `StaticText` | `feature/tasks/domain/state/TaskDraft.kt` | - | - |

**The columns are checked, and the one that was prose is gone.** The **File** column is
checked against the import list: a row that names a file that no longer owns a bridged
field fails, and a file that owns one and has no row fails. The **Field** column is
checked against the file's own identifiers: a row that names a field the file does not
have fails. The **Source**, **Owner**, **Counter** and **Advances** columns are checked
against the owner's declarations.

The **Upstream** column is gone. It said, in prose, who may write the value — and prose
drifts: the column was maintained by hand, the scan was per file, and nothing proved that
the field the table named was the field the file had. The question it answered is now
answered twice over, by things that cannot drift:

- the **Source** column says what the upstream may do. A `StaticText` source is a
  promise that the upstream replaces the value only with one the field never produced; an
  `OwnedText` says it may replace it with anything, and carries the count of the
  replacements.
- the **Owner** column names the file that holds the value — and a file that holds the
  value is the party that writes it.

So the registry says only what it checks.

The **Owner** column names the file that builds the source — the component that holds the
value and knows who else writes it. It is the owner the field's answer comes from, and it
is the file the source is read out of. The GenUI renderer used to be the one row whose
owner was the field site itself; it is not any more — the data context builds the source
and hands it out whole, because the context is what knows the value and the server's
count of writes.

The GenUI's count is kept in the **data model**, per path, next to the value it counts:
the count says "this value was replaced from outside", and it is the count of *this
path's* rewrites. It used to be a counter beside the surfaces — one per surface, moved by
a write to any path of it — which is why the Counter and Advances columns point at the
data model now.

The **Counter** column names the file that declares the count; the **Advances** column
names the file that moves it. They are often the same file, and they are not always: the
note's editor counts its revisions in the state and moves them in the ViewModel, because
the state is what the field is handed and the ViewModel is what replaces the note.

The note's body is not a bridged field — it is a rich-text editor that holds its own
content — and it takes the rewrite a different way: the editor's session is re-seeded
from the draft when the draft's revision changes. The revision is the same idea as the
count in an owned source: the party that rewrote the content says so.

**The counter columns name the files that own the number.** A count that is passed as a
literal can never move, so the field would never learn that it is a rewrite — and a
frozen count is worse than none, because it looks like the contract is honoured. The
count moves when the entity was *replaced* from outside, and it never moves for a
keystroke or for the echo of the field's own save. The **Counter** column names the file
that declares it, the **Advances** column the file that moves it.

**There are two kinds of counter, and they are not the same thing.**

- **A count that travels with a value.** The drafts hold their text in a
  `Revisioned<String>`: the value and the count of the times it was replaced from outside
  live in one object, and the count moves *only* through `Revisioned.replacedBy`. A draft
  that held the text and the count in two places could replace the text and leave the
  count where it was — and the field would take the replacement for an echo of its own
  last push, and keep the text the user typed. That is the lie the type exists to prevent,
  and it is silent: nothing fails, the field just stops listening.
  **The count belongs to one value.** A draft with two fields holds two `Revisioned`s, and
  each one's count moves for its own field: a rewrite of the body does not move the
  title's count, and a rewrite of the title does not move the body's. `replacedIfChangedBy`
  says it in one place — the count moves when the value changed, and stays where it was
  when the upstream wrote the value the owner already held.
- **A count of the upstream's writes.** A GenUI surface counts the *server's writes*, and
  a server write may not change the value at all — so the count is a property of the
  surface, not of a value, and it is a plain number the controller increments. The data
  context builds the source out of the value and that number.

The first travels with the value; the second is the surface's own. What they share is
the rule: the count moves when the content was *replaced* from outside, and never for a
keystroke.

**A file may appear twice.** The task title and the task description are served by two
upstreams — the create draft and the detail draft — and the two are different kinds of
upstream. The create draft seeds the value once and afterwards holds what the user typed,
so a replacement lands on a value the field never produced: `StaticText` is the honest
answer there. The detail draft observes an entity a sync can rewrite, and the rewrite can
land on a value the field produced: `OwnedText`, with the draft's count.

**The GenUI renderer does not build its own source, and neither does anybody else.** Its
owner is a server, and the renderer is not the component that knows what the server wrote.
The data context is: it holds the value at the path and the server's count of writes, and
it hands the source out whole. The renderer reads it and passes it to the bridge, like
every other field in the app.
