# The write path has one shape, and the search has one implementation

Issue: — · Related: `docs/decisions/2026-10-22-the-write-path-has-one-shape.md`,
`docs/decisions/2026-10-22-the-tool-asks-the-search-the-screen-asks.md`,
`docs/decisions/2026-10-22-the-screen-is-called-proposals.md`

Status: **accepted**
Tags: **ai write-path proposals contract search ui**

## What

Four defects, one shape: **two things that should have been one thing were two.**

1. **The write path was half direct.** The delete tools proposed; the create and
   update tools wrote. The same act — a change to the user's own data — got two
   treatments, and which one it got was decided by which tool the model happened
   to pick. A user who agreed to one change could not tell which of the two they
   were agreeing to.

   The proposal vocabulary was also missing shapes: no project could be changed
   (only deleted), no tag could be changed at all, and nothing could be
   *created* — a proposal describes a change to something that exists, and a
   creation has no target.

2. **The search tool was a filter, not a search.** `search_tasks` read every
   task and kept the ones whose *title* contained the word. The app has a
   search: it finds a task by its description, by its tag, by its project, and it
   understands `tag:`, `due:`, and a minus that negates a term. The person at
   the keyboard asks the search screen and finds the task they meant; the model
   asked the tool and found nothing.

3. **The proposal card was drawn twice.** The shared card was written for
   exactly this, and the review screen drew a second one beside it — its own
   buttons ("Apply" / "No"), its own vocabulary. Three surfaces, two renderings
   of one thing, two sets of words for the same act.

4. **The screen was called "Review" and did not review.** The review procedure
   — four scenarios — lives in the chat. The screen listed proposals. It
   promised more than it gave.

## The change in behaviour

- **Every write tool proposes.** `create_task`, `update_task`, `create_project`,
  `update_project`, `create_note`, `update_note` and `decompose_and_create`
  store a proposal instead of writing. The write happens when the user confirms.
- **A creation is a proposal.** The proposal names the id the thing *will* have,
  so the proposal and the task it talks about are the same task.
- **The vocabulary grew.** Task fields gain `Kind`, `DueTime`, `Project` and
  `Someday`; `SetTaskTags` says a set; `ProjectField` and `SetProjectField` say
  a project change; `CreateTask`, `CreateProject` and `CreateNote` say a
  creation.
- **The tools report what they did.** A proposal id and a `proposalCreated`
  flag — and no id naming a thing that does not exist.
- **The contract version moves.** Six tool names changed meaning under the same
  names, so the surface contract version is 2.
- **The search tool asks the app's search.** One implementation of "what does
  this word match", so the model and the person get the same answer.
- **One card.** The proposals screen draws the shared card, so the person meets
  one set of words wherever a proposal appears.
- **The screen is called "Proposals".** It lists proposals; the review
  procedure stays in the chat, and that is recorded rather than hidden.

## What is not in this change

- **The review scenarios do not come to the app.** They are the model's, and
  the app has no machinery for running an agent from a screen. The screen stops
  claiming to be a review; bringing the procedure to the app is separate work.
- **Tags are still not proposed.** `create_tag` writes directly, and the
  registry says why: the proposal vocabulary has no tag kinds at all. Closing
  that is the same shape of work as this change and is not in it.
- **The proposal's rejection fingerprint is unchanged.** A creation's
  fingerprint is its title, so a creation the user turned down is not proposed
  again.
- **The tools that only read are unchanged.** `get_task`, `list_tasks` and the
  rest still read.

## Why now

Because the review that asked for it found four cases of the same shape: two
things that should have been one thing were two, and in every case the person
paid for the difference. The write path is the expensive one — a rule that is
written down and not applied is a rule that is true in the document and false in
the product.
