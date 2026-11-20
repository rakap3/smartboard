package id.kelasku.papanguru

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** [id] is empty for snapshots that are only rendered (thumbnails, export). */
internal class PageSnapshot(val items: List<BoardItem>, val color: BoardColor, val pattern: BoardPattern, val id: String = "")

/** Immutable copy of a document taken on the UI thread so it can be written on a worker thread. */
internal class BoardSnapshot(val id: String, val name: String, val pageIndex: Int, val pages: List<PageSnapshot>) {
    companion object {
        fun of(doc: BoardDocument) = BoardSnapshot(
            doc.id, doc.name, doc.pageIndex, doc.pages.map { PageSnapshot(it.items, it.color, it.pattern, it.id) }
        )
    }
}

internal class BoardSummary(val id: String, val name: String, val modified: Long, val pages: Int, val thumbnail: File)

/** JSON encoding of boards. Uses only org.json so it can be unit tested with the org.json artifact. */
internal object BoardCodec {
    const val VERSION = 1

    fun encode(snapshot: BoardSnapshot, modified: Long): String = JSONObject().apply {
        put("version", VERSION)
        put("name", snapshot.name)
        put("modified", modified)
        put("pageIndex", snapshot.pageIndex)
        put("pages", JSONArray().apply {
            snapshot.pages.forEach { page ->
                put(JSONObject().apply {
                    if (page.id.isNotEmpty()) put("id", page.id)
                    put("color", page.color.name)
                    put("pattern", page.pattern.name)
                    put("items", JSONArray().apply { page.items.forEach { put(encodeItem(it)) } })
                })
            }
        })
    }.toString()

    fun decode(id: String, json: String): BoardDocument {
        val root = JSONObject(json)
        val pages = mutableListOf<BoardPage>()
        val pageIds = mutableSetOf<String>()
        val array = root.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until array.length()) {
            val page = array.getJSONObject(i)
            val items = mutableListOf<BoardItem>()
            val list = page.optJSONArray("items") ?: JSONArray()
            for (k in 0 until list.length()) decodeItem(list.getJSONObject(k))?.let(items::add)
            // Boards saved before page ids existed (or with a bad/duplicate id) get a new one.
            val pageId = page.optString("id").takeIf { BoardPage.SAFE_PAGE_ID.matches(it) && it !in pageIds } ?: BoardPage.newPageId()
            pageIds += pageId
            pages += BoardPage(
                items,
                enumOr(page.optString("color"), BoardColor.BLACKBOARD),
                enumOr(page.optString("pattern"), BoardPattern.DOTS),
                pageId,
            )
        }
        if (pages.isEmpty()) pages += BoardPage()
        return BoardDocument(id, root.optString("name", "Papan"), pages, root.optInt("pageIndex", 0).coerceIn(0, pages.lastIndex))
    }

    private fun encodeItem(item: BoardItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        when (item) {
            is InkItem -> {
                put("t", "ink"); put("c", item.color); put("w", num(item.width)); put("p", encodePoints(item.points))
                if (item.highlighter) put("h", true)
            }
            is ShapeItem -> {
                put("t", "shape"); put("k", item.type.name); put("c", item.color); put("w", num(item.width))
                put("x1", num(item.x1)); put("y1", num(item.y1)); put("x2", num(item.x2)); put("y2", num(item.y2))
            }
            is TextItem -> {
                put("t", "text"); put("s", item.text); put("c", item.color); put("z", num(item.size))
                put("x", num(item.x)); put("y", num(item.y)); put("mw", num(item.measuredWidth)); put("mh", num(item.measuredHeight))
            }
            is ImageItem -> {
                put("t", "img"); put("f", item.file)
                put("x", num(item.x)); put("y", num(item.y)); put("w", num(item.w)); put("h", num(item.h))
                if (item.locked) put("l", true)
            }
        }
    }

    private fun decodeItem(o: JSONObject): BoardItem? {
        val id = o.optLong("id")
        fun f(key: String) = o.optDouble(key, 0.0).toFloat()
        return when (o.optString("t")) {
            "ink" -> decodePoints(o.optString("p")).takeIf { it.size >= 2 }?.let {
                InkItem(id, o.optInt("c"), f("w"), it, o.optBoolean("h"))
            }
            "shape" -> ShapeItem(
                id, enumOr(o.optString("k"), ShapeType.LINE), o.optInt("c"), f("w"), f("x1"), f("y1"), f("x2"), f("y2")
            )
            "text" -> TextItem(id, o.optString("s"), f("x"), f("y"), f("z"), o.optInt("c"), f("mw"), f("mh"))
            "img" -> o.optString("f").takeIf { SAFE_FILE.matches(it) }?.let {
                ImageItem(id, it, f("x"), f("y"), f("w"), f("h"), o.optBoolean("l"))
            }
            else -> null
        }
    }

    /** Points are stored as "x,y,x,y" rounded to 0.1 board units, which keeps files small. */
    fun encodePoints(points: FloatArray): String {
        val sb = StringBuilder(points.size * 6)
        points.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            appendNum(sb, v)
        }
        return sb.toString()
    }

    fun decodePoints(s: String): FloatArray {
        if (s.isEmpty()) return FloatArray(0)
        val parts = s.split(',')
        val out = FloatArray(parts.size - parts.size % 2)
        for (i in out.indices) out[i] = parts[i].toFloatOrNull() ?: return FloatArray(0)
        return out
    }

    private fun appendNum(sb: StringBuilder, v: Float) {
        val tenths = (v * 10).roundToInt()
        if (tenths % 10 == 0) sb.append(tenths / 10) else sb.append(tenths / 10f)
    }

    private fun num(v: Float): Double = (v * 10).roundToInt() / 10.0

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    val SAFE_FILE = Regex("[A-Za-z0-9_-]+[.](png|jpg)")
}

