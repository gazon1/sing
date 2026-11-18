---
change: link-previews-in-task-titles
verdict: pass
date: 2026-11-16
pr: https://github.com/gazon1/sing/pull/2958
reviewed: 41e9036a452bd0599a4352d507113aeef7095cd0
documents: sha256:96b26f9990e8b44730fba78a450ae749913a6b890b231e54de18d6213b9ce69d
---

## What was reviewed

The link-preview feature end to end, on `gazon1/feat-tasks-add-cached-link-previews-for-urls-in`:

- `shared/src/commonMain/kotlin/com/singularity/todo/core/linkpreview/` — the address
  rule, the metadata reader, the public-address judge, the guarded fetcher, the cache
  and the repository;
- `shared/src/jvmMain/…/linkpreview/` and `shared/src/androidMain/…/linkpreview/` — the
  two resolver actuals;
- `TaskLinkPreviewSlot`, `LinkPreviewCard`, `TaskLinkPreview` and the wiring into
  `TaskDetailCoordinator`, `TaskDetailContent` and `TaskEditorContent`;
- the setting, its storage and its screen row;
- the tests.

The review was a separate session's, aimed at what the diff could be wrong about rather
than at whether it compiles: the author reviewed their own work as well, but a session
reviewing itself inherits the assumptions the diff was built on.

## What was found, and what was done about it

Two findings were blocking. Both are fixed, in the same commit.

**A relative reference was resolved against the wrong path.** `PreviewUrls.resolve`
treated a query-only reference (`?a=1`) and a fragment-only one (`#top`) as a relative
*path*, so it appended a directory: `?a=1` on `https://example.com/p` became
`https://example.com/p/?a=1`, and `#top` lost the path entirely. The page's own path is
what those forms replace the query or fragment *of*. Both now keep the page's path, and
`PreviewUrlsTest` covers each.

**The hide acted on an address the user might not be looking at.** `TaskLinkPreviewSlot`
kept a `currentUrl` field that the collector coroutine and `onIntent` both wrote. Two
writers of one field is two answers to "which address is on screen", and a dismissal
that landed on the stale one would hide a card the user never dismissed. The address is
now read from the state that is actually rendered — a `Ready` card — so a hide can only
act on what is on screen, and a hide while the card is still loading does nothing at
all. `TaskLinkPreviewSlotTest` covers both.

Three further findings were checked and needed no change:

- **Redirect policy.** `PageFetcher` takes the policy over rather than trusting the
  caller's client (`client.config { followRedirects = false }`). `PageFetcherTest` now
  passes a client that *does* follow redirects, so the test would fail if the override
  were removed — a redirect into the local network is refused and the target is never
  requested.
- **`config` inherits the timeouts.** Read in Ktor 3.6.1: `HttpClientConfig.plusAssign`
  copies the installed plugins, so the shared client's `HttpTimeout` still applies. The
  second client shares the first's engine, so there is still one connection pool.
- **The wiring is real, not merely present.** Checked by grep rather than assumed: the
  slot is constructed in `TaskDetailCoordinator`, the card is mounted in
  `TaskDetailContent`, `RefreshLinkPreview` is fired from a `LaunchedEffect` keyed by the
  address, and `linkPreviewsEnabled` is read by the repository before any request.

## What this verdict does not say

It does not say the SSRF defence is complete against every resolver: the fetcher judges
the addresses a name resolves to at check time, and a name whose answer changes between
the check and the connect is out of reach of a check that is not at the socket. That is
recorded in the ADR as the known limit rather than left to be discovered.

## Second pass — the simplification

After the first review, the change was rewritten to stop re-implementing standards. What
was reviewed, and what was found:

- **URL parsing is Ktor's now.** Probe-verified before the swap: `Url` handles IPv6
  authority, user info and percent-encoding, which the hand-rolled reader did not — it
  returned `"["` as the host of `https://[::1]/x`, so IPv6 links never previewed. The
  swap fixed a real bug. Two behaviours are *not* Ktor's and are kept deliberately:
  `takeFrom` does not collapse `..` (probed: `/post/deep/../a.png` survives), so
  `collapseDots` stays; and `Url("example.com/x")` parses to host `localhost`, so a
  scheme is required as *text* before the address is trusted.
- **HTML parsing is Ksoup's now.** 0.1.x resolves Ktor 2.3.7 into the runtime against
  this project's 3.6, so the floor is 0.2.x; `ksoup-network` is deliberately absent.
  The existing parser fixtures pass unchanged, which is what made deleting the
  hand-rolled reader safe.
