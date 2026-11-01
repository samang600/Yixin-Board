package io.github.yixinboard.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The SlowRenju engine (C++, GPL-3.0, by Tianyi Hao) running through JNI.
 *
 * The native engine is compiled for 15x15 only and keeps global state, so searches are serialised
 * in native code; [think] must be called from a thread with a large stack (see [STACK_SIZE]).
 */
object SlowRenju {
    /** The native search recurses deeply with ~6 KB frames. */
    const val STACK_SIZE = 16L * 1024 * 1024
    const val BOARD_SIZE = 15
    /** SlowRenju's win score. */
    private const val SR_WIN = 15000

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("slowrenju")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun supports(size: Int) = isAvailable && size == BOARD_SIZE

    @JvmStatic private external fun nativeThink(moves: IntArray, rule: Int, nodeLimit: Long): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeStatus(): LongArray

    /**
     * Searches until [timeMs] elapses or [cancel] is set, then returns the best move found.
     * Falls back to the built-in [Engine] if the native engine returns an unusable move.
     */
    fun think(
        moves: List<Int>,
        rule: Rule,
        timeMs: Long,
        cancel: AtomicBoolean,
        progress: (SearchResult) -> Unit = {},
    ): SearchResult {
        val start = System.nanoTime()
        val elapsed = { (System.nanoTime() - start) / 1_000_000L }
        val done = AtomicBoolean(false)
        // The native call blocks, so a watchdog enforces the time limit and cancellation. It keeps
        // re-sending the stop request, which also covers a stop issued while the search was queued.
        val watchdog = Thread {
            var lastReport = 0L
            while (!done.get()) {
                val t = elapsed()
                if (cancel.get() || t >= timeMs) nativeStop()
                else if (t - lastReport >= 250) {
                    lastReport = t
                    progress(statusResult(-1, t))
                }
                try { Thread.sleep(15) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "slowrenju-watchdog"; start() }

        val move = try {
            nativeThink(moves.toIntArray(), rule.id, 0L)
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
        val result = statusResult(move, elapsed())
        if (isLegal(moves, rule, move)) return result

        // Should not happen; never let the game get stuck on a bad engine answer.
        val engine = Engine(BOARD_SIZE, rule)
        engine.setPosition(moves)
        val side = if (moves.size % 2 == 0) BLACK else WHITE
        return engine.think(side, 200, AtomicBoolean(false))
    }

    private fun statusResult(move: Int, elapsedMs: Long): SearchResult {
        val s = nativeStatus()
        val eval = s[2].toInt()
        val score = when {
            eval >= SR_WIN -> Engine.WIN - 100
            eval <= -SR_WIN -> -(Engine.WIN - 100)
            else -> eval * 10
        }
        return SearchResult(move, score, s[1].toInt(), s[0], elapsedMs)
    }

    private fun isLegal(moves: List<Int>, rule: Rule, move: Int): Boolean {
        if (move !in 0 until BOARD_SIZE * BOARD_SIZE || move in moves) return false
        if (rule != Rule.RENJU || moves.size % 2 != 0) return true
        val b = Board(BOARD_SIZE)
        moves.forEachIndexed { i, m -> b.cells[m] = if (i % 2 == 0) BLACK else WHITE }
        return !Rules.isForbidden(b, move % BOARD_SIZE, move / BOARD_SIZE)
    }
}
