package io.github.c4software.gliff.client

import io.github.c4software.gliff.proto.ClipboardItem
import io.github.c4software.gliff.proto.ClipboardMsg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardSyncTest {
    private val sent = ArrayList<Pair<ClipboardMsg, ByteArray?>>()
    private val pasted = ArrayList<String>()
    private val sync = ClipboardSync({ msg, payload -> sent.add(msg to payload) }, { pasted.add(it) })
    private val text = ClipboardItem.Mime("text/plain;charset=utf-8")

    private fun take(): List<ClipboardMsg> = sent.map { it.first }.also { sent.clear() }

    @Test
    fun aCopyHereIsOfferedAndServedWhenTheRemotePastes() {
        sync.localText("héllo")
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Offer(1, ClipboardSync.TEXT_MIMES, emptyList())), take())
        // The same text again is no news.
        sync.localText("héllo")
        assertEquals(emptyList(), take())

        sync.onMessage(ClipboardMsg.Request(2, 1, ClipboardItem.Mime("UTF8_STRING")), null)
        // The text, then the empty chunk that says it is all.
        assertEquals(
            listOf<ClipboardMsg>(ClipboardMsg.Data(2, 0, 6, false), ClipboardMsg.Data(2, 6, 0, true)),
            sent.map { it.first },
        )
        assertContentEquals("héllo".encodeToByteArray(), sent[0].second)
        assertEquals(0, sent[1].second!!.size)
    }

    @Test
    fun aLargeTextWaitsForAcks() {
        val big = "x".repeat(ClipboardSync.CHUNK * 6 + 10)
        sync.localText(big)
        take()
        sync.onMessage(ClipboardMsg.Request(4, 1, text), null)
        // Four chunks in flight, no more.
        assertEquals((0 until 4).map { ClipboardSync.CHUNK.toLong() * it }, take().map { (it as ClipboardMsg.Data).offset })
        sync.onMessage(ClipboardMsg.Ack(4, ClipboardSync.CHUNK.toLong() * 2), null)
        assertEquals(2, take().size)
        sync.onMessage(ClipboardMsg.Ack(4, ClipboardSync.CHUNK.toLong() * 6), null)
        assertEquals(
            listOf<ClipboardMsg>(
                ClipboardMsg.Data(4, ClipboardSync.CHUNK.toLong() * 6, 10, false),
                ClipboardMsg.Data(4, ClipboardSync.CHUNK.toLong() * 6 + 10, 0, true),
            ),
            take(),
        )
        // An ack for more than was sent is a peer gone wrong.
        sync.localText("y".repeat(ClipboardSync.CHUNK * 5))
        take()
        sync.onMessage(ClipboardMsg.Request(6, 2, text), null)
        take()
        sync.onMessage(ClipboardMsg.Ack(6, Long.MAX_VALUE), null)
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Abort(6)), take())
        sync.onMessage(ClipboardMsg.Ack(6, 1), null)
        assertEquals(emptyList(), take())
    }

    @Test
    fun requestsThatCannotBeServedAreRefused() {
        sync.onMessage(ClipboardMsg.Request(2, 0, text), null)
        sync.localText("a")
        take()
        // A stale offer, a file, a type we do not have, and an id of our own parity.
        sync.onMessage(ClipboardMsg.Request(2, 7, text), null)
        sync.onMessage(ClipboardMsg.Request(4, 1, ClipboardItem.File(0)), null)
        sync.onMessage(ClipboardMsg.Request(6, 1, ClipboardItem.Mime("image/png")), null)
        sync.onMessage(ClipboardMsg.Request(3, 1, text), null)
        assertEquals(listOf(2, 4, 6, 3).map<Int, ClipboardMsg> { ClipboardMsg.Abort(it) }, take())
        // The clipboard emptied: the offer is withdrawn, and nothing is served any more.
        sync.localText(null)
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Offer(2, emptyList(), emptyList())), take())
        sync.onMessage(ClipboardMsg.Request(8, 2, text), null)
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Abort(8)), take())
    }

    @Test
    fun aCopyOnTheRemoteIsFetchedAtOnce() {
        sync.onMessage(ClipboardMsg.Offer(9, listOf("text/html", "text/plain", "UTF8_STRING"), emptyList()), null)
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Request(1, 9, ClipboardItem.Mime("text/plain"))), take())
        sync.onMessage(ClipboardMsg.Data(1, 0, 4, false), "ça ".encodeToByteArray())
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Ack(1, 4)), take())
        sync.onMessage(ClipboardMsg.Data(1, 4, 2, false), "va".encodeToByteArray())
        sync.onMessage(ClipboardMsg.Data(1, 6, 0, true), ByteArray(0))
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Ack(1, 6)), take())
        assertEquals(listOf("ça va"), pasted)
        // Seeing that text on the clipboard here is not a copy to announce back.
        sync.localText("ça va")
        assertEquals(emptyList(), take())
        // The next request has the next odd id.
        sync.onMessage(ClipboardMsg.Offer(10, listOf("STRING"), emptyList()), null)
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Request(3, 10, ClipboardItem.Mime("STRING"))), take())
    }

    @Test
    fun anOfferWithoutTextOrABrokenTransferFetchesNothing() {
        sync.onMessage(ClipboardMsg.Offer(1, listOf("image/png"), emptyList()), null)
        sync.onMessage(ClipboardMsg.Offer(2, emptyList(), emptyList()), null)
        assertEquals(emptyList(), take())
        sync.onMessage(ClipboardMsg.Offer(3, listOf("text/plain"), emptyList()), null)
        take()
        // A chunk out of place ends the transfer.
        sync.onMessage(ClipboardMsg.Data(1, 5, 1, true), ByteArray(1))
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Abort(1)), take())
        sync.onMessage(ClipboardMsg.Data(1, 0, 1, true), ByteArray(1))
        assertEquals(emptyList(), take())
        // A newer offer replaces the fetch in progress.
        sync.onMessage(ClipboardMsg.Offer(4, listOf("text/plain"), emptyList()), null)
        sync.onMessage(ClipboardMsg.Offer(5, listOf("text/plain"), emptyList()), null)
        assertEquals(
            listOf(
                ClipboardMsg.Request(3, 4, ClipboardItem.Mime("text/plain")), ClipboardMsg.Abort(3),
                ClipboardMsg.Request(5, 5, ClipboardItem.Mime("text/plain")),
            ),
            take(),
        )
        // More than a phone's clipboard takes is cut off.
        var offset = 0L
        while (offset + ClipboardSync.CHUNK <= ClipboardSync.MAX_TEXT) {
            sync.onMessage(ClipboardMsg.Data(5, offset, ClipboardSync.CHUNK, false), ByteArray(ClipboardSync.CHUNK))
            offset += ClipboardSync.CHUNK
        }
        take()
        sync.onMessage(ClipboardMsg.Data(5, offset, ClipboardSync.CHUNK, false), ByteArray(ClipboardSync.CHUNK))
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Abort(5)), take())
        assertEquals(emptyList(), pasted)
        // The peer giving up is the end of it.
        sync.onMessage(ClipboardMsg.Offer(6, listOf("text/plain"), emptyList()), null)
        take()
        sync.onMessage(ClipboardMsg.Abort(7), null)
        sync.onMessage(ClipboardMsg.Data(7, 0, 1, true), "x".encodeToByteArray())
        assertEquals(emptyList(), pasted)
        assertNull(take().firstOrNull())
    }

    @Test
    fun aNewServerIsToldWhatTheClipboardHolds() {
        sync.localText("kept")
        take()
        sync.connected()
        assertEquals(listOf<ClipboardMsg>(ClipboardMsg.Offer(2, ClipboardSync.TEXT_MIMES, emptyList())), take())
        // What came from the old server is not offered to the new one.
        sync.onMessage(ClipboardMsg.Offer(1, listOf("text/plain"), emptyList()), null)
        sync.onMessage(ClipboardMsg.Data(1, 0, 3, true), "abc".encodeToByteArray())
        sync.localText("abc")
        take()
        sync.connected()
        assertEquals(emptyList(), take())
    }
}
