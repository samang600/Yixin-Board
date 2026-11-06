package io.github.yixinboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native SlowRenju tests. They need a host build of libslowrenju on java.library.path
 * (env SLOWRENJU_LIB_DIR) and are skipped otherwise.
 */
class SlowRenjuTest {

    @Before
    fun requireLibrary() = assumeTrue("libslowrenju not available", SlowRenju.isAvailable)

    private fun <T> onBigStack(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        val t = Thread(null, { try { result = block() } catch (e: Throwable) { error = e } }, "test", SlowRenju.STACK_SIZE)
        t.start(); t.join()
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private val midgame = listOf(112, 111, 128, 96, 126, 98, 142, 97, 95, 110, 129, 125, 83, 124, 138, 130, 114, 100, 99, 84)

    @Test
    fun respectsTimeLimitAndReturnsLegalMove() = onBigStack {
        for (rule in Rule.entries) {
            val start = System.currentTimeMillis()
            val r = SlowRenju.think(midgame, rule, 400, AtomicBoolean(false))
            val took = System.currentTimeMillis() - start
            println("SlowRenju $rule: move=${r.move} depth=${r.depth} nodes=${r.nodes} took=${took}ms")
            assertTrue("took $took ms", took < 1500)
            assertTrue(r.move in 0 until 225 && r.move !in midgame)
        }
    }

    @Test
    fun cancelStopsQuickly() = onBigStack {
        val cancel = AtomicBoolean(false)
        Thread { Thread.sleep(150); cancel.set(true) }.start()
        val start = System.currentTimeMillis()
        val r = SlowRenju.think(midgame, Rule.RENJU, 60_000, cancel)
        val took = System.currentTimeMillis() - start
        assertTrue("took $took ms", took < 1500)
        assertTrue(r.move !in midgame)
    }

    @Test
    fun findsWinAndBlocksFive() = onBigStack {
        // Black to move with four in a row h8-k8 (open): must win.
        val win = SlowRenju.think(listOf(112, 0, 113, 1, 114, 2, 115, 3), Rule.FREESTYLE, 500, AtomicBoolean(false))
        assertTrue(win.move == 111 || win.move == 116)
        // White to move against black's four h8-k8 blocked on the left: must block at l8.
        val block = SlowRenju.think(listOf(112, 111, 113, 1, 114, 2, 115), Rule.FREESTYLE, 500, AtomicBoolean(false))
        assertEquals(116, block.move)
    }

    /** SlowRenju vs the built-in engine; games = system property engineMatchGames (default 0 = skip). */
    @Test
    fun matchAgainstBuiltInEngine() = onBigStack {
        val games = System.getProperty("engineMatchGames")?.toIntOrNull() ?: 0
        assumeTrue(games > 0)
        val ms = 500L
        for (rule in listOf(Rule.FREESTYLE, Rule.RENJU)) {
            var srWins = 0; var builtinWins = 0; var draws = 0
            for (gi in 0 until games) {
                val g = Game(15, rule)
                // Vary openings: a different second/third move each game.
                val openings = listOf(listOf(112), listOf(112, 113), listOf(112, 98), listOf(112, 97, 126), listOf(112, 128, 96))
                openings[gi / 2 % openings.size].forEach { g.play(it) }
                val srIsBlack = gi % 2 == 0
                while (!g.isOver) {
                    val srToMove = (g.sideToMove == BLACK) == srIsBlack
                    val r = if (srToMove) {
                        SlowRenju.think(g.moves, rule, ms, AtomicBoolean(false))
                    } else {
                        Engine(15, rule).run { setPosition(g.moves); think(g.sideToMove, ms, AtomicBoolean(false)) }
                    }
                    val err = g.play(r.move)
                    assertEquals("illegal move by ${if (srToMove) "SlowRenju" else "built-in"} at ${g.recordString()}", null, err)
                }
                val srColor = if (srIsBlack) BLACK else WHITE
                when (g.result) {
                    srColor -> srWins++
                    3 -> draws++
                    else -> builtinWins++
                }
                println("$rule game ${gi + 1}: SlowRenju ${if (srIsBlack) "black" else "white"} -> " +
                    "${if (g.result == srColor) "SlowRenju" else if (g.result == 3) "draw" else "built-in"} wins in ${g.moves.size} moves: ${g.recordString()}")
            }
            println("MATCH $rule: SlowRenju $srWins - $builtinWins built-in ($draws draws)")
        }
    }
}
