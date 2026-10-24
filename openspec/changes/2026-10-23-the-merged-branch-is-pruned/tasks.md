# Tasks — the-merged-branch-is-pruned

## 1. Build the plan

- [x] 1.1 Ask GitHub for the merged pull requests, with the merged commit, the
      owner of the head, the repository and a page size that covers the whole
      corpus.
- [x] 1.2 Take the local branches from git and join them with the merged pull
      requests by the commit, not by the name — names are reused.
- [x] 1.3 Print the plan, and the reason for every branch that is in it and for
      every branch that is not.

## 2. Never delete the wrong branch

- [x] 2.1 Exclude the branch the checkout stands on.
- [x] 2.2 Exclude the branches held by another worktree.
- [x] 2.3 Exclude `main` and `master` by name.
- [x] 2.4 Exclude the branches without a merged pull request.
- [x] 2.5 Exclude the branches of a fork, and say that the pull request is
      somebody else's — not that there is no pull request.
- [x] 2.6 Exclude the branches whose merged pull request cannot be confirmed.
- [x] 2.7 Exclude the branches that moved on after the merge, and print the
      commits that exist in no `origin/*` and in no `main`.

## 3. Delete only on an explicit request

- [x] 3.1 Print the plan by default; delete only with `--yes`.
- [x] 3.2 Say what was deleted, and what was not.
- [x] 3.3 Exit 0 when the plan was built, 1 when a tool failed, 2 on a usage
      error.

## 4. Check the property, not the code

- [x] 4.1 Drive the script with a stand-in for `gh` that reads the request the
      way `gh` answers it, and one for `git` over a real history.
- [x] 4.2 Test the contract of the request to GitHub: the state, the merged
      commit, the owner, the repository and the page size.
- [x] 4.3 Mutate the script — take out a rule, change the request, blank the
      "only in the local copy" indicator — and see the tests go red.
- [x] 4.4 Say in the decision record what the tool does with a branch whose tip
      is behind the merged head, and why that is safe.
