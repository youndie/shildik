package ru.workinprogress.shildik.server.boot

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * The same as on the JVM, through posix.
 *
 * A blank string counts as absent, exactly as there: "set to empty" and "not set" are one state
 * here, and telling them apart would invent a third for nothing.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun optional(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
