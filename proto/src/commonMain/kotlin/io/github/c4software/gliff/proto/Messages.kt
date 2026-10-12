package io.github.c4software.gliff.proto

/**
 * The gliff messages, ported from `crates/gliff-proto/src/msg.rs`, which is
 * the reference. A message travels as `[variant, [field 0, field 1, ...]]`:
 * the numbers are positions and names never appear.
 *
 * Unsigned wire integers keep their raw bits here: a `u32` is an `Int` and a
 * `u64` a `Long`. Sizes and key codes never reach the sign bit; ids, serials
 * and timestamps are only compared or echoed.
 *
 * Compatibility, as in the reference: extra fields are ignored, a message
 * this build does not know decodes as `null` so the caller skips it, and an
 * enum value this build does not know fails its message, except in the lists
 * of `Hello` and in optional enum fields.
 */
const val PROTOCOL_VERSION = 1

/** `ServerMsg.Error` codes. */
const val ERROR_VERSION = 1
const val ERROR_NO_CHROMA = 2
const val ERROR_NO_CODEC = 3

/** Codes 1 to 255: the two ends cannot work together, reconnecting cannot help. */
fun isIncompatible(code: Int): Boolean = code in 1..255

enum class Codec(val wire: Int) {
    RawBgra(0), H264(1), H265(2), Av1(3);

    companion object {
        fun decode(r: CborReader): Codec = fromWire(entries, r.u64()) { it.wire }
    }
}

enum class VideoPipeline(val wire: Int) {
    Gpu(0), Cpu(1);

    companion object {
        fun decode(r: CborReader): VideoPipeline = fromWire(entries, r.u64()) { it.wire }
    }
}

enum class ChromaMode(val wire: Int) {
    /** Single stream, encoder produced 4:4:4. */
    Native444(0),

    /** A main 4:2:0 stream plus an auxiliary one carrying the dropped chroma. */
    Dual420(1),

    /** One 4:2:0 stream. */
    Single420(2);

    companion object {
        fun decode(r: CborReader): ChromaMode = fromWire(entries, r.u64()) { it.wire }
    }
}

enum class Axis(val wire: Int) {
    Vertical(0), Horizontal(1);

    companion object {
        fun decode(r: CborReader): Axis = fromWire(entries, r.u64()) { it.wire }
    }
}

private inline fun <T> fromWire(all: List<T>, value: Long, wire: (T) -> Int): T =
    all.firstOrNull { wire(it).toLong() == value } ?: throw UnknownVariantException(value)

data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun encode(w: CborWriter) {
        w.array(4)
        w.int(x.toLong())
        w.int(y.toLong())
        w.int(width.toLong())
        w.int(height.toLong())
    }

    companion object {
        fun decode(r: CborReader): Rect {
            val f = r.fields()
            return Rect(
                f.req("x") { r.i32() },
                f.req("y") { r.i32() },
                f.req("width") { r.i32() },
                f.req("height") { r.i32() },
            ).also { f.end() }
        }
    }
}

data class ClientCaps(
    val codecs: List<Codec>,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Chroma modes the client can decode, preferred first. */
    val chroma: List<ChromaMode>,
    val features: List<String> = emptyList(),
) {
    fun encode(w: CborWriter) {
        w.array(5)
        w.list(codecs) { uint(it.wire.toLong()) }
        w.u32(maxWidth)
        w.u32(maxHeight)
        w.list(chroma) { uint(it.wire.toLong()) }
        w.list(features) { text(it) }
    }

    companion object {
        fun decode(r: CborReader): ClientCaps {
            val f = r.fields()
            return ClientCaps(
                f.req("codecs") { r.knownList { Codec.decode(r) } },
                f.req("max_width") { r.u32() },
                f.req("max_height") { r.u32() },
                f.req("chroma") { r.knownList { ChromaMode.decode(r) } },
                f.req("features") { r.list { r.text() } },
            ).also { f.end() }
        }
    }
}

data class OutputInfo(val name: String, val width: Int, val height: Int, val scaleMilli: Int) {
    fun encode(w: CborWriter) {
        w.array(4)
        w.text(name)
        w.u32(width)
        w.u32(height)
        w.u32(scaleMilli)
    }

    companion object {
        fun decode(r: CborReader): OutputInfo {
            val f = r.fields()
            return OutputInfo(
                f.req("name") { r.text() },
                f.req("width") { r.u32() },
                f.req("height") { r.u32() },
                f.req("scale_milli") { r.u32() },
            ).also { f.end() }
        }
    }
}

