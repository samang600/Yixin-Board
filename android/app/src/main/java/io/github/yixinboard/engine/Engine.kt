package io.github.yixinboard.engine

import java.util.concurrent.atomic.AtomicBoolean

class SearchResult(
    val move: Int,
    /** Score from the point of view of the side to move. */
    val score: Int,
    val depth: Int,
    val nodes: Long,
    val elapsedMs: Long,
    val vcf: Boolean = false,
) {
    val isWin get() = score >= Engine.WIN - 1000
    val isLoss get() = score <= -Engine.WIN + 1000
}

/**
 * Built-in gomoku / renju engine.
 *
 * Evaluation counts every line of five cells ("window") that holds stones of only one colour;
 * window values are kept incrementally so a move costs only 20 window updates. The search is an
 * iterative-deepening alpha-beta (negamax) with threat extensions on fives, plus a dedicated
 * VCF (victory by continuous fours) solver used both for attack and for defence at the root.
 */
class Engine(val size: Int, val rule: Rule) {

    companion object {
        const val WIN = 10_000_000
        private val SC = intArrayOf(0, 2, 24, 260, 2600, 200_000)
        private const val MAX_PLY = 64
    }

    private val nn = size * size
    val board = Board(size)
    private val cells = board.cells
    private val near = IntArray(nn)
    private val cnt = arrayOf(IntArray(0), IntArray(4 * nn), IntArray(4 * nn)) // [color][d*nn + start]
    private val score = IntArray(3)
    /** For each cell, the 20 window ids (d*nn + start) containing it, or -1. */
    private val slots = IntArray(nn * 20)

    private val candBuf = Array(MAX_PLY + 1) { IntArray(nn) }
    private val scoreBuf = Array(MAX_PLY + 1) { IntArray(nn) }

    private var nodes = 0L
    private var deadline = 0L
    private var cancel: AtomicBoolean? = null
    private var vcfBudget = 0L

    private class Abort : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    init {
        for (y in 0 until size) for (x in 0 until size) {
            val idx = y * size + x
            for (d in 0 until 4) for (k in 0 until 5) {
                val sx = x - k * DX[d]
                val sy = y - k * DY[d]
                val ex = sx + 4 * DX[d]
                val ey = sy + 4 * DY[d]
                slots[idx * 20 + d * 5 + k] =
                    if (board.inside(sx, sy) && board.inside(ex, ey)) d * nn + sy * size + sx else -1
            }
        }
    }

    // ------------------------------------------------------------------ position handling

    fun setPosition(moves: List<Int>) {
        while (true) {
            val idx = cells.indexOfFirst { it != EMPTY }
            if (idx < 0) break
            unplace(idx)
        }
        moves.forEachIndexed { i, m -> place(m, if (i % 2 == 0) BLACK else WHITE) }
    }

    fun place(idx: Int, color: Int) {
        updateWindows(idx, color, +1)
        cells[idx] = color
        updateNear(idx, +1)
    }

    fun unplace(idx: Int) {
        val color = cells[idx]
        cells[idx] = EMPTY
        updateWindows(idx, color, -1)
        updateNear(idx, -1)
    }

    private fun updateWindows(idx: Int, color: Int, delta: Int) {
        val mine = cnt[color]
        val theirs = cnt[3 - color]
        val base = idx * 20
        for (i in 0 until 20) {
            val w = slots[base + i]
            if (w < 0) continue
            val m = mine[w]
            val o = theirs[w]
            if (o == 0) score[color] -= SC[m] else if (m == 0) score[3 - color] -= SC[o]
            val m2 = m + delta
            mine[w] = m2
            if (o == 0) score[color] += SC[m2] else if (m2 == 0) score[3 - color] += SC[o]
        }
    }

    private fun updateNear(idx: Int, delta: Int) {
        val x = idx % size
        val y = idx / size
        for (yy in maxOf(0, y - 2)..minOf(size - 1, y + 2))
            for (xx in maxOf(0, x - 2)..minOf(size - 1, x + 2))
                near[yy * size + xx] += delta
    }

    // ------------------------------------------------------------------ helpers

    /** Cheap pre-test: some window through idx already holds four stones of [color] and nothing else. */
    private fun maybeFive(idx: Int, color: Int): Boolean {
        val mine = cnt[color]
        val theirs = cnt[3 - color]
        val base = idx * 20
        for (i in 0 until 20) {
            val w = slots[base + i]
            if (w >= 0 && mine[w] == 4 && theirs[w] == 0) return true
        }
        return false
    }

    private fun wins(idx: Int, color: Int): Boolean =
        maybeFive(idx, color) && Rules.wouldWin(board, idx % size, idx / size, color, rule)

    private fun makesFour(idx: Int, color: Int): Boolean {
        val mine = cnt[color]
        val theirs = cnt[3 - color]
        val base = idx * 20
        for (i in 0 until 20) {
            val w = slots[base + i]
            if (w >= 0 && mine[w] == 3 && theirs[w] == 0) return true
        }
        return false
    }

