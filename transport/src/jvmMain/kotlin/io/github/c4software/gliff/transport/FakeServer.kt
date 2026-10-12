package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ChromaMode
import io.github.c4software.gliff.proto.ClientMsg
import io.github.c4software.gliff.proto.Codec
import io.github.c4software.gliff.proto.ERROR_NO_CHROMA
import io.github.c4software.gliff.proto.ERROR_VERSION
import io.github.c4software.gliff.proto.EndOfStreamException
import io.github.c4software.gliff.proto.FrameReader
import io.github.c4software.gliff.proto.FrameWriter
import io.github.c4software.gliff.proto.OutputInfo
import io.github.c4software.gliff.proto.PROTOCOL_VERSION
import io.github.c4software.gliff.proto.ServerMsg
import io.github.c4software.gliff.proto.SessionInfo
import io.github.c4software.gliff.proto.VideoPipeline
import java.io.File
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A stand-in for `gliff-server --stdio` that needs no Hyprland: it loops an
 * Annex B H.264 file as the video stream. For developing the clients only.
 * Like the real server it stops sending when frames go unacked.
 *
 *   fake-server --stdio [--headless | --output NAME] [--sample FILE] [--size WxH] [--fps N]
 */
fun main(args: Array<String>) {
    var sample = System.getenv("GLIFF_FAKE_SAMPLE") ?: "sample.h264"
    var size = 1280 to 720
    var fps = 30
    var headless = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--stdio" -> {}
            "--headless" -> headless = true
            "--output" -> i++
            "--sample" -> sample = args[++i]
            "--size" -> size = args[++i].split('x').let { it[0].toInt() to it[1].toInt() }
            "--fps" -> fps = args[++i].toInt()
            else -> {
                System.err.println("fake-server: unexpected argument ${args[i]}")
                exitProcess(2)
            }
        }
        i++
    }
    val units = accessUnits(File(sample).readBytes())
    System.err.println("fake-server: ${units.size} access units from $sample")

    // stdout is the protocol; nothing else may write to it.
    val out = System.out
    System.setOut(System.err)
    val reader = FrameReader(System.`in`.asByteSource(), ClientMsg::decode)
    val writer = FrameWriter(out.asByteSink())
    val lock = Any()
    fun send(msg: ServerMsg, vararg payloads: ByteArray) = synchronized(lock) { writer.write(msg, *payloads) }

    val hello = reader.readMsg() as? ClientMsg.Hello ?: run {
        System.err.println("fake-server: expected Hello")
        exitProcess(1)
    }
    if (hello.version != PROTOCOL_VERSION) {
        send(ServerMsg.Error(ERROR_VERSION, Session.versionMismatch(hello.version, PROTOCOL_VERSION)))
        exitProcess(1)
    }
    if (ChromaMode.Single420 !in hello.caps.chroma) {
        send(ServerMsg.Error(ERROR_NO_CHROMA, "No chroma mode in common"))
        exitProcess(1)
    }
    val name = if (headless) "HEADLESS-2" else "FAKE-1"
    send(ServerMsg.HelloAck(PROTOCOL_VERSION, SessionInfo(headless, name), listOf(OutputInfo(name, size.first, size.second, 1000)), emptyList()))
    send(ServerMsg.Ping(System.currentTimeMillis()))
    fun config(view: Pair<Int, Int>) = ServerMsg.StreamConfig(
        Codec.H264, ChromaMode.Single420, VideoPipeline.Cpu, size.first, size.second, 1000,
        ByteArray(0), null, view.first, view.second, fps,
    )
    send(config(size))

    // Frames the client may leave unacked before the stream stops.
    val window = Semaphore(8)
    thread(name = "fake-server-reader", isDaemon = true) {
        var ponged = false
        try {
            while (true) {
                when (val msg = reader.readMsg()) {
                    is ClientMsg.Pong -> ponged = true
                    is ClientMsg.FrameAck -> window.release()
                    is ClientMsg.Ping -> send(ServerMsg.Pong(msg.t, System.currentTimeMillis()))
                    is ClientMsg.Bye -> exitProcess(0)
                    is ClientMsg.Resize -> {
                        System.err.println("fake-server: resize ${msg.width}x${msg.height} scale ${msg.scale}")
                        // The sample cannot be re-encoded: only the view follows.
                        if (headless) send(config(msg.width to msg.height))
                    }
                    else -> System.err.println("fake-server: $msg")
                }
                if (!ponged) {
                    System.err.println("fake-server: a message arrived before Pong")
                    exitProcess(1)
                }
            }
        } catch (_: EndOfStreamException) {
            exitProcess(0)
        }
    }

    var id = 0L
    val start = System.nanoTime()
    while (true) {
        for (unit in units) {
            window.acquire()
            id++
            send(
                ServerMsg.VideoFrame(id, (System.nanoTime() - start) / 1000, unit.keyframe, emptyList(), unit.bytes.size, 0),
                unit.bytes,
            )
            val due = start + id * 1_000_000_000L / fps
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
    }
}

internal class AccessUnit(val bytes: ByteArray, val keyframe: Boolean)

/** Split an Annex B stream into access units, each starting at its first NAL. */
internal fun accessUnits(stream: ByteArray): List<AccessUnit> {
    // Offsets of each start code (the 00 00 01 part) and of its NAL header.
    val nals = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i + 3 < stream.size) {
        if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
            val start = if (i > 0 && stream[i - 1].toInt() == 0) i - 1 else i
            nals.add(start to i + 3)
            i += 3
        } else {
            i++
        }
    }
    val units = ArrayList<AccessUnit>()
    var unitStart = -1
    var hasSlice = false
    var keyframe = false
    fun close(end: Int) {
        if (unitStart >= 0 && hasSlice) units.add(AccessUnit(stream.copyOfRange(unitStart, end), keyframe))
    }
    for ((start, header) in nals) {
        val type = stream[header].toInt() and 0x1f
        val slice = type == 1 || type == 5
        // first_mb_in_slice == 0 is a leading 1 bit: a new picture.
        val firstSlice = slice && header + 1 < stream.size && stream[header + 1].toInt() and 0x80 != 0
        if (hasSlice && (!slice || firstSlice)) {
            close(start)
            unitStart = -1
            hasSlice = false
            keyframe = false
        }
        if (unitStart < 0) unitStart = start
        if (slice) hasSlice = true
        if (type == 5) keyframe = true
    }
    close(stream.size)
    return units
}
