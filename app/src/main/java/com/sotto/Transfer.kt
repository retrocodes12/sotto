package com.sotto

/**
 * Multi-frame transfers over the modem, for anything longer than one frame (photos).
 *
 * Wire format, one modem frame each. Every frame starts with a tag byte that cannot begin
 * UTF-8 text, so plain messages and transfer frames share the channel.
 *
 *   DATA  A5 | id | seq | total | bytes...      chunk 0's bytes start with kind(1) len(2) sender(2) msgSeq(1) hops(1) 0(1)
 *   END   A7 | id | total                       "pass finished; tell me what you are missing"
 *   REQ   A6 | id | n | seq...                  receiver's missing list
 *   DONE  A8 | id                               receiver has everything
 *
 * The sender sends every chunk, then END, then waits. The receiver answers END with REQ
 * (missing chunks) or DONE; the sender resends only the requested chunks and repeats.
 */
object Transfer {
    const val MAX_FRAME = 140
    const val KIND_TEXT = 1
    const val KIND_JPEG = 2

    private const val TAG_DATA = 0xA5
    private const val TAG_REQ = 0xA6
    private const val TAG_END = 0xA7
    private const val TAG_DONE = 0xA8
    private const val META = 8
    private const val FIRST_CHUNK_BYTES = MAX_FRAME - 4 - META
    private const val CHUNK_BYTES = MAX_FRAME - 4

    sealed class Frame(val id: Int) {
        class Data(id: Int, val seq: Int, val total: Int, val bytes: ByteArray) : Frame(id)
        class End(id: Int, val total: Int) : Frame(id)
        class Req(id: Int, val missing: List<Int>) : Frame(id)
        class Done(id: Int) : Frame(id)
    }

    fun isTransferFrame(payload: ByteArray): Boolean =
        payload.isNotEmpty() && (payload[0].toInt() and 0xFF) in TAG_DATA..TAG_DONE

    fun parse(p: ByteArray): Frame? {
        if (p.size < 2) return null
        val id = p[1].toInt() and 0xFF
        return when (p[0].toInt() and 0xFF) {
            TAG_DATA -> if (p.size >= 4) Frame.Data(id, p[2].toInt() and 0xFF, p[3].toInt() and 0xFF, p.copyOfRange(4, p.size)) else null
            TAG_END -> if (p.size >= 3) Frame.End(id, p[2].toInt() and 0xFF) else null
            TAG_REQ -> if (p.size >= 3) {
                val n = p[2].toInt() and 0xFF
                if (p.size < 3 + n) null else Frame.Req(id, (0 until n).map { p[3 + it].toInt() and 0xFF })
            } else null
            TAG_DONE -> Frame.Done(id)
            else -> null
        }
    }

    /** Splits content into chunk payloads (whole frames, tag included). At most 255 chunks. */
    fun chunks(id: Int, kind: Int, sender: Int, msgSeq: Int, hops: Int, content: ByteArray): List<ByteArray>? {
        val rest = (content.size - FIRST_CHUNK_BYTES).coerceAtLeast(0)
        val total = 1 + (rest + CHUNK_BYTES - 1) / CHUNK_BYTES
        if (total > 255 || content.size > 0xFFFF) return null
        val out = ArrayList<ByteArray>(total)
        var off = 0
        for (seq in 0 until total) {
            val head = if (seq == 0) byteArrayOf(kind.toByte(), (content.size shr 8).toByte(), content.size.toByte(), (sender shr 8).toByte(), sender.toByte(), msgSeq.toByte(), hops.toByte(), 0) else ByteArray(0)
            val take = minOf(if (seq == 0) FIRST_CHUNK_BYTES else CHUNK_BYTES, content.size - off)
            out += byteArrayOf(TAG_DATA.toByte(), id.toByte(), seq.toByte(), total.toByte()) + head + content.copyOfRange(off, off + take)
            off += take
        }
        return out
    }

    fun endFrame(id: Int, total: Int) = byteArrayOf(TAG_END.toByte(), id.toByte(), total.toByte())
    fun doneFrame(id: Int) = byteArrayOf(TAG_DONE.toByte(), id.toByte())
    fun reqFrame(id: Int, missing: List<Int>): ByteArray {
        val m = missing.take(MAX_FRAME - 3)
        return byteArrayOf(TAG_REQ.toByte(), id.toByte(), m.size.toByte()) + ByteArray(m.size) { m[it].toByte() }
    }

    class Assembled(val kind: Int, val sender: Int, val msgSeq: Int, val hops: Int, val content: ByteArray)

    /** Kind, sender and content from a complete set of chunk bytes (tag and header stripped). */
    fun assemble(parts: List<ByteArray>): Assembled? {
        val first = parts.firstOrNull() ?: return null
        if (first.size < META) return null
        val kind = first[0].toInt() and 0xFF
        val len = ((first[1].toInt() and 0xFF) shl 8) or (first[2].toInt() and 0xFF)
        val sender = ((first[3].toInt() and 0xFF) shl 8) or (first[4].toInt() and 0xFF)
        val body = first.copyOfRange(META, first.size) + parts.drop(1).fold(ByteArray(0)) { acc, b -> acc + b }
        return if (body.size == len) Assembled(kind, sender, first[5].toInt() and 0xFF, first[6].toInt() and 0xFF, body) else null
    }

    /** Receiver-side bookkeeping for one transfer. */
    class Incoming(val id: Int, val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var lastAt = 0L
        var complete = false
        var logId = 0L
        var replyAttempt = 0
        val received: Int get() = parts.count { it != null }
        val missing: List<Int> get() = parts.indices.filter { parts[it] == null }
    }
}
