package ru.workinprogress.shildik.auth.google

import kotlin.test.Test

/**
 * The method is built **without a substituted engine** — exactly as it is in production.
 *
 * Every other test passes a `MockEngine`, and so none of them noticed that no engine was among
 * the dependencies at all: `HttpClient()` fails at construction with "Failed to find HTTP client
 * engine implementation". In production this surfaced as a pod crash-looping at start-up — with a
 * fully green test suite.
 */
class GoogleEngineTest {
    @Test
    fun `it is built without a substituted engine`() {
        // With no engine on the classpath the constructor throws ISE and the test fails here.
        GoogleAuthMethod(clientId = "cid", clientSecret = "csec")
    }
}
