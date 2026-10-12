package io.github.c4software.gliff.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SshTargetTest {
    @Test
    fun theCommandRunsTheServerUnderShWithARuntimeDir() {
        val target = SshTarget("box", "val", serverArgs = listOf("--headless"))
        assertEquals(
            "exec sh -c 'export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}\"; " +
                "exec gliff-server --stdio --headless'",
            target.command,
        )
    }

    @Test
    fun argumentsAreQuotedAndTheServerPathIsAShellFragment() {
        val target = SshTarget(
            "box", "val",
            serverBin = "env WAYLAND_DISPLAY=wayland-1 gliff-server",
            serverArgs = listOf("--output", "DP 1", "it's"),
        )
        // Quoted once for the inner shell, and once more as the argument of sh -c.
        assertTrue(
            target.command.endsWith(
                "exec env WAYLAND_DISPLAY=wayland-1 gliff-server --stdio --output '\\''DP 1'\\'' '\\''it'\\''\\'\\'''\\''s'\\'''"
            ),
            target.command,
        )
    }

    @Test
    fun quoting() {
        assertEquals("--output", SshTarget.shellQuote("--output"))
        assertEquals("a=b:c@d,e/f.g_h", SshTarget.shellQuote("a=b:c@d,e/f.g_h"))
        assertEquals("''", SshTarget.shellQuote(""))
        assertEquals("'a b'", SshTarget.shellQuote("a b"))
        assertEquals("'\$HOME'", SshTarget.shellQuote("\$HOME"))
        assertEquals("'it'\\''s'", SshTarget.shellQuote("it's"))
    }

    @Test
    fun parsing() {
        assertEquals(SshTarget("box", "val"), SshTarget.parse("box", "val"))
        assertEquals(SshTarget("box", "root"), SshTarget.parse("root@box", "val"))
        assertEquals(SshTarget("box", "root", 2222), SshTarget.parse("root@box:2222", "val"))
        assertFailsWith<IllegalArgumentException> { SshTarget.parse("root@", "val") }
    }

    @Test
    fun theKeymapCommandIsOneShellArgument() {
        val command = RemoteKeymap.command
        assertTrue(command.startsWith("exec sh -c '"), command)
        assertTrue(command.endsWith("exec xkbcli compile-keymap \"\$@\"'"), command)
        assertTrue("hyprctl getoption" in command)
    }
}
