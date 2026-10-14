package io.github.c4software.gliff.data.security

import android.content.Context
import android.os.Build
import io.github.c4software.gliff.domain.identity.Identity
import io.github.c4software.gliff.transport.SshKeys
import java.io.File
import java.security.KeyPair

/**
 * The device's Ed25519 key, made on first use and kept in the app's private
 * storage, outside backups: a restored backup must not carry it to another
 * device.
 */
class AndroidIdentity(context: Context) : Identity {
    private val file = File(context.noBackupFilesDir, "id_ed25519.pk8")
    private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val pair = if (file.exists()) {
            SshKeys.decode(file.readBytes())
        } else {
            SshKeys.generate().also { made ->
                // Written whole or not at all, so a crash cannot leave half a key.
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(SshKeys.encode(made))
                check(tmp.renameTo(file)) { "cannot store the ssh key" }
            }
        }
        cached = pair
        return pair
    }

    override fun authorizedKey(): String =
        SshKeys.authorizedKey(keyPair(), "gliff@" + Build.MODEL.replace(Regex("\\s+"), "-"))
}
