package ru.workinprogress.shildik.cli

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * The HTTP engine is chosen by the platform, and that is not decoration.
 *
 * CIO on Kotlin/Native cannot do TLS sessions: the very first `https://` request fails with
 * "TLS sessions are not supported on Native platform". While the CLI only talked to its own
 * management port through a port-forward (plain HTTP) this never showed up — it surfaced on the
 * first HTTPS call to somebody else's provider (services/shildik-cli.md §Quirks).
 */
expect fun platformHttpEngine(): HttpClientEngineFactory<*>
