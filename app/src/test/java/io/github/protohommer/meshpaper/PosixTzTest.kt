package io.github.protohommer.meshpaper

import io.github.protohommer.meshpaper.mesh.PosixTz
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PosixTzTest {
    private val now = Instant.parse("2026-11-25T15:00:00Z")

    @Test fun argentina() = assertEquals("STD3", PosixTz.of(ZoneId.of("America/Argentina/Cordoba"), now))
    @Test fun utc() = assertEquals("STD0", PosixTz.of(ZoneId.of("UTC"), now))
    @Test fun india() = assertEquals("STD-5:30", PosixTz.of(ZoneId.of("Asia/Kolkata"), now))
    @Test fun berlin() = assertEquals("STD-1DST-2,M3.5.0/2,M10.5.0/3", PosixTz.of(ZoneId.of("Europe/Berlin"), now))
    @Test fun newYork() = assertEquals("STD5DST4,M3.2.0/2,M11.1.0/2", PosixTz.of(ZoneId.of("America/New_York"), now))
    @Test fun santiago() = assertEquals("STD4DST3,M9.1.0/0,M4.1.0/0", PosixTz.of(ZoneId.of("America/Santiago"), now))
}
