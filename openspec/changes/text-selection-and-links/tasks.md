# Tasks — text selection and link dispatch

Status: **accepted**

## Stage 0 — one owner for the vocabulary

- [x] `core/links/InternalLinks.kt` — the schemes, the identifier rule, `parse`,
      `findIn`, `urlFor`
- [x] `InternalLinkTarget` replaces the two link-reference types
- [x] The notes' own `LinkSchemes.kt` is deleted, not re-exported
- [x] The two copies of the extractor become one
- [x] The task description's extractor reads the shared rule
- [x] `InternalLinksTest`, `InternalLinksAgreementTest`

## Stage 1 — the mechanics

- [x] `core/ui/text/TextLinks.kt` — `TextLink`, `TextLinkSpan`, `TextLinkDetector`
- [x] `core/ui/text/SelectableText.kt` — `SelectableText`, `SelectableTextGroup`,
      `UnselectableContent`
- [x] `core/ui/text/LinkifiedText.kt` — `LinkifiedText`
- [x] `core/ui/text/AppUriHandler.kt` — `rememberAppUriHandler`
- [x] `core/ui/text/LocalInternalLink.kt` — `LocalInternalLinkHandler`
- [x] `feature/nav/InternalLinkNavigation.kt` — where an internal link points
- [x] The app's root provides the handler, on both platforms
- [x] `TextLinkDetectorTest`, `InternalLinkNavigationTest`

## Stage 2–3 — the surfaces

- [x] `MessageBubble` — the assistant's answer is linkified
- [x] `TaskDetailProposalSection` — the model's summary is linkified
- [x] GenUI `TextRenderer` — a generated screen's text is linkified
- [x] `NotePreviewScreen` — the body is selectable; its links keep their own callback

## Stage 4 — the inventory

- [x] `LogbookSection` — note titles and time rows
- [x] `LinkedBacklinksCard` — the titles of the notes that link back
- [x] `AttachmentAnnotationPanel` — the quote and the annotation's text
- [x] `CalendarSyncSettingsScreen` — the failure messages
- [x] `TimeTrackingSection` — the section's message
- [x] `BackupScreen` — the backup id
- [x] `AiUsageScreen` — the model id and the tool name
- [x] `AiProviderSettingsScreen` — the provider and model ids
- [x] `StatisticsScreen` — the project names
- [x] `SearchScreen` — the titles of the note, project and tag results

## Stage 5 — the gates

- [x] `SelectableTextScan` — the treatment of a file, derived from its imports
- [x] `SelectableTextConventionTest` — the blocking reader of the registry
- [x] `check-selectable-text-coverage.py` — the advisory scan, and a line in the
      static gate registry

## Stage 6 — the documents

- [x] ADR `2026-10-21-text-selection-and-link-dispatch`
- [x] Skill `singularity-todo-selectable-text` — the registry and the rules
- [x] `SKILLS-CATALOG.md` regenerated

## Not done, and why

- [ ] The backlink query still interpolates the identifier into a match pattern
      unescaped — its own task, `one-grammar-for-internal-links`
- [ ] The advisory scan's list is not empty: 37 surfaces render prose and are not
      classified. Some are labels that do not need to be selectable; the rest are the
      next surfaces to convert.
