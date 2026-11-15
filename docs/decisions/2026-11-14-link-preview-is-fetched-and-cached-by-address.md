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

The app fetches the page itself, through **one guarded fetcher**, and caches the
result **by normalised address**:

1. `PageFetcher` accepts only `http`/`https`, judges every hop — the typed
   address and each redirect target — as a public unicast address, follows at most
   three redirects by hand, accepts only HTML (or `image/*` for a picture), and
   reads at most 512 KB.
2. `HostAddressResolver` is a platform port so `commonMain` can ask what a name
   resolves to at all. A name is refused when it does not resolve, and refused
   when **any** of its addresses is not public — the connection may land on any of
   them.
3. The preview is four strings and a fetch timestamp, cached in the settings
   `DataStore` under the normalised address. The image bytes go on disk in
   `preview_images/`, fetched through the same guarded path. **No HTML is stored.**
4. Dismissal is per **address**, not per task: the card is a property of "a task
   whose title names this link", so the same link in another task is the thing the
   user already dismissed. Hiding writes nothing to the task, which is what makes
   "hiding must not change the title" structural rather than a rule to remember.
5. The preview follows the **title draft**, so it appears as the user types and
   needs no save round trip. A fresh entry costs no request, which is what keeps
   navigation and recomposition free.

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

# Links

- Issue: #2953 — cached link previews for URLs in task titles.
- Related: #2902 (visual URL highlighting in the title), #2952 (rich description).
- Code: `shared/src/commonMain/kotlin/com/singularity/todo/core/linkpreview/`.
