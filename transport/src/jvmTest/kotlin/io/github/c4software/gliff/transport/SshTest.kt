package io.github.c4software.gliff.transport

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The built-in ssh client against a real OpenSSH server started for the test
 * on a local port. Skipped where `sshd` is not installed.
 */
class SshTest {
    private val dir: File = Files.createTempDirectory("gliff-ssh").toFile()
    private var sshd: Process? = null
    private var port = 0
    private val key = SshKeys.generate()

    private fun run(vararg command: String): String {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${command.joinToString(" ")}: $out" }
        return out
    }

    private fun startServer() {
        val binary = listOf("/usr/bin/sshd", "/usr/sbin/sshd").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("no sshd on this machine", binary != null)
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", "$dir/hostkey")
        File(dir, "authorized_keys").writeText(SshKeys.authorizedKey(key, "gliff-test") + "\n")
        // Stands in for gliff-server: echoes the session back.
        File(dir, "echo-server").apply {
            writeText("#!/bin/sh\necho \"args: \$*\" >&2\nexec cat\n")
            setExecutable(true)
        }
        port = ServerSocket(0).use { it.localPort }
        sshd = ProcessBuilder(
            binary!!.path, "-D", "-e", "-p", "$port", "-o", "ListenAddress=127.0.0.1",
            "-h", "$dir/hostkey", "-o", "AuthorizedKeysFile=$dir/authorized_keys", "-o", "PidFile=none",
            "-o", "UsePAM=no", "-o", "StrictModes=no", "-o", "PasswordAuthentication=no",
        ).redirectErrorStream(true).redirectOutput(File(dir, "sshd.log")).start()
        repeat(50) {
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("sshd did not start: " + File(dir, "sshd.log").readText())
    }

    @AfterTest
    fun stop() {
        sshd?.destroy()
        sshd?.waitFor(5, TimeUnit.SECONDS)
        dir.deleteRecursively()
    }

    private fun target() = SshTarget(
        "127.0.0.1", System.getProperty("user.name"), port,
        serverBin = "$dir/echo-server", serverArgs = listOf("--headless"),
    )

    @Test
    fun aGeneratedKeyLogsInAndTheSessionCarriesBytesBothWays() {
        startServer()
        val seen = ArrayList<HostKey>()
        val logs = java.util.concurrent.LinkedBlockingQueue<String>()
        val transport = Ssh.connect(target(), key, { seen.add(it); true }, log = { logs.add(it) })
        try {
            // The fingerprint shown to the user is the one OpenSSH prints.
            val expected = run("ssh-keygen", "-lf", "$dir/hostkey.pub").split(" ")[1]
            assertEquals(expected, seen.single().fingerprint)
            assertTrue(seen.single().fingerprint.startsWith("SHA256:"))
            assertEquals("args: --stdio --headless", logs.poll(5, TimeUnit.SECONDS))

            val sent = ByteArray(200_000) { (it * 31).toByte() }
            transport.sink.write(sent, 0, sent.size)
            transport.sink.flush()
            val back = ByteArray(sent.size)
            var got = 0
            while (got < back.size) {
                val n = transport.source.read(back, got, back.size - got)
                check(n > 0) { "stream ended after $got bytes" }
                got += n
            }
            assertContentEquals(sent, back)
        } finally {
            transport.close()
        }
    }

    @Test
    fun aStoredKeyStillLogsIn() {
        startServer()
        val restored = SshKeys.decode(SshKeys.encode(key))
        assertEquals(SshKeys.authorizedKey(key, "c"), SshKeys.authorizedKey(restored, "c"))
        Ssh.connect(target(), restored, { true }).close()
    }

    @Test
    fun aRefusedHostKeyStopsBeforeAuthentication() {
        startServer()
        val e = assertFailsWith<HostKeyRejectedException> { Ssh.connect(target(), key, { false }) }
        assertTrue(e.key.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun trustOnFirstUse() {
        startServer()
        val first = assertFailsWith<UnknownHostKeyException> { Ssh.connectPinned(target(), key, null) }
        Ssh.connectPinned(target(), key, first.key.fingerprint).close()
        val changed = assertFailsWith<ChangedHostKeyException> { Ssh.connectPinned(target(), key, "SHA256:other") }
        assertEquals("SHA256:other", changed.expected)
        assertEquals(first.key, changed.key)
    }

    @Test
    fun anUnknownClientKeyIsATransportError() {
        startServer()
        val e = assertFailsWith<TransportException> { Ssh.connect(target(), SshKeys.generate(), { true }) }
        assertTrue(e !is HostKeyRejectedException)
    }

    @Test
    fun theAuthorizedKeyLineIsWhatOpenSshExpects() {
        val line = SshKeys.authorizedKey(key, "phone")
        File(dir, "k.pub").writeText(line + "\n")
        // ssh-keygen parses it and reports an ED25519 key with our comment.
        val listed = run("ssh-keygen", "-lf", "$dir/k.pub")
        assertTrue("ED25519" in listed && "phone" in listed, listed)
    }
}
