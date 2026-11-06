package io.github.yixinboard.engine

const val EMPTY = 0
const val BLACK = 1
const val WHITE = 2
const val OUT = -1

fun opponent(color: Int) = 3 - color

/** Same numbering as Yixin's `INFO rule`: 0 = freestyle, 1 = standard (exactly five), 2 = renju. */
enum class Rule(val id: Int) {
    FREESTYLE(0), STANDARD(1), RENJU(2);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: FREESTYLE
    }
}

/** The four line directions: horizontal, vertical, diagonal, anti-diagonal. */
val DX = intArrayOf(1, 0, 1, 1)
val DY = intArrayOf(0, 1, 1, -1)

class Board(val size: Int) {
    val cells = IntArray(size * size)

    fun inside(x: Int, y: Int) = x in 0 until size && y in 0 until size
    fun at(x: Int, y: Int): Int = if (inside(x, y)) cells[y * size + x] else OUT
    fun set(x: Int, y: Int, v: Int) {
        cells[y * size + x] = v
    }

    fun clear() = cells.fill(EMPTY)
}

object Rules {

    /** Length of the run of [color] through (x, y) in direction [d], treating (x, y) itself as [color]. */
    fun runLength(b: Board, x: Int, y: Int, d: Int, color: Int): Int {
        val dx = DX[d]
        val dy = DY[d]
        var n = 1
        var cx = x + dx
        var cy = y + dy
        while (b.at(cx, cy) == color) { n++; cx += dx; cy += dy }
        cx = x - dx; cy = y - dy
        while (b.at(cx, cy) == color) { n++; cx -= dx; cy -= dy }
        return n
    }

    fun isFive(len: Int, color: Int, rule: Rule): Boolean = when (rule) {
        Rule.FREESTYLE -> len >= 5
        Rule.STANDARD -> len == 5
        Rule.RENJU -> if (color == BLACK) len == 5 else len >= 5
    }

    /** Would a stone of [color] at (x, y) win the game? (x, y) may be empty or already hold the stone. */
    fun wouldWin(b: Board, x: Int, y: Int, color: Int, rule: Rule): Boolean {
        for (d in 0 until 4) if (isFive(runLength(b, x, y, d, color), color, rule)) return true
        return false
    }

    /** The winning stones through (x, y), or null if the stone there does not win. */
    fun winningLine(b: Board, x: Int, y: Int, color: Int, rule: Rule): List<Int>? {
        for (d in 0 until 4) {
            if (!isFive(runLength(b, x, y, d, color), color, rule)) continue
            val line = ArrayList<Int>()
            line.add(y * b.size + x)
            var cx = x + DX[d]; var cy = y + DY[d]
            while (b.at(cx, cy) == color) { line.add(cy * b.size + cx); cx += DX[d]; cy += DY[d] }
            cx = x - DX[d]; cy = y - DY[d]
            while (b.at(cx, cy) == color) { line.add(cy * b.size + cx); cx -= DX[d]; cy -= DY[d] }
            return line
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Renju forbidden moves (black only): double-three, double-four and overline.
    // An exact five always takes priority over a forbidden shape.
    // ---------------------------------------------------------------------------------------

    fun isForbidden(b: Board, x: Int, y: Int): Boolean {
        if (b.at(x, y) != EMPTY) return false
        b.set(x, y, BLACK)
        try {
            var overline = false
            for (d in 0 until 4) {
                val len = runLength(b, x, y, d, BLACK)
                if (len == 5) return false
                if (len > 5) overline = true
            }
            if (overline) return true
            var fours = 0
            for (d in 0 until 4) fours += foursInDirection(b, x, y, d)
            if (fours >= 2) return true
            var threes = 0
            for (d in 0 until 4) {
                if (isOpenThree(b, x, y, d)) {
                    threes++
                    if (threes >= 2) return true
                }
            }
            return false
        } finally {
            b.set(x, y, EMPTY)
        }
    }

    /**
     * Offsets k (along direction d) of empty points where an extra black stone makes an exact
     * five through the black stone at (x, y). At most two distinct points can exist.
     */
    private fun fiveCompletions(b: Board, x: Int, y: Int, d: Int, out: IntArray): Int {
        var n = 0
        for (k in -4..4) {
            if (k == 0) continue
            val px = x + k * DX[d]
            val py = y + k * DY[d]
            if (b.at(px, py) != EMPTY) continue
            b.set(px, py, BLACK)
            val len = runLength(b, x, y, d, BLACK)
            b.set(px, py, EMPTY)
            if (len == 5 && n < out.size) out[n++] = k
        }
        return n
    }

    /** Number of distinct fours (0, 1 or 2) through the black stone at (x, y) in direction d. */
    private fun foursInDirection(b: Board, x: Int, y: Int, d: Int): Int {
        val ks = IntArray(4)
        val n = fiveCompletions(b, x, y, d, ks)
        if (n == 0) return 0
        if (n == 1) return 1
        // Two completion points exactly five apart are the two ends of one straight four.
        return if (n == 2 && kotlin.math.abs(ks[0] - ks[1]) == 5) 1 else 2
    }

    private fun isStraightFour(b: Board, x: Int, y: Int, d: Int): Boolean {
        val ks = IntArray(4)
        val n = fiveCompletions(b, x, y, d, ks)
        for (i in 0 until n) for (j in i + 1 until n) if (kotlin.math.abs(ks[i] - ks[j]) == 5) return true
        return false
    }

    /**
     * True if the black stone at (x, y) forms a real open three in direction d: there is an empty
     * point that turns it into a straight four, and that point is not itself forbidden.
     */
    private fun isOpenThree(b: Board, x: Int, y: Int, d: Int): Boolean {
        for (k in -4..4) {
            if (k == 0) continue
            val px = x + k * DX[d]
            val py = y + k * DY[d]
            if (b.at(px, py) != EMPTY) continue
            b.set(px, py, BLACK)
            val straight = isStraightFour(b, x, y, d)
            b.set(px, py, EMPTY)
            if (straight && !isForbidden(b, px, py)) return true
        }
        return false
    }
}
