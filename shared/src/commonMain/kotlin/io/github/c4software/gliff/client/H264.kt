package io.github.c4software.gliff.client

import io.github.c4software.gliff.mirror.Fmp4

/**
 * What a hardware decoder needs to be told about gliff's H.264.
 *
 * The server writes its SPS without VUI. A decoder then has to assume the
 * worst the level allows, up to 16 frames held back for reordering, and shows
 * nothing until that many have come in. A desktop that stands still sends
 * three frames and stops, so the screen stays black. The stream never
 * reorders (one reference frame, no B-frames): saying so in the SPS makes
 * every frame come out as it goes in.
 */
object H264 {
    private const val NAL_SLICE = 1
    private const val NAL_IDR = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_AUD = 9

    /** Profiles whose SPS carries the chroma format and scaling matrices. */
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /**
     * The Annex B access unit with a VUI added to its SPS: no reordering, and
     * BT.709 limited range, which is what the server encodes. Returned as is
     * when there is no SPS, when it has a VUI already, or when it cannot be
     * read.
     */
    fun withoutReordering(accessUnit: ByteArray): ByteArray {
        val start = findSps(accessUnit) ?: return accessUnit
        var end = nextStartCode(accessUnit, start)
        // Zero bytes in front of a start code belong to it, not to the SPS.
        while (end > start && accessUnit[end - 1] == 0.toByte()) end--
        val sps = try {
            rewriteSps(unescape(accessUnit, start, end)) ?: return accessUnit
        } catch (_: IndexOutOfBoundsException) {
            return accessUnit
        } catch (_: IllegalArgumentException) {
            return accessUnit
        }
        val escaped = escape(sps)
        val out = ByteArray(accessUnit.size - (end - start) + escaped.size)
        accessUnit.copyInto(out, 0, 0, start)
        escaped.copyInto(out, start)
        accessUnit.copyInto(out, start + escaped.size, end, accessUnit.size)
        return out
    }

    /** The SPS and PPS a keyframe carries, each without its start code; null when it has none. */
    fun parameterSets(accessUnit: ByteArray): Fmp4.ParameterSets? = Fmp4.parameterSets(accessUnit)

    /**
     * The Annex B access unit as a decoder fed through a format description
     * takes it (AVCC): each NAL unit behind its length on four bytes, in
     * place of a start code. Parameter sets and delimiters are left out: they
     * travel in the format description. Empty when nothing else is there.
     */
    fun avcc(accessUnit: ByteArray): ByteArray {
        val units = Fmp4.nalUnits(accessUnit).filter {
            when (accessUnit[it.first].toInt() and 0x1f) {
                NAL_SPS, NAL_PPS, NAL_AUD -> false
                else -> true
            }
        }
        val out = ByteArray(units.sumOf { 4 + it.count() })
        var at = 0
        for (unit in units) {
            val length = unit.count()
            for (i in 0..3) out[at + i] = (length ushr (24 - 8 * i)).toByte()
            accessUnit.copyInto(out, at + 4, unit.first, unit.first + length)
            at += 4 + length
        }
        return out
    }

