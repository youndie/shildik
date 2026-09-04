package ru.workinprogress.shildik.storage.sqlx4k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * On the JVM the call suspends, so cancelling it on a deadline is enough.
 *
 * There is deliberately no pool of our own here: it would be a needless cost and a source of
 * failures in itself. The first version of this change introduced a shared pool for both
 * platforms, and the test run hit it immediately — servers shared ten threads and timed out where
 * the database was answering.
 *
 * **`withContext(Dispatchers.IO)` is mandatory, and not for the sake of a thread.** Inside
 * `runTest` time is virtual: a `withTimeout` there fires instantly and every query would time out
 * before starting. Switching the dispatcher moves the wait onto real time — precisely the case
 * where "the test broke" means "time is different in the test" rather than "the code is wrong".
 */
internal actual suspend fun <T> bounded(block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        withTimeout(QUERY_TIMEOUT) { block() }
    }
