# a-write-tool-reports-the-write-it-did-not-perform

Backlog entry: `ai-tools-drop-a-failed-write`. Found by the dropped-`Result`
audit of 2026-10-07; no issue number yet.

## What

Seven AI tools in `feature/ai/tools/` call a `Result`-returning repository
method and discard the result, then return an output DTO that asserts the write
happened. On a failed write the tool returns exactly the same JSON as on a
successful one.

The seven are `CreateNoteTool`, `CreateProjectTool`, `CreateTagTool`,
`CreateTaskTool`, `UpdateNoteTool`, `UpdateProjectTool`, `UpdateTaskTool`.

The fix is `.getOrThrow()`, the shape the four `Delete*Tool` in the same package
already use.

## Why

The output of a tool call is the only thing the model knows about what happened.
`CreateNoteTool.execute` builds a `noteId` before the write and returns
`CreateNoteOutput(noteId.value, note.title)` whether or not
`notesRepository.create(note)` succeeded. The model is therefore told a note
exists at an id that names nothing, and it builds its next action on that
premise — opening the note, linking to it, referring to it in its reply to the
user.

This is the worst of the three classes the audit separated. Class B
(`syncRepository.enqueue`, 18 sites) loses a push; Class C and E lose a status
refresh. Only Class A hands the model a fabricated fact about the world it is
acting on, and it is the one where the wrong belief is carried forward into
subsequent turns.

The asymmetry inside the package is the tell: `DeleteNoteTool:74` calls
`proposals.save(proposal).getOrThrow()`. The author knew the contract for a
write and applied it to deletion; the create/update path never got it. Four
tools in one package already do the right thing, so this is a missing
convention rather than a missing idea.

## How

Append `.getOrThrow()` to the seven calls. No DTO changes: the output types keep
their current shape and the model keeps receiving the same JSON on success.

`.getOrThrow()` rather than catching and encoding an error into the DTO,
because the latter changes a contract the model is prompted against and buys a
failure path nobody has specified. Throwing makes the tool call fail, which is
the outcome the model already knows how to handle, and it matches the package's
own four precedents.

**Invariant:** each of the seven gets a control that fails today. A fake
repository returns `Result.failure` from `create`/`update`, the tool is invoked,
and the call must not produce a success payload. Without those, "the tools
handle failures" and "the tools were never exercised against one" are the same
observation.