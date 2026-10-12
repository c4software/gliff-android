package io.github.c4software.gliff.transport

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jcajce.interfaces.EdDSAPublicKey

/** The shared key format against the ssh library's own encoding of a real key. */
class SshWireJvmTest {
    private val key = SshKeys.generate()
    private val blob: ByteArray = Buffer.PlainBuffer().putPublicKey(key.public).compactData

    @Test
    fun theBlobIsTheOneTheSshLibraryWrites() {
        val raw = (key.public as EdDSAPublicKey).pointEncoding
        assertContentEquals(blob, SshWire.ed25519Blob(raw))
        assertEquals("ssh-ed25519", SshWire.keyType(blob))
        assertEquals(
            "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " c",
            SshKeys.authorizedKey(key, "c"),
        )
    }

    @Test
    fun theFingerprintIsTheOneOfTheBlob() {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest), Ssh.fingerprint(key.public))
    }
}
