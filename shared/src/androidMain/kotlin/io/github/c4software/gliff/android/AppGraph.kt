package io.github.c4software.gliff.android

import android.content.Context
import android.os.Build
import io.github.c4software.gliff.client.Connector
import io.github.c4software.gliff.client.FrameSink
import io.github.c4software.gliff.client.Identity
import io.github.c4software.gliff.client.KeyValueStore
import io.github.c4software.gliff.client.MachineRepository
import io.github.c4software.gliff.client.SessionController
import io.github.c4software.gliff.client.SessionState
import io.github.c4software.gliff.client.SettingsRepository
import io.github.c4software.gliff.mirror.MirrorServer
import io.github.c4software.gliff.transport.Ssh
import io.github.c4software.gliff.transport.SshKeys
import io.github.c4software.gliff.transport.SshTarget
import java.io.File
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What lives as long as the process: the saved machines, the ssh key, and the
 * open sessions. Sessions are kept here and not in an activity, so a window
 * being resized, rotated or recreated never cuts a connection.
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

    private val sessions = ConcurrentHashMap<String, SessionController>()
    /** Read by the mirror's threads too. */
    private val taps = ConcurrentHashMap<String, Tap>()

    /** The web mirror, while the option is on. */
    @Volatile
    private var mirror: MirrorServer? = null

    /** The machine whose window was last in front: the one the mirror shows. */
    @Volatile
    private var front: String? = null

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
        mirror?.stop()
        mirror = null
        if (token == null) return
        val input: ((String) -> Unit)? = if (control) {
            { text -> front?.let { sessions[it] }?.browserInput(text) }
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
        front?.let { taps[it] }?.let { server.configure(it.width, it.height) }
        mirror = server
    }

    /** A viewer starts on a keyframe; asked twice, as requests too close together are merged. */
    private fun keyframeForViewer() {
        scope.launch {
            repeat(2) {
                front?.let { sessions[it] }?.requestKeyframe()
                delay(SessionController.KEYFRAME_REQUEST_GAP_MS + 50)
            }
        }
    }

    /** Where the web mirror answers, for the options screen. */
    fun mirrorUrls(token: String): List<String> =
        MirrorServer.addresses().map { "http://$it:${MirrorServer.DEFAULT_PORT}/$token/" }

    /** `machineId`'s window is the one in front. Main thread. */
    fun front(machineId: String) {
        if (front == machineId) return
        front = machineId
        mirror?.let { server ->
            server.reset()
            taps[machineId]?.let { server.configure(it.width, it.height) }
        }
        sessions[machineId]?.requestKeyframe()
    }

    /** One session's video, passed on to the mirror while that session is in front. */
    private inner class Tap(private val machineId: String) : FrameSink {
        @Volatile
        var width = 0

        @Volatile
        var height = 0

        override fun configure(width: Int, height: Int) {
            this.width = width
            this.height = height
            if (front == machineId) mirror?.configure(width, height)
        }

        override fun frame(data: ByteArray, keyframe: Boolean) {
            if (front == machineId) mirror?.frame(data, keyframe)
        }
    }

    /** The session of a machine, started on first use. Main thread. */
    fun session(machineId: String): SessionController = sessions.getOrPut(machineId) {
        val tap = Tap(machineId).also { taps[machineId] = it }
        if (front == null) front = machineId
        SessionController(
            machineId, machines, connector, MediaCodecDecoder(), scope, tap,
            onFailure = { android.util.Log.w("gliff", "session failed", it) },
            clipboardOut = clipboard::write,
            clipboardOn = { settings.settings.value.clipboard },
            nowMs = System::currentTimeMillis,
        )
            .also { controller ->
                controller.start()
                scope.launch {
                    controller.state.collect { state ->
                        if (state is SessionState.Failed) android.util.Log.w("gliff", "session failed: ${state.message}")
                    }
                }
            }
    }

    /** The machine's window is gone for good. Main thread. */
    fun endSession(machineId: String) {
        taps.remove(machineId)
        if (front == machineId) {
            front = null
            mirror?.reset()
        }
        sessions.remove(machineId)?.let {
            it.close()
            it.decoder.close()
        }
    }
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