/**
 * Stores boards under filesDir/boards/<id>/: board.json, meta.json (for the board list), thumb.png,
 * and images/ for inserted pictures and imported PDF pages. Writes happen on one worker thread in order.
 */
internal class BoardStore(context: Context) {
    private val root = File(context.filesDir, "boards").apply { mkdirs() }
    /** Shared by every instance (board and home screen) so saves and listings stay in order. */
    private val worker get() = WORKER

    fun imagesDir(id: String) = File(dir(id), "images").apply { mkdirs() }

    fun create(name: String): BoardDocument {
        val doc = BoardDocument(UUID.randomUUID().toString(), name)
        saveNow(BoardSnapshot.of(doc))
        return doc
    }

    fun list(): List<BoardSummary> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "board.json").isFile }
        .map { dir ->
            val meta = try {
                JSONObject(File(dir, "meta.json").readText())
            } catch (_: Exception) {
                JSONObject()
            }
            BoardSummary(
                dir.name, meta.optString("name", "Papan"), meta.optLong("modified", dir.lastModified()),
                meta.optInt("pages", 1), File(dir, "thumb.png")
            )
        }
        .sortedByDescending { it.modified }

    fun load(id: String): BoardDocument? {
        if (!SAFE_ID.matches(id)) return null
        val file = File(dir(id), "board.json")
        val doc = try {
            BoardCodec.decode(id, file.readText())
        } catch (_: Exception) {
            return null
        }
        // History isn't persisted, so images no page references any more can go.
        val used = doc.referencedImages()
        imagesDir(id).listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
        return doc
    }

    fun save(snapshot: BoardSnapshot, thumbnail: Bitmap? = null) {
        worker.execute {
            saveNow(snapshot)
            if (thumbnail != null) writeThumbnail(snapshot.id, thumbnail)
        }
    }

    /** Runs [task] after every pending save, on the worker thread. */
    fun afterPendingWrites(task: () -> Unit) = worker.execute(task)

    fun rename(id: String, name: String) {
        worker.execute {
            val doc = load(id) ?: return@execute
            doc.name = name
            saveNow(BoardSnapshot.of(doc))
        }
    }

    fun delete(id: String) {
        if (!SAFE_ID.matches(id)) return
        worker.execute { dir(id).deleteRecursively() }
    }

    private fun saveNow(snapshot: BoardSnapshot) {
        val now = System.currentTimeMillis()
        try {
            val dir = dir(snapshot.id).apply { mkdirs() }
            writeAtomic(File(dir, "board.json"), BoardCodec.encode(snapshot, now))
            writeAtomic(File(dir, "meta.json"), JSONObject().apply {
                put("name", snapshot.name)
                put("modified", now)
                put("pages", snapshot.pages.size)
            }.toString())
        } catch (_: IOException) {
            // Keep the previous file; the next autosave retries.
        }
    }

    private fun writeThumbnail(id: String, bitmap: Bitmap) {
        try {
            val tmp = File(dir(id), "thumb.png.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(File(dir(id), "thumb.png"))
        } catch (_: IOException) {
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Gagal menyimpan ${target.name}")
        }
    }

    private fun dir(id: String) = File(root, id)

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9-]+")
        private val WORKER: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
    }
}
