---
status: accepted
date: 2026-09-26
---

# Internationalization

## Context

The app is English-only currently. This ADR defines the approach for adding i18n support when it becomes necessary.

## Decision

### When to add i18n

Add i18n support when:
- User-facing strings exceed 100 hardcoded strings
- A user requests non-English support
- The app is launched in a non-English market

### Approach

**Resource strings for all user-facing text:**

```kotlin
// ❌ WRONG — hardcoded string
Text("Are you sure?")

// ✅ CORRECT — string resource
Text(stringResource(R.string.confirmation_dialog_body))
```

**String resource file structure:**

```
res/
└── values/
    └── strings.xml          # English (default)
res/
└── values-ru/
    └── strings.xml          # Russian (when added)
```

### What to internationalize

| Content | Internationalize? |
|---|---|
| UI labels and buttons | Yes — all |
| Error messages | Yes — all |
| Date/time formatting | Yes — use `DateTimeFormatter` with locale |
| Number formatting | Yes — use `NumberFormat` with locale |
| Pluralization | Yes — use `Plurals` resource |
| Currency | Yes — use `NumberFormat.getCurrencyInstance(locale)` |
| Internal logs | No |
| File paths | No |
| Code identifiers | No |

### Date/time

Use `kotlinx.datetime` with explicit locale-aware formatting:

```kotlin
// ❌ WRONG — locale-ignorant
val formatted = instant.toString()

// ✅ CORRECT — locale-aware
val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
val formatted = formatter.format(instant)
```

### RTL support

When Arabic or Hebrew support is added:
- Use `Modifier.layoutDirection` for explicit RTL
- Test at 100% and 200% font scaling
- Verify icons flip correctly (arrows, chevrons)

### Number formatting

```kotlin
// ❌ WRONG
val text = "Score: ${count / total * 100}%"

// ✅ CORRECT
val percent = NumberFormat.getPercentInstance(locale).format(count.toDouble() / total)
val text = "Score: $percent"
```

## Consequences

- Adding a new string: add to `strings.xml`, not inline
- Date/number formatting: always use locale-aware APIs
- RTL: plan for it when adding the first RTL language