data class SessionInfo(val headless: Boolean, val output: String) {
    fun encode(w: CborWriter) {
        w.array(2)
        w.bool(headless)
        w.text(output)
    }

    companion object {
        fun decode(r: CborReader): SessionInfo {
            val f = r.fields()
            return SessionInfo(
                f.req("headless") { r.bool() },
                f.req("output") { r.text() },
            ).also { f.end() }
        }
    }
}

data class ClipboardFile(
    /** Path relative to the copied item, `/`-separated. */
    val path: String,
    val size: Long,
    val dir: Boolean,
) {
    fun encode(w: CborWriter) {
        w.array(3)
        w.text(path)
        w.uint(size)
        w.bool(dir)
    }

    companion object {
        fun decode(r: CborReader): ClipboardFile {
            val f = r.fields()
            return ClipboardFile(
                f.req("path") { r.text() },
                f.req("size") { r.u64() },
                f.req("dir") { r.bool() },
            ).also { f.end() }
        }
    }
}

sealed interface ClipboardItem {
    data class Mime(val mimeType: String) : ClipboardItem

    /** Index into the offer's `files`. */
    data class File(val index: Int) : ClipboardItem

    fun encode(w: CborWriter) {
        w.array(2)
        when (this) {
            is Mime -> {
                w.uint(0)
                w.array(1)
                w.text(mimeType)
            }
            is File -> {
                w.uint(1)
                w.array(1)
                w.u32(index)
            }
        }
    }

    companion object {
        fun decode(r: CborReader): ClipboardItem {
            val outer = r.fields()
            val variant = outer.req("variant") { r.u64() }
            return outer.req("fields") {
                val f = r.fields()
                when (variant) {
                    0L -> Mime(f.req("mime") { r.text() })
                    1L -> File(f.req("index") { r.u32() })
                    else -> throw UnknownVariantException(variant)
                }.also { f.end() }
            }.also { outer.end() }
        }
    }
}

/** The clipboard messages, which have the same shape in both directions. */
sealed interface ClipboardMsg {
    /** The sender's selection changed; empty lists mean it was cleared. */
    data class Offer(val serial: Int, val mimeTypes: List<String>, val files: List<ClipboardFile>) : ClipboardMsg

    /** Stream `item` from the offer with `serial`; `id` is the requester's. */
    data class Request(val id: Int, val serial: Int, val item: ClipboardItem) : ClipboardMsg

    /** One chunk; `dataLen` payload bytes follow on the wire. */
    data class Data(val id: Int, val offset: Long, val dataLen: Int, val done: Boolean) : ClipboardMsg

    /** The requester has consumed `received` bytes so far. */
    data class Ack(val id: Int, val received: Long) : ClipboardMsg

    data class Abort(val id: Int) : ClipboardMsg
}

private fun CborWriter.clipboardFields(m: ClipboardMsg) {
    when (m) {
        is ClipboardMsg.Offer -> {
            array(3)
            u32(m.serial)
            list(m.mimeTypes) { text(it) }
            list(m.files) { it.encode(this) }
        }
        is ClipboardMsg.Request -> {
            array(3)
            u32(m.id)
            u32(m.serial)
            m.item.encode(this)
        }
        is ClipboardMsg.Data -> {
            array(4)
            u32(m.id)
            uint(m.offset)
            u32(m.dataLen)
            bool(m.done)
        }
        is ClipboardMsg.Ack -> {
            array(2)
            u32(m.id)
            uint(m.received)
        }
        is ClipboardMsg.Abort -> {
            array(1)
            u32(m.id)
        }
    }
}

private fun offer(r: CborReader, f: CborReader.Fields) = ClipboardMsg.Offer(
    f.req("serial") { r.u32() },
    f.req("mime_types") { r.list { r.text() } },
    f.req("files") { r.list { ClipboardFile.decode(r) } },
)

private fun request(r: CborReader, f: CborReader.Fields) = ClipboardMsg.Request(
    f.req("id") { r.u32() },
    f.req("serial") { r.u32() },
    f.req("item") { ClipboardItem.decode(r) },
)

private fun data(r: CborReader, f: CborReader.Fields) = ClipboardMsg.Data(
    f.req("id") { r.u32() },
    f.req("offset") { r.u64() },
    f.req("data_len") { r.u32() },
    f.req("done") { r.bool() },
)

private fun ack(r: CborReader, f: CborReader.Fields) = ClipboardMsg.Ack(
    f.req("id") { r.u32() },
    f.req("received") { r.u64() },
)

