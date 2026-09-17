---
title: "SelectorSerializer: plain KSerializer instead of JsonContentPolymorphicSerializer"
date: 2026-09-17
tags: [serialization, agenda, selector]
---

## Context

`Selector` is a sealed interface with 15 concrete subtypes used as filter predicates in `AgendaDefinition`. MR1 stored `Selector.Tag` with discriminator `"Tag"` and a single `id` field; MR2 renamed it to `Selector.Tags` with `ids: Set<TagId>`. Both formats must be readable during the transition.

kotlinx.serialization generates a `JsonContentPolymorphicSerializer` for `@Serializable sealed interface Selector`, which uses `serializer<Selector>().descriptor` internally. Since `Selector` is annotated `@Serializable(with = SelectorSerializer::class)`, calling `serializer<Selector>()` returns the custom serializer — causing infinite recursion when that serializer's `selectDeserializer` also calls `serializer<Selector>()`.

## Idea

Replace `JsonContentPolymorphicSerializer` with a plain `KSerializer<Selector>` that:
1. Overrides `descriptor` with `buildClassSerialDescriptor("Selector")` to break the recursion.
2. Overrides `serialize` to manually build `JsonObject` with `_type` for every branch — generated concrete serializers would omit the discriminator.
3. Overrides `deserialize` to read `_type` from the JSON object and reconstruct each selector type.

## Decision

`SelectorSerializer` in `feature/agenda/domain/model/Selector.kt` is a plain `object : KSerializer<Selector>`. The `@Serializable(with = SelectorSerializer::class)` annotation on the `Selector` interface activates it for all serialization paths.

**Descriptor** — `buildClassSerialDescriptor("Selector")` instead of `serializer<Selector>().descriptor`, which would recurse infinitely.

**serialize()** — manually constructs `JsonObject` with `_type` for all 15 branches:
```kotlin
is Selector.Tag -> JsonObject(
    mapOf("_type" to JsonPrimitive("Tag"), "id" to JsonPrimitive(value.id.value))
)
is Selector.Tags -> JsonObject(
    mapOf(
        "_type" to JsonPrimitive("Tags"),
        "ids" to JsonArray(value.ids.map { JsonPrimitive(it.value) }),
        "matchAll" to JsonPrimitive(value.matchAll),
    )
)
// ... all 15 types
encoder.encodeJsonElement(element)
```

Generated serializers (e.g. `serializer<Selector.Tags>()`) do NOT add `_type`, so they cannot be used — even for types that don't need migration. Every branch must be explicit.

**deserialize()** — reads `_type` from the JSON object and constructs each selector:
```kotlin
val type = element.jsonObject["_type"]?.jsonPrimitive?.content
    ?: throw SerializationException("Missing '_type' discriminator")
return when (type) {
    "Tag" -> Selector.Tag(TagId(...)) // MR1 legacy
    "Tags" -> Json.decodeFromJsonElement(serializer<Selector.Tags>(), element)
    // ... all types
}
```

## Rationale

A custom `KSerializer` gives full control over both encode and decode. The key insight is that the generated concrete serializer for e.g. `Selector.Tags` has no `@Serializable(with = ...)` annotation, so `serializer<Selector.Tags>()` is the generated serializer directly — no recursion. This lets us safely delegate deserialization to generated serializers for MR2+ types while handling the MR1 `Tag` case manually.

## Consequences

- **Always** build `JsonObject` with `_type` manually in `serialize()` for sealed interface serializers — generated serializers for concrete subtypes omit the discriminator.
- **Never** use `serializer<Selector>().descriptor` inside a custom `SelectorSerializer` — it returns the custom serializer itself, causing infinite recursion.
- The `@Serializable(with = ...)` annotation on the sealed interface activates the custom serializer for ALL paths including nested occurrences (e.g. `AllOf.children: List<Selector>`).
- MR1 JSON `{"_type":"Tag","id":"..."}` and MR2 JSON `{"_type":"Tags","ids":[...],"matchAll":false}` both round-trip correctly.