    private fun forbidden(idx: Int, color: Int): Boolean =
        rule == Rule.RENJU && color == BLACK && Rules.isForbidden(board, idx % size, idx / size)

    /** Ordering score: what the side gains by playing idx plus what it takes away from the opponent. */
    private fun moveScore(idx: Int, side: Int): Int {
        val mine = cnt[side]
        val theirs = cnt[3 - side]
        val base = idx * 20
        var att = 0
        var def = 0
        for (i in 0 until 20) {
            val w = slots[base + i]
            if (w < 0) continue
            val m = mine[w]
            val o = theirs[w]
            if (o == 0) att += SC[m + 1] - SC[m]
            if (m == 0) def += SC[o + 1] - SC[o]
        }
        return att + def * 7 / 8
    }

    private fun evaluate(side: Int): Int = score[side] - score[3 - side] + 20

    private fun checkAbort() {
        nodes++
        if (nodes and 1023L == 0L) {
            if (cancel?.get() == true || System.nanoTime() > deadline) throw Abort()
        }
    }

    /** Partial selection sort: bring the [k] best-scored entries to the front. */
    private fun selectTop(cand: IntArray, sc: IntArray, n: Int, k: Int) {
        for (i in 0 until minOf(k, n)) {
            var best = i
            for (j in i + 1 until n) if (sc[j] > sc[best]) best = j
            if (best != i) {
                val t = cand[i]; cand[i] = cand[best]; cand[best] = t
                val s = sc[i]; sc[i] = sc[best]; sc[best] = s
            }
        }
    }

    // ------------------------------------------------------------------ alpha-beta

    private fun width(ply: Int) = when {
        ply <= 1 -> 14
        ply <= 3 -> 10
        else -> 7
    }

