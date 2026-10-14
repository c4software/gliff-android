package io.github.c4software.gliff.domain.machine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MachineTest {
    @Test
    fun addressesParse() {
        assertEquals(Triple("val", "box", 22), Machine.parseAddress("val@box"))
        assertEquals(Triple("val", "10.0.0.2", 2222), Machine.parseAddress("  val@10.0.0.2:2222 "))
        assertEquals(Triple("val", "box.tail1234.ts.net", 22), Machine.parseAddress("val@box.tail1234.ts.net"))
        // A bare IPv6 address has colons but no port.
        assertEquals(Triple("val", "fd7a::1", 22), Machine.parseAddress("val@fd7a::1"))
        for (bad in listOf("", "box", "@box", "val@", "val@box:", "val@box:0", "val@box:99999", "val@box:ssh", "a@b@c", "val@my box")) {
            assertNull(Machine.parseAddress(bad), bad)
        }
    }

    @Test
    fun addressShowsOnlyAnUnusualPort() {
        assertEquals("val@box", Machine(user = "val", host = "box").address)
        assertEquals("val@box:2222", Machine(user = "val", host = "box", port = 2222).address)
    }

    @Test
    fun serverArgumentsFollowTheReferenceClient() {
        val m = Machine(user = "val", host = "box")
        assertEquals(listOf("--headless"), m.serverArgs)
        assertEquals(listOf("--output", "auto"), m.copy(headless = false).serverArgs)
        assertEquals(listOf("--output", "DP-1"), m.copy(headless = false, output = " DP-1 ").serverArgs)
        // The output name is ignored for a private screen.
        assertEquals(listOf("--headless"), m.copy(output = "DP-1").serverArgs)
    }

    @Test
    fun anEditKeepsTheTrustedKeyOfTheSameHostOnly() {
        val m = Machine(user = "val", host = "box", hostKey = "SHA256:abc")
        assertEquals("SHA256:abc", m.edited("root", "box", 22, false, " DP-1 ", "", 1920, 1080).hostKey)
        assertNull(m.edited("val", "other", 22, true, "", "gliff-server", -1, -1).hostKey)
        assertNull(m.edited("val", "box", 2222, true, "", "gliff-server", -1, -1).hostKey)
    }

    @Test
    fun anEditTrimsWhatWasTypedAndKeepsTheId() {
        val m = Machine(id = "1", user = "val", host = "box")
        assertEquals(
            Machine(id = "1", user = "root", host = "box", headless = false, output = "DP-1", forcedWidth = 1920, forcedHeight = 1080),
            m.edited("root", "box", 22, false, " DP-1 ", "  ", 1920, 1080),
        )
        assertEquals("/opt/gliff-server", m.edited("val", "box", 22, true, "", " /opt/gliff-server ", -1, -1).serverBin)
    }
}
