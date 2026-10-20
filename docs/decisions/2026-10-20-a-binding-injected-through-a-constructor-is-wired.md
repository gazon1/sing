---
title: "A binding injected through a constructor is wired"
date: 2026-10-20
status: accepted
tags: [tooling, di, gates]
---

## Context

`scripts/find-unwired-surfaces.py` is the gate that answers "is there code in
this repo that nothing reaches?". It has a detector for Koin bindings: a
`single<X>` that no `get<X>()` / `inject<X>()` / `koinViewModel<X>()` ever
names is a binding that will never be injected, so the feature behind it is
unreachable no matter how green the build is.

The gate was advisory, with a floor of three findings and no positive
control. The three findings were:

```
single<AuthGateway> is bound but no get/inject/koinViewModel call references it
single<DeviceIdProvider> is bound but no get/inject/koinViewModel call references it
single<GenuiTransport> is bound but no get/inject/koinViewModel call references it
```

All three are false positives, and all three are false for the same reason.
Here is how `AuthGateway` is actually injected:

```kotlin
single<AuthRepository> {
    SupabaseAuthRepository(
        log = Logger.withTag("AuthRepository"),
        gateway = get(),
    )
}
```

Koin's `get()` takes its type from the parameter it fills. The call site
never spells `AuthGateway`, so a scan that only recognises `get<Name>()`
cannot see the injection — and the fix the gate had was an allowlist:
`DECLARED_INTENT`, thirty-odd names, each of which is a *claim* that
somebody audited by hand. A claim list is a list that rots: the next
constructor that gains a parameter makes the claim wrong, and nothing notices.

## Idea

- **A.** Add the three names to the allowlist and make the gate blocking.
  The gate goes green on a list of assertions nobody re-checks.
- **B.** Teach the detector to resolve the type behind a bare `get()`, and make
  the gate blocking on that.
- **C.** Leave the gate advisory and move on. The findings are known and
  documented.

## Decision

**B.** The detector resolves the missing fact instead of claiming it.

- Every class's constructor parameters are indexed (`class Foo(a: A, b: B)` →
  `Foo → [(a, A), (b, B)]`), over production sources only.
- For each bare `get()` in production code, the detector finds the call that
  contains it and reads the type of the parameter it fills — by **name** when
  the argument is named (`gateway = get()`), by **position** when it is not
  (`GenuiSession(get(), get(), …)`).
- A constructor reference (`viewModelOf(::MyViewModel)`) is the same fact
  written differently — Koin fills that constructor's parameters from the
  graph — so its parameter types count as consumed too.
- The resolution is restricted to production sources: a binding that only a
  test injects is unwired in production, which is the whole point of the
  shape.

The floor goes to zero, the gate becomes **blocking**, and the two
advisory exemptions it carried are replaced by real sabotage controls:

- `unwired-surfaces` — the control appends a binding nothing injects to a real
  DI module, and the report has to name it.
- `script-conventions` — the control adds an `argparse` import to a gate
  script, and the report has to name it.

The `script conventions` gate was advisory for a second reason, fixed in the
same change: it detected `argparse` by looking for the *substring* `"import
argparse"`, which matches its own detection needle. The checker reported
itself as a violation, so the corpus was never green, and a green corpus is
the precondition for a control that can tell "caught the mutation" from
"still failing the known violations". The check now parses the module and
looks for a real import.

## Rationale

- **A gate that cannot fail is a gate that reports success forever.** The
  advisory status of both gates was a statement about the *corpus*, not about
  the *checker*: the corpus is red, so a control could not distinguish a
  caught mutation from a pre-existing failure. Once the corpus is green, the
  same gate can carry a control, and a control is what makes a gate's green
  mean something.
- **The allowlist was a workaround for a detector that could not follow a
  constructor.** Every entry in it says "consumed via constructor injection —
  the static scan cannot track that". Thirty names is thirty places where the
  scan is known to be wrong, and each fix was a manual audit rather than a
  change in the detector. The detector now follows the constructor, so the
  scan is right for the reason the allowlist said it was not.
- **A finding that names a false thing trains the reader to ignore the
  gate.** Three findings that are wrong in the same way, on a gate that is
  advisory, is a gate whose output nobody reads — and the next real orphan
  arrives in that same stream.
- **The false positive was not a small imprecision.** It was the *shape* of
  the most common injection in the codebase: a repository or a client
  constructed inside a binding with `get()` for its collaborators. The
  detector was blind to the normal case and clear-sighted about the unusual
  one.
- **C was never acceptable.** The gate is the only thing that notices a
  feature nobody can reach. Leaving it advisory means the answer to "is this
  wired?" is "the gate says so, but nobody checks".

## Consequences

- **The gate is blocking now.** A new orphan binding fails the build instead
  of being counted.
- **The allowlist is still there** for the shapes a static scan cannot
  follow — default arguments, interface-typed bindings, stubs declared for a
  future change. It is shorter than it was, and what is left in it is a
  decision rather than a blind spot.
- **The detector is a heuristic, and it is a stricter one.** A type named in
  a generic position still counts as consumed, as before; the constructor
  resolution adds a second, more precise way to be consumed. A false
  negative — a binding reported as wired because some constructor takes a
  parameter of that type — is possible, and is the price of not demanding
  that every injection be spelled out.
- **The two gates' clean runs are now checked by the same wiring gate that
  checks the others.** A blocking gate without a control would have been a
  new hole in the one place this repo looks for holes.

## Links

- `scripts/find-unwired-surfaces.py` — the constructor index and the
  resolution
- `scripts/tests/test_find_unwired_surfaces.py` — the resolution in both
  directions: a binding injected through a constructor is wired, a binding
  nothing injects is still reported
- `scripts/src/sgate/commands/_registry.py` — the two controls
- `config/docs/unwired-surfaces-floor.txt` — the floor, now zero
