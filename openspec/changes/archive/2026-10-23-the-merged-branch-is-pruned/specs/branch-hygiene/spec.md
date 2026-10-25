# branch-hygiene — Observable Behavior

**capability:** `branch-hygiene` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-1 The plan names the branches a merged pull request left behind

The tool SHALL build a plan from the local branches and the merged pull requests of
the repository the checkout points at, and SHALL print the plan before anything is
deleted.

A branch SHALL be in the plan only when a merged pull request has the same head and
the local tip of the branch is that merged commit or an ancestor of it. The plan
SHALL say which of the two rules matched.

**Rationale:** git can only tell that a branch was *not* merged by ancestry, and a
squash-merge breaks that ancestry. GitHub knows the pull request closed and which
branch it came from, and the commit is the only evidence that the local branch is
the one that was merged — names are reused.

#### Scenario: The branch is the merged head

- **Given** a local branch whose tip is the commit a merged pull request was
  merged from
- **When** the plan is built
- **Then** the branch is in the plan
- **And** the plan says the tip is the head of the pull request

#### Scenario: The branch is behind the merged head

- **Given** a local branch whose tip is an ancestor of the commit a merged pull
  request was merged from
- **When** the plan is built
- **Then** the branch is in the plan
- **And** the plan says the content is already in `main`

#### Scenario: The branch moved on after the merge

- **Given** a local branch whose tip is neither the merged commit nor an ancestor
  of it
- **When** the plan is built
- **Then** the branch is not in the plan
- **And** the plan says how many of its commits exist in no `origin/*` and in no
  `main`

### Requirement: REQ-2 Nothing is deleted without an explicit request

The tool SHALL print the plan and delete nothing by default. Deleting SHALL require
an explicit flag, and the tool SHALL report what it deleted.

**Rationale:** the fact that a pull request was merged says that its content is in
`main`. It does not say that nobody still needs the branch.

#### Scenario: The plan is printed

- **Given** local branches whose pull requests were merged
- **When** the tool runs without the deletion flag
- **Then** it prints the plan
- **And** no branch is deleted

#### Scenario: The deletion is requested

- **Given** a plan
- **When** the tool runs with the deletion flag
- **Then** the branches of the plan are deleted
- **And** the tool says which ones

### Requirement: REQ-3 The branches that are never deleted are named

The tool SHALL NOT delete the branch the checkout stands on, a branch held by
another worktree, `main` and `master`, a branch without a merged pull request, a
branch of a fork, a branch whose merged pull request cannot be confirmed, and a
branch that moved on after the merge.

For each of them the tool SHALL give the reason, and the reason SHALL be true: a
branch of a fork has a merged pull request that belongs to somebody else, and a
branch whose pull request cannot be confirmed is not the same thing as a branch
without a merged pull request.

**Rationale:** each of these is a case where deleting would destroy something, or
where git itself would refuse — and a tool that fails where it could have said "this
one is in use" teaches its reader that its output is noise.

#### Scenario: A branch of a fork

- **Given** a local branch whose name matches a merged pull request of another
  repository
- **When** the plan is built
- **Then** the branch is not in the plan
- **And** the reason says the pull request belongs to another repository

#### Scenario: A branch held by another worktree

- **Given** a local branch that another worktree stands on
- **When** the plan is built
- **Then** the branch is not in the plan
- **And** the reason says the branch is in use

### Requirement: REQ-4 The request to GitHub is part of the contract

The tool SHALL ask GitHub for the merged pull requests of the repository the
checkout points at, with the merged commit, the owner of the head, the repository
it is reading, and a page size that covers the whole corpus of merged pull
requests.

When the origin remote cannot be read, the tool SHALL fall back to the repository it
knows and SHALL say which repository it used.

**Rationale:** the whole tool is the question "which pull requests are merged". A
regression in the request itself makes the tool empty or wrong, and the suite has to
notice it.

#### Scenario: The request names the merged pull requests

- **Given** a tool that asks GitHub for the merged pull requests
- **When** the request is inspected
- **Then** it asks for the state "merged"
- **And** it asks for the merged commit, the owner of the head and the repository
