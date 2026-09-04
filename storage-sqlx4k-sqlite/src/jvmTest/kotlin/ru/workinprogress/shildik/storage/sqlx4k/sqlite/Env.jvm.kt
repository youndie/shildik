package ru.workinprogress.shildik.storage.sqlx4k.sqlite

internal actual fun env(name: String): String? = System.getenv(name)

/** xerial opens a connection with foreign keys off, and nothing here can change that. */
internal actual val foreignKeysEnforced: Boolean = false
