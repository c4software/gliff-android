package io.github.c4software.gliff.domain.clipboard

import io.github.c4software.gliff.proto.ClipboardItem
import io.github.c4software.gliff.proto.ClipboardMsg

/**
 * The text clipboard, shared with the remote. It follows the transfer rules
 * of `gliff_proto::clipboard` and the engine in `gliff_transport::clipboard`.
 *
 * Towards the remote it works as the reference does: a change here is only
 * announced (`Offer`), and the text travels when an application there pastes
 * (`Request`), in chunks of at most [CHUNK] bytes with [WINDOW] of them
 * unacknowledged at most.
 *
 * From the remote it cannot: a phone's clipboard holds text, not a promise
 * of text, and it cannot be filled while the app is in the background. So an
 * offer of text is fetched at once and handed to [onRemoteText].
 *
 * Not thread-safe: the owner calls it from one place at a time.
 */
class ClipboardSync(
    /** A message for the server, with the chunk that follows a `Data`. */
    private val send: (ClipboardMsg, ByteArray?) -> Unit,
    /** The remote's clipboard now holds this text. */
    private val onRemoteText: (String) -> Unit,
) {
    /** What was last announced, and under which serial. */
    private var offered: ByteArray? = null
    private var serial = 0

    /** The text last seen here, to announce again to a new server. */
    private var local: String? = null

    /** Text that came from the remote: seeing it on the clipboard here is no news to it. */
    private var fromRemote: String? = null

    private class Serve(val bytes: ByteArray) {
        var offset = 0

        /** End offsets of the chunks sent and not yet acknowledged. */
        val unacked = ArrayDeque<Int>()
        var finished = false
    }

    private val serving = HashMap<Int, Serve>()

    private class Fetch(val id: Int) {
        val chunks = ArrayList<ByteArray>()
        var received = 0L
    }

    private var fetch: Fetch? = null

    /** Requests are numbered by the side that makes them: odd here, even on the server. */
    private var nextId = 1

    /** The clipboard here holds `text`; null or empty when it holds no text. */
    fun localText(text: String?) {
        val now = text?.takeIf { it.isNotEmpty() }
        if (now == local) return
        local = now
        if (now != null && now == fromRemote) return
        offer(now)
    }

    /** A new server knows nothing of what was offered to the last one. */
    fun connected() {
        serving.clear()
        fetch = null
        offered = null
        local?.takeIf { it != fromRemote }?.let(::offer)
    }

    private fun offer(text: String?) {
        val bytes = text?.encodeToByteArray()?.takeIf { it.size <= MAX_ITEM }
        // Nothing was offered, and there is still nothing to offer.
        if (bytes == null && offered == null) return
        offered = bytes
        serial++
        // Transfers of the offer this one replaces are no longer wanted.
        serving.clear()
        send(ClipboardMsg.Offer(serial, if (bytes == null) emptyList() else TEXT_MIMES, emptyList()), null)
    }

    fun onMessage(msg: ClipboardMsg, payload: ByteArray?) {
        when (msg) {
            is ClipboardMsg.Offer -> onOffer(msg)
            is ClipboardMsg.Request -> onRequest(msg)
            is ClipboardMsg.Data -> onData(msg, payload ?: ByteArray(0))
            is ClipboardMsg.Ack -> {
                val serve = serving[msg.id] ?: return
                if (msg.received < 0 || msg.received > serve.offset) {
                    serving.remove(msg.id)
                    send(ClipboardMsg.Abort(msg.id), null)
                    return
                }
                while (serve.unacked.isNotEmpty() && serve.unacked.first() <= msg.received) serve.unacked.removeFirst()
                pump(msg.id, serve)
            }
            is ClipboardMsg.Abort -> {
                serving.remove(msg.id)
                if (fetch?.id == msg.id) fetch = null
            }
        }
    }

    private fun onOffer(offer: ClipboardMsg.Offer) {
        // Whatever was being fetched is of an offer that is gone.
        fetch?.let { send(ClipboardMsg.Abort(it.id), null) }
        fetch = null
        val mime = TEXT_MIMES.firstOrNull { it in offer.mimeTypes } ?: return
        val id = nextId
        // Stays odd, and never wraps to an id the server could have chosen.
        nextId = if (nextId >= Int.MAX_VALUE - 2) 1 else nextId + 2
        fetch = Fetch(id)
        send(ClipboardMsg.Request(id, offer.serial, ClipboardItem.Mime(mime)), null)
    }

    private fun onRequest(request: ClipboardMsg.Request) {
        val bytes = offered
        val item = request.item
        val wanted = request.id % 2 == 0 && request.serial == serial && bytes != null &&
            item is ClipboardItem.Mime && item.mimeType in TEXT_MIMES && request.id !in serving
        if (!wanted || bytes == null) {
            send(ClipboardMsg.Abort(request.id), null)
            return
        }
        val serve = Serve(bytes)
        serving[request.id] = serve
        pump(request.id, serve)
    }

    /** Send what the window allows; the last chunk is an empty one marked done, as the reference sends it. */
    private fun pump(id: Int, serve: Serve) {
        while (!serve.finished && serve.unacked.size < WINDOW) {
            val length = minOf(CHUNK, serve.bytes.size - serve.offset)
            val done = length == 0
            send(
                ClipboardMsg.Data(id, serve.offset.toLong(), length, done),
                serve.bytes.copyOfRange(serve.offset, serve.offset + length),
            )
            serve.offset += length
            serve.unacked.addLast(serve.offset)
            serve.finished = done
        }
        if (serve.finished) serving.remove(id)
    }

    private fun onData(data: ClipboardMsg.Data, payload: ByteArray) {
        val fetch = fetch?.takeIf { it.id == data.id } ?: return
        val ok = payload.size == data.dataLen && data.offset == fetch.received && payload.size <= CHUNK &&
            fetch.received + payload.size <= MAX_TEXT
        if (!ok) {
            this.fetch = null
            send(ClipboardMsg.Abort(data.id), null)
            return
        }
        if (payload.isNotEmpty()) fetch.chunks.add(payload)
        fetch.received += payload.size
        if (!data.done) {
            send(ClipboardMsg.Ack(data.id, fetch.received), null)
            return
        }
        this.fetch = null
        val bytes = ByteArray(fetch.received.toInt())
        var at = 0
        for (chunk in fetch.chunks) {
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        val text = bytes.decodeToString()
        if (text.isEmpty()) return
        fromRemote = text
        onRemoteText(text)
    }

    companion object {
        /** Largest `Data` chunk, and how many may be unacknowledged. */
        const val CHUNK = 256 * 1024
        const val WINDOW = 4

        /** The reference's cap on one in-memory item. */
        const val MAX_ITEM = 32 * 1024 * 1024

        /** More text than this does not go on a phone's clipboard. */
        const val MAX_TEXT = 1024 * 1024L

        /** Text flavours, best first; the reference treats them as one. */
        val TEXT_MIMES = listOf("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")
    }
}
