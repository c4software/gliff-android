package io.github.c4software.gliff.mirror

import io.github.c4software.gliff.transport.accessUnits
import java.io.File

/**
 * Serves an Annex B file through the mirror, in a loop, to try the page in a
 * browser without a phone: `MirrorToolKt FILE WIDTHxHEIGHT [fps] [port]`.
 */
fun main(args: Array<String>) {
    val units = accessUnits(File(args[0]).readBytes())
    val (width, height) = args[1].split('x').map { it.toInt() }
    val fps = args.getOrNull(2)?.toInt() ?: 30
    val port = args.getOrNull(3)?.toInt() ?: MirrorServer.DEFAULT_PORT
    val restart = java.util.concurrent.atomic.AtomicBoolean()
    val server = MirrorServer(port, "test", onViewer = { restart.set(true) }, onInput = ::println, log = ::println)
    server.configure(width, height)
    server.start()
    println("http://127.0.0.1:$port/test/")
    while (true) {
        for (unit in units) {
            if (restart.get()) break
            server.frame(unit.bytes, unit.keyframe)
            Thread.sleep(1000L / fps)
        }
        restart.set(false)
    }
}
