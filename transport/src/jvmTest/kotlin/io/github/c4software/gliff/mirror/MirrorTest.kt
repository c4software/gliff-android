package io.github.c4software.gliff.mirror

import io.github.c4software.gliff.domain.session.Viewport
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume

class MirrorTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** SPS and PPS of gliff-server 0.3.1 at 1920x1080, then a made-up slice. */
    private val sps = hex("67640033ac1b1a80780227e540")
    private val pps = hex("68ee3c80")
    private val keyframe = hex("00000001") + sps + hex("00000001") + pps + hex("00000001 6588800040 01")
    private val delta = hex("00000001 419a0004 0000 000001 41aabb")

    /** The boxes of an MP4 buffer, top level only: type to payload. */
    private fun boxes(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var at = 0
        while (at < data.size) {
            val size = ByteBuffer.wrap(data, at, 4).int
            assertTrue(size >= 8 && at + size <= data.size, "box of $size bytes at $at of ${data.size}")
            out.add(String(data, at + 4, 4, Charsets.ISO_8859_1) to data.copyOfRange(at + 8, at + size))
            at += size
        }
        return out
    }

    @Test
    fun parameterSetsComeOutOfAKeyframe() {
        val sets = assertNotNull(Fmp4.parameterSets(keyframe))
        assertContentEquals(sps, sets.sps)
        assertContentEquals(pps, sets.pps)
        assertEquals("avc1.640033", Fmp4.codec(sets))
        assertNull(Fmp4.parameterSets(delta))
        // Zeros in front of a start code are the start code's.
        assertEquals(listOf(4, 3), Fmp4.nalUnits(delta).map { it.count() })
    }

    @Test
    fun theInitSegmentDescribesTheStream() {
        val init = Fmp4.init(1920, 1080, Fmp4.parameterSets(keyframe)!!)
        assertEquals(listOf("ftyp", "moov"), boxes(init).map { it.first })
        val moov = boxes(boxes(init)[1].second)
        assertEquals(listOf("mvhd", "trak", "mvex"), moov.map { it.first })
        // The parameter sets are in there whole.
        val text = String(init, Charsets.ISO_8859_1)
        assertTrue(String(sps, Charsets.ISO_8859_1) in text && String(pps, Charsets.ISO_8859_1) in text)
    }

    @Test
    fun aFragmentCarriesTheSlicesWithTheirLengths() {
        val fragment = Fmp4.fragment(7, 10_500, keyframe, keyframe = true)
        val (moof, mdat) = boxes(fragment).also { assertEquals(listOf("moof", "mdat"), it.map { b -> b.first }) }
        // Only the slice: parameter sets belong to the init segment.
        assertContentEquals(hex("00000006 658880004001"), mdat.second)
        // The sample's data starts right behind the mdat header.
        val moofSize = moof.second.size + 8
        val trun = String(fragment, Charsets.ISO_8859_1).indexOf("trun")
        assertEquals(moofSize + 8, ByteBuffer.wrap(fragment, trun + 12, 4).int)
        assertEquals(4 + 6, ByteBuffer.wrap(fragment, trun + 20, 4).int)
        val two = boxes(Fmp4.fragment(8, 12_000, delta, keyframe = false))[1].second
        assertContentEquals(hex("00000004 419a0004 00000003 41aabb"), two)
    }

    @Test
    fun ffmpegReadsWhatTheMuxerWrites() {
        val sample = File("../scripts/sample.h264").takeIf { it.exists() } ?: File(System.getenv("GLIFF_SAMPLE") ?: "/nonexistent")
        Assume.assumeTrue("no sample stream", sample.exists())
        Assume.assumeTrue("no ffprobe", runCatching { ProcessBuilder("ffprobe", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val units = io.github.c4software.gliff.transport.accessUnits(sample.readBytes())
        val sets = Fmp4.parameterSets(units.first().bytes)!!
        val size = (System.getenv("GLIFF_SAMPLE_SIZE") ?: "1920x1080").split('x').map { it.toInt() }
        val out = File.createTempFile("gliff", ".mp4")
        try {
            out.outputStream().use { o ->
                o.write(Fmp4.init(size[0], size[1], sets))
                units.forEachIndexed { i, u -> o.write(Fmp4.fragment(i + 1, i.toLong() * Fmp4.FRAME_TICKS, u.bytes, u.keyframe)) }
            }
            val probe = ProcessBuilder(
                "ffprobe", "-v", "error", "-count_frames", "-show_entries", "stream=codec_name,width,height,nb_read_frames",
                "-of", "csv=p=0", out.path,
            ).redirectErrorStream(true).start()
            val text = probe.inputStream.bufferedReader().readText().trim()
            assertEquals(0, probe.waitFor(), text)
            assertEquals("h264,${size[0]},${size[1]},${units.size}", text)
        } finally {
            out.delete()
        }
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        return code to (c.getHeaderField("Location") ?: body)
    }

    @Test
    fun onlyTheTokenOpensThePage() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val server = MirrorServer(port, "s3cret", onViewer = {})
        server.start()
        try {
            assertEquals(404, get(port, "/").first)
            assertEquals(404, get(port, "/wrong/").first)
            assertEquals(404, get(port, "/s3cre/").first)
            assertEquals(404, get(port, "/s3cretx/").first)
            assertEquals(302 to "/s3cret/", get(port, "/s3cret"))
            val (code, page) = get(port, "/s3cret/")
            assertEquals(200, code)
            assertTrue("<video" in page && "fetch('stream'" in page && "fetch('view'" in page)
            // What installs it as an application sits behind the token too.
            assertTrue("\"start_url\"" in get(port, "/s3cret/manifest.json").second)
            assertTrue("<svg" in get(port, "/s3cret/icon.svg").second)
            assertEquals(404, get(port, "/manifest.json").first)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, path: String, body: String): Int {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 3000
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.responseCode
    }

    @Test
    fun inputIsTakenOnlyWhenControlIsOnAndTheTokenRight() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val watching = MirrorServer(port, "tok", onViewer = {})
        watching.start()
        try {
            assertEquals(403, post(port, "/tok/input", "m 0.5 0.5"))
            assertTrue("CONTROL = false" in get(port, "/tok/").second)
        } finally {
            watching.stop()
        }
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        val controlled = MirrorServer(port, "tok", onViewer = {}, onInput = { got.add(it) })
        controlled.start()
        try {
            assertEquals(404, post(port, "/nope/input", "m 0.5 0.5"))
            assertEquals(404, post(port, "/tok/other", "m 0.5 0.5"))
            assertEquals(200, post(port, "/tok/input", "m 0.5 0.5\nb 0 1"))
            assertEquals("m 0.5 0.5\nb 0 1", got.poll(3, TimeUnit.SECONDS))
            assertNull(got.poll())
            assertTrue("CONTROL = true" in get(port, "/tok/").second)
        } finally {
            controlled.stop()
        }
    }

    @Test
    fun thePageWindowIsKeptWhileSomeoneWatches() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val views = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = MirrorServer(port, "t", onViewer = {}, onView = { views.add(it?.toString() ?: "gone") }, viewGraceMs = 300)
        server.start()
        try {
            assertEquals(404, post(port, "/nope/view", "2560 1440 1"))
            // Read-only pages say it too; nonsense is skipped.
            for (junk in listOf("", "2560 1440", "0 1440 1", "2560 1440 NaN", "2560 1440 -1", "a b c")) {
                assertEquals(200, post(port, "/t/view", junk))
            }
            assertNull(views.poll(100, TimeUnit.MILLISECONDS))
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /t/stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertEquals('H'.code, socket.getInputStream().read())
                assertEquals(200, post(port, "/t/view", "2560 1440 1.250"))
                assertEquals(Viewport(2560, 1440, 1.25f).toString(), views.poll(3, TimeUnit.SECONDS))
                // It stays as long as the stream is read.
                assertNull(views.poll(600, TimeUnit.MILLISECONDS))
            }
            assertEquals("gone", views.poll(3, TimeUnit.SECONDS))
            // A page that tells its size and never comes for the stream does not hold it.
            assertEquals(200, post(port, "/t/view", "800 600 2"))
            assertEquals(Viewport(800, 600, 2f).toString(), views.poll(3, TimeUnit.SECONDS))
            assertEquals("gone", views.poll(3, TimeUnit.SECONDS))
            assertNull(views.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            server.stop()
        }
    }

    @Test
    fun aViewerStartsOnAKeyframeWithTheInitSegment() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val asked = CountDownLatch(1)
        val server = MirrorServer(port, "t", onViewer = { asked.countDown() })
        server.configure(1920, 1080)
        server.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /t/stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertTrue(asked.await(5, TimeUnit.SECONDS), "the session was not asked for a keyframe")
                // Until a keyframe comes, nothing can be shown.
                server.frame(delta, keyframe = false)
                server.frame(keyframe, keyframe = true)
                server.frame(delta, keyframe = false)
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                assertTrue(head.startsWith("HTTP/1.1 200 OK") && "video/mp4" in head, head.toString())
                val expected = Fmp4.init(1920, 1080, Fmp4.parameterSets(keyframe)!!).size +
                    Fmp4.fragment(1, 0, keyframe, true).size + Fmp4.fragment(2, 0, delta, false).size
                val body = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (body.size() < expected) body.write(chunk, 0, input.read(chunk).also { assertTrue(it > 0) })
                assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat"), boxes(body.toByteArray()).map { it.first })
                // A new size is a new stream: the viewer is let go, to come back.
                server.configure(1280, 720)
                assertEquals(-1, input.read())
            }
        } finally {
            server.stop()
        }
    }
}
