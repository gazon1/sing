# the-orphan-bindings-are-bound-but-never-injected

Status: **deferred**
Tags: **koin di orphan-binding**

## What

Koin bindings are declared and never injected. They are no-ops at
runtime, and the unwired-surfaces detector reports them:

| Binding | Module |
|---|---|
| `single<AuthGateway>` | `shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt` |
| `single<DeviceIdProvider>` | `shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt` |

A binding nobody injects is not a binding. It is a line that reads like a
promise, and the graph verification the Koin compiler plugin performs does not
catch it: the plugin proves that what *is* injected can be constructed, not
that every declaration has a consumer.

The failure mode this creates is specific. A feature is written against one
of these interfaces, the interface is satisfied by the binding, and the
feature works — until the binding is deleted as "unused", at which point the
feature stops compiling and the deletion looks like it was touching an
unrelated file. Or: a second implementation is added, the binding is changed
to return it, and nothing changes in the app, because nothing asked for the
first one.

## What this change does not do yet

It is **deferred**, and the deferral is deliberate. Each of the two needs a
product answer before it can be closed, and guessing would be worse than
leaving them visible:

- **`AuthGateway`** — is authentication a thing the app still has? If yes,
  who consumes the gateway? If no, the interface and the binding both go.
- **`DeviceIdProvider`** — the device id is needed by sync, by the backup
  format, or by neither. The answer decides whether the binding is the
  provider or the provider is dead.

Closing them by deleting the bindings alone would be the wrong move: the
interfaces would remain, and the *next* binding to them would be the same
orphans. Closing them by inventing consumers would be worse: it would wire
features nobody asked for.

## The third binding, since closed

The third orphan this change was written about, `single<GenuiTransport>`
(`feature/genui/di/GenuiDiModule.kt`), is gone. The screen's transport seam was
the last place in the app that could make a second model call, and the change
`the-turn-is-one-model-call` deleted it rather than give it a consumer: the
turn asks the model now, and a seam with no traffic is a seam that was designed
and never used. The interface went with the binding, which is the closure this
change asks for — not the "delete the binding and leave the hole" it warns
against.

## What not to do

- **Do not delete a binding because it is unreferenced.** The interface is the
  claim; the binding is one way of satisfying it. Deleting the binding
  without deciding the claim leaves the hole where the code was.
- **Do not add a consumer to make the detector quiet.** A consumer nobody
  wanted is a feature that exists to satisfy a gate.
- **Do not teach the graph test "every binding must be injected".** That rule
  would ban the deliberate seams — a binding that exists so a later change
  has a place to land, declared on purpose.

## Consequences

- The two remaining bindings stay visible in the detector's output until each
  one is decided.
- The detector's finding is the record: two specific interfaces, two specific
  modules, and a question attached to each.
- The third one is closed: `GenuiTransport` is gone, and its interface with it.
- The graph test does not claim to prove what it cannot prove. It proves that
  what is injected can be constructed; it does not claim that every
  declaration has a consumer.
