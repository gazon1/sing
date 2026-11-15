---
change: link-previews-in-task-titles
verdict: pass
date: 2026-11-14
pr: https://github.com/gazon1/sing/pull/2958
reviewed: d06509b8bdcd2af0ac205677c3d7b9f0e1359b83
documents: sha256:be86e2834e56fee63cbf176d8cc013f6231bcfd806c70bbfa8ad79f98e8061f5
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
