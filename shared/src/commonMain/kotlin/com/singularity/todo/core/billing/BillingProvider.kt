package com.singularity.todo.core.billing

/**
 * Identifies the billing provider that granted a subscription.
 *
 * Used to determine which provider to query for entitlement status
 * and for routing support requests.
 */
enum class BillingProvider {
    /** Google Play Billing (Android). */
    GOOGLE_PLAY,
    /** GitHub Sponsors. */
    GITHUB_SPONSOR,
    /** Internal grant: promo code, lifetime access, or team license. */
    INTERNAL_GRANT,
}
