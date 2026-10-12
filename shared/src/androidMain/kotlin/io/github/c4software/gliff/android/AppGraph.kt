package io.github.c4software.gliff.android

import android.content.Context
import android.os.Build
import io.github.c4software.gliff.client.Connector
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.KeyValueStore
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.MirrorSink
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.client.Sessions
import io.github.c4software.gliff.client.SettingsRepository
import io.github.c4software.gliff.mirror.MirrorServer
import io.github.c4software.gliff.transport.Ssh
import io.github.c4software.gliff.transport.SshKeys
import io.github.c4software.gliff.transport.SshTarget
import java.io.File
import java.security.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What lives as long as the process: the saved machines, the ssh key, the
 * open sessions and the web mirror.
 */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val store = PreferencesStore(app)
    val machines = MachineRepository(store)
    val settings = SettingsRepository(store)
    val identity = AndroidIdentity(app)
    val clipboard = AndroidClipboard(app)

    private val connector = Connector { machine ->
        Ssh.connectPinned(
            SshTarget(machine.host, machine.user, machine.port, machine.serverBin, machine.serverArgs),
            identity.keyPair(),
            machine.hostKey,
            log = { android.util.Log.i("gliff-server", it) },
        )
    }

    /** The open sessions; they outlive the activities that show them. */
    val sessions = Sessions(
        machines, settings, connector, scope,
        nowMs = System::currentTimeMillis,
        clipboardOut = clipboard::write,
        log = { message, cause -> android.util.Log.w("gliff", message, cause) },
        decoder = ::MediaCodecDecoder,
    )

    /** The web mirror, while the option is on. */
    @Volatile
    private var mirror: MirrorServer? = null

    init {
        scope.launch {
            settings.settings.map { Triple(it.httpMirror, it.mirrorToken, it.mirrorControl) }.distinctUntilChanged()
                .collect { (on, token, control) ->
                    if (on && token.isEmpty()) {
                        // Comes back here with the token set.
                        settings.update { it.copy(mirrorToken = MirrorServer.newToken()) }
                    } else {
                        withContext(Dispatchers.IO) { runMirror(if (on) token else null, control) }
                    }
                }
        }
    }

    private fun runMirror(token: String?, control: Boolean) {
        sessions.mirror = null
        mirror?.stop()
        mirror = null
        if (token == null) return
        val input: ((String) -> Unit)? = if (control) {
            { text -> sessions.frontSession?.browserInput(text) }
        } else {
            null
        }
        val server = MirrorServer(MirrorServer.DEFAULT_PORT, token, onViewer = ::keyframeForViewer, onInput = input) {
            android.util.Log.i("gliff", it)
        }
        try {
            server.start()
        } catch (e: java.io.IOException) {
            android.util.Log.w("gliff", "cannot start the web mirror", e)
            return
        }
        mirror = server
        sessions.mirror = object : MirrorSink {
            override fun configure(width: Int, height: Int) = server.configure(width, height)
            override fun frame(data: ByteArray, keyframe: Boolean) = server.frame(data, keyframe)
            override fun reset() = server.reset()
        }
    }

    /** A viewer starts on a keyframe; asked twice, as requests too close together are merged. */
    private fun keyframeForViewer() {
        scope.launch {
            repeat(2) {
                sessions.frontSession?.requestKeyframe()
                delay(SessionController.KEYFRAME_REQUEST_GAP_MS + 50)
            }
        }
    }

    /** Where the web mirror answers, for the options screen. */
    fun mirrorUrls(token: String): List<String> =
        MirrorServer.addresses().map { "http://$it:${MirrorServer.DEFAULT_PORT}/$token/" }

    /** `machineId`'s window is the one in front. Main thread. */
    fun front(machineId: String) = sessions.front(machineId)

    /** The session of a machine, started on first use. Main thread. */
    fun session(machineId: String): SessionController = sessions.session(machineId)

    /** The machine's window is gone for good. Main thread. */
    fun endSession(machineId: String) = sessions.endSession(machineId)
}

private class PreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("gliff", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

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
