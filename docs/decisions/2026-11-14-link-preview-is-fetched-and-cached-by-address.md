---
title: Link previews are fetched by the app, not by the page, and cached by address
date: 2026-11-14
status: accepted
tags: [tasks, link-preview, security, cache, ssrf]
---

# Context

A task title is plain text, and people paste links into it. #2953 asks for the
Telegram-style preview of that link under the title in Task Detail, and it puts
several constraints on how the page may be read: HTTP/HTTPS only, no JavaScript,
public addresses only, bounded responses, no page content stored, and no AI
without explicit consent.

The alternatives were:

- **A backend/metadata service.** Rejected: it adds a deployment, an auth story
  and a privacy question about who sees which URL, for a card that is decoration.
  It also does not answer "works offline for the cached case" any better than the
  app does.
- **Letting the image loader fetch the remote address.** Rejected on security
  grounds: `og:image` comes from the page, so it is untrusted, and a loader
  request would skip the SSRF check, the size limit and the media-type check the
  page itself goes through.
- **A Room table for the cache.** Rejected: the value is three strings and a
  timestamp, derived and disposable. A table would need a schema migration, a
  wipe/backup verdict and a sync decision for data nobody misses.

# Decision

Nothing here re-implements a standard. Each of the four "standard" jobs is delegated to
something that already knows the standard, and only the policy stays ours:

| Job | Owner | Why not ours |
|---|---|---|
| URL parsing, resolution, IPv6 authority | **Ktor `Url` / `URLBuilder`** | Already on the classpath, and the shape of a URL is a standard with too many corners (`::`, user info, percent-encoding) to approximate. |
| HTML parsing and metadata extraction | **Ksoup** (`com.fleeksoft.ksoup:ksoup`, KMP) | The same reason. Malformed markup, entities and attribute quoting all have written answers. |
| "Is this address public?" | **ours** — `PublicAddresses` | It is a *policy* ("may a preview reach it"), not a parse. It is checked against `com.github.seancfoley:ipaddress`, test-only, address by address. |
| Transport, limits, redirects | **ours** — `PageFetcher` on the existing Ktor client | Security is the point; a library that follows redirects for you is the hole. |

Two of those decisions are worth their own note.

**Ksoup, and the version.** `0.1.x` resolves **Ktor 2.3.7** into the runtime against this
project's 3.6 — a conflict that would be discovered as a `NoSuchMethodError` on a page
that happens to hit the changed API. `0.2.x` dropped the Ktor dependency, so that is the
floor. `ksoup-network` is deliberately **not** declared: the fetch is `PageFetcher`'s, and
a second network path is a second place the address checks could be forgotten.

**IPAddress is a test oracle, not the implementation.** It is a JVM library, so it cannot
live in `commonMain`; putting it behind a new port would add a seam whose two halves
cannot disagree. It earns more as a *reference*: `PublicAddressesOracleTest` sweeps both
ends of every reserved block — IPv4, IPv6, and the IPv4-embedded forms — and fails when
the policy and the library disagree. It found two real gaps on its first run: the
documentation blocks were allowed, and `fec0::/10` (site-local) was not refused.

The behaviour itself, unchanged:

1. `PageFetcher` accepts only `http`/`https`, judges every hop — the typed address and
   each redirect target — as a public unicast address, follows at most three redirects
   **by hand** with the client's own redirect following switched off, accepts only HTML
   (or `image/*` for a picture), and reads at most 512 KB.
2. `HostAddressResolver` is a platform port so `commonMain` can ask what a name resolves
   to at all. A name is refused when it does not resolve, and refused when **any** of its
   addresses is not public — the connection may land on any of them.
3. The preview is four strings and a fetch timestamp, cached in the settings `DataStore`
   under the normalised address, each field cut to what the card can hold. The image bytes
   go on disk in `preview_images/`, fetched through the same guarded path. **No HTML is
   stored.**
4. Dismissal is per **address**, not per task. Hiding writes nothing to the task, which is
   what makes "hiding must not change the title" structural rather than a rule to remember.
5. The preview follows the **title draft**. A fresh entry costs no request, and concurrent
   requests for one address share a single flight.

# Rationale

- **One fetcher for a page and its picture.** The picture's address came from the
  page. Two paths would mean two places to forget the SSRF check.
- **An allowlist of public addresses, not a denylist of private ones.** A denylist
  always misses a spelling (`127.1`, an IPv4 address inside IPv6, a NAT64 prefix),
  and a miss is a request that goes out.
