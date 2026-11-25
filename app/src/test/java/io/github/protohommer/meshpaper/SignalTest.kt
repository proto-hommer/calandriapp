package io.github.protohommer.meshpaper

import io.github.protohommer.meshpaper.mesh.Signal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ConfigProtos.Config.DeviceConfig.Role

class SignalTest {
    private val now = 1_000_000L
    private fun node(role: Role?, agoS: Long) = MeshNode(
        num = 1, id = "!00000001", longName = "x", shortName = "x", lastHeard = now - agoS,
        snr = 0f, hopsAway = 0, battery = null, lat = null, lon = null, channel = 0, role = role,
    )

    @Test fun clientLostAfterAnHour() {
        assertFalse(Signal.isLost(node(Role.CLIENT, 50 * 60), now))
        assertTrue(Signal.isLost(node(Role.CLIENT, 2 * 3600), now))
    }

    @Test fun routerNotLostWhileQuiet() {
        // Un router manda posición y telemetría cada 12 h: a las 2 h sin oírlo sigue andando.
        assertFalse(Signal.isLost(node(Role.ROUTER, 2 * 3600), now))
        assertFalse(Signal.isStale(node(Role.ROUTER, 2 * 3600), now))
        assertTrue(Signal.isLost(node(Role.ROUTER, 14 * 3600), now))
    }
}
