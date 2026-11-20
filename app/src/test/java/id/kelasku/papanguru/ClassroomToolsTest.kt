package id.kelasku.papanguru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class ClassroomToolsTest {
    private fun Calculator.keys(vararg keys: String) = apply { keys.forEach(::press) }

    @Test
    fun calculatorChainsOperations() {
        assertEquals("20", Calculator().keys("2", "+", "3", "×", "4", "=").display) // left to right: (2+3)*4
    }

    @Test
    fun calculatorUsesDecimalComma() {
        assertEquals("0,3", Calculator().keys(",", "1", "+", "0", ",", "2", "=").display)
    }

    @Test
    fun calculatorDivision() {
        assertEquals("0,333333333333", Calculator().keys("1", "÷", "3", "=").display)
        assertEquals(Calculator.ERROR, Calculator().keys("5", "÷", "0", "=").display)
    }

    @Test
    fun calculatorEditing() {
        val c = Calculator().keys("1", "2", "3", "⌫", "±")
        assertEquals("-12", c.display)
        assertEquals("0", c.keys("C").display)
        assertEquals("50", Calculator().keys("5", "0", "0", "0", "%", "×", "1", "=").display)
    }

    @Test
    fun calculatorShowsExpression() {
        val c = Calculator().keys("7", "−", "2", "=")
        assertEquals("5", c.display)
        assertEquals("7 − 2 =", c.expression)
    }

    private fun ruler() = Instrument(InstrumentType.RULER, density = 1f, pxPerCm = 40f, viewWidth = 2000f).apply {
        cx = 500f
        cy = 500f
    }

    @Test
    fun rulerSnapsToNearestEdge() {
        val r = ruler()
        val snap = r.edgeNear(510f, 450f, 26f)
        assertNotNull(snap)
        val out = FloatArray(2)
        r.project(snap!!.edge, 530f, 440f, out)
        assertEquals(530f, out[0], 0.01f)
        assertEquals(458f, out[1], 0.01f) // top edge: 500 - 84/2
        assertNull("far from every edge", r.edgeNear(510f, 300f, 26f))
    }

    @Test
    fun rotatedRulerProjectsAlongItsEdge() {
        val r = ruler()
        r.angle = (PI / 2).toFloat()
        // Rotated 90°: the edges are vertical lines at x = 500 ± 42.
        val snap = r.edgeNear(545f, 520f, 26f)!!
        val out = FloatArray(2)
        r.project(snap.edge, 560f, 600f, out)
        assertEquals(542f, out[0], 0.01f)
        assertEquals(600f, out[1], 0.01f)
        assertTrue(r.contains(500f, 700f))
        assertFalse(r.contains(600f, 500f))
    }

    @Test
    fun protractorArcProjectionKeepsRadius() {
        val p = Instrument(InstrumentType.PROTRACTOR, 1f, 40f, 2000f).apply {
            cx = 0f
            cy = 0f
        }
        val snap = p.edgeNear(0f, -395f, 26f)!!
        assertEquals(Instrument.ARC, snap.edge)
        val out = FloatArray(2)
        p.project(snap.edge, 100f, -300f, out)
        assertEquals(400f, hypot(out[0], out[1]), 0.01f)
    }

    @Test
    fun angleSnapsToFifteenDegrees() {
        val r = ruler()
        r.setAngleSnapped(Math.toRadians(44.0).toFloat())
        assertEquals(45, r.degrees())
        r.setAngleSnapped(Math.toRadians(40.0).toFloat())
        assertEquals(40, r.degrees())
    }

    @Test
    fun arcPointsFollowCircle() {
        val pts = Instrument.arcPoints(10f, 10f, 5f, 0f, (PI).toFloat(), 100f)
        for (i in 0 until pts.size / 2) assertEquals(5f, hypot(pts[i * 2] - 10f, pts[i * 2 + 1] - 10f), 0.001f)
        assertEquals(15f, pts[0], 0.001f)
        assertEquals(5f, pts[pts.size - 2], 0.001f)
    }
}