    /** Offset of the SPS's NAL header, looking no further than the first slice. */
    private fun findSps(data: ByteArray): Int? {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                when (data[i + 3].toInt() and 0x1f) {
                    NAL_SPS -> return i + 3
                    NAL_SLICE, NAL_IDR -> return null
                }
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    private fun nextStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i
            i++
        }
        return data.size
    }

    /** Drop the emulation prevention bytes: `00 00 03` stands for `00 00`. */
    private fun unescape(data: ByteArray, start: Int, end: Int): ByteArray {
        val out = ByteArray(end - start)
        var n = 0
        var zeros = 0
        for (i in start until end) {
            val b = data[i]
            if (zeros >= 2 && b == 3.toByte()) {
                zeros = 0
                continue
            }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            out[n++] = b
        }
        return out.copyOf(n)
    }

    private fun escape(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size * 3 / 2 + 1)
        var n = 0
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && (b.toInt() and 0xff) <= 3) {
                out[n++] = 3
                zeros = 0
            }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            out[n++] = b
        }
        return out.copyOf(n)
    }

    /** The SPS (header byte included, unescaped) with a VUI; null when it has one. */
    private fun rewriteSps(nal: ByteArray): ByteArray? {
        val r = BitReader(nal)
        r.skip(8) // NAL header
        val profile = r.bits(8)
        r.skip(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue()
            if (chroma == 3) r.skip(1)
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.bit()) {
                for (i in 0 until if (chroma == 3) 12 else 8) {
                    if (r.bit()) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.ue()
                r.ue()
                repeat(r.ue()) { r.ue() }
            }
        }
        val refFrames = r.ue()
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        r.ue() // pic_width_in_mbs_minus1
        r.ue() // pic_height_in_map_units_minus1
        if (!r.bit()) r.skip(1) // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        r.skip(1) // direct_8x8_inference_flag
        if (r.bit()) repeat(4) { r.ue() } // frame cropping
        val vuiFlag = r.position
        if (r.bit()) return null

        val w = BitWriter(nal, vuiFlag)
        w.bit(true) // vui_parameters_present_flag
        w.bit(false) // aspect_ratio_info_present_flag
        w.bit(false) // overscan_info_present_flag
        w.bit(true) // video_signal_type_present_flag
        w.bits(5, 3) // video_format: unspecified
        w.bit(false) // video_full_range_flag
        w.bit(true) // colour_description_present_flag
        w.bits(1, 8) // colour_primaries: BT.709
        w.bits(1, 8) // transfer_characteristics: BT.709
        w.bits(1, 8) // matrix_coefficients: BT.709
        w.bit(false) // chroma_loc_info_present_flag
        w.bit(false) // timing_info_present_flag
        w.bit(false) // nal_hrd_parameters_present_flag
        w.bit(false) // vcl_hrd_parameters_present_flag
        w.bit(false) // pic_struct_present_flag
        w.bit(true) // bitstream_restriction_flag
        w.bit(true) // motion_vectors_over_pic_boundaries_flag
        w.ue(0) // max_bytes_per_pic_denom: no limit
        w.ue(0) // max_bits_per_mb_denom: no limit
        w.ue(16) // log2_max_mv_length_horizontal
        w.ue(16) // log2_max_mv_length_vertical
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(refFrames, 1)) // max_dec_frame_buffering
        w.bit(true) // rbsp_stop_one_bit
        return w.finish()
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        repeat(size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            if (next != 0) last = next
        }
    }

    private class BitReader(private val data: ByteArray) {
        var position = 0
            private set

        fun bit(): Boolean {
            val set = data[position ushr 3].toInt() and (0x80 ushr (position and 7)) != 0
            position++
            return set
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or if (bit()) 1 else 0 }
            return value
        }

        fun skip(count: Int) {
            position += count
        }

        fun ue(): Int {
            var zeros = 0
            while (!bit()) require(++zeros <= 31) { "exp-golomb code too long" }
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
        }
    }

    /** Starts as the first `keep` bits of `head`. */
    private class BitWriter(head: ByteArray, keep: Int) {
        private var data = head.copyOf(head.size + 16)
        private var position = keep

        init {
            // Clear what follows the kept bits.
            val first = keep ushr 3
            if (keep and 7 != 0) data[first] = (data[first].toInt() and (0xff00 ushr (keep and 7))).toByte()
            data.fill(0, if (keep and 7 != 0) first + 1 else first)
        }

        fun bit(set: Boolean) {
            if (set) data[position ushr 3] = (data[position ushr 3].toInt() or (0x80 ushr (position and 7))).toByte()
            position++
        }

        fun bits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bit(value ushr i and 1 == 1)
        }

        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - code.countLeadingZeroBits()
            bits(0, length - 1)
            bits(code, length)
        }

        fun finish(): ByteArray = data.copyOf((position + 7) ushr 3)
    }
}
