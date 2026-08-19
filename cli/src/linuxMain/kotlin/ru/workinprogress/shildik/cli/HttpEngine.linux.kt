package ru.workinprogress.shildik.cli

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

/**
 * On Linux — curl. The price of that: the binary needs libcurl in the system, so it stops being
 * fully self-contained. Ktor has no TLS-capable alternative on native, and without TLS the CLI
 * cannot read somebody else's provider.
 */
actual fun platformHttpEngine(): HttpClientEngineFactory<*> = Curl
