package io.github.c4software.gliff.mirror

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Shows the session's video to a web browser, over plain HTTP.
 *
 * The page is at `/<token>/`: the token keeps the screen from whoever merely
 * finds the port. Its video comes from `/<token>/stream`, an endless MP4 the
 * page feeds to a Media Source. Viewers only watch, unless `onInput` is
 * given: the page then posts its mouse and keyboard events to
 * `/<token>/input`, and they are handed over as they came.
 *
 * A viewer starts on a keyframe, so `onViewer` asks the session for one. When
 * the stream's parameters change the viewers are dropped, and the page
 * connects again for a fresh start.
 */
class MirrorServer(
    private val port: Int,
    private val token: String,
    /** A viewer is waiting for a keyframe. Called from a server thread. */
    private val onViewer: () -> Unit,
    /** The page's input events, one per line; null keeps the page to watching. Called from a server thread. */
    private val onInput: ((String) -> Unit)? = null,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val viewers = CopyOnWriteArrayList<Viewer>()

    @Volatile
    private var socket: ServerSocket? = null

    private var width = 0
    private var height = 0
    private var sets: Fmp4.ParameterSets? = null
    private var init: ByteArray? = null
    private var sequence = 0

    val viewerCount: Int get() = viewers.size

    /** Start listening; throws `IOException` when the port is taken. */
    fun start() {
        if (socket != null) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        thread(name = "gliff-mirror", isDaemon = true) {
            try {
                while (true) {
                    val client = server.accept()
                    thread(name = "gliff-mirror-client", isDaemon = true) { serve(client) }
                }
            } catch (_: IOException) {
                // Closed by stop().
            }
        }
        log("mirror listening on port $port")
    }

    fun stop() {
        socket?.let { runCatching { it.close() } }
        socket = null
        reset()
    }

    /** Another stream from here on (another session, another size): viewers start over. */
    fun reset() {
        synchronized(lock) {
            sets = null
            init = null
        }
        viewers.forEach { it.close() }
    }

    /** The coded size of the frames that follow. */
    fun configure(width: Int, height: Int) {
        synchronized(lock) {
            if (width == this.width && height == this.height) return
            this.width = width
            this.height = height
        }
        reset()
    }

    /** One Annex B access unit of the session, in decoding order. */
    fun frame(data: ByteArray, keyframe: Boolean) {
        if (viewers.isEmpty()) return
        synchronized(lock) {
            if (keyframe) {
                val found = Fmp4.parameterSets(data)
                if (found != null && found != sets) {
                    val restart = sets != null
                    sets = found
                    init = Fmp4.init(width, height, found)
                    if (restart) viewers.forEach { it.close() }
                }
            }
            val init = init ?: return
            var fragment: ByteArray? = null
            for (viewer in viewers) {
                if (!viewer.started) {
                    if (!keyframe) continue
                    viewer.started = true
                    viewer.offer(init)
                }
                val bytes = fragment ?: Fmp4.fragment(++sequence, sequence.toLong() * Fmp4.FRAME_TICKS, data, keyframe)
                    .also { fragment = it }
                viewer.offer(bytes)
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = REQUEST_TIMEOUT_MS
            client.tcpNoDelay = true
            val request = readRequest(client) ?: return client.close()
            val path = request.path
            val out = client.getOutputStream()
            val mine = "/$token"
            if (request.method == "POST") {
                val input = onInput
                when {
                    !sameText(path.take(mine.length), mine) || path != "$mine/input" ->
                        respond(out, "404 Not Found", "text/plain", "Not found")
                    input == null -> respond(out, "403 Forbidden", "text/plain", "Control is off")
                    else -> {
                        input(request.body)
                        respond(out, "200 OK", "text/plain", "")
                    }
                }
                out.flush()
                client.close()
                return
            }
            when {
                // Compared whole and in constant time: the token is the only lock.
                !sameText(path.substringBefore('?').take(mine.length), mine) -> respond(out, "404 Not Found", "text/plain", "Not found")
                path == mine -> out.write(
                    "HTTP/1.1 302 Found\r\nLocation: $mine/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                )
                path == "$mine/" -> respond(
                    out, "200 OK", "text/html; charset=utf-8",
                    PAGE.replace("CONTROL = false", "CONTROL = ${onInput != null}"),
                )
                path == "$mine/stream" -> {
                    if (viewers.size >= MAX_VIEWERS) {
                        respond(out, "503 Service Unavailable", "text/plain", "Too many viewers")
                    } else {
                        stream(client, out)
                        return
                    }
                }
                else -> respond(out, "404 Not Found", "text/plain", "Not found")
            }
            out.flush()
            client.close()
        } catch (_: IOException) {
            runCatching { client.close() }
        }
    }

    private fun stream(client: Socket, out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.flush()
        client.soTimeout = 0
        val viewer = Viewer(client, out)
        viewers.add(viewer)
        log("mirror viewer from ${client.inetAddress.hostAddress}")
        onViewer()
        viewer.run()
        viewers.remove(viewer)
    }

    private class Request(val method: String, val path: String, val body: String)

    /** The request, or null when it is not one this server understands. */
    private fun readRequest(client: Socket): Request? {
        val input = client.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > MAX_REQUEST) return null
            head.append(b.toChar())
        }
        val lines = head.lines()
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        return when (parts[0]) {
            "GET" -> Request("GET", parts[1], "")
            "POST" -> {
                val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: return null
                if (length !in 0..MAX_REQUEST) return null
                val body = input.readNBytes(length)
                if (body.size != length) return null
                Request("POST", parts[1], body.toString(Charsets.UTF_8))
            }
            else -> null
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
    }

    private fun sameText(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private class Viewer(private val client: Socket, private val out: OutputStream) {
        /** It has had its initialisation segment and a keyframe. */
        var started = false
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_FRAMES)

        /** Never blocks the session: a viewer that cannot keep up is dropped and comes back. */
        fun offer(bytes: ByteArray) {
            if (!queue.offer(bytes)) close()
        }

        fun run() {
            try {
                while (true) {
                    val bytes = queue.take()
                    if (bytes === END) break
                    out.write(bytes)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            runCatching { client.close() }
        }

        fun close() {
            queue.clear()
            queue.offer(END)
            runCatching { client.close() }
        }

        private companion object {
            val END = ByteArray(0)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_VIEWERS = 4
        private const val QUEUE_FRAMES = 240
        private const val REQUEST_TIMEOUT_MS = 5000
        private const val MAX_REQUEST = 8192

        /** A token short enough to type and long enough not to be guessed over a network. */
        fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = java.security.SecureRandom()
            return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** This device's addresses on its networks, most useful first. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { it is java.net.Inet4Address && !it.isLinkLocalAddress }
                .map(InetAddress::getHostAddress)
                .toList()
        }.getOrDefault(emptyList())

        private val PAGE = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>gliff</title>
<style>
html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
video { width: 100%; height: 100%; object-fit: contain; display: block; }
#note { position: fixed; left: 0; right: 0; bottom: 16px; text-align: center; color: #9aa; font: 14px system-ui, sans-serif; }
</style>
</head>
<body>
<video id="video" muted autoplay playsinline></video>
<div id="note">Waiting for the session…</div>
<script>
const video = document.getElementById('video');
const note = document.getElementById('note');
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function play() {
  const source = new MediaSource();
  video.src = URL.createObjectURL(source);
  await new Promise(r => source.addEventListener('sourceopen', r, { once: true }));
  // The stream's own profile and level are in its first segment; this only picks the decoder.
  const buffer = source.addSourceBuffer('video/mp4; codecs="avc1.640033"');
  // Frames follow one another whatever their timestamps say.
  buffer.mode = 'sequence';
  const pending = [];
  let failed = null;
  const pump = () => {
    if (buffer.updating || failed) return;
    try {
      if (pending.length) {
        buffer.appendBuffer(pending.shift());
      } else if (video.currentTime > 30 && buffer.buffered.length && buffer.buffered.start(0) < video.currentTime - 20) {
        buffer.remove(0, video.currentTime - 10);
      }
    } catch (e) { failed = e; }
  };
  buffer.addEventListener('updateend', () => {
    const ranges = video.buffered;
    if (ranges.length) {
      note.hidden = true;
      // Stay on the newest frame: a mirror has no use for a backlog.
      const end = ranges.end(ranges.length - 1);
      if (end - video.currentTime > 0.25 || video.currentTime < ranges.start(0)) video.currentTime = Math.max(end - 0.02, ranges.start(0));
      if (video.paused) video.play().catch(() => {});
    }
    pump();
  });
  buffer.addEventListener('error', () => { failed = new Error('buffer'); });
  const response = await fetch('stream', { cache: 'no-store' });
  if (!response.ok) throw new Error('HTTP ' + response.status);
  const reader = response.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done || failed) break;
    pending.push(value);
    pump();
  }
  try { await reader.cancel(); } catch (e) {}
}

const CONTROL = false;
if (CONTROL) {
  const queue = [];
  let busy = false;
  // One request at a time, so events arrive in the order they happened.
  async function flush() {
    if (busy || !queue.length) return;
    busy = true;
    const body = queue.splice(0).join('\n');
    try { await fetch('input', { method: 'POST', body }); } catch (e) {}
    busy = false;
    flush();
  }
  function push(line) {
    // A newer position replaces one still waiting.
    if (line[0] === 'm' && queue.length && queue[queue.length - 1][0] === 'm') queue.pop();
    queue.push(line);
    flush();
  }
  // Where the pointer is in the picture, which the video box may letterbox.
  function place(e) {
    const box = video.getBoundingClientRect();
    const vw = video.videoWidth, vh = video.videoHeight;
    if (!vw || !vh) return null;
    const scale = Math.min(box.width / vw, box.height / vh);
    const w = vw * scale, h = vh * scale;
    const x = (e.clientX - box.left - (box.width - w) / 2) / w;
    const y = (e.clientY - box.top - (box.height - h) / 2) / h;
    return 'm ' + Math.min(1, Math.max(0, x)).toFixed(5) + ' ' + Math.min(1, Math.max(0, y)).toFixed(5);
  }
  function move(e) { const m = place(e); if (m) push(m); return m; }
  window.addEventListener('mousemove', move);
  window.addEventListener('mousedown', e => { if (move(e)) push('b ' + e.button + ' 1'); e.preventDefault(); });
  window.addEventListener('mouseup', e => { if (move(e)) push('b ' + e.button + ' 0'); e.preventDefault(); });
  window.addEventListener('contextmenu', e => e.preventDefault());
  window.addEventListener('wheel', e => {
    // Lines or pixels, as the device reports them; a notch is about 100 pixels or 3 lines.
    const unit = e.deltaMode === 1 ? 3 : 100;
    push('w ' + (e.deltaX / unit).toFixed(3) + ' ' + (e.deltaY / unit).toFixed(3));
    e.preventDefault();
  }, { passive: false });
  window.addEventListener('keydown', e => {
    if (['Control', 'Shift', 'Alt', 'Meta', 'AltGraph', 'CapsLock', 'Dead'].includes(e.key)) return;
    e.preventDefault();
    // AltGr made the character already; it is not Ctrl+Alt for the remote.
    const altGr = e.getModifierState && e.getModifierState('AltGraph');
    let mods = altGr ? 0 : (e.ctrlKey ? 1 : 0) + (e.altKey ? 2 : 0) + (e.metaKey ? 4 : 0);
    if (e.key.length === 1 || [...e.key].length === 1) {
      push('t ' + mods + ' ' + encodeURIComponent(e.key));
    } else {
      push('s ' + (mods + (e.shiftKey ? 8 : 0)) + ' ' + e.key);
    }
  });
}

(async () => {
  if (!window.MediaSource) { note.textContent = 'This browser cannot play the stream (no Media Source).'; return; }
  for (;;) {
    try { await play(); } catch (e) {}
    await sleep(500);
  }
})();
</script>
</body>
</html>
""".trimIndent()
    }
}
