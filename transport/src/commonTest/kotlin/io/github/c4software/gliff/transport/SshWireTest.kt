package io.github.c4software.gliff.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SshWireTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val publicKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun theAuthorizedKeyLine() {
        // Checked with `ssh-keygen -lf`, which reads it as an ED25519 key.
        assertEquals(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g phone",
            SshWire.authorizedKey(publicKey, "phone"),
        )
    }

    @Test
    fun theFingerprintIsUnpaddedBase64() {
        // The SHA-256 of that key's blob, and what ssh-keygen prints for it.
        val digest = hex("98aa94fb42bc3a12a603c6c1422f51cf443997bfe0d7ad2120ffab258493363e")
        assertEquals("SHA256:mKqU+0K8OhKmA8bBQi9Rz0Q5l7/g160hIP+rJYSTNj4", SshWire.fingerprint(digest))
    }

    @Test
    fun wrongSizesAreRefused() {
        assertFailsWith<IllegalArgumentException> { SshWire.ed25519Blob(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SshWire.fingerprint(ByteArray(20)) }
    }

    @Test
    fun theKeyTypeIsReadFromTheBlob() {
        assertEquals("ssh-ed25519", SshWire.keyType(SshWire.ed25519Blob(publicKey)))
        assertEquals("ssh-rsa", SshWire.keyType(hex("00000007") + "ssh-rsa".encodeToByteArray() + ByteArray(8)))
        assertNull(SshWire.keyType(ByteArray(0)))
        assertNull(SshWire.keyType(hex("000000ff7373")))
        assertNull(SshWire.keyType(hex("ffffffff7373")))
    }

    @Test
    fun algorithmNames() {
        assertEquals("Ed25519", SshWire.algorithmName("ssh-ed25519"))
        assertEquals("RSA", SshWire.algorithmName("ssh-rsa"))
        assertEquals("ECDSA", SshWire.algorithmName("ecdsa-sha2-nistp256"))
        assertEquals("sk-ssh-ed25519@openssh.com", SshWire.algorithmName("sk-ssh-ed25519@openssh.com"))
    }
}
