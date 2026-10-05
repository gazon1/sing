package com.singularity.todo.core.analytics

// Provenance: ADAPTED from Tasks.org (GPL-3.0) — event naming conventions only.
//   Full registry: docs/legal/PROVENANCE.md

/**
 * Canonical analytics event names and parameter keys.
 *
 * Mirrors Tasks.org naming conventions for familiarity:
 * - Screen events: `SCREEN_*`
 * - Action events: `ADD_*`, `COMPLETE_*`, `CREATE_*`
 * - Settings events: `SettingsClick.*`
 * - Cloud onboarding: `CloudOnboarding.*`
 * - Parameters: `PARAM_*`
 *
 * @see Analytics.logEvent
 */
object AnalyticsEvents {
    // ─── Screen views ───────────────────────────────────────────────────

    const val APP_OPENED = "Application Opened"
    const val APP_BACKGROUNDED = "Application Backgrounded"
    const val SCREEN_ADD_ACCOUNT = "screen_add_account"
    const val SCREEN_PRICING = "screen_pricing"
    const val SCREEN_RESTORE_PURCHASES = "screen_restore_purchases"
    const val SCREEN_WELCOME = "screen_welcome"

    // ─── Task actions ─────────────────────────────────────────────────

    const val ADD_TASK = "add_task"
    const val COMPLETE_TASK = "complete_task"
    const val CREATE_LIST = "create_list"
    const val CREATE_TAG = "create_tag"

    // ─── Account / auth ────────────────────────────────────────────────

    const val ADD_ACCOUNT = "add_account"
    const val SYNC_ADD_ACCOUNT = "sync_add_account"
    const val SIGN_IN_ERROR = "sign_in_error"
    const val SIGN_IN_PROVIDER_SELECTED = "sign_in_provider_selected"

    // ─── Billing / pricing ─────────────────────────────────────────────

    const val PRICING_BILLING_TOGGLE = "pricing_billing_toggle"
    const val PRICING_SIGN_IN_CLICK = "pricing_sign_in_click"
    const val PRICING_SPONSOR_CLICK = "pricing_sponsor_click"
    const val PRICING_SUBSCRIBE_CLICK = "pricing_subscribe_click"

    // ─── Restore purchases ────────────────────────────────────────────

    const val RESTORE_ERROR = "restore_error"
    const val RESTORE_NOT_SPONSOR = "restore_not_sponsor"
    const val RESTORE_SELECTION = "restore_selection"
    const val RESTORE_SPONSOR_CLICK = "restore_sponsor_click"
    const val RESTORE_SUCCESS = "restore_success"

    // ─── Sync ─────────────────────────────────────────────────────────

    const val INITIAL_SYNC_COMPLETE = "initial_sync_complete"
    const val SYNC_UNKNOWN_ACCESS = "sync_unknown_access"

    // ─── Onboarding ───────────────────────────────────────────────────

    const val ONBOARDING_COMPLETE = "onboarding_complete"

    // ─── Settings clicks ───────────────────────────────────────────────

    object SettingsClick {
        const val DELETE_LIST = "settings_delete_list"
        const val DELETE_TAG = "settings_delete_tag"
        const val WHATS_NEW = "settings_whats_new"
        const val RATE_TASKS = "settings_rate_tasks"
        const val DOCUMENTATION = "settings_documentation"
        const val ISSUE_TRACKER = "settings_issue_tracker"
        const val CONTACT_DEVELOPER = "settings_contact_developer"
        const val SEND_LOGS = "settings_send_logs"
        const val REDDIT = "settings_reddit"
        const val TWITTER = "settings_twitter"
        const val SOURCE_CODE = "settings_source_code"
        const val THIRD_PARTY_LICENSES = "settings_third_party_licenses"
        const val TOS = "settings_tos"
        const val PRIVACY_POLICY = "settings_privacy_policy"
    }

    // ─── Cloud onboarding funnel ──────────────────────────────────────

    const val CLOUD_ONBOARDING = "cloud_onboarding"

    object CloudOnboarding {
        const val TRIGGERED = "triggered"
        const val WELCOME = "welcome"
        const val SIGN_IN = "sign_in"
        const val SIGNED_IN = "signed_in"
        const val CREATE_LIST = "create_list"
        const val DONE = "done"
    }

    // ─── Content provider ─────────────────────────────────────────────

    const val CONTENT_PROVIDER_API = "cp_api"
    const val CONTENT_PROVIDER_TASKS = "cp_tasks"
    const val CONTENT_PROVIDER_ASTRID2 = "cp_astrid2"

    // ─── AI ───────────────────────────────────────────────────────────

    const val MCP_TOOL_USED = "mcp_tool_used"
    const val APP_FUNCTION_CALL = "app_function_call"
    const val APP_INTENT_CALL = "app_intent_call"

    // ─── Misc ────────────────────────────────────────────────────────

    const val SORT_CHANGE = "sort_change"

    // ─── Parameter keys ───────────────────────────────────────────────

    const val PARAM_ACCESS = "access"
    const val PARAM_COLLECTION = "collection"
    const val PARAM_FROM_BACKGROUND = "from_background"
    const val PARAM_MESSAGE = "message"
    const val PARAM_PACKAGE = "package"
    const val PARAM_PERIOD = "period"
    const val PARAM_PROVIDER = "provider"
    const val PARAM_SELECTION = "selection"
    const val PARAM_SOURCE = "source"
    const val PARAM_STEP = "step"
    const val PARAM_TASK_COUNT = "task_count"
    const val PARAM_TIER = "tier"
    const val PARAM_TYPE = "type"

    // ─── Parameter values ─────────────────────────────────────────────

    const val PERIOD_ANNUAL = "annual"
    const val PERIOD_MONTHLY = "monthly"
    const val SELECTION_GITHUB = "github"
    const val SELECTION_GOOGLE_PLAY = "google_play"
    const val SOURCE_SETTINGS = "settings"
    const val TIER_CLOUD = "cloud"
    const val TIER_NYP = "nyp"
}

/**
 * Convenience extension to log a cloud onboarding funnel step.
 *
 * @param step One of [AnalyticsEvents.CloudOnboarding] constants.
 */
fun Analytics.logCloudOnboarding(step: String) =
    logEvent(AnalyticsEvents.CLOUD_ONBOARDING, AnalyticsEvents.PARAM_STEP to step)
