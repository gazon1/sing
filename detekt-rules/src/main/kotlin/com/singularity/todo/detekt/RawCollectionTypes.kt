package com.singularity.todo.detekt

/**
 * The collection types that break a stability promise.
 *
 * A `List` is the obvious one: it is an interface, so the Compose compiler
 * cannot know that the same list handed twice will not change, and a class
 * that holds one cannot honestly promise to be immutable. The other names here
 * are the same promise broken in other spellings:
 *
 * - `MutableList`, `MutableSet`, `MutableMap` and their concrete forms
 *   (`ArrayList`, `HashSet`, `HashMap`) are mutable by name — the instance
 *   itself changes;
 * - `Array` is mutable by nature: its elements can be replaced, and the
 *   compiler treats it as unstable;
 * - `Collection` is an interface like `List`, only wider.
 *
 * What is deliberately absent: `Iterable` and `Sequence`. They are lazy
 * interfaces — rare as a parameter of a composable, and a rule that reports
 * rarely is a rule people turn off. Their absence is a statement about how
 * often a rule fires, not about whether they are unstable: they are.
 *
 * The set lives here because two rules answer the same question — "is this
 * type a raw collection?" — and a set that drifts apart is a rule that
 * contradicts the other one.
 */
internal object RawCollectionTypes {

    /** The names of the raw collection types, as the source spells them. */
    val NAMES: Set<String> = setOf(
        "List",
        "MutableList",
        "ArrayList",
        "Set",
        "MutableSet",
        "HashSet",
        "Map",
        "MutableMap",
        "HashMap",
        "Collection",
        "Array",
    )
}