- **`PublicAddresses` stays ours, and is now checked against a reference.** IPAddress is
  JVM-only, so it earns its place as a `jvmTest` oracle rather than as a port. On its
  first run it found two real defects: the documentation blocks (`192.0.2.0/24` and
  friends) were allowed, and `fec0::/10` was not refused. Both are fixed.
- **Single-flight, field clamping, and image lifecycle** were added and tested: two
  concurrent callers cost one fetch; a field is cut to what the card holds; a refresh
  naming a different picture deletes the old file, and the card is keyed on both the
  address and the picture so it cannot show the previous one.

One bug was found in the review's own test, worth recording because it is the kind that
passes for the wrong reason: the single-flight test initially counted *every* `fetch`,
and the preview image is a legitimate second fetch, so it reported two calls when there
was one page fetch. It now counts page fetches only.

## Third pass — the package audit

A further audit of the cache and concurrency behaviour found four defects, all fixed,
each with a test that was **checked to fail without its fix**:

1. **Every evicted image leaked.** `put` reported *addresses* to the caller, and
   `forget` treated its argument as a *key*, so the delete hit a path that was never
   written. An orphan file then rendered as the current picture. `put` now deletes the
   images of what it evicts itself; there is no second owner and no cleanup pass.
2. **`https://e.com:443/a` and `https://e.com/a` were two cache entries.** A default
   port is not part of the address. Only a port that changes it is kept.
3. **An answer that landed after the setting was turned off was still shown.** The
   toggle was read before the request but not before the answer was returned. The fetch
   is still allowed to finish — cancelling it needs a job handle per caller and buys
   nothing — but its answer is discarded.
4. **A late refresh could overwrite the card with a link the title no longer named.**
   The slot compared nothing after `refresh` returned. It now checks that the title
   still names the address before putting the answer on screen.

The third pass also added fetcher cases the audit asked for: a redirect with no
`Location`, a relative `Location`, and a cancellation that must stay a cancellation
rather than becoming a fetch failure.

Worth recording: the first version of test 4 **passed with the guard removed**, because
the collector coroutine overwrote the stale value regardless of order. It was rewritten
to hold the fetch open until the title had moved on, which is what makes the two orders
distinguishable — a test that cannot fail is not a test.

## Still open, deliberately

**DNS rebinding.** The fetcher judges the addresses a name resolves to, then Ktor
connects to the name. A DNS answer that changes between the two is out of reach without a
pinned-IP transport, which Ktor's engines do not expose, and writing one is a larger
change than this feature justifies. It is recorded in the ADR as the known limit. Any
future fix must not weaken the redirect, TLS or hostname checks to get there.

## Fourth pass — the security and concurrency audit

Five defects, all fixed, each with a test checked to fail without its fix:

1. **The IPv6 policy was a denylist, not an allowlist.** `else -> true` said yes to the
   unassigned space outside global unicast (`4000::/3`) and to 6to4 — where
   `2002:7f00:1::` is 127.0.0.1 wearing an IPv6 address. Only `2000::/3` is allowed
   now, and the four prefixes that carry an IPv4 address (mapped, compatible, NAT64,
   6to4) are judged by the address they carry.
2. **A cancelled owner left its waiters hanging.** If the coroutine that owned a
   single-flight fetch was cancelled, its deferred was never completed and every waiter
   waited for the life of its scope. The flight is now cancelled in a `finally`, and a
   waiter that is still alive takes the work over.
3. **Dismissals were unbounded in practice.** The limit lived in `put`, so a person who
   only dismissed cards — never opening a new page — accumulated them without end
   (measured: 700 markers against a bound of 500). `hide` now trims in the same `edit`.
4. **Credentials in a URL were not refused.** Ktor does not turn them into an
   `Authorization` header today, but passing somebody's login to a stranger's site on
   the strength of "the client happens not to" is not a decision worth making.
5. **`collapseDots` collapsed `//`.** `/a//b` became `/a/b`, which is a different
   resource on a server that distinguishes them. It is now RFC 3986 §5.2.4: dot
   segments go, empty segments stay.

### Two corrections to my own earlier reporting

The IPv6 and dot-segment fixes were each reported with a negative control. The first
attempt at both **silently failed to apply the revert** — the string I replaced did not
match the file — and I reported "control passed" for a control that never ran. Re-run
against the real code, both fail as they should. A control that did not execute is worse
than no control, because it is cited as evidence.

