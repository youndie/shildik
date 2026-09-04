@file:OptIn(ExperimentalForeignApi::class)

package ru.workinprogress.shildik.storage.sqlx4k.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun env(name: String): String? = getenv(name)?.toKString()
