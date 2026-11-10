package com.tvsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameAnalyzerTest {
    private val red = 0xFF0000
    private val blue = 0x0000FF
    private val green = 0x00FF00

    private fun frame(w: Int, h: Int, color: (x: Int, y: Int) -> Int) =
        IntArray(w * h) { color(it % w, it / w) }

    private fun analyzer(layout: LedLayout, stableFrames: Int = 3) = FrameAnalyzer(
        layout = layout,
        edgeDepth = 0.15f,
        smoothing = 0f,
        saturation = 1f,
        brightness = 1f,
        letterboxStableFrames = stableFrames,
    )

    private fun ledColors(a: FrameAnalyzer): List<Int> = (0 until a.ledCount).map { i ->
        val b = a.ledBytes
        ((b[i * 3].toInt() and 0xff) shl 16) or ((b[i * 3 + 1].toInt() and 0xff) shl 8) or (b[i * 3 + 2].toInt() and 0xff)
    }

    private fun layout(corner: StartCorner, clockwise: Boolean) = LedLayout(
        top = 2, right = 1, bottom = 2, left = 1, startCorner = corner, clockwise = clockwise,
    )

    private val splitFrame = frame(32, 18) { x, _ -> if (x < 16) red else blue }

    @Test
    fun clockwiseFromTopLeft() {
        val a = analyzer(layout(StartCorner.TOP_LEFT, clockwise = true))
        a.analyze(splitFrame, 32, 18)
        // top L->R, right, bottom R->L, left
        assertEquals(listOf(red, blue, blue, blue, red, red), ledColors(a))
    }

    @Test
    fun clockwiseFromBottomLeft() {
        val a = analyzer(layout(StartCorner.BOTTOM_LEFT, clockwise = true))
        a.analyze(splitFrame, 32, 18)
        // left, top L->R, right, bottom R->L
        assertEquals(listOf(red, red, blue, blue, blue, red), ledColors(a))
    }

    @Test
    fun counterClockwiseFromBottomLeft() {
        val a = analyzer(layout(StartCorner.BOTTOM_LEFT, clockwise = false))
        a.analyze(splitFrame, 32, 18)
        // bottom L->R, right, top R->L, left
        assertEquals(listOf(red, blue, blue, blue, red, red), ledColors(a))
        assertArrayEquals(intArrayOf(4, 3, 2, 1, 0, 5), FrameAnalyzer.stripOrder(layout(StartCorner.BOTTOM_LEFT, false)))
    }

    @Test
    fun lampsUseLeftAndRightOfPicture() {
        val a = analyzer(layout(StartCorner.TOP_LEFT, clockwise = true))
        a.analyze(splitFrame, 32, 18)
        assertEquals(red, a.leftLamp)
        assertEquals(blue, a.rightLamp)
    }

    @Test
    fun letterboxBarsAreIgnoredOnceStable() {
        val w = 32
        val h = 18
        val letterboxed = frame(w, h) { _, y -> if (y < 3 || y >= h - 3) 0 else green }
        val a = analyzer(LedLayout(2, 0, 0, 0, StartCorner.TOP_LEFT, true), stableFrames = 3)

        a.analyze(letterboxed, w, h)
        assertEquals(0, a.letterbox.rows)
        assertEquals(0, ledColors(a)[0])

        repeat(3) { a.analyze(letterboxed, w, h) }
        assertEquals(3, a.letterbox.rows)
        assertEquals(green, ledColors(a)[0])
    }

    @Test
    fun allBlackFrameIsNotTreatedAsLetterbox() {
        val black = IntArray(32 * 18)
        val a = analyzer(layout(StartCorner.TOP_LEFT, clockwise = true), stableFrames = 1)
        repeat(5) { a.analyze(black, 32, 18) }
        assertEquals(0, a.letterbox.rows)
        assertEquals(0, a.letterbox.cols)
        assertEquals(0, a.avgLuma)
    }

    @Test
    fun smoothingMovesPartwayTowardNewColor() {
        val a = FrameAnalyzer(LedLayout(1, 0, 0, 0, StartCorner.TOP_LEFT, true), smoothing = 0.5f, saturation = 1f)
        a.analyze(IntArray(32 * 18), 32, 18)
        a.analyze(IntArray(32 * 18) { 0xC8C8C8 }, 32, 18)
        assertEquals(100, a.ledBytes[0].toInt() and 0xff)
    }
}
