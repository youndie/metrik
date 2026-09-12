package io.github.youndie.metrik.server

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
 * ## THE TLS ASSERTION IS NOT HERE, AND THAT WAS MEASURED RATHER THAN ASSUMED
 *
 * This module is why the probe HAS a TLS assertion: `ktor-client-cio` has no TLS on Kotlin/Native,
 * Telegram notifications silently never left the process for months, and the fix was `Curl` on
 * native and `CIO` on the JVM through `expect`/`actual`. So this is the one service that can hand
 * the probe a real engine.
 *
 * It was wired, run, and taken out. Without a stand the only hermetic shape is "attempt TLS against
 * a socket that hangs up and check the failure is not a refusal" — and on `linuxX64` the run said:
 *
 *     reach-over-tls(https://localhost:56093): IllegalStateException: Connection failed …
 *     Reason: SSL connect error (CURLE_SSL_CONNECT_ERROR)
 *
 * Curl reports its ORDINARY handshake failure as `IllegalStateException`, which is the same type
 * ktor uses to refuse TLS outright. No exception type separates "reached the TLS layer and failed"
 * from "has no TLS at all"; only the message text does, and a gate resting on a sentence passes the
 * day it is reworded.
 *
 * So the report says TLS is uncovered, which is true, instead of a green line that proves nothing.
 * What would cover it is `parityProbe` pointed at a service with a certificate — a stand this
 * repository does not have in CI.
 *
 * `runBlocking`, not `runTest`: the test dispatcher's clock is virtual, so a socket with a timeout
 * around it reports a timeout before it has done anything — a harness verdict that reads as a
 * platform one.
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
                aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0)).use { plain ->
                    // A bound socket is enough for the name lookup: the kernel completes a
                    // connection from its backlog without anyone accepting it.
                    val report =
                        probePlatform(
                            host = configuredProbeTarget()?.host ?: "localhost",
                            port = (plain.localAddress as InetSocketAddress).port,
                        )
                    println(report)
                    report.orFail()
                }
            }
        }
    }
}
