package id.kelasku.papanguru

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class CaptureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val file = imageFile(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Tangkapan hanya dapat dibaca")
        return ParcelFileDescriptor.open(imageFile(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
        throw UnsupportedOperationException()

    private fun imageFile(uri: Uri): File {
        val app = context ?: throw FileNotFoundException()
        val name = uri.pathSegments.singleOrNull()?.takeIf { filename.matches(it) } ?: throw FileNotFoundException()
        return File(app.cacheDir, "captures/$name").takeIf { it.isFile } ?: throw FileNotFoundException()
    }

    companion object {
        private val filename = Regex("Papan-Guru-[0-9-]+[.]png")

        internal fun prepareShare(context: Context, image: Bitmap): Uri {
            val directory = File(context.cacheDir, "captures")
            directory.mkdirs()
            val cutoff = System.currentTimeMillis() - 86_400_000L
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            val file = File.createTempFile("Papan-Guru-", ".png", directory)
            var ready = false
            try {
                if (!file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }) {
                    throw IOException("Gagal membuat PNG")
                }
                ready = true
                return Uri.Builder().scheme("content").authority("${context.packageName}.captures")
                    .appendPath(file.name).build()
            } finally {
                if (!ready) file.delete()
            }
        }
    }
}
