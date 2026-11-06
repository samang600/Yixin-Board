package io.github.yixinboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RulesTest {

    private fun board(vararg rows: String): Board {
        val b = Board(15)
        // rows[0] is the top row; 'X' = black, 'O' = white
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, c ->
                when (c) {
                    'X' -> b.set(x, y, BLACK)
                    'O' -> b.set(x, y, WHITE)
                }
            }
        }
        return b
    }

    @Test
    fun fiveWinsUnderAllRules() {
        val b = board("XXXX.")
        for (r in Rule.entries) assertTrue(Rules.wouldWin(b, 4, 0, BLACK, r))
    }

    @Test
    fun overlineOnlyWinsInFreestyleOrForWhiteInRenju() {
        val b = board("XXX.XX", "OOO.OO")
        assertTrue(Rules.wouldWin(b, 3, 0, BLACK, Rule.FREESTYLE))
        assertFalse(Rules.wouldWin(b, 3, 0, BLACK, Rule.STANDARD))
        assertFalse(Rules.wouldWin(b, 3, 0, BLACK, Rule.RENJU))
        assertTrue(Rules.wouldWin(b, 3, 1, WHITE, Rule.RENJU))
        assertFalse(Rules.wouldWin(b, 3, 1, WHITE, Rule.STANDARD))
    }

    @Test
    fun doubleThreeIsForbidden() {
        val b = board(
            "...............",
            "...............",
            "...............",
            "......X........",
            ".......X.......",
            ".........XX....",
        )
        // (8,5): diagonal X X _ and horizontal _ X X -> two open threes
        assertTrue(Rules.isForbidden(b, 8, 5))
    }

    @Test
    fun doubleFourIsForbidden() {
        val b = board(
            "...............",
            "....X..........",
            "....X..........",
            "....X..........",
            "...............",
            ".XXX...........",
        )
        assertTrue(Rules.isForbidden(b, 4, 5))
    }

    @Test
    fun fourFourInOneLineIsForbidden() {
        val b = board("...............", "...............", ".X.XX..X.......")
        // X . X X [X] . X  -> two fours on one line
        assertTrue(Rules.isForbidden(b, 5, 2))
    }

    @Test
    fun overlineIsForbiddenButFiveIsNot() {
        val b = board("...............", "XXX.XX.........", "XX.XX..........")
        assertTrue(Rules.isForbidden(b, 3, 1))
        assertFalse(Rules.isForbidden(b, 2, 2))
    }

    @Test
    fun blockedThreeDoesNotCount() {
        val b = board(
            "...............",
            "...............",
            "...............",
            "......X........",
            ".......X.......",
            ".........XXO...",
        )
        // horizontal three is X X X O -> blocked on one side, only one open three
        assertFalse(Rules.isForbidden(b, 8, 5))
    }

    @Test
    fun threeAndFourIsAllowed() {
        val b = board(
            "...............",
            "....X..........",
            "....X..........",
            "....X..........",
            "...............",
            "..XX...........",
        )
        assertFalse(Rules.isForbidden(b, 4, 5))
    }

    @Test
    fun recordRoundTrip() {
        val g = Game(15, Rule.RENJU)
        listOf(112, 113, 98).forEach { assertEquals(null, g.play(it)) }
        val parsed = Game.parseRecord(g.recordString(), 15)
        assertEquals(listOf(112, 113, 98), parsed)
        assertEquals("h8i8i9", g.recordString())
    }

    @Test
    fun gameDetectsWin() {
        val g = Game(15, Rule.FREESTYLE)
        val moves = listOf(0, 20, 1, 21, 2, 22, 3, 23, 4)
        moves.forEach { g.play(it) }
        assertEquals(BLACK, g.result)
        assertEquals(5, g.winLine.size)
    }

    @Test
    fun engineCompletesFiveAndBlocksFive() {
        val e = Engine(15, Rule.FREESTYLE)
        // Black: 112,113,114,115 ; White: 0,1,2  -> black to move? (4 black, 3 white => white to move)
        e.setPosition(listOf(112, 0, 113, 1, 114, 2, 115))
        val r = e.think(WHITE, 1000, AtomicBoolean(false))
        assertTrue(r.move == 111 || r.move == 116)

        val e2 = Engine(15, Rule.FREESTYLE)
        e2.setPosition(listOf(112, 0, 113, 1, 114, 2, 115, 3))
        val r2 = e2.think(BLACK, 1000, AtomicBoolean(false))
        assertTrue(r2.move == 111 || r2.move == 116)
        assertTrue(r2.isWin)
    }

    @Test
    fun engineFindsVcf() {
        // Black has two broken threes that give a forced win by fours.
        val g = Game(15, Rule.FREESTYLE)
        val b = listOf(7 * 15 + 7, 7 * 15 + 8, 7 * 15 + 9, 8 * 15 + 10, 9 * 15 + 10)
        val w = listOf(7 * 15 + 6, 0, 14, 224, 210)
        for (i in b.indices) { g.play(b[i]); g.play(w[i]) }
        val e = Engine(15, Rule.FREESTYLE)
        e.setPosition(g.moves)
        val r = e.think(BLACK, 2000, AtomicBoolean(false))
        assertTrue(r.isWin)
    }

    @Test
    fun engineNeverPlaysForbiddenEvenWhenLost() {
        // White threatens five at (4,0); for black that point is a double-four (forbidden).
        val black = listOf(1 * 15 + 4, 2 * 15 + 4, 3 * 15 + 4, 1 * 15 + 3, 2 * 15 + 2, 3 * 15 + 1)
        val white = listOf(0, 1, 2, 3, 224, 194)
        val moves = black.indices.flatMap { listOf(black[it], white[it]) }
        val g = Game(15, Rule.RENJU)
        moves.forEach { assertEquals(null, g.play(it)) }
        assertTrue(g.isForbidden(4))
        val e = Engine(15, Rule.RENJU)
        e.setPosition(moves)
        val r = e.think(BLACK, 500, AtomicBoolean(false))
        assertEquals(null, g.play(r.move))
        assertTrue(r.isLoss)
    }

    @Test
    fun engineSelfPlayFinishesLegally() {
        for (rule in Rule.entries + Rule.RENJU + Rule.RENJU) {
            val g = Game(15, rule)
            var guard = 0
            while (!g.isOver && guard++ < 225) {
                val e = Engine(15, rule)
                e.setPosition(g.moves)
                val r = e.think(g.sideToMove, 100, AtomicBoolean(false))
                assertEquals("illegal move by engine under $rule at ply ${g.moves.size}", null, g.play(r.move))
            }
        }
    }
}
