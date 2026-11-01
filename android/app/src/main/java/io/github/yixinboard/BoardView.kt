package io.github.yixinboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.yixinboard.engine.BLACK
import io.github.yixinboard.engine.EMPTY
import io.github.yixinboard.engine.Game
import kotlin.math.min
import kotlin.math.roundToInt

class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var game: Game? = null
        set(value) { field = value; invalidate() }
    var showNumbers = false
        set(value) { field = value; invalidate() }
    var showCoords = true
        set(value) { field = value; invalidate() }
    var forbidden: List<Int> = emptyList()
        set(value) { field = value; invalidate() }
    /** Engine suggestion to highlight, or -1. */
    var hint = -1
        set(value) { field = value; invalidate() }
    /** Pending move when "tap twice to confirm" is on, or -1. */
    var preview = -1
        set(value) { field = value; invalidate() }

    var onCellTapped: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xDC, 0xB3, 0x5C) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x2A, 0x10); strokeWidth = density }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x2A, 0x10) }
    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x5A, 0x40, 0x18); textAlign = Paint.Align.CENTER
    }
    private val stonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE5, 0x39, 0x35) }
    private val forbidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xD3, 0x2F, 0x2F); strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density
    }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xE5, 0x39, 0x35); style = Paint.Style.STROKE; strokeWidth = 2.5f * density
    }

    private var cell = 0f
    private var originX = 0f
    private var originY = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardSide = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED -> h
            else -> min(w, h)
        }
        val mw = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side
        val mh = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h else side
        setMeasuredDimension(mw, mh)
    }

    private fun layoutBoard(n: Int) {
        boardSide = min(width, height).toFloat()
        boardLeft = (width - boardSide) / 2f
        boardTop = (height - boardSide) / 2f
        // Leave room for one cell of margin (coordinates live there) on each side.
        val margin = if (showCoords) 1.1f else 0.6f
        cell = boardSide / (n - 1 + 2 * margin)
        originX = boardLeft + cell * margin
        originY = boardTop + cell * margin
    }

    private fun cx(x: Int) = originX + x * cell
    private fun cy(y: Int) = originY + y * cell

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        val n = g.size
        layoutBoard(n)
        canvas.drawRoundRect(boardLeft, boardTop, boardLeft + boardSide, boardTop + boardSide, 6 * density, 6 * density, boardPaint)

        // Grid
        linePaint.strokeWidth = maxOf(1f, cell / 28f)
        for (i in 0 until n) {
            canvas.drawLine(cx(0), cy(i), cx(n - 1), cy(i), linePaint)
            canvas.drawLine(cx(i), cy(0), cx(i), cy(n - 1), linePaint)
        }
        linePaint.strokeWidth = maxOf(2f, cell / 14f)
        canvas.drawRect(cx(0), cy(0), cx(n - 1), cy(n - 1), linePaint.apply { style = Paint.Style.STROKE })
        linePaint.style = Paint.Style.FILL

        // Star points
        val stars = if (n >= 13) listOf(3, n / 2, n - 4) else listOf(n / 2)
        for (sx in stars) for (sy in stars) {
            if (n >= 13 && (sx == n / 2) != (sy == n / 2)) continue
            canvas.drawCircle(cx(sx), cy(sy), cell * 0.11f, starPaint)
        }

        // Coordinates: letters along the bottom, numbers on the left (1 at the bottom).
        if (showCoords) {
            coordPaint.textSize = cell * 0.42f
            for (i in 0 until n) {
                canvas.drawText(('A' + i).toString(), cx(i), cy(n - 1) + cell * 0.95f, coordPaint)
                canvas.drawText((n - i).toString(), cx(0) - cell * 0.75f, cy(i) + coordPaint.textSize * 0.35f, coordPaint)
            }
        }

        val r = cell * 0.46f
        // Forbidden marks
        for (idx in forbidden) {
            if (g.board.cells[idx] != EMPTY) continue
            val x = cx(idx % n); val y = cy(idx / n); val s = cell * 0.2f
            canvas.drawLine(x - s, y - s, x + s, y + s, forbidPaint)
            canvas.drawLine(x - s, y + s, x + s, y - s, forbidPaint)
        }

        // Stones
        numberPaint.textSize = cell * if (g.moves.size >= 100) 0.36f else 0.44f
        g.moves.forEachIndexed { i, idx ->
            val color = g.board.cells[idx]
            drawStone(canvas, idx % n, idx / n, color == BLACK, r, 255)
            val x = cx(idx % n); val y = cy(idx / n)
            val isLast = i == g.moves.size - 1
            if (showNumbers) {
                numberPaint.color = when {
                    isLast -> Color.rgb(0xFF, 0x52, 0x52)
                    color == BLACK -> Color.WHITE
                    else -> Color.BLACK
                }
                canvas.drawText((i + 1).toString(), x, y + numberPaint.textSize * 0.36f, numberPaint)
            } else if (isLast) {
                canvas.drawCircle(x, y, cell * 0.12f, markPaint)
            }
        }

        // Winning line
        if (g.winLine.isNotEmpty()) {
            for (idx in g.winLine) canvas.drawCircle(cx(idx % n), cy(idx / n), r * 0.92f, winPaint)
        }

        // Preview stone (tap-to-confirm) and engine hint
        if (preview >= 0 && g.board.cells[preview] == EMPTY) {
            drawStone(canvas, preview % n, preview / n, g.sideToMove == BLACK, r, 140)
        }
        if (hint >= 0 && g.board.cells[hint] == EMPTY) {
            drawStone(canvas, hint % n, hint / n, g.sideToMove == BLACK, r, 110)
            ringPaint.color = Color.rgb(0x2E, 0x7D, 0x32)
            canvas.drawCircle(cx(hint % n), cy(hint / n), r, ringPaint)
        }
    }

    private fun drawStone(canvas: Canvas, x: Int, y: Int, black: Boolean, r: Float, alpha: Int) {
        val px = cx(x); val py = cy(y)
        if (alpha == 255) canvas.drawCircle(px + r * 0.08f, py + r * 0.1f, r, shadowPaint)
        stonePaint.shader = if (black) {
            RadialGradient(px - r * 0.35f, py - r * 0.35f, r * 1.3f, Color.rgb(0x66, 0x66, 0x66), Color.rgb(0x0A, 0x0A, 0x0A), Shader.TileMode.CLAMP)
        } else {
            RadialGradient(px - r * 0.35f, py - r * 0.35f, r * 1.4f, Color.WHITE, Color.rgb(0xC8, 0xC8, 0xC0), Shader.TileMode.CLAMP)
        }
        stonePaint.alpha = alpha
        canvas.drawCircle(px, py, r, stonePaint)
        stonePaint.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = game ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                layoutBoard(g.size)
                if (cell <= 0f) return true
                val x = ((event.x - originX) / cell).roundToInt()
                val y = ((event.y - originY) / cell).roundToInt()
                if (x in 0 until g.size && y in 0 until g.size) {
                    performClick()
                    onCellTapped?.invoke(y * g.size + x)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
