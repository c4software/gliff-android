package io.github.c4software.gliff.transport

import kotlin.io.encoding.Base64

/**
 * Keys as ssh writes them (RFC 4253, RFC 8709): what goes in
 * `authorized_keys`, and what the user compares for a host key.
 */
object SshWire {
    const val ED25519 = "ssh-ed25519"

    /** The public key blob of an Ed25519 key, from its 32 bytes. */
    fun ed25519Blob(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "an Ed25519 public key is 32 bytes, got ${publicKey.size}" }
        val name = ED25519.encodeToByteArray()
        val blob = ByteArray(4 + name.size + 4 + publicKey.size)
        be32(blob, 0, name.size)
        name.copyInto(blob, 4)
        be32(blob, 4 + name.size, publicKey.size)
        publicKey.copyInto(blob, 8 + name.size)
        return blob
    }

    /** The line to append to `~/.ssh/authorized_keys` on the server. */
    fun authorizedKey(publicKey: ByteArray, comment: String): String =
        "$ED25519 " + Base64.encode(ed25519Blob(publicKey)) + " " + comment

    /** The `SHA256:...` fingerprint OpenSSH prints, from the SHA-256 of the key blob. */
    fun fingerprint(sha256: ByteArray): String {
        require(sha256.size == 32) { "a SHA-256 digest is 32 bytes, got ${sha256.size}" }
        return "SHA256:" + Base64.encode(sha256).trimEnd('=')
    }

    /** The key type a blob starts with (`ssh-ed25519`, `ssh-rsa`...); null when it is not a key blob. */
    fun keyType(blob: ByteArray): String? {
        if (blob.size < 4) return null
        val length = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        if (length <= 0 || length > 64 || blob.size < 4 + length) return null
        return blob.decodeToString(4, 4 + length)
    }

    /** The name shown next to a fingerprint for a key type. */
    fun algorithmName(keyType: String): String = when {
        keyType == ED25519 -> "Ed25519"
        keyType == "ssh-rsa" || keyType.startsWith("rsa-sha2-") -> "RSA"
        keyType.startsWith("ecdsa-sha2-") -> "ECDSA"
        keyType == "ssh-dss" -> "DSA"
        else -> keyType
    }

    private fun be32(dst: ByteArray, at: Int, v: Int) {
        for (i in 0..3) dst[at + i] = (v ushr (8 * (3 - i))).toByte()
    }
}
