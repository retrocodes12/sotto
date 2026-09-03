package com.sotto

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Messages kept on the phone: a JSON file for the entries and one JPEG per photo. Entries
 * still in flight (a transfer with progress) are not written; they would be stuck forever.
 */
object History {
    private const val FILE = "history.json"
    private const val MAX = 500

    private fun photoDir(context: Context) = File(context.filesDir, "photos").also { it.mkdirs() }
    private fun photoFile(context: Context, id: Long) = File(photoDir(context), "$id.jpg")

    fun load(context: Context): List<LogEntry> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                val photo = photoFile(context, id)
                val image = if (o.optBoolean("photo") && photo.exists()) BitmapFactory.decodeFile(photo.path) else null
                LogEntry(
                    id = id, time = o.getString("time"), kind = LogEntry.Kind.valueOf(o.getString("kind")), text = o.optString("text"),
                    protocol = o.optString("protocol"), bytes = o.optInt("bytes"), image = image,
                    senderId = if (o.has("sender")) o.getInt("sender") else null, via = if (o.has("via")) o.getInt("via") else null,
                    peer = if (o.has("peer")) o.getInt("peer") else null, seq = if (o.has("seq")) o.getInt("seq") else null,
                    delivered = o.optBoolean("delivered"),
                    card = if (o.has("card")) LogEntry.Card(o.getInt("card"), o.getString("fields").split('\u001F')) else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Writes the newest [MAX] finished entries. Call off the main thread. */
    fun save(context: Context, entries: List<LogEntry>) {
        val keep = entries.filter { it.fraction == null }.take(MAX)
        val arr = JSONArray()
        for (e in keep) {
            e.imageBytes?.let { bytes -> val pf = photoFile(context, e.id); if (!pf.exists()) pf.writeBytes(bytes) }
            val hasPhoto = e.image != null && photoFile(context, e.id).exists()
            arr.put(JSONObject().apply {
                put("id", e.id); put("time", e.time); put("kind", e.kind.name); put("text", e.text)
                put("protocol", e.protocol); put("bytes", e.bytes); put("photo", hasPhoto)
                e.senderId?.let { put("sender", it) }; e.via?.let { put("via", it) }; e.peer?.let { put("peer", it) }
                e.seq?.let { put("seq", it) }; put("delivered", e.delivered)
                e.card?.let { put("card", it.kind); put("fields", it.fields.joinToString("\u001F")) }
            })
        }
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
        // photos no entry refers to any more
        val ids = keep.map { it.id }.toSet()
        photoDir(context).listFiles()?.forEach { pf -> pf.nameWithoutExtension.toLongOrNull()?.let { if (it !in ids) pf.delete() } }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        photoDir(context).listFiles()?.forEach { it.delete() }
    }
}
