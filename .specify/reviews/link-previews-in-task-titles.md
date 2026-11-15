---
change: link-previews-in-task-titles
verdict: pass
date: 2026-11-14
pr: https://github.com/gazon1/sing/pull/2958
reviewed: d06509b8bdcd2af0ac205677c3d7b9f0e1359b83
documents: sha256:e9306706d573306352a35bbb392093cfcb1583d91007ff1f7158d031efd1917a
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
