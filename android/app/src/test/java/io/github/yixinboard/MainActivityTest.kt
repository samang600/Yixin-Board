package io.github.yixinboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.github.yixinboard.engine.WHITE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {

    private fun tap(board: BoardView, x: Int, y: Int, n: Int) {
        // Mirror BoardView's layout maths (coordinates on => 1.1 cell margin).
        val side = minOf(board.width, board.height).toFloat()
        val cell = side / (n - 1 + 2.2f)
        val left = (board.width - side) / 2f + cell * 1.1f
        val top = (board.height - side) / 2f + cell * 1.1f
        val px = left + x * cell
        val py = top + y * cell
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, px, py, 0)
        board.dispatchTouchEvent(down)
        board.dispatchTouchEvent(up)
    }

    private fun waitForAi(activity: MainActivity, status: TextView) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!status.text.contains("thinking")) return
            Thread.sleep(50)
        }
    }

    private fun screenshot(view: View, name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun humanPlaysBlackAndAiReplies() {
        RuntimeEnvironment.getApplication().getSharedPreferences("yixin", Context.MODE_PRIVATE)
            .edit().clear().putInt("level", 0).putBoolean("numbers", true).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val board = activity.findViewById<BoardView>(R.id.board)
        val status = activity.findViewById<TextView>(R.id.status)
        val info = activity.findViewById<TextView>(R.id.info)
        assertTrue(board.width > 0 && board.height > 0)
        assertEquals("Black to move", status.text.toString())

        val g = board.game!!
        // Play a few human moves; the AI (white by default) answers each.
        val human = listOf(7 to 7, 8 to 8, 6 to 8, 8 to 6)
        for ((x, y) in human) {
            if (g.isOver) break
            val idx = y * 15 + x
            if (g.board.cells[idx] != 0) continue
            tap(board, x, y, 15)
            shadowOf(Looper.getMainLooper()).idle()
            waitForAi(activity, status)
        }
        assertTrue("moves=${g.moves.size}", g.moves.size >= 6)
        assertEquals(0, g.moves.size % 2) // AI has replied to every human move
        assertTrue(g.moves.withIndex().filter { it.index % 2 == 1 }.all { g.board.cells[it.value] == WHITE })
        println("status=${status.text} info=${info.text} record=${g.recordString()}")
        screenshot(activity.window.decorView.rootView, "main_portrait.png")

        // Undo goes back two moves (to the human's turn).
        val before = g.moves.size
        activity.findViewById<View>(R.id.btn_undo).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before - 2, board.game!!.moves.size)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    fun landscapeLayoutRendersWithForbiddenMarks() {
        RuntimeEnvironment.getApplication().getSharedPreferences("yixin", Context.MODE_PRIVATE)
            .edit().clear()
            .putBoolean("aiWhite", false)
            // Black to move; h8 would make a double-three.
            .putString("moves", listOf(7 * 15 + 5, 0, 7 * 15 + 6, 14, 5 * 15 + 7, 210, 6 * 15 + 7, 224).joinToString(","))
            .commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val board = activity.findViewById<BoardView>(R.id.board)
        assertTrue("forbidden should include h8", board.forbidden.contains(7 * 15 + 7))
        screenshot(activity.window.decorView.rootView, "main_landscape.png")
    }
}
