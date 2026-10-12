package io.github.c4software.gliff.transport

import io.github.c4software.gliff.proto.ByteSink
import io.github.c4software.gliff.proto.ByteSource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun InputStream.asByteSource(): ByteSource = ByteSource { dst, offset, length ->
    try {
        read(dst, offset, length)
    } catch (e: IOException) {
        throw TransportException(e.message ?: e.toString(), e)
    }
}

fun OutputStream.asByteSink(): ByteSink = object : ByteSink {
    override fun write(src: ByteArray, offset: Int, length: Int) = wrap { this@asByteSink.write(src, offset, length) }
    override fun flush() = wrap { this@asByteSink.flush() }

    private inline fun wrap(io: () -> Unit) {
        try {
            io()
        } catch (e: IOException) {
            throw TransportException(e.message ?: e.toString(), e)
        }
    }
}

/**
 * A transport over a local process, e.g. the system `ssh`. Its stderr is
 * inherited so server logs reach the terminal.
 */
class ProcessTransport(command: List<String>) : Transport {
    private val process: Process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    override val source: ByteSource = process.inputStream.asByteSource()
    override val sink: ByteSink = process.outputStream.asByteSink()

    override fun close() {
        process.destroy()
    }
}
