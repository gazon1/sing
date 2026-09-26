---
name: ux-a11y-review
description: Accessibility and UX review checklist for UI changes — contrast, touch targets, content descriptions, keyboard navigation, screen reader support.
---

# UX / Accessibility Review

## When to use

- Before merging any UI change (new screen, new component, layout change)
- When reviewing a PR that adds or modifies user-facing UI
- When a user reports a usability issue

## Prerequisites

- Material 3 Design guidelines
- WCAG 2.1 AA standards
- Android accessibility checklist

## Step-by-step

### Step 1 — Visual review

Check these without screen reader or keyboard:

- [ ] Color contrast ≥ 4.5:1 for text (WCAG AA)
- [ ] Color contrast ≥ 3:1 for large text and UI components
- [ ] Touch targets ≥ 48dp × 48dp
- [ ] No information conveyed by color alone (use icons + color)
- [ ] Text scales correctly (test at 200% font size)
- [ ] Layout doesn't break at small screen sizes (360dp width)

### Step 2 — Keyboard / D-pad navigation

For Desktop and Android TV:

- [ ] All interactive elements are focusable
- [ ] Focus order follows visual order
- [ ] Focus is visible (custom focus indicator)
- [ ] Tab cycles through all interactive elements
- [ ] Enter/Space activates focused element
- [ ] Escape closes sheets/dialogs

### Step 3 — Screen reader (TalkBack / VoiceOver)

- [ ] All images have `contentDescription` (or `null` if decorative)
- [ ] Buttons announce their action, not just their label
- [ ] Custom components have proper `semantics`
- [ ] Reading order is logical (use `Modifier.testTag` for automation)
- [ ] Dialogs announce their title and role
- [ ] Lists announce item count and current position

### Step 4 — Motion and animation

- [ ] Animations respect `reduce motion` system setting
- [ ] No flashing content (>3 flashes per second)
- [ ] Transitions are ≤ 300ms

### Step 5 — Content and copy

- [ ] Error messages are specific ("Password must be ≥ 8 chars" not "Invalid password")
- [ ] Empty states have a message and call to action
- [ ] Loading states are indicated (skeleton, spinner, or progress bar)
- [ ] No placeholder text that could be mistaken for real content

## Common pitfalls

1. **Missing contentDescription on icons** — decorative icons should have `null`, not empty string
2. **Focus order mismatch** — often broken when using `Row` with custom order vs visual order
3. **Color-only information** — red/green is the most common accessibility violation
4. **Insufficient touch target size** — buttons near the edge of the screen often < 48dp
5. **Reading order in complex layouts** — `Column` vs `Row` changes the implicit reading order

## Decision tree: when to block

```
REVIEWER SEES ISSUE
  │
  ├─── Contrast ratio < 4.5:1?
  │         YES → BLOCK — WCAG AA violation
  │         NO  → Continue
  │
  ├─── Touch target < 48dp × 48dp?
  │         YES → BLOCK — Android touch target requirement
  │         NO  → Continue
  │
  ├─── Interactive element not focusable?
  │         YES → BLOCK — keyboard navigation broken
  │         NO  → Continue
  │
  ├─── Image without contentDescription?
  │         YES → BLOCK — screen reader cannot describe it
  │         NO  → Continue
  │
  ├─── Information conveyed by color alone?
  │         YES → BLOCK — colorblind users cannot see it
  │         NO  → Continue
  │
  └─── All checks pass → APPROVE (with non-blocking suggestions)
```

## Output template

```
## UX/A11y Review: PR-XXX

**Visual:** PASS / FAIL
- [ ] Contrast: FAIL — button text #AAAAAA on #FFFFFF (2.8:1)
- [ ] Touch targets: PASS
- [ ] Color-only info: PASS

**Keyboard/D-pad:** PASS / FAIL
- [ ] Focus order: FAIL — settings icon before "Back" in header
- [ ] Focus visible: PASS

**Screen reader:** PASS / FAIL
- [ ] Content descriptions: FAIL — tag icon has empty string
- [ ] Reading order: PASS

**Motion:** PASS / FAIL
- [ ] Reduce motion: PASS (verified with system setting)

**Blocking issues:** 2
**Non-blocking suggestions:** 3
```