    private fun negamax(side: Int, depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        checkAbort()
        val opp = 3 - side
        val cand = candBuf[ply]
        val sc = scoreBuf[ply]
        var n = 0
        var threats = 0
        for (i in 0 until nn) {
            if (cells[i] != EMPTY || near[i] == 0) continue
            if (wins(i, side)) return WIN - ply
            cand[n++] = i
        }
        if (n == 0) return 0
        // Opponent threatens five: the only sensible replies are the blocking points.
        for (j in 0 until n) {
            if (wins(cand[j], opp)) cand[threats++] = cand[j]
        }
        if (threats > 0) {
            var m = 0
            for (j in 0 until threats) if (!forbidden(cand[j], side)) cand[m++] = cand[j]
            if (m == 0) return -(WIN - ply - 1)
            if (ply >= MAX_PLY - 1) return evaluate(side)
            var alpha = alpha0
            var best = -WIN
            for (j in 0 until m) {
                val mv = cand[j]
                place(mv, side)
                val v = try {
                    -negamax(opp, maxOf(depth - 1, 0), -beta, -alpha, ply + 1)
                } finally {
                    unplace(mv)
                }
                if (v > best) best = v
                if (v > alpha) alpha = v
                if (alpha >= beta) break
            }
            return best
        }
        if (depth <= 0 || ply >= MAX_PLY - 1) return evaluate(side)

        for (j in 0 until n) sc[j] = moveScore(cand[j], side)
        val w = width(ply)
        selectTop(cand, sc, n, w + 4)
        var alpha = alpha0
        var best = -WIN
        var tried = 0
        var j = 0
        while (j < n && tried < w) {
            val mv = cand[j++]
            if (forbidden(mv, side)) continue
            tried++
            place(mv, side)
            val v = try {
                -negamax(opp, depth - 1, -beta, -alpha, ply + 1)
            } finally {
                unplace(mv)
            }
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return if (tried == 0) evaluate(side) else best
    }

    // ------------------------------------------------------------------ VCF

    /** Finds a VCF for [att] (who is to move). Returns the first move, or -1. */
    private fun vcf(att: Int, depth: Int): Int {
        checkAbort()
        if (--vcfBudget < 0) return -1
        val def = 3 - att
        for (i in 0 until nn) if (cells[i] == EMPTY && near[i] > 0 && wins(i, att)) return i
        for (i in 0 until nn) if (cells[i] == EMPTY && near[i] > 0 && wins(i, def)) return -1
        if (depth <= 0) return -1
        for (i in 0 until nn) {
            if (cells[i] != EMPTY || near[i] == 0 || !makesFour(i, att)) continue
            if (forbidden(i, att)) continue
            place(i, att)
            var w1 = -1
            var two = false
            val x = i % size
            val y = i / size
            loop@ for (d in 0 until 4) for (k in -4..4) {
                if (k == 0) continue
                val px = x + k * DX[d]
                val py = y + k * DY[d]
                if (!board.inside(px, py)) continue
                val p = py * size + px
                if (cells[p] != EMPTY || !wins(p, att)) continue
                if (w1 == -1) w1 = p else if (p != w1) { two = true; break@loop }
            }
            if (w1 == -1) { unplace(i); continue }
            if (two || forbidden(w1, def)) { unplace(i); return i }
            place(w1, def)
            val r = try {
                vcf(att, depth - 1)
            } finally {
                unplace(w1)
                unplace(i)
            }
            if (r >= 0) return i
        }
        return -1
    }

    private fun findVcf(att: Int, budget: Long): Int {
        vcfBudget = budget
        return vcf(att, 14)
    }

    // ------------------------------------------------------------------ root

    /**
     * Searches the current position for the side to move ([side]) for at most [timeMs].
     * [progress] is called after every completed iteration.
     */
    fun think(
        side: Int,
        timeMs: Long,
        cancelFlag: AtomicBoolean,
        progress: (SearchResult) -> Unit = {},
    ): SearchResult {
        val start = System.nanoTime()
        cancel = cancelFlag
        deadline = start + timeMs * 1_000_000L
        nodes = 0
        val elapsed = { (System.nanoTime() - start) / 1_000_000L }
        val opp = 3 - side

        if (cells.all { it == EMPTY }) return SearchResult((size / 2) * size + size / 2, 0, 0, 0, 0)

        // Root candidates.
        val cand = ArrayList<Int>()
        for (i in 0 until nn) if (cells[i] == EMPTY && near[i] > 0) cand.add(i)
        if (cand.isEmpty()) {
            val any = cells.indexOfFirst { it == EMPTY }
            return SearchResult(any, 0, 0, 0, elapsed())
        }
        cand.firstOrNull { wins(it, side) }?.let { return SearchResult(it, WIN, 1, 1, elapsed()) }
        val blocks = cand.filter { wins(it, opp) }
        var legal = (if (blocks.isNotEmpty()) blocks else cand).filter { !forbidden(it, side) }
        if (legal.isEmpty()) {
            // Lost (e.g. black's only block is forbidden): still play some legal move.
            val fallback = cand.firstOrNull { !forbidden(it, side) }
                ?: (0 until nn).firstOrNull { cells[it] == EMPTY && !forbidden(it, side) }
                ?: cand.first()
            return SearchResult(fallback, -WIN, 1, 1, elapsed())
        }
        if (legal.size == 1) return SearchResult(legal[0], 0, 1, 1, elapsed())

        // Our own VCF.
        if (blocks.isEmpty()) {
            try {
                val m = findVcf(side, 60_000)
                if (m >= 0) return SearchResult(m, WIN - 10, 0, nodes, elapsed(), vcf = true)
            } catch (_: Abort) {
            }
        }

        legal = legal.sortedByDescending { moveScore(it, side) }.take(24)
        var bestMove = legal[0]
        var bestScore = -WIN
        var bestDepth = 0
        val scores = HashMap<Int, Int>()
        var order = legal
        try {
            var depth = 2
            while (depth <= 30) {
                var alpha = -WIN - 1
                var iterBest = -1
                var iterScore = -WIN - 1
                for (mv in order) {
                    place(mv, side)
                    val v = try {
                        -negamax(opp, depth - 1, -WIN - 1, -alpha, 1)
                    } finally {
                        unplace(mv)
                    }
                    scores[mv] = v
                    if (v > iterScore) { iterScore = v; iterBest = mv }
                    if (v > alpha) alpha = v
                }
                bestMove = iterBest
                bestScore = iterScore
                bestDepth = depth
                progress(SearchResult(bestMove, bestScore, bestDepth, nodes, elapsed()))
                if (iterScore >= WIN - 1000 || iterScore <= -WIN + 1000) break
                order = order.sortedByDescending { scores[it] ?: -WIN }
                // Not enough time left for another (much larger) iteration.
                if (elapsed() * 4 > timeMs && depth >= 4) break
                depth++
            }
        } catch (_: Abort) {
        }

        // VCF defence: avoid a move that lets the opponent win by continuous fours.
        if (bestScore > -WIN + 1000 && blocks.isEmpty()) {
            val ranked = order.sortedByDescending { scores[it] ?: -WIN }.take(8)
            deadline = maxOf(deadline, System.nanoTime() + 300_000_000L)
            try {
                for (mv in ranked) {
                    place(mv, side)
                    val threat = try {
                        findVcf(opp, 6_000)
                    } finally {
                        unplace(mv)
                    }
                    if (threat < 0) {
                        if (mv != bestMove) {
                            bestMove = mv
                            bestScore = scores[mv] ?: bestScore
                        }
                        break
                    }
                }
            } catch (_: Abort) {
            }
        }
        return SearchResult(bestMove, bestScore, bestDepth, nodes, elapsed())
    }
}
