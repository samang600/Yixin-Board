package io.github.yixinboard.engine

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for the Rapfi engine (GPL-3.0, https://github.com/dotb1ue/rapfi) running as a child
 * process that speaks the Gomocup protocol with the Yixin-Board extensions over stdin/stdout.
 *
 * One process is kept alive between moves (weights stay loaded and the hash table is reused).
 * Searches are serialised; a cancelled search is stopped with YXSTOP and its answer consumed, so
 * the next search always starts on a clean stream. Rapfi exits by itself when stdin is closed.
 */
class RapfiEngine(
    private val executable: File,
    private val configFile: File,
) : Closeable {

    private class Proc(val process: Process, val writer: BufferedWriter, val lines: LinkedBlockingQueue<String>)

    @Volatile private var proc: Proc? = null
    private var boardSize = -1
    private var ruleId = -1

    /** Raised when the process misbehaves; callers fall back to another engine. */
    class EngineException(message: String) : IOException(message)

    companion object {
        private const val EOF = "\u0000EOF"
        private val MOVE = Regex("""^\s*(\d+)\s*,\s*(\d+)\s*$""")
        private val DEPTH = Regex("""Depth (\d+)""")
        private val EVAL = Regex("""Eval ([+-]?M?\d+)""")
        private val NODES = Regex("""Node ([\d.]+)([KMG]?)""")

        fun gomocupRule(rule: Rule) = when (rule) {
            Rule.FREESTYLE -> 0
            Rule.STANDARD -> 1
            Rule.RENJU -> 4
        }

        /** Rapfi's evaluation ("-301", "+M5", "-M12") as a score on the app's scale. */
        fun parseEval(s: String): Int = when {
            s.startsWith("+M") || s.startsWith("M") -> Engine.WIN - 100 - (s.filter { it.isDigit() }.toIntOrNull() ?: 0)
            s.startsWith("-M") -> -(Engine.WIN - 100 - (s.filter { it.isDigit() }.toIntOrNull() ?: 0))
            else -> (s.toIntOrNull() ?: 0) * 10
        }

        private fun parseNodes(m: MatchResult): Long {
            val v = m.groupValues[1].toDoubleOrNull() ?: return 0
            val mul = when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "G" -> 1e9; else -> 1.0 }
            return (v * mul).toLong()
        }
    }

    private fun ensureProcess(): Proc {
        proc?.let { if (it.process.isAlive) return it }
        val process = ProcessBuilder(executable.path, "--config", configFile.path)
            .directory(configFile.parentFile)
            .redirectErrorStream(true)
            .start()
        val lines = LinkedBlockingQueue<String>()
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { seq -> seq.forEach { lines.put(it) } }
            } catch (_: IOException) {
            } finally {
                lines.put(EOF)
            }
        }.apply { isDaemon = true; name = "rapfi-reader"; start() }
        val p = Proc(process, BufferedWriter(OutputStreamWriter(process.outputStream)), lines)
        proc = p
        boardSize = -1
        ruleId = -1
        return p
    }

    private fun send(p: Proc, vararg commands: String) {
        try {
            for (c in commands) { p.writer.write(c); p.writer.write("\n") }
            p.writer.flush()
        } catch (e: IOException) {
            throw EngineException("cannot write to Rapfi: ${e.message}")
        }
    }

    private fun poll(p: Proc, ms: Long): String? {
        val line = p.lines.poll(ms, TimeUnit.MILLISECONDS)
        if (line == EOF) throw EngineException("Rapfi exited")
        return line
    }

    /**
     * Searches the position for the side to move, for about [timeMs] (Rapfi's own time control,
     * with YXSTOP as a hard limit) or until [cancel] is set. Throws [EngineException] on failure.
     */
    @Synchronized
    fun think(
        moves: List<Int>,
        size: Int,
        rule: Rule,
        timeMs: Long,
        threads: Int,
        cancel: AtomicBoolean,
        progress: (SearchResult) -> Unit = {},
    ): SearchResult {
        try {
            return search(ensureProcess(), moves, size, rule, timeMs, threads, cancel, progress)
        } catch (e: EngineException) {
            close()
            throw e
        }
    }

    private fun search(
        p: Proc,
        moves: List<Int>,
        size: Int,
        rule: Rule,
        timeMs: Long,
        threads: Int,
        cancel: AtomicBoolean,
        progress: (SearchResult) -> Unit,
    ): SearchResult {
        p.lines.clear()
        if (size != boardSize) {
            send(p, "START $size")
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                val line = poll(p, 100) ?: if (System.currentTimeMillis() > deadline) throw EngineException("no reply to START") else continue
                if (line.trim() == "OK") break
                if (line.startsWith("ERROR")) throw EngineException(line)
            }
            boardSize = size
            ruleId = -1
        }
        val r = gomocupRule(rule)
        if (r != ruleId) { send(p, "INFO rule $r"); ruleId = r }
        send(p, "INFO thread_num $threads", "INFO timeout_match 0", "INFO timeout_turn $timeMs")

        // Stones in game order; 1 = side to move ("own"), 2 = opponent. The first stone is black.
        val toMove = moves.size % 2
        val board = ArrayList<String>(moves.size + 2)
        board.add("BOARD")
        moves.forEachIndexed { i, m -> board.add("${m % size},${m / size},${if (i % 2 == toMove) 1 else 2}") }
        board.add("DONE")
        val start = System.currentTimeMillis()
        send(p, *board.toTypedArray())

        var depth = 0
        var score = 0
        var nodes = 0L
        var stopSent = false
        var lastReport = 0L
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (!stopSent && (cancel.get() || elapsed > timeMs + 300)) {
                send(p, "YXSTOP")
                stopSent = true
            }
            if (elapsed > timeMs + 15_000) throw EngineException("Rapfi did not answer")
            val line = poll(p, 20) ?: continue
            val mv = MOVE.matchEntire(line)
            if (mv != null) {
                val x = mv.groupValues[1].toInt()
                val y = mv.groupValues[2].toInt()
                if (x !in 0 until size || y !in 0 until size) throw EngineException("bad move $line")
                return SearchResult(y * size + x, score, depth, nodes, System.currentTimeMillis() - start)
            }
            if (!line.startsWith("MESSAGE")) continue
            DEPTH.find(line)?.let { depth = it.groupValues[1].toInt() }
            EVAL.find(line)?.let { score = parseEval(it.groupValues[1]) }
            NODES.find(line)?.let { nodes = parseNodes(it) }
            if (elapsed - lastReport >= 250 && depth > 0) {
                lastReport = elapsed
                progress(SearchResult(-1, score, depth, nodes, elapsed))
            }
        }
    }

    /** Stops the process. Not synchronised, so it never waits for a running search (which then fails). */
    override fun close() {
        val p = proc ?: return
        proc = null
        try { p.writer.close() } catch (_: IOException) {}
        try {
            if (!p.process.waitFor(500, TimeUnit.MILLISECONDS)) p.process.destroyForcibly()
        } catch (_: InterruptedException) {
            p.process.destroyForcibly()
        }
    }
}
