package io.github.c4software.gliff.data.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H264Test {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** The head of a keyframe from gliff-server 0.3.1 at 1920x1080: SPS, PPS, the IDR's first bytes. */
    private val keyframe = hex(
        "00000001 67640033ac1b1a80780227e540" +
            "00000001 68ee3c80" +
            "00000001 658880004000"
    )

    @Test
    fun theSpsSaysNothingIsReordered() {
        val out = H264.withoutReordering(keyframe)
        // The SPS up to its last bit, the VUI flag, now set; then the VUI:
        // video signal (BT.709, limited range) and the bitstream restriction
        // (no reordering, one frame buffered).
        assertEquals(
            "00000001" + "67640033ac1b1a80780227e5" + "9a80808083c2211a80" +
                "00000001" + "68ee3c80" +
                "00000001" + "658880004000",
            out.hex(),
        )
    }

    @Test
    fun aSecondPassChangesNothing() {
        val once = H264.withoutReordering(keyframe)
        assertSame(once, H264.withoutReordering(once))
    }

    @Test
    fun aFrameWithoutSpsIsLeftAlone() {
        val delta = hex("00000001 419a0004 00000167")
        assertSame(delta, H264.withoutReordering(delta))
    }

    @Test
    fun aBrokenSpsIsLeftAlone() {
        for (bytes in listOf("00000001 67", "00000001 6764", "00000001 67640033 00000001 68ee3c80", "00000001 67640033 000000")) {
            val data = hex(bytes)
            assertContentEquals(data, H264.withoutReordering(data))
        }
    }

    @Test
    fun zeroRunsStayEscaped() {
        // Baseline, no cropping: the bytes before the VUI end in zeros.
        val sps = hex("000001 6742001e f4 0b 04 b2 000001 68ce3880")
        val out = H264.withoutReordering(sps)
        val body = out.copyOfRange(3, out.size - 7)
        for (i in 0 until body.size - 2) {
            val run = body[i] == 0.toByte() && body[i + 1] == 0.toByte() && (body[i + 2].toInt() and 0xff) < 3
            assertEquals(false, run, "start code emulated at $i in ${body.hex()}")
        }
        assertEquals("00000168ce3880", out.copyOfRange(out.size - 7, out.size).hex())
    }

    @Test
    fun theParameterSetsOfAKeyframe() {
        val sets = H264.parameterSets(keyframe)!!
        assertEquals("67640033ac1b1a80780227e540", sets.sps.hex())
        assertEquals("68ee3c80", sets.pps.hex())
        assertNull(H264.parameterSets(hex("00000001 419a0004")))
    }

    @Test
    fun startCodesBecomeLengths() {
        // Only the slice is left: the parameter sets go in the format description.
        // Its last byte here is a zero, which no whole NAL unit ends on: it
        // goes with the padding a start code may be given.
        assertEquals("00000005" + "6588800040", H264.avcc(keyframe).hex())
        // Start codes of three and of four bytes, a delimiter in front, two slices.
        val unit = hex("000001 09f0 00000001 419a0004 000001 419b")
        assertEquals("00000004" + "419a0004" + "00000002" + "419b", H264.avcc(unit).hex())
    }

    @Test
    fun aRewrittenSpsStillSplits() {
        val out = H264.withoutReordering(keyframe)
        assertEquals("67640033ac1b1a80780227e59a80808083c2211a80", H264.parameterSets(out)!!.sps.hex())
        assertEquals("00000005" + "6588800040", H264.avcc(out).hex())
    }

    @Test
    fun nothingToDecodeIsEmpty() {
        assertEquals(0, H264.avcc(ByteArray(0)).size)
        assertEquals(0, H264.avcc(hex("00000001 68ee3c80")).size)
        assertEquals(0, H264.avcc(hex("0102030405")).size)
    }
}
