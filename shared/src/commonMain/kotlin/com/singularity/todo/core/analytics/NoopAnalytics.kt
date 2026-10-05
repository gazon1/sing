package com.singularity.todo.core.analytics

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — no-op implementation only.
//   Full registry: docs/legal/PROVENANCE.md

/**
 * No-op analytics implementation. All methods are safe no-ops —
 * useful when no real SDK is connected or when the user has opted out.
 */
class NoopAnalytics : Analytics {
    override fun logEvent(event: String, vararg params: Pair<String, Any>) {
        // no-op
    }

    override fun identify(distinctId: String) {
        // no-op
    }
}
