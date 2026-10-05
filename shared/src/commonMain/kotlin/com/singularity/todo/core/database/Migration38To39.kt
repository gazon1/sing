package com.singularity.todo.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migration from v38 to v39 — a profile learns whose it is.
 *
 * Adds a nullable `user_id` to `profiles`, leaving existing rows NULL.
 *
 * ## Why the profile had no owner
 *
 * Every other user-scoped table carries one, and `profiles` did not. That was survivable
 * while a profile was only a namespace to switch between, and stopped being survivable the
 * moment REQ-UA-017 asked whose data to erase.
 *
 * A profile is not recorded anywhere else either. `tasks`, `notes`, `projects`, `tags`,
 * `time_entries` and `agenda_views` have no `profile_id` column — the profile survives only as
 * the shape of the id in `scopedUserIdFor`:
 *
 * ```
 * default profile → "owner"
 * any other       → "prof-a/owner"
 * ```
 *
 * So an owner-scoped delete had to *infer* a profile from a string prefix. The inference is
 * what this migration removes: `WHERE user_id = :owner` erases the default profile only and
 * leaves every other one behind, silently, in the shape of success.
 *
 * ## Why the column is nullable
 *
 * A profile created before sign-in belongs to nobody yet, and "nobody" is a real state rather
 * than a missing value — the account-less session owns its data, and that data is not owned by
 * a profile row that happened to exist already. NOT NULL would force a placeholder identity
 * that every later read would take for a real owner, which is worse than an honest NULL.
 *
 * ## Why existing rows are left NULL
 *
 * A pre-existing profile's owner is not derivable after the fact: the rows it owns name the
 * owner, but a profile may hold rows from several accounts' scopes over its lifetime, and
 * nothing recorded which was which. Guessing would file one account's profile under another —
 * the same silent misattribution the 37→38 outbox backfill refused — and it would then decide
 * which account's profile gets erased on the next switch.
 *
 * Nothing is cleared here, unlike that migration. The rows themselves stay valid and stay
 * usable; what is missing is only the attribution, and the cost of the upgrade is that a
 * profile created before v39 is invisible to an owner-scoped erase until it is claimed.
 *
 * See `docs/decisions/2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first.md`.
 */
class Migration38To39 : Migration(38, 39) {
    override suspend fun migrate(connection: SQLiteConnection) {
        // Nullable with no default: SQLite adds a nullable column without one, and an
        // existing row correctly reads NULL rather than an empty string that would be
        // indistinguishable from an owner whose id happens to be empty.
        connection.execSQL("ALTER TABLE profiles ADD COLUMN user_id TEXT")
        connection.execSQL("CREATE INDEX IF NOT EXISTS index_profiles_user_id ON profiles (user_id)")
    }
}
