package io.github.youndie.shildik.server.boot

public actual fun optional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
