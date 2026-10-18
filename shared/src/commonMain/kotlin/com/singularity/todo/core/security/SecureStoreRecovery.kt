package com.singularity.todo.core.security

/**
 * Opens a keychain, erasing what it cannot read.
 *
 * ## Why this exists as a policy object and not as a `try` inside the port
 *
 * `EncryptedSharedPreferences` throws rather than blocks when the ciphertext on disk does not
 * match the key it holds. The state that produces it is ordinary: Android's Auto Backup copies
 * `shared_prefs/` across an uninstall, and the Android Keystore key that decrypts it is deleted
 * on uninstall and never backed up. The reinstalled app then holds a file encrypted with a key
 * this device no longer has, and every read raises `GeneralSecurityException` /
 * `KeyStoreException` for the life of that install.
 *
 * AndroidX documents the backup exclusion as mandatory, and the Android app declares it. This
 * is the other half — recovery for what the exclusion cannot reach: an install that already
 * carries the broken file, or a device that ran a backup before the rule shipped.
 *
 * Deleting the file is what makes the next attempt succeed. Retrying alone does not: a
 * `by lazy` that threw rethrows the same error forever, which is how a caller ends up with a
 * screen that can never finish starting. The data was already unreadable, so erasing it
 * destroys nothing that could have been recovered.
 *
 * ## Why the decision is separated from the platform
 *
 * The policy is "an unreadable encrypted store is erased and reopened". That sentence is the
 * bug fix, and it was previously embedded in a factory that needs a `Context`, a `MasterKey`
 * and a `security-crypto` artifact — none of which exist in a JVM test. Leaving it there means
 * the part that decides whether an app can recover from a broken install is the one part
 * nothing can test. The two lambdas carry the platform; the decision does not.
 */
internal object SecureStoreRecovery {

    /**
     * Runs [open], and if it fails, runs [erase] and tries once more.
     *
     * Only the *first* failure is erased for. A second failure is not a stale-ciphertext
     * problem — erasing again would not change the outcome — so it is rethrown with the
     * original attached, and the caller decides what that means. Retrying indefinitely is how
     * a revoked lock screen or a full disk turns into a startup that never completes.
     */
    inline fun <T> openOrErase(open: () -> T, erase: () -> Unit): T =
        try {
            open()
        } catch (first: Throwable) {
            erase()
            try {
                open()
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
}
