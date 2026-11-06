package io.github.yixinboard.engine

/** A game record: board size, rule, the move list and a redo stack. */
class Game(val size: Int, val rule: Rule) {
    val board = Board(size)
    val moves = ArrayList<Int>()
    private val redoStack = ArrayList<Int>()

    /** 0 = still playing, BLACK / WHITE = winner, 3 = draw. */
    var result = 0
        private set
    var winLine: List<Int> = emptyList()
        private set

    val sideToMove get() = if (moves.size % 2 == 0) BLACK else WHITE
    val isOver get() = result != 0
    val canRedo get() = redoStack.isNotEmpty()
    val redoMoves: List<Int> get() = redoStack.asReversed()

    fun isForbidden(idx: Int): Boolean =
        rule == Rule.RENJU && board.cells[idx] == EMPTY && Rules.isForbidden(board, idx % size, idx / size)

    /** Forbidden points for black (renju only) in the current position. */
    fun forbiddenPoints(): List<Int> {
        if (rule != Rule.RENJU || isOver) return emptyList()
        return (0 until size * size).filter { isForbidden(it) }
    }

    enum class MoveError { OCCUPIED, FORBIDDEN, GAME_OVER }

    /** Plays [idx] for the side to move; returns null on success. */
    fun play(idx: Int, keepRedo: Boolean = false): MoveError? {
        if (isOver) return MoveError.GAME_OVER
        if (idx !in 0 until size * size || board.cells[idx] != EMPTY) return MoveError.OCCUPIED
        val color = sideToMove
        if (color == BLACK && isForbidden(idx)) return MoveError.FORBIDDEN
        board.cells[idx] = color
        moves.add(idx)
        if (!keepRedo) {
            if (redoStack.isNotEmpty() && redoStack.last() == idx) redoStack.removeAt(redoStack.size - 1)
            else redoStack.clear()
        }
        updateResult()
        return null
    }

    fun undo(): Boolean {
        if (moves.isEmpty()) return false
        val idx = moves.removeAt(moves.size - 1)
        board.cells[idx] = EMPTY
        redoStack.add(idx)
        updateResult()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val idx = redoStack.removeAt(redoStack.size - 1)
        play(idx, keepRedo = true)
        return true
    }

    fun setRedo(list: List<Int>) {
        redoStack.clear()
        redoStack.addAll(list.asReversed())
    }

    private fun updateResult() {
        result = 0
        winLine = emptyList()
        if (moves.isEmpty()) return
        val last = moves.last()
        val color = board.cells[last]
        Rules.winningLine(board, last % size, last / size, color, rule)?.let {
            result = color
            winLine = it
            return
        }
        if (moves.size == size * size) result = 3
    }

    // ------------------------------------------------------------------ text records

    fun coord(idx: Int): String {
        val x = idx % size
        val y = idx / size
        return "${'a' + x}${size - y}"
    }

    fun recordString(): String = moves.joinToString("") { coord(it) }

    companion object {
        private val TOKEN = Regex("([a-zA-Z])\\s*(\\d{1,2})")

        /** Parses a record such as "h8i9j10" or "h8 i9 j10" into board indices. */
        fun parseRecord(text: String, size: Int): List<Int>? {
            val out = ArrayList<Int>()
            for (m in TOKEN.findAll(text)) {
                val x = m.groupValues[1].lowercase()[0] - 'a'
                val row = m.groupValues[2].toInt()
                val y = size - row
                if (x !in 0 until size || y !in 0 until size) return null
                out.add(y * size + x)
            }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}
