package io.github.protohommer.meshpaper

import io.github.protohommer.meshpaper.mesh.Calandria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CalandriaInfoTest {
    @Test fun parsesOwnVersion() {
        val i = Calandria.parse("CALANDRIA/1;model=Calandria MK1 epaper;fw=2.8.3.abc1234;env=x;ver=0.6.0".toByteArray(), 1)
        assertNotNull(i)
        assertEquals("0.6.0", i!!.ver)
        assertEquals("2.8.3", i.base)
        assertEquals("abc1234", i.commit)
    }

    @Test fun oldFirmwareHasNoVersion() {
        val i = Calandria.parse("CALANDRIA/1;model=Calandria MK1 epaper;fw=2.8.3.edc1ef7;env=x".toByteArray(), 1)!!
        assertEquals("", i.ver)
    }

    @Test fun storedInfoRoundTrips() {
        val i = Calandria.Info("M", "2.8.3.abc1234", "e", 5, "0.6.0")
        assertEquals(i, Calandria.decode(Calandria.encode(i)))
        // Guardado por una versión anterior de la app (4 campos).
        assertEquals("", Calandria.decode("M\u001f2.8.3.abc1234\u001fe\u001f5")!!.ver)
    }

    @Test fun shortTags() {
        assertEquals("0.6.0", Calandria.shortTag("calandria-0.6.0"))
        assertEquals("ws154.5", Calandria.shortTag("v2.8.3-ws154.5"))
    }
}