### On the CI failures

Six blocking static gates fail on this branch. Five are pre-existing on `main` and name
files this branch never touches (`04-pick-emoji.yaml`, `SavedSearchesRow.kt`, two
long-standing ADRs, and a detekt-governance failure that passes locally — it is a
missing artifact in the CI cache). The sixth was mine: `check-dead-settings` could not
see the setting being read because the consumer named its parameter `enabled`. Renaming
it to `linkPreviewsEnabled` makes the read visible and is a better name anyway. The
gate passes.

`RecurrenceStringsResourceTest` still fails, and still fails on a clean tree of this
branch; it asserts a resource string this branch never touches.

## Fifth pass — padded IPv4, and a correction about CI

**A padded octet could be judged by the wrong stack.** `0177.0.0.1` is 127.0.0.1 to a
resolver that reads octal and 177.0.0.1 to the JVMs this app ships on — both behaviours
exist. The parser accepted it as `177.0.0.1` on its own reading and called it public. A
part with a leading zero is no longer an octet, so the string is not a literal and the
platform resolver decides; an end-to-end test asserts that a resolver answer of loopback
stops the request before it is made. The negative control fails without the guard.

**DNS rebinding is now stated, not merely mentioned.** The ADR had no section for it —
the earlier verdict claimed one did. It does now: the gap, why closing it needs a pinned
transport, what the design does guarantee, and the constraint that a future fix must not
weaken the redirect, TLS or hostname checks.

**Two dead methods removed.** `LinkPreviewRepository.forget` and
`LinkPreviewCache.forget` were declared, implemented, tested — and called by nothing in
production. `find-unwired-surfaces.py` cannot see this (it reports unused Koin bindings),
so the only defence is reading the call graph. Deleted with their tests.

**I was wrong about CI, and the reason matters.** I reported
`RecurrenceStringsResourceTest` as pre-existing because it failed on a stashed clean tree
*of this branch*. That tree was 20 commits behind `origin/main`, which had already fixed
it (`471c367bc6`, `trim = false`). After merging main the test passes, and so do the other
five static gates I had called pre-existing — main had fixed those too. The lesson is the
one the review pressed: compare against the actual base, not against a stale copy of your
own branch.

## Sixth pass — the contract moved, and this verdict is re-issued

The contract moved after the fifth pass: `41e9036a45` rewrote `REQ-LINKPREVIEW-002`, so
this verdict was speaking about a contract that no longer existed and the gate
(`review verdicts cover every change`) was failing on the digest. A contract that moves is
re-issued, not re-read — so this verdict is re-issued against the contract as it stands,
reviewed against `41e9036a45`.

The move widened the requirement: **every** address the preview obtains — the typed host,
every address a name resolves to, every redirect target — must be judged before the
request, and inside global unicast the blocks the registries mark not globally reachable
are refused by name as well (documentation `2001:db8::/32` and `3fff::/20`, benchmarking
`2001:2::/48`, the discarded and deprecated prefixes, Teredo). It also recorded the DNS
rebinding limitation as an accepted limit rather than a defect to be closed here.

What the re-review checked, against the code and not against the prose:

- **Every clause of the moved requirement has an implementation site.** The allowlist is
  global unicast `2000::/3` and nothing else; the non-global blocks inside it are refused
  by name (`PublicAddresses.isSpecialPurpose`), the forms that carry an IPv4 address are
  judged by the address they carry, and every hop is judged before the request is made,
  redirect targets included.
- **The tests the contract names exist and pass.** `PublicAddressesTest` (every spelling
  of a non-public address, with the positive controls just past each block),
  `PublicAddressesOracleTest` (the policy against the IANA special-purpose blocks) and
  `PageFetcherTest` (a redirect into the local network is refused and the target is never
  requested) — all green.
- **The DNS rebinding limit is in the ADR**, with the gap, the pinned transport that would
  close it, and the guarantee that a future fix must not weaken the redirect, TLS or
  hostname checks.
- **The registry was checked against IANA's own tables**, not against this codebase's
  comments about them.

Two things this re-issue does not claim: that the earlier passes describe the code as it
is now (the fourth pass narrates an IPv6 policy that was a denylist at the time — the
allowlist came later, and the credential refusal is not in that narrative), and anything
about the preview's user-facing behaviour, which no review here looked at.
