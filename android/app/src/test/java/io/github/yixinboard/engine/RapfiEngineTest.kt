package io.github.yixinboard.engine

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives a host build of Rapfi (system property rapfiBin, from env RAPFI_BIN) through the same
 * [RapfiEngine] client the app uses, with the app's config.toml and the bundled weights.
 * Skipped when no binary is given.
 */
class RapfiEngineTest {

    companion object {
        private var engine: RapfiEngine? = null

        @BeforeClass @JvmStatic
        fun setUp() {
            val bin = System.getProperty("rapfiBin")?.let(::File)
            if (bin == null || !bin.canExecute()) return
            val dir = Files.createTempDirectory("rapfi").toFile()
            File(System.getProperty("rapfiConfig")).copyTo(File(dir, "config.toml"))
            val nets = File(System.getProperty("rapfiNetworks"))
            File(nets, "classical/model210901.bin").copyTo(File(dir, "model210901.bin"))
            File(nets, "mix9svq").listFiles { f -> f.name.endsWith(".bin.lz4") }!!.forEach { it.copyTo(File(dir, it.name)) }
            engine = RapfiEngine(bin, File(dir, "config.toml"))
        }

        @AfterClass @JvmStatic
        fun tearDown() { engine?.close() }
    }

    private fun e(): RapfiEngine { assumeTrue("RAPFI_BIN not set", engine != null); return engine!! }

    private val midgame = listOf(112, 111, 128, 96, 126, 98, 142, 97, 95, 110, 129, 125, 83, 124, 138, 130, 114, 100, 99, 84)

    @Test
    fun respectsTimeAndPlaysLegalMovesForAllRules() {
        for (rule in Rule.entries) {
            val start = System.currentTimeMillis()
            val r = e().think(midgame, 15, rule, 500, 2, AtomicBoolean(false))
            val took = System.currentTimeMillis() - start
            println("Rapfi $rule: move=${r.move} depth=${r.depth} eval=${r.score / 10} nodes=${r.nodes} took=${took}ms")
            assertTrue("took $took ms", took < 2500)
            assertTrue(Legality.isLegal(midgame, 15, rule, r.move))
        }
    }

    @Test
    fun cancelStopsQuickly() {
        val cancel = AtomicBoolean(false)
        Thread { Thread.sleep(200); cancel.set(true) }.start()
        val start = System.currentTimeMillis()
        val r = e().think(midgame, 15, Rule.RENJU, 60_000, 2, cancel)
        val took = System.currentTimeMillis() - start
        assertTrue("took $took ms", took < 1500)
        assertTrue(Legality.isLegal(midgame, 15, Rule.RENJU, r.move))
    }

    @Test
    fun findsWinBlocksFiveAndPlaysOtherSizes() {
        val win = e().think(listOf(112, 0, 113, 1, 114, 2, 115, 3), 15, Rule.FREESTYLE, 500, 1, AtomicBoolean(false))
        assertTrue(win.move == 111 || win.move == 116)
        val block = e().think(listOf(112, 111, 113, 1, 114, 2, 115), 15, Rule.FREESTYLE, 500, 1, AtomicBoolean(false))
        assertEquals(116, block.move)
        // Board size change (START is re-sent) and the empty-board first move.
        val first = e().think(emptyList(), 19, Rule.RENJU, 300, 1, AtomicBoolean(false))
        assertTrue(first.move in 0 until 19 * 19)
        val r20 = e().think(listOf(210), 20, Rule.FREESTYLE, 300, 1, AtomicBoolean(false))
        assertTrue(Legality.isLegal(listOf(210), 20, Rule.FREESTYLE, r20.move))
    }

    @Test
    fun recoversAfterProcessIsKilled() {
        e().think(midgame, 15, Rule.RENJU, 200, 1, AtomicBoolean(false))
        e().close() // like the app being trimmed; the next search restarts the process
        val r = e().think(midgame, 15, Rule.RENJU, 200, 1, AtomicBoolean(false))
        assertNotNull(r)
        assertTrue(Legality.isLegal(midgame, 15, Rule.RENJU, r.move))
    }

    /** Rapfi vs SlowRenju (needs both; games = engineMatchGames). */
    @Test
    fun matchAgainstSlowRenju() {
        val games = System.getProperty("engineMatchGames")?.toIntOrNull() ?: 0
        assumeTrue(games > 0 && SlowRenju.isAvailable)
        val ms = 500L
        for (rule in listOf(Rule.FREESTYLE, Rule.RENJU)) {
            var rapfiWins = 0; var srWins = 0; var draws = 0
            for (gi in 0 until games) {
                val g = Game(15, rule)
                val openings = listOf(listOf(112), listOf(112, 113), listOf(112, 98), listOf(112, 97, 126), listOf(112, 128, 96))
                openings[gi / 2 % openings.size].forEach { g.play(it) }
                val rapfiIsBlack = gi % 2 == 0
                while (!g.isOver) {
                    val rapfiToMove = (g.sideToMove == BLACK) == rapfiIsBlack
                    val r = if (rapfiToMove) {
                        e().think(g.moves, 15, rule, ms, 1, AtomicBoolean(false))
                    } else {
                        var res: SearchResult? = null
                        val t = Thread(null, { res = SlowRenju.think(g.moves, rule, ms, AtomicBoolean(false)) }, "sr", SlowRenju.STACK_SIZE)
                        t.start(); t.join()
                        res!!
                    }
                    assertEquals("illegal move by ${if (rapfiToMove) "Rapfi" else "SlowRenju"} at ${g.recordString()}", null, g.play(r.move))
                }
                val rapfiColor = if (rapfiIsBlack) BLACK else WHITE
                when (g.result) { rapfiColor -> rapfiWins++; 3 -> draws++; else -> srWins++ }
                println("$rule game ${gi + 1}: Rapfi ${if (rapfiIsBlack) "black" else "white"} -> " +
                    "${if (g.result == rapfiColor) "Rapfi" else if (g.result == 3) "draw" else "SlowRenju"} wins in ${g.moves.size} moves")
            }
            println("MATCH $rule: Rapfi $rapfiWins - $srWins SlowRenju ($draws draws)")
        }
    }
}
