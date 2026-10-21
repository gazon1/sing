---
title: The kill switch was never applied
date: 2026-10-20
status: accepted
tags: [mcp, tools, ai, config]
---

# The kill switch was never applied

## Context

`RemoteConfigSnapshot.mcpToolFlags` is a map from a tool name to a boolean. A tool whose
flag is `false` is meant to be taken out of the loop — an operational control for a tool
that misbehaves in production, without shipping a new build.

Both consumers of the flags called the same rule, `filterTools` in
`shared/src/commonMain/kotlin/com/singularity/todo/core/ai/AiToolFilters.kt`, which keys
the flags by the tool's **ID**:

```kotlin
fun filterTools(tools: List<Tool<*, *>>, flags: Map<String, Boolean>): List<Tool<*, *>> =
    tools.filter { tool ->
        val id = tool.toolId()
        id == null || flags[id] != false
    }
```

`toolId()` read the `NAME` constant of the tool class by reflection:

```kotlin
val companion = clazz.declaredClasses.find { it.simpleName == "Companion" }
companion?.getDeclaredField("NAME")?.apply { isAccessible = true }?.get(null) as? String
    ?: clazz.getDeclaredField("NAME").apply { isAccessible = true }.get(null) as? String
```

The `?: ` fallback looks like it covers a missing field on the companion. It does not:
`getDeclaredField` on a class that has no such field **throws**
`NoSuchFieldException`, and the throw happened *before* the fallback could run. The
enclosing `catch (e: Throwable) { null }` turned it into a quiet `null`.

And the constant is not on the companion. The Kotlin compiler compiles a `const val` in a
companion object as a static field of the **containing class** — verified against the
shipped bytecode:

```
$ javap -p shared/build/classes/kotlin/jvm/main/.../GetTaskTool.class
  public static final ...GetTaskTool$Companion Companion;
  public static final java.lang.String NAME;      ← on the class

$ javap -p shared/build/classes/kotlin/jvm/main/.../GetTaskTool$Companion.class
public final class ...GetTaskTool$Companion {
  private ...GetTaskTool$Companion();
  public ...GetTaskTool$Companion(kotlin.jvm.internal.DefaultConstructorMarker);
}                                                  ← no fields
```

So `toolId()` returned `null` for **every** tool in the project. A tool with no ID has
no flag key, and `filterTools` treats that as "not disabled". The switch was inert: a
tool flagged `false` stayed enabled, in the app and in the MCP server alike.

The switch also *looked* alive, in both directions: the code that consumed the flags was
present, the flag keys were the real tool names, and the configuration path carried them
from the remote gateway to the cache to the consumer. Nothing was missing except the
constant lookup that connects the two ends.

The defect was found by a test written for a different purpose. The new MCP test
asserted the property the design leans on — *a switched-off tool is never registered* —
and it failed on the first run. The failure was read as "the test is wrong" twice before
the actual cause was found.

## Idea

- **A.** Leave the rule alone; it is the documented policy, and the test is what is
  wrong.
- **B.** Fix the fallback so the ID resolves, and pin it with a test.
- **C.** Replace reflection with a compile-time mechanism: every tool declares its ID in
  an interface, and the flags are checked against that.

## Decision

**B**, now — with the test that found it kept as the gate.

* `toolId()` looks in **both** places: the companion first, then the class itself, and a
  miss in one place ends neither the search nor the function. Each lookup is guarded
  separately, so a field that cannot be opened is a `null` for that place only.
* The ID is pinned by `AiToolFiltersTest`, with a tool class of **each** shape — one
  declared at the top level of the file, one nested inside the test class. The two shapes
  are indistinguishable in the source of the tool, and only the bytecode differs, so a
  test that covers one of them leaves the other silently unprotected.
* The MCP-side test keeps its own claim: a switched-off tool is not registered at all,
  and the snapshot names it as disabled.

