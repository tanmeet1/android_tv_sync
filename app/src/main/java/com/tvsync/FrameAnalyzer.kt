package com.tvsync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a small downscaled screen frame (pixels packed as 0xRRGGBB) into per-LED strip colors
 * and one color for each side lamp.
 */
class FrameAnalyzer(
    private val layout: LedLayout,
    private val edgeDepth: Float = 0.15f,
    smoothing: Float = 0.5f,
    private val saturation: Float = 1.2f,
    private val brightness: Float = 1f,
    letterboxStableFrames: Int = 15,
) {
    val ledCount = layout.total

    /** RGB triplets in strip order, ready to send to WLED. */
    val ledBytes = ByteArray(ledCount * 3)

    var leftLamp = 0
        private set
    var rightLamp = 0
        private set
    var avgLuma = 0
        private set

    val letterbox = LetterboxDetector(letterboxStableFrames)

    private val alpha = 1f - smoothing.coerceIn(0f, 0.95f)
    private val order = stripOrder(layout)
    private val ledState = FloatArray(ledCount * 3)
    private val lampState = FloatArray(6)
    private var primed = false
    private val region = IntArray(4)
    private val rgb = FloatArray(3)
    private val ignored = IntArray(4)
    private var hasIgnored = false

    /** Excludes a rectangle (e.g. our own on-screen overlay) from all color sampling. End-exclusive. */
    fun setIgnoredArea(x0: Int, y0: Int, x1: Int, y1: Int) {
        ignored[0] = x0; ignored[1] = y0; ignored[2] = x1; ignored[3] = y1
        hasIgnored = x1 > x0 && y1 > y0
    }

    fun clearIgnoredArea() {
        hasIgnored = false
    }

    fun analyze(px: IntArray, w: Int, h: Int) {
        letterbox.update(px, w, h)
        val cx0 = letterbox.cols
        val cx1 = w - letterbox.cols
        val cy0 = letterbox.rows
        val cy1 = h - letterbox.rows
        avgLuma = averageLuma(px, w)

        for (i in 0 until ledCount) {
            edgeRegion(order[i], cx0, cy0, cx1, cy1)
            // A region fully hidden by the ignored area keeps its previous color.
            if (average(px, w, region[0], region[1], region[2], region[3])) blend(ledState, i * 3)
            for (c in 0..2) ledBytes[i * 3 + c] = ledState[i * 3 + c].roundToInt().toByte()
        }

        val lampWidth = max(1, ((cx1 - cx0) * LAMP_WIDTH).roundToInt())
        if (average(px, w, cx0, cy0, cx0 + lampWidth, cy1)) blend(lampState, 0)
        if (average(px, w, cx1 - lampWidth, cy0, cx1, cy1)) blend(lampState, 3)
        leftLamp = pack(lampState, 0)
        rightLamp = pack(lampState, 3)
        primed = true
    }

    /** Sample rectangle for LED [k], counted clockwise from the top-left corner. */
    private fun edgeRegion(k: Int, cx0: Int, cy0: Int, cx1: Int, cy1: Int) {
        val cw = cx1 - cx0
        val ch = cy1 - cy0
        val dx = (cw * edgeDepth).roundToInt().coerceIn(1, cw)
        val dy = (ch * edgeDepth).roundToInt().coerceIn(1, ch)
        var i = k
        if (i < layout.top) {
            span(cx0, cw, layout.top, i, horizontal = true)
            region[1] = cy0; region[3] = cy0 + dy
            return
        }
        i -= layout.top
        if (i < layout.right) {
            span(cy0, ch, layout.right, i, horizontal = false)
            region[0] = cx1 - dx; region[2] = cx1
            return
        }
        i -= layout.right
        if (i < layout.bottom) {
            span(cx0, cw, layout.bottom, layout.bottom - 1 - i, horizontal = true)
            region[1] = cy1 - dy; region[3] = cy1
            return
        }
        i -= layout.bottom
        span(cy0, ch, layout.left, layout.left - 1 - i, horizontal = false)
        region[0] = cx0; region[2] = cx0 + dx
    }

    private fun span(origin: Int, length: Int, count: Int, index: Int, horizontal: Boolean) {
        val start = origin + length * index / count
        val end = (origin + length * (index + 1) / count).coerceIn(start + 1, origin + length)
        if (horizontal) {
            region[0] = start; region[2] = end
        } else {
            region[1] = start; region[3] = end
        }
    }

    /** Averages a rectangle into [rgb]. Returns false if no pixels were sampled. */
    private fun average(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in y0 until y1) {
            val rowIgnored = hasIgnored && y >= ignored[1] && y < ignored[3]
            var i = y * w + x0
            for (x in x0 until x1) {
                val p = px[i++]
                if (rowIgnored && x >= ignored[0] && x < ignored[2]) continue
                r += (p shr 16) and 0xff
                g += (p shr 8) and 0xff
                b += p and 0xff
                n++
            }
        }
        if (n == 0) return false
        adjust(r.toFloat() / n, g.toFloat() / n, b.toFloat() / n)
        return true
    }

    private fun adjust(r: Float, g: Float, b: Float) {
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        rgb[0] = ((luma + (r - luma) * saturation) * brightness).coerceIn(0f, 255f)
        rgb[1] = ((luma + (g - luma) * saturation) * brightness).coerceIn(0f, 255f)
        rgb[2] = ((luma + (b - luma) * saturation) * brightness).coerceIn(0f, 255f)
    }

    private fun blend(state: FloatArray, at: Int) {
        for (c in 0..2) {
            state[at + c] = if (primed) state[at + c] + alpha * (rgb[c] - state[at + c]) else rgb[c]
        }
    }

    private fun pack(state: FloatArray, at: Int): Int =
        (state[at].roundToInt() shl 16) or (state[at + 1].roundToInt() shl 8) or state[at + 2].roundToInt()

    private fun averageLuma(px: IntArray, w: Int): Int {
        var sum = 0L
        var n = 0
        for (i in px.indices) {
            if (hasIgnored) {
                val x = i % w
                val y = i / w
                if (x >= ignored[0] && x < ignored[2] && y >= ignored[1] && y < ignored[3]) continue
            }
            sum += luma(px[i])
            n++
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    companion object {
        /** Fraction of the picture width averaged for each side lamp. */
        const val LAMP_WIDTH = 0.4f

        /** Maps strip position -> LED index counted clockwise from the top-left corner. */
        fun stripOrder(layout: LedLayout): IntArray {
            val n = layout.total
            val offset = when (layout.startCorner) {
                StartCorner.TOP_LEFT -> 0
                StartCorner.TOP_RIGHT -> layout.top
                StartCorner.BOTTOM_RIGHT -> layout.top + layout.right
                StartCorner.BOTTOM_LEFT -> layout.top + layout.right + layout.bottom
            }
            return IntArray(n) { i ->
                if (layout.clockwise) (offset + i) % n else (offset - 1 - i).mod(n)
            }
        }

        fun luma(p: Int): Int =
            (299 * ((p shr 16) and 0xff) + 587 * ((p shr 8) and 0xff) + 114 * (p and 0xff)) / 1000
    }
}

/** Detects letterbox (top/bottom) and pillarbox (left/right) black bars, with hysteresis. */
class LetterboxDetector(private val stableFrames: Int) {
    private val rowAxis = Axis()
    private val colAxis = Axis()

    /** Rows cropped from both the top and the bottom. */
    val rows: Int get() = rowAxis.value

    /** Columns cropped from both the left and the right. */
    val cols: Int get() = colAxis.value

    fun update(px: IntArray, w: Int, h: Int) {
        // Only the top bar is measured; subtitles often sit in the bottom bar.
        val maxRows = (h * MAX_BAR).toInt()
        val topRows = countDark(maxRows + 1) { darkRow(px, w, it) }
        if (topRows <= maxRows) rowAxis.offer(topRows)

        val maxCols = (w * MAX_BAR).toInt()
        val leftCols = countDark(maxCols + 1) { darkCol(px, w, h, it) }
        val rightCols = countDark(maxCols + 1) { darkCol(px, w, h, w - 1 - it) }
        val sideCols = minOf(leftCols, rightCols)
        if (sideCols <= maxCols) colAxis.offer(sideCols)
    }

    private inline fun countDark(limit: Int, isDark: (Int) -> Boolean): Int {
        var n = 0
        while (n < limit && isDark(n)) n++
        return n
    }

    private fun darkRow(px: IntArray, w: Int, y: Int): Boolean {
        var dark = 0
        for (x in 0 until w) if (isDark(px[y * w + x])) dark++
        return dark >= w * DARK_FRACTION
    }

    private fun darkCol(px: IntArray, w: Int, h: Int, x: Int): Boolean {
        var dark = 0
        for (y in 0 until h) if (isDark(px[y * w + x])) dark++
        return dark >= h * DARK_FRACTION
    }

    private fun isDark(p: Int): Boolean =
        ((p shr 16) and 0xff) < DARK_LEVEL && ((p shr 8) and 0xff) < DARK_LEVEL && (p and 0xff) < DARK_LEVEL

    private inner class Axis {
        var value = 0
        private var candidate = 0
        private var votes = 0

        fun offer(raw: Int) {
            if (abs(raw - value) <= 1) {
                votes = 0
                return
            }
            if (raw == candidate) votes++ else {
                candidate = raw
                votes = 1
            }
            if (votes >= stableFrames) {
                value = raw
                votes = 0
            }
        }
    }

    private companion object {
        /** Bars larger than this are treated as a dark scene, not letterboxing. */
        const val MAX_BAR = 0.3f
        const val DARK_LEVEL = 32
        const val DARK_FRACTION = 0.9f
    }
}
