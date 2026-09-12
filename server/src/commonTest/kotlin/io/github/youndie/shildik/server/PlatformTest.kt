package io.github.youndie.shildik.server

import io.github.youndie.sborka.probe.configuredProbeTarget
import io.github.youndie.sborka.probe.orFail
import io.github.youndie.sborka.probe.probePlatform
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/*
 * Does the platform under this target do what this server assumes?
 *
 * The assertions are `sborka:platform-probe`, and they go through ktor's own API rather than the
 * syscall under it: the resolution failure this portfolio paid for was in that API while every
 * syscall below it worked.
 *
 * ## ONLY ONE OF THE THREE TARGETS SHIPS
 *
 * This module declares `jvm`, `macosArm64` and `linuxX64`, and the probe runs on all three. Only
 * `linuxX64` is deployed: both distributions — `:distribution` and `:distribution-sqlite` — declare
 * that target alone, because they exist to produce the binary that goes into a container.
 *
 * So a green line for `jvm` or `macosArm64` is a green line about a target nobody runs. It is worth
 * having — the JVM target is where this code is developed, and a difference there is a difference
 * somebody will meet — but it is not coverage of production, and this comment exists so that nobody
 * reads it as such.
 *
 * `runBlocking`, not `runTest`: the test dispatcher's clock is virtual, so a socket with a timeout
 * around it reports a timeout before it has done anything — a harness verdict that reads as a
 * platform one.
 *
 * TLS is not asserted here: the outbound calls this repository makes live in `:auth-google` and
 * `:oidc-auth-server`, which carry their own engines. The report names it as uncovered.
 */
class PlatformTest {
    @Test
    fun theBuildsProbeTargetReachesThisTest() {
        // The host arrives through an environment variable that `sborka.parity` sets and
        // `platform-probe` reads, and the two spell it in different builds. Without this assertion
        // the names could drift apart and every probe below would fall back to its default — a
        // lookup test that passes because it looked nowhere.
        val configured = assertNotNull(configuredProbeTarget(), "sborka.parity did not reach the test")
        assertEquals("localhost", configured.host)
    }

    @Test
    fun thePlatformDoesWhatThisServerAssumes() {
        runBlocking {
            SelectorManager(Dispatchers.Default).use { selector ->
                aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0)).use { listener ->
                    val port = (listener.localAddress as InetSocketAddress).port
                    val report = probePlatform(configuredProbeTarget()?.host ?: "localhost", port)
                    println(report)
                    report.orFail()
                }
            }
        }
    }
}
