# Tasks — the write path has one shape

**status:** accepted

---

## Phase 1 — The vocabulary

- [x] `TaskField` gains `Kind`, `DueTime`, `Project` and `Someday`
- [x] `SetTaskTags` — a set of tags, not an addition
- [x] `ProjectField` and `SetProjectField` — a project can be changed
- [x] `CreateTask`, `CreateProject`, `CreateNote` — a creation is a change
- [x] The payloads are typed, and the values inside them are still the model's
      strings
- [x] The fingerprint of a creation is its title, so a creation the user turned
      down is not proposed again
- [x] The summary of every new kind says what the user will read
- [x] The database codec writes and reads every new kind

## Phase 2 — The plan and the dispatch

- [x] The plan carries a creation, a project write and a tag set
- [x] The planner builds the thing a creation brings into being, with the
      identifier the proposal named and the owner the confirmation carries
- [x] The dispatcher creates the task, the project and the note; it updates the
      project and the task's tags
- [x] A value the plan cannot read fails before the claim, not during the
      dispatch

## Phase 3 — The tools

- [x] `create_task` proposes a task
- [x] `update_task` proposes the difference, one item per changed field
- [x] `create_project` proposes a project
- [x] `update_project` proposes the changed fields
- [x] `create_note` proposes a note, with the body as the canonical HTML the
      note will carry
- [x] `update_note` proposes the changed fields
- [x] `decompose_and_create` proposes every sub-task of the decomposition
- [x] The tools share one path: `proposeChange`
- [x] Each tool's answer says a proposal was created, and names no identifier
      of a thing that does not exist
- [x] A value the model got wrong is reported to the model, not stored
- [x] A value the model spelled in lower case is stored the way the entity holds
      it, so the proposal still applies when the user says yes

## Phase 4 — The search

- [x] `search_tasks` asks the application's search
- [x] Its description names the terms the query language understands
- [x] A query that cannot be read is reported as such
- [x] The tool stays about tasks

## Phase 5 — The card and the name

- [x] The proposals screen draws the shared card
- [x] The projection that existed only to feed the private card is gone
- [x] The ViewModel grew `ConfirmAll` and `Dismiss`
- [x] The screen, the destination, the ViewModel and the state are called
      "Proposals"
- [x] The empty state says what the screen is, and stays selectable

## Phase 6 — The record and the checks

- [x] The registry of direct writers holds one entry, with its reason
- [x] The gate's positive control follows the registry
- [x] The surface contract version is 2
- [x] The tool catalogue is regenerated
- [x] The decisions are recorded
- [x] The change is recorded, with the specs
- [x] The tests cover the new kinds, the new tools and the new card
