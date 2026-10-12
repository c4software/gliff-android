package io.github.c4software.gliff.mirror

/**
 * The server's H.264 as fragmented MP4, which is what a browser's Media
 * Source plays: one initialisation segment carrying the parameter sets, then
 * one fragment per frame.
 */
object Fmp4 {
    const val TIMESCALE = 90_000

    /** Every frame claims a sixtieth of a second; the player shows it and waits for the next. */
    const val FRAME_TICKS = TIMESCALE / 60

    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_AUD = 9

    /** A stream's SPS and PPS, each a whole NAL unit without its start code. */
    class ParameterSets(val sps: ByteArray, val pps: ByteArray) {
        override fun equals(other: Any?) =
            other is ParameterSets && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)

        override fun hashCode() = sps.contentHashCode() * 31 + pps.contentHashCode()
    }

    /** The NAL units of an Annex B access unit, as offset ranges past their start codes. */
    fun nalUnits(data: ByteArray): List<IntRange> {
        val units = ArrayList<IntRange>()
        var start = -1
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (start >= 0) units.add(start until trimZeros(data, start, i))
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start in 0 until data.size) units.add(start until trimZeros(data, start, data.size))
        return units.filter { !it.isEmpty() }
    }

    /** Zero bytes in front of a start code belong to it, not to the unit before. */
    private fun trimZeros(data: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start && data[e - 1] == 0.toByte()) e--
        return e
    }

    /** The parameter sets a keyframe carries; null when it has none. */
    fun parameterSets(accessUnit: ByteArray): ParameterSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (unit in nalUnits(accessUnit)) {
            when (accessUnit[unit.first].toInt() and 0x1f) {
                NAL_SPS -> sps = accessUnit.copyOfRange(unit.first, unit.last + 1)
                NAL_PPS -> pps = accessUnit.copyOfRange(unit.first, unit.last + 1)
            }
        }
        return if (sps != null && pps != null && sps.size >= 4) ParameterSets(sps, pps) else null
    }

    /** `avc1.PPCCLL`, the codec name a browser wants for these parameter sets. */
    fun codec(sets: ParameterSets): String =
        "avc1." + (1..3).joinToString("") { (sets.sps[it].toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The segment a player needs first: what the stream is, and no frame yet. */
    fun init(width: Int, height: Int, sets: ParameterSets): ByteArray {
        val o = Out()
        o.box("ftyp") {
            text("iso5")
            u32(0x200)
            text("iso5")
            text("iso6")
            text("mp41")
            text("avc1")
        }
        o.box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(0)
                u32(0)
                u32(TIMESCALE)
                u32(0)
                u32(0x00010000)
                u16(0x0100)
                zeros(10)
                matrix()
                zeros(24)
                u32(2) // next track id
            }
            box("trak") {
                fullBox("tkhd", 0, 3) {
                    u32(0)
                    u32(0)
                    u32(1) // track id
                    u32(0)
                    u32(0)
                    zeros(8)
                    u16(0)
                    u16(0)
                    u16(0)
                    u16(0)
                    matrix()
                    u32(width shl 16)
                    u32(height shl 16)
                }
                box("mdia") {
                    fullBox("mdhd", 0, 0) {
                        u32(0)
                        u32(0)
                        u32(TIMESCALE)
                        u32(0)
                        u16(0x55c4) // language: undetermined
                        u16(0)
                    }
                    fullBox("hdlr", 0, 0) {
                        u32(0)
                        text("vide")
                        zeros(12)
                        text("gliff")
                        u8(0)
                    }
                    box("minf") {
                        fullBox("vmhd", 0, 1) { zeros(8) }
                        box("dinf") {
                            fullBox("dref", 0, 0) {
                                u32(1)
                                fullBox("url ", 0, 1) {}
                            }
                        }
                        box("stbl") {
                            fullBox("stsd", 0, 0) {
                                u32(1)
                                box("avc1") {
                                    zeros(6)
                                    u16(1) // data reference index
                                    zeros(16)
                                    u16(width)
                                    u16(height)
                                    u32(0x00480000)
                                    u32(0x00480000)
                                    u32(0)
                                    u16(1) // frames per sample
                                    zeros(32)
                                    u16(0x0018)
                                    u16(0xffff)
                                    box("avcC") {
                                        u8(1)
                                        u8(sets.sps[1].toInt())
                                        u8(sets.sps[2].toInt())
                                        u8(sets.sps[3].toInt())
                                        u8(0xff) // 4-byte NAL lengths
                                        u8(0xe1) // one SPS
                                        u16(sets.sps.size)
                                        bytes(sets.sps)
                                        u8(1)
                                        u16(sets.pps.size)
                                        bytes(sets.pps)
                                    }
                                }
                            }
                            fullBox("stts", 0, 0) { u32(0) }
                            fullBox("stsc", 0, 0) { u32(0) }
                            fullBox("stsz", 0, 0) {
                                u32(0)
                                u32(0)
                            }
                            fullBox("stco", 0, 0) { u32(0) }
                        }
                    }
                }
            }
            box("mvex") {
                fullBox("trex", 0, 0) {
                    u32(1)
                    u32(1)
                    u32(0)
                    u32(0)
                    u32(0)
                }
            }
        }
        return o.finish()
    }

    /**
     * One frame as a fragment. `sequence` counts fragments from 1 and
     * `decodeTime` is in [TIMESCALE] ticks. Parameter sets and delimiters are
     * left out: they live in the initialisation segment.
     */
    fun fragment(sequence: Int, decodeTime: Long, accessUnit: ByteArray, keyframe: Boolean): ByteArray {
        val units = nalUnits(accessUnit).filter {
            when (accessUnit[it.first].toInt() and 0x1f) {
                NAL_SPS, NAL_PPS, NAL_AUD -> false
                else -> true
            }
        }
        val sampleSize = units.sumOf { 4 + it.count() }
        val o = Out()
        var dataOffsetAt = 0
        val moofStart = o.size
        o.box("moof") {
            fullBox("mfhd", 0, 0) { u32(sequence) }
            box("traf") {
                // Offsets count from the start of this moof.
                fullBox("tfhd", 0, 0x020000) { u32(1) }
                fullBox("tfdt", 1, 0) {
                    u32((decodeTime ushr 32).toInt())
                    u32(decodeTime.toInt())
                }
                // One sample, with its data offset, duration, size and flags.
                fullBox("trun", 0, 0x000701) {
                    u32(1)
                    dataOffsetAt = size
                    u32(0)
                    u32(FRAME_TICKS)
                    u32(sampleSize)
                    // Depends on no other frame, or on others and not a sync point.
                    u32(if (keyframe) 0x02000000 else 0x01010000)
                }
            }
        }
        o.patch(dataOffsetAt, o.size - moofStart + 8)
        o.box("mdat") {
            for (unit in units) {
                u32(unit.count())
                bytes(accessUnit, unit.first, unit.count())
            }
        }
        return o.finish()
    }

    private class Out {
        private var data = ByteArray(1024)
        var size = 0
            private set

        private fun room(more: Int) {
            if (size + more > data.size) data = data.copyOf(maxOf(data.size * 2, size + more))
        }

        fun u8(value: Int) {
            room(1)
            data[size++] = value.toByte()
        }

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun u32(value: Int) {
            u16(value ushr 16)
            u16(value)
        }

        fun zeros(count: Int) {
            room(count)
            data.fill(0, size, size + count)
            size += count
        }

        fun text(value: String) = value.forEach { u8(it.code) }

        fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size) {
            room(length)
            value.copyInto(data, size, offset, offset + length)
            size += length
        }

        fun patch(at: Int, value: Int) {
            for (i in 0..3) data[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        /** The identity transform every movie and track header carries. */
        fun matrix() {
            for (value in intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)) u32(value)
        }

        inline fun box(type: String, content: Out.() -> Unit) {
            val start = size
            u32(0)
            text(type)
            content()
            patch(start, size - start)
        }

        inline fun fullBox(type: String, version: Int, flags: Int, content: Out.() -> Unit) = box(type) {
            u32((version shl 24) or flags)
            content()
        }

        fun finish(): ByteArray = data.copyOf(size)
    }
}
