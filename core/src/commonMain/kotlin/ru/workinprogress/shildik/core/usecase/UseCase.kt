package ru.workinprogress.shildik.core.usecase

import kotlin.coroutines.cancellation.CancellationException

/**
 * A single domain operation.
 *
 * A copy of an existing convention (`ru.workinprogress.core:use-case`) rather than a reinvention:
 * that artifact publishes its native variant **for macos_arm64 only**, and we also need linuxX64 —
 * see research §1.6. The signature matches the original deliberately, so that moving to the shared
 * artifact (once it grows linux targets) is a change of import.
 */
fun interface UseCase<in P, out T> {
    suspend operator fun invoke(params: P): Result<T>
}

/** As in the original: cancelling a coroutine does not turn into a `Result.failure`. */
@Suppress("RedundantSuspendModifier")
suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
