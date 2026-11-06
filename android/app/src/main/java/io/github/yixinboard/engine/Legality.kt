package io.github.yixinboard.engine

/** Checks that an external engine's answer is a legal move for the side to move. */
object Legality {
    fun isLegal(moves: List<Int>, size: Int, rule: Rule, move: Int): Boolean {
        if (move !in 0 until size * size || move in moves) return false
        if (rule != Rule.RENJU || moves.size % 2 != 0) return true
        val b = Board(size)
        moves.forEachIndexed { i, m -> b.cells[m] = if (i % 2 == 0) BLACK else WHITE }
        return !Rules.isForbidden(b, move % size, move / size)
    }
}
