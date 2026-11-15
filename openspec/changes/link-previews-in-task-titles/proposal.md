# A task title's link shows a preview of the page it names

Status: **active**
Tags: **tasks link-preview security cache**

Related: ADR `2026-11-14-link-preview-is-fetched-and-cached-by-address`; issues
[#2953](https://github.com/gazon1/sing/issues/2953) (this change),
[#2902](https://github.com/gazon1/sing/issues/2902) (URL highlighting in the title),
[#2952](https://github.com/gazon1/sing/issues/2952) (rich description).

## What

A task title is plain text. People paste links into it — an article to read, a
ticket, a document — and the link is all the screen shows: a run of coloured
characters that says nothing about what is behind it.

This change adds the Telegram-style preview: under the title in Task Detail, a
compact card with the site's name, the page's title, a short description and, when
the page offers one, a picture.

## What it does not change

- **The title stays plain text.** No markup, no rich links, no change to the
  description editor. The URL is extracted with the same detector that already
  underlines it (#2902), so there is no second opinion about what an address is.
- **Nothing is fetched from a task list.** The card is Task Detail only.
- **No AI.** The card is what the page says about itself. #2953 makes
  summarisation opt-in, and this change takes the smaller half.

## Why it is not just "load the og:image"

Three parts of this are decisions rather than plumbing:

- **The address is untrusted input.** It was typed by a person or arrived over
  sync. Fetching whatever it names would let a title point the app at its own host,
  at a private network, at a file, or at a redirect chain that ends there. So the
  fetcher judges **every hop** against an allowlist of public addresses, and asks a
  platform port what a name resolves to, because a name alone proves nothing.
- **The picture's address is equally untrusted.** It comes from the page, so it is
  fetched through the same guarded path and stored on disk — not handed to the image
  loader as a remote URL, which would skip every check the page went through.
- **The preview must cost nothing to look at.** Reading the cache is what
  recomposition does; the network is asked once per address per six hours. The
  cache is keyed by the normalised address, not by the task, so two tasks quoting
  the same page cost one fetch.

## The change

- `core/linkpreview/` — the rule for which address a title previews, the metadata
  reader, the public-address judge, the guarded fetcher, the cache and the
  repository. `HostAddressResolver` is the one platform port.
- `TaskLinkPreviewSlot` — the screen's slice: the title draft drives it, a fresh
  cache entry costs no request, and a failure is a quiet line rather than an error.
- `LinkPreviewCard` — drawn inside the hero, between the title and the
  description.
- A setting, on by default, that stops the fetch before it is made when off.