private fun abort(r: CborReader, f: CborReader.Fields) = ClipboardMsg.Abort(f.req("id") { r.u32() })

sealed interface ClientMsg {
    data class Hello(
        val version: Int = PROTOCOL_VERSION,
        /** XKB keymap text, or empty for the server's default. */
        val keymap: String,
        val caps: ClientCaps,
    ) : ClientMsg

    /** The window size in physical pixels and its display scale. */
    data class Resize(val width: Int, val height: Int, val scale: Float) : ClientMsg

    /** `keycode` is a Linux evdev code (xkb keycode minus 8). */
    data class Key(val keycode: Int, val pressed: Boolean) : ClientMsg

    /** Absolute position in the remote's logical space. */
    data class PointerMotion(val x: Double, val y: Double) : ClientMsg

    /** `button` is an evdev `BTN_*` code. */
    data class PointerButton(val button: Int, val pressed: Boolean) : ClientMsg

    data class PointerAxis(val axis: Axis, val value: Double, val discrete: Int?, val stop: Boolean) : ClientMsg

    data class FrameAck(val frameId: Long, val decodedAtMs: Long) : ClientMsg

    data object RequestKeyframe : ClientMsg

    data class Clipboard(val msg: ClipboardMsg) : ClientMsg

    data class Ping(val t: Long) : ClientMsg

    data object Bye : ClientMsg

    /** The local keymap changed since `Hello`. */
    data class Keymap(val keymap: String) : ClientMsg

    /** Answer to `ServerMsg.Ping`, sent before anything else. */
    data class Pong(val t: Long) : ClientMsg

    fun encode(): ByteArray {
        val w = CborWriter()
        w.array(2)
        when (this) {
            is Hello -> {
                w.uint(0)
                w.array(3)
                w.uint(version.toLong())
                w.text(keymap)
                caps.encode(w)
            }
            is Resize -> {
                w.uint(1)
                w.array(3)
                w.u32(width)
                w.u32(height)
                w.f32(scale)
            }
            is Key -> {
                w.uint(2)
                w.array(2)
                w.u32(keycode)
                w.bool(pressed)
            }
            is PointerMotion -> {
                w.uint(3)
                w.array(2)
                w.f64(x)
                w.f64(y)
            }
            is PointerButton -> {
                w.uint(4)
                w.array(2)
                w.u32(button)
                w.bool(pressed)
            }
            is PointerAxis -> {
                w.uint(5)
                w.array(4)
                w.uint(axis.wire.toLong())
                w.f64(value)
                if (discrete == null) w.nil() else w.int(discrete.toLong())
                w.bool(stop)
            }
            is FrameAck -> {
                w.uint(6)
                w.array(2)
                w.uint(frameId)
                w.uint(decodedAtMs)
            }
            RequestKeyframe -> {
                w.uint(7)
                w.array(0)
            }
            is Clipboard -> {
                w.uint(
                    when (msg) {
                        is ClipboardMsg.Offer -> 8
                        is ClipboardMsg.Request -> 9
                        is ClipboardMsg.Data -> 10
                        is ClipboardMsg.Ack -> 13
                        is ClipboardMsg.Abort -> 14
                    }
                )
                w.clipboardFields(msg)
            }
            is Ping -> {
                w.uint(11)
                w.array(1)
                w.uint(t)
            }
            Bye -> {
                w.uint(12)
                w.array(0)
            }
            is Keymap -> {
                w.uint(15)
                w.array(1)
                w.text(keymap)
            }
            is Pong -> {
                w.uint(16)
                w.array(1)
                w.uint(t)
            }
        }
        return w.toByteArray()
    }

