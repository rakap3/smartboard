package id.kelasku.papanguru

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Image and PDF import/export. Everything here may block; call it off the UI thread. */
internal object BoardFiles {
    private const val MAX_IMAGE = 2048
    private const val PDF_PAGE_PIXELS = 2000
    private const val PDF_LONG_SIDE_PT = 1190f
    const val MAX_PDF_PAGES = 100

    /** [bitmap] is the stored image, still usable unless the caller recycles it. */
    class StoredImage(val file: String, val width: Int, val height: Int, val bitmap: Bitmap)

    /** Decodes a picked image (downscaled to at most 2048 px) and stores it in the board's image folder. */
    fun importImage(context: Context, uri: Uri, dir: File): StoredImage {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null when only reading bounds, so the block yields Unit explicitly.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: throw IOException()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Bukan gambar")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Gambar tidak bisa dibaca")
        val bitmap = fitWithin(decoded, MAX_IMAGE)
        return store(bitmap, dir)
    }

    /**
     * Renders each PDF page to an image and returns one locked, white board page per PDF page,
     * scaled to fit [frame]. [progress] gets (done, total).
     */
    fun importPdf(context: Context, uri: Uri, dir: File, frame: Box, newId: () -> Long, progress: (Int, Int) -> Unit): List<BoardPage> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException()
        val pages = mutableListOf<BoardPage>()
        try {
            val pdf = PdfRenderer(descriptor)
            try {
                val total = minOf(pdf.pageCount, MAX_PDF_PAGES)
                for (i in 0 until total) {
                    progress(i, total)
                    val page = pdf.openPage(i)
                    try {
                        val ratio = page.width.toFloat() / page.height
                        val w = if (ratio >= 1) PDF_PAGE_PIXELS else (PDF_PAGE_PIXELS * ratio).roundToInt()
                        val h = if (ratio >= 1) (PDF_PAGE_PIXELS / ratio).roundToInt() else PDF_PAGE_PIXELS
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val stored = store(bitmap, dir, opaque = true)
                        bitmap.recycle()
                        val margin = minOf(frame.width, frame.height) * 0.03f
                        val fit = minOf((frame.width - 2 * margin) / w, (frame.height - 2 * margin) / h)
                        val iw = w * fit
                        val ih = h * fit
                        val image = ImageItem(
                            newId(), stored.file, frame.centerX - iw / 2, frame.centerY - ih / 2, iw, ih, locked = true
                        )
                        pages += BoardPage(listOf(image), BoardColor.WHITE, BoardPattern.NONE)
                    } finally {
                        // Page/PdfRenderer only implement AutoCloseable on newer releases, so close explicitly.
                        page.close()
                    }
                }
                progress(total, total)
            } finally {
                pdf.close()
            }
        } finally {
            descriptor.close()
        }
        return pages
    }

    /** The board area a page exports: the home frame, grown to include all content. */
    fun exportBounds(items: List<BoardItem>, frame: Box): Box {
        val content = Box.unionOf(items) ?: return frame
        return frame.union(content.inset(24f))
    }

    fun exportPdf(pages: List<PageSnapshot>, frame: Box, images: ImageCache, density: Float, out: OutputStream) {
        val pdf = PdfDocument()
        try {
            val renderer = BoardRenderer(density, images, cachePaths = false)
            pages.forEachIndexed { index, page ->
                val bounds = exportBounds(page.items, frame)
                val k = PDF_LONG_SIDE_PT / max(bounds.width, bounds.height)
                val info = PdfDocument.PageInfo.Builder(
                    (bounds.width * k).roundToInt().coerceAtLeast(1), (bounds.height * k).roundToInt().coerceAtLeast(1), index + 1
                ).create()
                val pdfPage = pdf.startPage(info)
                drawPage(pdfPage.canvas, renderer, page, bounds, k, info.pageWidth.toFloat(), info.pageHeight.toFloat())
                pdf.finishPage(pdfPage)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    /** Renders one page to a bitmap whose longer side is [longSide] pixels. */
    fun renderPage(page: PageSnapshot, frame: Box, images: ImageCache?, density: Float, longSide: Int, cachePaths: Boolean = false): Bitmap {
        val bounds = exportBounds(page.items, frame)
        val k = longSide / max(bounds.width, bounds.height)
        val w = (bounds.width * k).roundToInt().coerceAtLeast(1)
        val h = (bounds.height * k).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawPage(Canvas(bitmap), BoardRenderer(density, images, cachePaths), page, bounds, k, w.toFloat(), h.toFloat())
        return bitmap
    }

    private fun drawPage(canvas: Canvas, renderer: BoardRenderer, page: PageSnapshot, bounds: Box, k: Float, w: Float, h: Float) {
        renderer.drawBackground(canvas, page.color, page.pattern, -bounds.left * k, -bounds.top * k, k, w, h)
        canvas.save()
        canvas.scale(k, k)
        canvas.translate(-bounds.left, -bounds.top)
        renderer.drawItems(canvas, page.items)
        canvas.restore()
    }

    private fun fitWithin(bitmap: Bitmap, limit: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= limit) return bitmap
        val f = limit.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun store(bitmap: Bitmap, dir: File, opaque: Boolean = !bitmap.hasAlpha()): StoredImage {
        dir.mkdirs()
        val jpeg = opaque
        val file = File.createTempFile("img-", if (jpeg) ".jpg" else ".png", dir)
        try {
            val ok = file.outputStream().use {
                bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, it)
            }
            if (!ok) throw IOException("Gambar gagal disimpan")
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return StoredImage(file.name, bitmap.width, bitmap.height, bitmap)
    }
}