- **The address as the cache key.** Two tasks quoting the same article cost one
  fetch, and an edit that keeps the link reuses the preview.
- **The settings `DataStore`, not a table.** It is where a preference-sized value
  belongs, and it already has a migration, a reset story and an import/export
  story. A cache in a preference needs none of its own.
- **No AI.** #2953 makes summarisation opt-in; the metadata-only card is the whole
  default path, and the type has no field for an inferred summary.

# Consequences

- **Desktop and Android both ask `InetAddress`,** so the answers agree. A platform
  with a different resolver would still be refused correctly, because the check is
  on the answer and not on who gave it.
- **A page that does not answer is a quiet line, not an error.** The address stays
  in the title and stays tappable. There is no snackbar for a preview failure: the
  user did not ask for the preview.
- **The image is optional everywhere.** Metadata is stored before the picture is
  fetched, and a failed picture is dropped. A card that is text only is a card.
- **The entries live outside the profile reset.** A preview is derived from a
  public page and describes nothing the user made; a reset that kept walking the
  cache would be paying a fetch to remove something nobody asked to remove. It is
  bounded instead: an entry older than six hours is refreshed on next open, and
  the store holds at most 500 addresses, the oldest dropped with its image. That
  is also why the choice is recorded: a cache with a bound needs no expiration
  sweep, and a table would have needed a wipe verdict.
- **The title draft and the saved title can disagree for a moment.** The preview
  follows the draft; when the draft is discarded the card follows the saved title
  on the next emission. Nothing is written, so there is nothing to reconcile.
- **Two new dependencies, both bounded.** Ksoup is `commonMain` and parses only; it
  cannot fetch. IPAddress is `jvmTest` only — it is absent from every shipped target, so
  it adds no runtime weight and no Android API floor. `minSdk` is 26, which would have
  allowed it in production if the port had been worth building.
- **The oracle is a slow test.** It runs in the `slow` population, not on every pull
  request, because it is a sweep rather than a unit test. The fast tests keep a hand-
  written table of the cases people hit.
- **IPv6 is an allowlist, not a denylist.** The first version refused a list of
  known-bad prefixes and allowed everything else, which says yes to the large,
  mostly-unassigned space outside global unicast (`4000::/3`) and — worse — to 6to4,
  whose `2002:7f00:1::` is 127.0.0.1 wearing an IPv6 address. Only `2000::/3` is
  allowed, and the four prefixes that carry an IPv4 address (mapped, compatible,
  NAT64, 6to4) are judged by the address they carry.
- **Eviction owns its files.** `LinkPreviewCache.put` deletes the images of the entries
  it evicts, rather than reporting them to the caller. The first version reported
  *addresses* where the caller needed *keys*, so every evicted image leaked — a file
  nobody points at, which the card then showed as current. A cache that deletes what it
  displaced needs no second owner and no cleanup pass.
- **The setting is checked where the answer is returned, not only where the request is
  started.** A page already in flight when the user turns previews off is allowed to
  finish — cancelling it would need a job handle per caller and would buy nothing — but
  its answer is discarded. Otherwise a card appeared after the user had asked for none.
- **Dot segments, and only dot segments.** `removeDotSegments` keeps empty segments:
  `/a//b` and `/a/b` are two resources on a server that distinguishes them, and
  collapsing them is rewriting the address rather than normalising it.
- **A cancelled owner ends its flight.** `fetchOnce` cancels the deferred in a
  `finally`, so a waiter cannot outlive the fetch it waits on; the waiter then takes
  the work over rather than inheriting somebody else's cancellation.
- **Dismissals are bounded where they are written.** A person can dismiss all day
  without opening a new page, so a bound that lived only in `put` would never run for
  them; `hide` trims in the same `edit`.
- **Credentials are refused.** A URL carrying user info is refused before the request:
  the preview has no use for somebody's login, and Ktor not turning it into an
  `Authorization` header today is not a guarantee for tomorrow.
- **Ktor does not collapse `..`.** `URLBuilder.takeFrom` implements RFC 3986 §5.2 but not
  §5.2.4, so `PreviewUrls` keeps a small dot-segment collapser. Removing it would make
  `/post/deep/../a.png` and `/post/a.png` two cache entries for one page.

# Links

- Issue: #2953 — cached link previews for URLs in task titles.
- Related: #2902 (visual URL highlighting in the title), #2952 (rich description).
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/linkpreview/`.