    companion object {
        /** Decode a message body; `null` for a message this build does not know. */
        fun decode(body: ByteArray): ClientMsg? = decodeMsg(body) { r, variant, f ->
            when (variant) {
                0L -> Hello(
                    f.req("version") { r.u16() },
                    f.req("keymap") { r.text() },
                    f.req("caps") { ClientCaps.decode(r) },
                )
                1L -> Resize(f.req("width") { r.u32() }, f.req("height") { r.u32() }, f.req("scale") { r.f32() })
                2L -> Key(f.req("keycode") { r.u32() }, f.req("pressed") { r.bool() })
                3L -> PointerMotion(f.req("x") { r.f64() }, f.req("y") { r.f64() })
                4L -> PointerButton(f.req("button") { r.u32() }, f.req("pressed") { r.bool() })
                5L -> PointerAxis(
                    f.req("axis") { Axis.decode(r) },
                    f.req("value") { r.f64() },
                    f.opt { r.i32() },
                    f.req("stop") { r.bool() },
                )
                6L -> FrameAck(f.req("frame_id") { r.u64() }, f.req("decoded_at_ms") { r.u64() })
                7L -> RequestKeyframe
                8L -> Clipboard(offer(r, f))
                9L -> Clipboard(request(r, f))
                10L -> Clipboard(data(r, f))
                11L -> Ping(f.req("t") { r.u64() })
                12L -> Bye
                13L -> Clipboard(ack(r, f))
                14L -> Clipboard(abort(r, f))
                15L -> Keymap(f.req("keymap") { r.text() })
                16L -> Pong(f.req("t") { r.u64() })
                else -> null
            }
        }
    }
}

sealed interface ServerMsg {
    data class HelloAck(
        val version: Int,
        val session: SessionInfo,
        val outputs: List<OutputInfo>,
        val features: List<String>,
    ) : ServerMsg

    data class StreamConfig(
        val codec: Codec,
        val chroma: ChromaMode,
        /** Which pipeline encodes on the server; `null` when unknown. */
        val pipeline: VideoPipeline?,
        /** Stream size in physical pixels. */
        val width: Int,
        val height: Int,
        /**
         * Effective stream scale x1000: pointer coordinates are stream pixels
         * divided by this scale.
         */
        val scaleMilli: Int,
        val extradata: ByteArray,
        val auxExtradata: ByteArray?,
        /** The box the stream is stretched to before fitting it to the window. */
        val viewWidth: Int,
        val viewHeight: Int,
        val fpsCap: Int,
    ) : ServerMsg {
        override fun equals(other: Any?): Boolean = other is StreamConfig &&
            codec == other.codec && chroma == other.chroma && pipeline == other.pipeline &&
            width == other.width && height == other.height && scaleMilli == other.scaleMilli &&
            extradata.contentEquals(other.extradata) &&
            (auxExtradata?.contentEquals(other.auxExtradata) ?: (other.auxExtradata == null)) &&
            viewWidth == other.viewWidth && viewHeight == other.viewHeight && fpsCap == other.fpsCap

        override fun hashCode(): Int =
            listOf(codec, chroma, pipeline, width, height, scaleMilli, viewWidth, viewHeight, fpsCap).hashCode()
    }

    /** `dataLen` main-stream bytes then `auxLen` auxiliary bytes follow. */
    data class VideoFrame(
        val frameId: Long,
        val ptsUs: Long,
        val keyframe: Boolean,
        val damage: List<Rect>,
        val dataLen: Int,
        /** Non-zero only in `Dual420`. */
        val auxLen: Int,
    ) : ServerMsg

    /** `argbLen` payload bytes follow. */
    data class CursorShape(
        val id: Int,
        val width: Int,
        val height: Int,
        val hotX: Int,
        val hotY: Int,
        val argbLen: Int,
    ) : ServerMsg

    data class CursorPos(val x: Double, val y: Double, val shapeId: Int, val visible: Boolean) : ServerMsg

    data class Clipboard(val msg: ClipboardMsg) : ServerMsg

    data class Pong(val t: Long, val serverNowMs: Long) : ServerMsg

    data class Error(val code: Int, val message: String, val version: Int? = PROTOCOL_VERSION) : ServerMsg

    /** Sent right after `HelloAck`; answered with `ClientMsg.Pong` ahead of anything else. */
    data class Ping(val t: Long) : ServerMsg

