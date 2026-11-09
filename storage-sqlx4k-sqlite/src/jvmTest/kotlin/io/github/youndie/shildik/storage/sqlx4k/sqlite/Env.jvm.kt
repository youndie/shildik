package io.github.youndie.shildik.storage.sqlx4k.sqlite

internal actual fun env(name: String): String? = System.getenv(name)
