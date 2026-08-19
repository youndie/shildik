package ru.workinprogress.shildik.cli

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/** On macOS — the system stack: the operator has nothing extra to install. */
actual fun platformHttpEngine(): HttpClientEngineFactory<*> = Darwin