    fun encode(): ByteArray {
        val w = CborWriter()
        w.array(2)
        when (this) {
            is HelloAck -> {
                w.uint(0)
                w.array(4)
                w.uint(version.toLong())
                session.encode(w)
                w.list(outputs) { it.encode(this) }
                w.list(features) { text(it) }
            }
            is StreamConfig -> {
                w.uint(1)
                w.array(11)
                w.uint(codec.wire.toLong())
                w.uint(chroma.wire.toLong())
                if (pipeline == null) w.nil() else w.uint(pipeline.wire.toLong())
                w.u32(width)
                w.u32(height)
                w.u32(scaleMilli)
                w.bytes(extradata)
                if (auxExtradata == null) w.nil() else w.bytes(auxExtradata)
                w.u32(viewWidth)
                w.u32(viewHeight)
                w.u32(fpsCap)
            }
            is VideoFrame -> {
                w.uint(2)
                w.array(6)
                w.uint(frameId)
                w.uint(ptsUs)
                w.bool(keyframe)
                w.list(damage) { it.encode(this) }
                w.u32(dataLen)
                w.u32(auxLen)
            }
            is CursorShape -> {
                w.uint(3)
                w.array(6)
                w.u32(id)
                w.u32(width)
                w.u32(height)
                w.int(hotX.toLong())
                w.int(hotY.toLong())
                w.u32(argbLen)
            }
            is CursorPos -> {
                w.uint(4)
                w.array(4)
                w.f64(x)
                w.f64(y)
                w.u32(shapeId)
                w.bool(visible)
            }
            is Clipboard -> {
                w.uint(
                    when (msg) {
                        is ClipboardMsg.Offer -> 5
                        is ClipboardMsg.Request -> 6
                        is ClipboardMsg.Data -> 7
                        is ClipboardMsg.Ack -> 10
                        is ClipboardMsg.Abort -> 11
                    }
                )
                w.clipboardFields(msg)
            }
            is Pong -> {
                w.uint(8)
                w.array(2)
                w.uint(t)
                w.uint(serverNowMs)
            }
            is Error -> {
                w.uint(9)
                // A trailing absent field is not sent at all.
                w.array(if (version == null) 2 else 3)
                w.uint(code.toLong())
                w.text(message)
                if (version != null) w.uint(version.toLong())
            }
            is Ping -> {
                w.uint(12)
                w.array(1)
                w.uint(t)
            }
        }
        return w.toByteArray()
    }

    companion object {
        /** Decode a message body; `null` for a message this build does not know. */
        fun decode(body: ByteArray): ServerMsg? = decodeMsg(body) { r, variant, f ->
            when (variant) {
                0L -> HelloAck(
                    f.req("version") { r.u16() },
                    f.req("session") { SessionInfo.decode(r) },
                    f.req("outputs") { r.list { OutputInfo.decode(r) } },
                    f.req("features") { r.list { r.text() } },
                )
                1L -> StreamConfig(
                    f.req("codec") { Codec.decode(r) },
                    f.req("chroma") { ChromaMode.decode(r) },
                    f.opt { r.optionalEnum { VideoPipeline.decode(r) } },
                    f.req("width") { r.u32() },
                    f.req("height") { r.u32() },
                    f.req("scale_milli") { r.u32() },
                    f.req("extradata") { r.bytes() },
                    f.opt { r.bytes() },
                    f.req("view_width") { r.u32() },
                    f.req("view_height") { r.u32() },
                    f.req("fps_cap") { r.u32() },
                )
                2L -> VideoFrame(
                    f.req("frame_id") { r.u64() },
                    f.req("pts_us") { r.u64() },
                    f.req("keyframe") { r.bool() },
                    f.req("damage") { r.list { Rect.decode(r) } },
                    f.req("data_len") { r.u32() },
                    f.req("aux_len") { r.u32() },
                )
                3L -> CursorShape(
                    f.req("id") { r.u32() },
                    f.req("width") { r.u32() },
                    f.req("height") { r.u32() },
                    f.req("hot_x") { r.i32() },
                    f.req("hot_y") { r.i32() },
                    f.req("argb_len") { r.u32() },
                )
                4L -> CursorPos(
                    f.req("x") { r.f64() },
                    f.req("y") { r.f64() },
                    f.req("shape_id") { r.u32() },
                    f.req("visible") { r.bool() },
                )
                5L -> Clipboard(offer(r, f))
                6L -> Clipboard(request(r, f))
                7L -> Clipboard(data(r, f))
                8L -> Pong(f.req("t") { r.u64() }, f.req("server_now_ms") { r.u64() })
                9L -> Error(f.req("code") { r.u16() }, f.req("message") { r.text() }, f.opt { r.u16() })
                10L -> Clipboard(ack(r, f))
                11L -> Clipboard(abort(r, f))
                12L -> Ping(f.req("t") { r.u64() })
                else -> null
            }
        }
    }
}

/** Read the `[variant, [fields]]` envelope and hand the fields to `variant`. */
private fun <M : Any> decodeMsg(
    body: ByteArray,
    variant: (CborReader, Long, CborReader.Fields) -> M?,
): M? {
    val r = CborReader(body)
    val outer = r.fields()
    val index = outer.req("variant") { r.u64() }
    return outer.req("fields") {
        val f = r.fields()
        variant(r, index, f)?.also { f.end() }
    }
}
