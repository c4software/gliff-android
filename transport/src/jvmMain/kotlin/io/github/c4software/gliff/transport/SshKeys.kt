package io.github.c4software.gliff.transport

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jcajce.interfaces.EdDSAPrivateKey
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** The client's own Ed25519 key: made in the app, never typed in. */
object SshKeys {
    /**
     * Make the full BouncyCastle the provider named "BC". Android registers a
     * cut-down one under that name, without Ed25519 or X25519, and the ssh
     * library asks for its algorithms by that name.
     */
    fun installProvider() {
        synchronized(this) {
            if (Security.getProvider("BC") is BouncyCastleProvider) return
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generate(): KeyPair {
        installProvider()
        return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair()
    }

    /** The private key as PKCS#8, to keep in the app's private storage. */
    fun encode(pair: KeyPair): ByteArray = pair.private.encoded

    fun decode(pkcs8: ByteArray): KeyPair {
        installProvider()
        val private = KeyFactory.getInstance("Ed25519", "BC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        return KeyPair((private as EdDSAPrivateKey).publicKey, private)
    }

    /** The line to append to `~/.ssh/authorized_keys` on the server. */
    fun authorizedKey(pair: KeyPair, comment: String): String {
        val blob = Buffer.PlainBuffer().putPublicKey(pair.public).compactData
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob) + " " + comment
    }
}
