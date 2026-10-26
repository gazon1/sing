package com.singularity.todo.core.backup

import com.singularity.todo.core.security.ArchiveTagMismatchException
import kotlinx.coroutines.CancellationException

/**
 * Runs [block], saying in the project's words what the cipher's failure means.
 *
 * The rules are the same as before the chain: a tag mismatch is the password,
 * anything else is the bytes not moving. A project failure passes through
 * untouched, and a cancellation is not a failure at all.
 *
 * ## Why the mapping lives here
 *
 * The container is read in three places — the whole archive, the chain, and the
 * key block — and each of them has to say the same thing about the same
 * failure. Three copies of that rule is three places for it to drift, and the
 * drift shows up as a person's password being blamed for a full disk.
 *
 * The last catch is deliberately wide: the archive layer does not know what the
 * platform threw while the bytes were moving, and every one of those failures
 * means the same thing to a person — "this archive could not be read". Narrowing
 * the catch would let a platform's own exception escape with a stack trace
 * instead of a sentence, which is the thing this mapping exists to prevent.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> archiveErrors(block: () -> T): T =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: BackupError) {
        throw error
    } catch (error: ArchiveTagMismatchException) {
        // The cipher says the tag does not match: the password is wrong.
        throw ArchiveEnvelopeError.WrongPassword(error)
    } catch (error: Exception) {
        // Anything else is not about the password: the bytes could not be moved
        // at all. Saying "wrong password" here sends the person to retype a
        // password that was never the problem.
        throw ArchiveEnvelopeError.Unusable(error)
    }
