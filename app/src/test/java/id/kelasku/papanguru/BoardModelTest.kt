package id.kelasku.papanguru

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardModelTest {
    private fun ink(id: Long, vararg points: Float, width: Float = 2f) = InkItem(id, 0xFFFFFFFF.toInt(), width, points)

    @Test
    fun undoRedoRestoreSnapshots() {
        val page = BoardPage()
        val a = ink(1, 0f, 0f, 10f, 10f)
        val b = ink(2, 5f, 5f)
        page.commit(listOf(a))
        page.commit(listOf(a, b))
        assertTrue(page.undo())
        assertEquals(listOf(a), page.items)
        assertTrue(page.canRedo)
        assertTrue(page.redo())
        assertEquals(listOf(a, b), page.items)
        assertFalse(page.redo())
    }

    @Test
    fun newCommitClearsRedo() {
        val page = BoardPage()
        page.commit(listOf(ink(1, 0f, 0f)))
        page.undo()
        page.commit(listOf(ink(2, 1f, 1f)))
        assertFalse(page.canRedo)
    }

    @Test
    fun committingSameListIsNoOp() {
        val page = BoardPage()
        val items = listOf(ink(1, 0f, 0f))
        page.commit(items)
        val revision = page.revision
        page.commit(items)
        assertEquals(revision, page.revision)
    }

    @Test
    fun discardLastIsNotRedoable() {
        val page = BoardPage()
        page.commit(listOf(ink(1, 0f, 0f)))
        assertTrue(page.discardLast())
        assertTrue(page.items.isEmpty())
        assertFalse(page.canRedo)
    }

    @Test
    fun historyIsBounded() {
        val page = BoardPage()
        repeat(BoardPage.HISTORY_LIMIT + 50) { page.commit(listOf(ink(it.toLong(), it.toFloat(), 0f))) }
        var undos = 0
        while (page.undo()) undos++
        assertEquals(BoardPage.HISTORY_LIMIT, undos)
    }

    @Test
    fun eraserSplitsStrokeInTwo() {
        val line = ink(1, 0f, 0f, 100f, 0f)
        var next = 10L
        val pieces = Geometry.eraseInk(line, 50f, -10f, 50f, 10f, 5f) { next++ }
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        assertTrue(pieces.all { p -> p.points.indices.step(2).all { p.points[it] < 44f || p.points[it] > 56f } })
        assertEquals(1L, pieces[0].id)
        assertEquals(10L, pieces[1].id)
    }

    @Test
    fun eraserMissReturnsNull() {
        val line = ink(1, 0f, 0f, 100f, 0f)
        assertNull(Geometry.eraseInk(line, 50f, 50f, 60f, 50f, 5f) { 99 })
    }

    @Test
    fun eraserRemovesDotCompletely() {
        val dot = ink(1, 10f, 10f)
        assertEquals(emptyList<InkItem>(), Geometry.eraseInk(dot, 10f, 10f, 10f, 10f, 3f) { 99 })
    }

    @Test
    fun hitTestsUseStrokeWidth() {
        val line = ink(1, 0f, 0f, 100f, 0f, width = 10f)
        assertTrue(line.hit(50f, 6f, 2f))
        assertFalse(line.hit(50f, 20f, 2f))
        val rect = ShapeItem(2, ShapeType.RECT, 0, 2f, 0f, 0f, 100f, 50f)
        assertTrue(rect.hit(100f, 25f, 2f))
        assertFalse("rectangles are outlines, not filled", rect.hit(50f, 25f, 2f))
    }

    @Test
    fun polygonContainment() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(Geometry.inPolygon(square, 5f, 5f))
        assertFalse(Geometry.inPolygon(square, 15f, 5f))
    }

    @Test
    fun transformScalesAroundPivot() {
        val t = ItemTransform(scale = 2f, px = 10f, py = 10f)
        val scaled = ink(1, 10f, 10f, 20f, 10f, width = 3f).transformed(t) as InkItem
        assertArrayEquals(floatArrayOf(10f, 10f, 30f, 10f), scaled.points, 0.001f)
        assertEquals(6f, scaled.width, 0.001f)
    }

    @Test
    fun documentIdsContinueAfterLoadedItems() {
        val doc = BoardDocument("x", "Papan", mutableListOf(BoardPage(listOf(ink(41, 0f, 0f)))))
        assertEquals(42L, doc.newId())
    }

    @Test
    fun codecRoundTrip() {
        val page = BoardPage(
            listOf(
                ink(1, 0.04f, 1.25f, -3.5f, 7f).let { InkItem(it.id, it.color, it.width, it.points, highlighter = true) },
                ShapeItem(2, ShapeType.ARROW, 0xFF00FF00.toInt(), 4f, 1f, 2f, 3f, 4f),
                TextItem(3, "Halo\nkelas", 5f, 6f, 32f, 0xFFFF0000.toInt(), 80f, 70f),
                ImageItem(4, "img-123.jpg", 0f, 0f, 640f, 480f, locked = true),
            ),
            BoardColor.WHITE, BoardPattern.LINES,
        )
        val doc = BoardDocument("id-1", "Matematika \"7A\"", mutableListOf(BoardPage(), page), pageIndex = 1)
        val decoded = BoardCodec.decode("id-1", BoardCodec.encode(BoardSnapshot.of(doc), 0))

        assertEquals("Matematika \"7A\"", decoded.name)
        assertEquals(1, decoded.pageIndex)
        val p = decoded.pages[1]
        assertEquals(BoardColor.WHITE, p.color)
        assertEquals(BoardPattern.LINES, p.pattern)
        val inkOut = p.items[0] as InkItem
        assertTrue(inkOut.highlighter)
        assertArrayEquals(floatArrayOf(0f, 1.3f, -3.5f, 7f), inkOut.points, 0.0001f)
        assertEquals(ShapeType.ARROW, (p.items[1] as ShapeItem).type)
        assertEquals("Halo\nkelas", (p.items[2] as TextItem).text)
        assertTrue((p.items[3] as ImageItem).locked)
        assertEquals(5L, decoded.newId())
    }

    @Test
    fun pageIdsSurviveSaveAndDuplicatesGetNewOnes() {
        val doc = BoardDocument("x", "Papan", mutableListOf(BoardPage(), BoardPage()))
        val decoded = BoardCodec.decode("x", BoardCodec.encode(BoardSnapshot.of(doc), 0))
        assertEquals(doc.pages.map { it.id }, decoded.pages.map { it.id })
        assertTrue(doc.pages.all { BoardPage.SAFE_PAGE_ID.matches(it.id) })
        assertNotEquals(doc.pages[0].id, doc.pages[0].copy().id)
    }

    @Test
    fun oldBoardsWithoutPageIdsGetUniqueIds() {
        val json = """{"pages":[{"items":[]},{"id":"p_same","items":[]},{"id":"p_same","items":[]},{"id":"../x","items":[]}]}"""
        val ids = BoardCodec.decode("x", json).pages.map { it.id }
        assertEquals(4, ids.toSet().size)
        assertEquals("p_same", ids[1])
        assertTrue(ids.all { BoardPage.SAFE_PAGE_ID.matches(it) })
    }

    @Test
    fun itemIdsStayInTheirSlot() {
        val student = ItemIds.of(7, 3)
        assertEquals(7, ItemIds.slotOf(student))
        assertEquals(3L, ItemIds.counterOf(student))
        // A student's item on the teacher's page must not push the teacher's counter into slot 7.
        val teacher = BoardDocument("x", "Papan", mutableListOf(BoardPage(listOf(ink(5, 0f, 0f), ink(student, 1f, 1f)))))
        assertEquals(6L, teacher.newId())
        val studentDoc = BoardDocument("y", "Lembar", mutableListOf(BoardPage(listOf(ink(student, 1f, 1f)))), slot = 7)
        assertEquals(ItemIds.of(7, 4), studentDoc.newId())
        // Largest slot still fits exactly in a JavaScript number.
        assertTrue(ItemIds.of(ItemIds.MAX_SLOT, ItemIds.MAX_COUNTER) < (1L shl 53))
    }

    @Test
    fun codecRejectsUnsafeImagePaths() {
        val json = """{"pages":[{"items":[{"t":"img","id":1,"f":"../../secret.png","w":1,"h":1}]}]}"""
        assertTrue(BoardCodec.decode("x", json).pages[0].items.isEmpty())
    }

    @Test
    fun codecToleratesMissingFields() {
        val doc = BoardCodec.decode("x", "{}")
        assertEquals(1, doc.pages.size)
        assertEquals("Papan", doc.name)
    }

    @Test
    fun exportBoundsGrowToContent() {
        val frame = Box(0f, 0f, 100f, 100f)
        assertEquals(frame, BoardFiles.exportBounds(emptyList(), frame))
        val far = BoardFiles.exportBounds(listOf(ink(1, 500f, 50f, width = 2f)), frame)
        assertTrue(far.right > 500f)
        assertEquals(0f, far.left, 0f)
    }
}
