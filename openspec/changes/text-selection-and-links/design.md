# Design — text selection and link dispatch

Status: **accepted**

## The vocabulary

`core/links/InternalLinks.kt` is the only place that knows what an internal link is.

```
InternalLinkSchemes   the schemes the app owns: note, task
                      the separator, and the one rule for an identifier
InternalLinkTarget    a scheme and an identifier — the app's single link reference
InternalLinkMatch     a link found in a piece of text: where it is and what it is
InternalLinks         parse, findIn, urlFor
```

The identifier charset is `[A-Za-z0-9_-]+`. Identifiers are generated as UUIDs
today, which are a subset of it. The rule is stated as a rule because the *readers*
of a link — the queries that find backlinks, the handlers that open a tap — have to
agree on it, and "it happens to be a UUID" is not something a reader can rely on.

A scheme that is not ours is not ours: `xnote://id` is a link to somebody else's
thing, and the app must not open it as its own.

## The widgets

`core/ui/text/` holds them.

| Widget | What it is |
|---|---|
| `SelectableText` | one text in a selection container |
| `SelectableTextGroup` | a block of text selectable as one |
| `UnselectableContent` | an interactive child, out of the surrounding selection |
| `LinkifiedText` | one text whose links are tappable, and which is selectable too |
| `TextLinkDetector` | what counts as a link in a piece of text |
| `rememberAppUriHandler` | the platform's handler, with the app's schemes in front of it |
| `LocalInternalLinkHandler` | who opens an internal link, provided by the app's root |

### Why the selection is a group and not a container per row

A selection container owns a selection state. One around every row of a list gives
every row its own manager, and the selection does not survive the row scrolling out
of view. One around the block gives the block one manager, which is what a person
means by "copy this paragraph".

### Why an interactive child is excluded explicitly

A button inside a selectable block would be swallowed by a long press that was meant
to start a selection. `UnselectableContent` takes it out. A text that carries its
own selection container keeps it: a nested container has its own registrar, so
wrapping *that* would not take its selection away.

### Why the link is a `Clickable` and not a `Url`

`LinkAnnotation.Url` asks the platform to open the address. An internal address has
no meaning outside the app, and handing one to the platform produces either an error
dialog or a browser search for the string. `LinkAnnotation.Clickable` says "this is
the app's action", and the app decides what it is — the safe direction by default.

## The dispatch

```
tap on a link
   └─ TextLinkDetector / InternalLinks reads what the link is
        ├─ web     → rememberAppUriHandler → the platform's handler
        └─ internal → LocalInternalLinkHandler → internalLinkDestination → a screen
```

The note preview passes its own callback to `rememberAppUriHandler`, because a note
links to another note and moving between the two inside the notes graph is that
screen's business — the app-wide handler would take it to the top level.

## The registry

One table, in the skill `singularity-todo-selectable-text`: one row per file, the
treatment its texts have, and the reason when the answer is "excluded".

The treatment is *derived from the code* by the test — from the files' imports, the
same argument the text-field convention makes. A file that calls `SelectableText`
has to import it; a mention in a comment imports nothing.

The table is written out anyway, because the act of writing it down is the point: a
classification nobody wrote down is a classification nobody made.

## The two gates

| Gate | What it does | Mode |
|---|---|---|
| `SelectableTextConventionTest` | every widget user is classified; every row matches the code | blocking (a JVM test) |
| `check-selectable-text-coverage.py` | finds the prose that no row covers and prints it | advisory (no JVM) |

The first compares two exact facts, so it can be wrong in only one way. The second
has to guess what "prose" means from the text of a call, so it reports and passes;
it becomes blocking when the inventory is closed and its empty list is the assertion.
