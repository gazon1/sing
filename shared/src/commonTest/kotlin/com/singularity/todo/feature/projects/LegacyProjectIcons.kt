package com.singularity.todo.feature.projects

/**
 * The values a project icon can hold, shared by the boundary tests.
 *
 * `ProjectIconRegistry` was deleted with #2935, so no production code names these
 * keys any more — but rows written before it, and documents from older clients,
 * still carry them. Keeping one list means the Room, backup and sync tests cannot
 * drift apart, and a key that is mapped by mistake cannot pass one boundary and
 * fail another.
 */
internal val LEGACY_MATERIAL_KEYS: List<String> =
    listOf(
        "Home",
        "Work",
        "Star",
        "Folder",
        "FolderSpecial",
        "Note",
        "DateRange",
        "ShoppingCart",
        "Flight",
        "LocalHospital",
        "School",
        "FitnessCenter",
        "Pets",
        "MusicNote",
        "Code",
        "Build",
        "Anchor",
        "MoreHoriz",
    )

/**
 * Composed sequences: ZWJ person and family, flag, skin tone, variation selector and
 * keycap are each several code points that must not be split or normalized.
 */
internal val COMPOSED_PROJECT_ICONS: List<String> =
    listOf("🧑‍💻", "🇬🇧", "🧘🏽", "🕊️", "👨‍👩‍👧‍👦", "1️⃣")

/** Every value a boundary test runs through: the retired keys, unknown text and composed sequences. */
internal val STORED_PROJECT_ICONS: List<String> =
    LEGACY_MATERIAL_KEYS + listOf("NotAKey", "marketing", "   ", "🏠") + COMPOSED_PROJECT_ICONS
