package ru.workinprogress.shildik.server

/**
 * Where our faults go.
 *
 * `:server` is shared code and cannot know about katcher: that one is JVM-only. So the reporter
 * arrives from outside, just like observability (BACKLOG M-52).
 */
fun interface ErrorReporter {
    fun report(error: Throwable)

    companion object {
        /**
         * The default is **the log**, not silence.
         *
         * This used to be `Silent`, and that left a hole: telemetry is attached behind an env gate,
         * so an installation without a katcher key lost our faults entirely. The client received
         * `server_error` while the logs held not a single line — exactly the case where "nothing
         * arrives in monitoring" is indistinguishable from "nothing is breaking".
         *
         * The log is not a replacement for telemetry but its lower bound: it is always there and it
         * goes nowhere over the network.
         */
        val Logging = ErrorReporter { error -> log.error("unhandled failure", error) }

        /** Silence is for tests only — the ones that check precisely the absence of a reaction. */
        val Silent = ErrorReporter { }
    }
}
