package ru.workinprogress.shildik.server

import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger

/**
 * The service log.
 *
 * We take **Ktor's own** logger rather than a separate library. It is already here — it arrives
 * with `ktor-server-core` — and it is multiplatform: on the JVM it is slf4j, so in production
 * everything still goes through logback and nothing changes in the pod logs; on Kotlin/Native it
 * prints to stdout with the level from `KTOR_LOG_LEVEL` (INFO by default).
 *
 * A separate facade (kotlin-logging and the like) is not taken: on native it does exactly the same,
 * and Ktor's own messages would go their own way regardless — the process would end up with two
 * mechanisms instead of one.
 *
 * **What the native logger has not got: configuration.** No format, no appenders — only the level.
 * That costs nothing today (on the JVM we run logback's bare default pattern), but structured logs,
 * should we need them, will have to be written by hand rather than "switched on in the
 * configuration" (research-native §7.3c).
 */
internal val log: Logger = KtorSimpleLogger("shildik")
