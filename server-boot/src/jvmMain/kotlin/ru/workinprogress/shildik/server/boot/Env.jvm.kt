package ru.workinprogress.shildik.server.boot

actual fun optional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
