# Tasks — link previews in task titles

Status: **active**

## Stage 0 — the pure rules

- [x] `PreviewUrls` — which address a title previews, the cache key, relative
      address resolution
- [x] `PageMetadataParser` — Open Graph first, HTML fallbacks, entities decoded
- [x] `PublicAddresses` — an allowlist of public unicast addresses, IPv4 and IPv6
- [x] `PreviewUrlsTest`, `PageMetadataParserTest`, `PublicAddressesTest`

## Stage 1 — the guarded fetch

- [x] `HostAddressResolver` port + `JvmHostAddressResolver` / `AndroidHostAddressResolver`
- [x] `PageFetcher` — scheme, every hop judged, three redirects, content type, size
- [x] `PageFetcherTest` against a `MockEngine`
- [x] Seam registry row and the `PLATFORM-REFERENCE` ports row

## Stage 2 — the cache

- [x] `LinkPreviewCache` — one entry per normalised address, images on disk, a bound
- [x] `LinkPreviewRepositoryImpl` — cache read, refresh window, guarded image fetch
- [x] `LinkPreviewCacheTest`, `LinkPreviewRepositoryTest`

## Stage 3 — the screen

- [x] `TaskLinkPreviewSlot` + `TaskLinkPreviewIntent` + `RefreshLinkPreview` /
      `HideLinkPreview` intents
- [x] `TaskDetailExtras.Ready.linkPreview`, coordinator merge and routing
- [x] `LinkPreviewCard` + `TaskLinkPreview`, drawn in the hero
- [x] `TaskLinkPreviewSlotTest`

## Stage 4 — the switch

- [x] `linkPreviewsEnabled` in the tasks settings, read before any request
- [x] The settings screen row and its test tag

## Stage 5 — the record

- [x] ADR `2026-11-14-link-preview-is-fetched-and-cached-by-address`
- [x] This change, and its entry in `.specify/issues/`
