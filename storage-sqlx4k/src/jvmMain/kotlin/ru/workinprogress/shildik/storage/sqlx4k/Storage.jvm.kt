package ru.workinprogress.shildik.storage.sqlx4k

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL

actual fun postgres(
    jdbcUrl: String,
    user: String,
    password: String,
    maxConnections: Int,
): Driver =
    PostgreSQL(
        url = jdbcUrl.toSqlx4kUrl(),
        username = user,
        password = password,
        options = poolOptions(maxConnections),
    )
