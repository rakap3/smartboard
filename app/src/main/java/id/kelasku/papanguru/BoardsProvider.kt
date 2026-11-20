package id.kelasku.papanguru

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import id.kelasku.core.BoardsContract
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Read-only list of boards for Edu Launcher. Exported, but readable only with the signature
 * permission [BoardsContract.PERMISSION] (see the manifest), so other apps can't read boards.
 */
class BoardsProvider : ContentProvider() {
    private lateinit var store: BoardStore

    override fun onCreate(): Boolean {
        store = BoardStore(context ?: return false)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments != listOf(BoardsContract.PATH_BOARDS)) return null
        waitForPendingSaves()
        val cursor = MatrixCursor(arrayOf(BoardsContract.COLUMN_ID, BoardsContract.COLUMN_NAME, BoardsContract.COLUMN_MODIFIED, BoardsContract.COLUMN_PAGES))
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 500
        for (board in store.list().take(limit)) cursor.addRow(arrayOf<Any>(board.id, board.name, board.modified, board.pages))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segments = uri.pathSegments
        if (mode != "r" || segments.size != 2 || segments[0] != BoardsContract.PATH_THUMB) throw FileNotFoundException(uri.toString())
        // The id is used as a directory name: only accept the store's own id format.
        val id = segments[1].takeIf { SAFE_ID.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val thumb = store.list().firstOrNull { it.id == id }?.thumbnail?.takeIf { it.isFile } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(thumb, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (uri.pathSegments.firstOrNull()) {
        BoardsContract.PATH_THUMB -> "image/png"
        BoardsContract.PATH_BOARDS -> "vnd.android.cursor.dir/vnd.id.kelasku.board"
        else -> null
    }

    /** A board closed a moment ago may still be saving; list after those writes (bounded wait). */
    private fun waitForPendingSaves() {
        val done = CountDownLatch(1)
        store.afterPendingWrites { done.countDown() }
        done.await(2, TimeUnit.SECONDS)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