C was not taken now. It is the better shape, and it removes a reflection from a hot
path, but it changes the contract of every tool class and every flag key at once — a
larger change than this one, and it should not ride along with a defect fix.

## Addendum — the reflection is gone (2026-10-20, later the same day)

C's *substance* was taken after all, in the narrow form it needed: the name is not read
out of the bytecode at all.

`filterTools` keys the flags by `tool.descriptor.name` — the name Koog registers the
tool under, the name the catalogue declares, and the name the agent calls. There is
nothing to look up, and nothing to look up *wrongly*. The reflection and the function
that carried it are deleted.

What made this safe is an invariant that was already enforced and had never been
written down: the runtime wiring test resolves the real Koin list on the JVM and on
Android and compares it with the catalogue, so *the descriptor's name is the catalogue's
name* is checked on both platforms, every build. `AiToolFiltersTest` now states the
other half — that the descriptor's name is the class's `NAME` constant, for both shapes
of class — and the flag key is the same string everywhere.

The flag key is therefore one fact with one owner. The earlier shape had two: a constant
in the bytecode and a string in a configuration, and the gate between them was a
reflection that failed silently.

## Addendum — a flag that matched nothing is reported (2026-10-20)

The switch works now, and the second half of the operator's picture was still missing: a
flag that names **no** tool. It matches nothing, so it switches nothing off — and the
operator who set it believes the tool is off, while it is exposed and callable.

`describe_toolbox` now lists those flags under `unmatchedFlags`, and the server logs them
at startup. They are not "disabled tools": a flag that matched nothing did not disable
anything, and reporting it as a disabled tool would be a different kind of lie.

The distinction is the whole point. Three facts, three answers:

- a flag that names a tool and says `false` — the tool is **disabled**;
- a flag that names nothing — the flag is **unmatched**, and the operator is told;
- no configuration at all — the status is **unavailable**, and the policy is fail-open.

## Rationale

The defect is not that the constant was in an unexpected place. It is that a lookup that
could fail was written as if it could not, behind a `catch` that turned the failure into
"no ID" — and "no ID" was interpreted as "no opinion", which is the one reading that
makes the switch invisible. A rule that cannot fail loudly must fail loudly somewhere
else; here, nowhere did.

The fix is deliberately narrow: the policy — *flags keyed by ID, missing means enabled* —
is unchanged, and only the lookup that feeds it is corrected. The alternative would have
been to change the policy to fail closed, which would have masked the broken lookup
behind a different symptom: a toolbox that loses tools for no visible reason.

Pinning both shapes matters because the shape of the class is not visible in the source
of the tool: `const val NAME` in a companion reads identically in both cases, and only
the bytecode differs. A test that covers one shape would leave the other silently
unprotected — and the shape is exactly what the broken lookup was sensitive to.

## Consequences

- The kill switch works now, for the first time. A tool flagged `false` disappears from
  `tools/list` and cannot be called; the in-app agent stops receiving it too.
- The flags are read once at startup in the MCP server, so the exposed toolbox is stable
  for the session — unchanged by this decision.
- `configurationStatus` keeps its meaning: `current`, `cached`, `stale`, `unavailable`
  or `error` describes where the *configuration* came from. It says nothing about
  whether a flag matched a tool, which is what the disabled list in `describe_toolbox`
  is for.
- Two detekt baseline entries became stale and were removed rather than left to rot.
- The claim "the in-app agent has honoured these flags since the flags existed" was
  false, and it was repeated in the architecture document, in the change proposal and in
  the registrar's own documentation. All three now say what is true.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ai/AiToolFilters.kt` — the rule
  and the lookup
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ai/AiToolFiltersTest.kt` — the
  ID, in both class shapes
- `mcp-server/src/test/kotlin/com/singularity/todo/mcp/ToolRegistrarTest.kt` — the switch
  where it is applied
- `docs/architecture/mcp-server.md` — the surface as it is now
- `openspec/changes/agent-toolbox-surface/design.md` — finding F16
