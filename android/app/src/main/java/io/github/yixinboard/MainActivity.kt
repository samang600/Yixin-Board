package io.github.yixinboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import io.github.yixinboard.engine.BLACK
import io.github.yixinboard.engine.Engine
import io.github.yixinboard.engine.Game
import io.github.yixinboard.engine.Rule
import io.github.yixinboard.engine.SearchResult
import io.github.yixinboard.engine.SlowRenju
import io.github.yixinboard.engine.WHITE
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        val SIZES = intArrayOf(15, 13, 17, 19, 20)
        val LEVELS_MS = longArrayOf(500, 1000, 2000, 5000, 10000, 30000)
        private const val PREFS = "yixin"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var aiBlackBtn: ToggleButton
    private lateinit var aiWhiteBtn: ToggleButton
    private lateinit var thinkBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button

    private lateinit var game: Game
    private var rule = Rule.RENJU
    private var size = 15
    private var aiBlack = false
    private var aiWhite = true
    private var levelIdx = 2
    private var showForbidden = true
    private var confirmMove = false
    /** Preferred engine: 0 = Rapfi, 1 = SlowRenju, 2 = built-in (see [effectiveEngine] for fallbacks). */
    private var engineChoice = 0

    /** When true the AI does not start on its own (after undo / stop) until the next human action. */
    private var paused = false

    private val handler = Handler(Looper.getMainLooper())
    private var thinking: Thinking? = null
    private var version = 0

    private class Thinking(
        val version: Int,
        val hint: Boolean,
        val engineName: String,
        val cancel: AtomicBoolean = AtomicBoolean(false),
    ) {
        @Volatile var discard = false
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        boardView = findViewById(R.id.board)
        statusText = findViewById(R.id.status)
        infoText = findViewById(R.id.info)
        aiBlackBtn = findViewById(R.id.btn_ai_black)
        aiWhiteBtn = findViewById(R.id.btn_ai_white)
        thinkBtn = findViewById(R.id.btn_think)
        undoBtn = findViewById(R.id.btn_undo)
        redoBtn = findViewById(R.id.btn_redo)

        loadState()

        boardView.onCellTapped = ::onCellTapped
        findViewById<Button>(R.id.btn_new).setOnClickListener {
            if (game.moves.isEmpty()) newGame() else confirm(getString(R.string.new_game_confirm)) { newGame() }
        }
        undoBtn.setOnClickListener { undo() }
        redoBtn.setOnClickListener { redo() }
        findViewById<Button>(R.id.btn_hint).setOnClickListener { hint() }
        thinkBtn.setOnClickListener { thinkOrStop() }
        aiBlackBtn.setOnCheckedChangeListener { _, checked ->
            if (aiBlack != checked) { aiBlack = checked; paused = false; afterChange() }
        }
        aiWhiteBtn.setOnCheckedChangeListener { _, checked ->
            if (aiWhite != checked) { aiWhite = checked; paused = false; afterChange() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        maybeStartAi()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) RapfiRuntime.shutdown()
    }

    override fun onPause() {
        super.onPause()
        abandonThinking()
        saveState()
    }

    // ------------------------------------------------------------------ state

    private fun loadState() {
        rule = Rule.fromId(prefs.getInt("rule", Rule.RENJU.id))
        size = prefs.getInt("size", 15).takeIf { it in SIZES } ?: 15
        aiBlack = prefs.getBoolean("aiBlack", false)
        aiWhite = prefs.getBoolean("aiWhite", true)
        levelIdx = prefs.getInt("level", 2).coerceIn(0, LEVELS_MS.size - 1)
        showForbidden = prefs.getBoolean("showForbidden", true)
        confirmMove = prefs.getBoolean("confirm", false)
        engineChoice = prefs.getInt("engine2", 0).coerceIn(0, 2)
        boardView.showNumbers = prefs.getBoolean("numbers", false)
        boardView.showCoords = prefs.getBoolean("coords", true)
        paused = prefs.getBoolean("paused", false)

        game = Game(size, rule)
        decode(prefs.getString("moves", "")).forEach { game.play(it) }
        game.setRedo(decode(prefs.getString("redo", "")))
        boardView.game = game
        aiBlackBtn.isChecked = aiBlack
        aiWhiteBtn.isChecked = aiWhite
    }

    private fun saveState() {
        prefs.edit()
            .putInt("rule", rule.id)
            .putInt("size", size)
            .putBoolean("aiBlack", aiBlack)
            .putBoolean("aiWhite", aiWhite)
            .putInt("level", levelIdx)
            .putBoolean("showForbidden", showForbidden)
            .putBoolean("confirm", confirmMove)
            .putInt("engine2", engineChoice)
            .putBoolean("numbers", boardView.showNumbers)
            .putBoolean("coords", boardView.showCoords)
            .putBoolean("paused", paused)
            .putString("moves", game.moves.joinToString(","))
            .putString("redo", game.redoMoves.joinToString(","))
            .apply()
    }

    private fun decode(s: String?): List<Int> =
        s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    // ------------------------------------------------------------------ actions

    private fun isAi(color: Int) = if (color == BLACK) aiBlack else aiWhite

    private fun onCellTapped(idx: Int) {
        if (game.isOver) return
        if (thinking?.hint == false) return // the AI is moving
        if (isAi(game.sideToMove) && !paused) return
        if (game.board.cells[idx] != 0) return
        if (game.sideToMove == BLACK && game.isForbidden(idx)) {
            toast(getString(R.string.forbidden_move))
            return
        }
        if (confirmMove && boardView.preview != idx) {
            boardView.preview = idx
            return
        }
        abandonThinking()
        game.play(idx)
        paused = false
        afterChange()
    }

    private fun newGame() {
        abandonThinking()
        game = Game(size, rule)
        boardView.game = game
        paused = false
        infoText.text = ""
        afterChange()
    }

    private fun undo() {
        abandonThinking()
        if (!game.undo()) return
        // Against the computer, go back to the human's turn.
        if (isAi(game.sideToMove) && !(aiBlack && aiWhite) && game.moves.isNotEmpty()) game.undo()
        paused = true
        infoText.text = ""
        afterChange()
    }

    private fun redo() {
        abandonThinking()
        if (!game.redo()) return
        paused = true
        afterChange()
    }

    private fun hint() {
        if (game.isOver || thinking != null) return
        startThinking(hint = true)
    }

    private fun thinkOrStop() {
        val t = thinking
        if (t != null) {
            // Stop: the engine returns its best move so far (a hint is just shown).
            t.cancel.set(true)
            if (!t.hint) paused = true
            return
        }
        if (game.isOver) return
        paused = false
        startThinking(hint = false)
    }

    /** Called after anything changes the position or the player setup. */
    private fun afterChange() {
        boardView.preview = -1
        boardView.hint = -1
        refresh()
        saveState()
        maybeStartAi()
    }

    private fun maybeStartAi() {
        if (thinking != null || paused || game.isOver) return
        if (isAi(game.sideToMove)) startThinking(hint = false)
    }

    // ------------------------------------------------------------------ engine thread

    private enum class EngineKind { RAPFI, SLOWRENJU, BUILTIN }

    /** Rapfi (arm64/x86_64 builds, any board) → SlowRenju (15×15) → built-in. */
    private fun effectiveEngine(): EngineKind = when {
        engineChoice == 0 && RapfiRuntime.isAvailable(this) -> EngineKind.RAPFI
        engineChoice <= 1 && SlowRenju.supports(size) -> EngineKind.SLOWRENJU
        else -> EngineKind.BUILTIN
    }

    private fun engineName(kind: EngineKind = effectiveEngine()) = getString(
        when (kind) {
            EngineKind.RAPFI -> R.string.engine_rapfi
            EngineKind.SLOWRENJU -> R.string.engine_slowrenju
            EngineKind.BUILTIN -> R.string.engine_builtin
        }
    )

    private fun startThinking(hint: Boolean) {
        val kind = effectiveEngine()
        val t = Thinking(++version, hint, engineName(kind))
        thinking = t
        val moves = ArrayList(game.moves)
        val side = game.sideToMove
        val gameRule = rule
        val gameSize = size
        val time = if (hint) minOf(LEVELS_MS[levelIdx], 3000L) else LEVELS_MS[levelIdx]
        refresh()
        val onProgress: (SearchResult) -> Unit = { progress ->
            handler.post { if (thinking === t && !t.discard) showEngineInfo(t.engineName, progress) }
        }
        val appContext = applicationContext
        val work = Runnable {
            val result = (if (kind == EngineKind.RAPFI) {
                RapfiRuntime.think(appContext, moves, gameSize, gameRule, time, t.cancel, onProgress)
            } else null)
                ?: (if (kind != EngineKind.BUILTIN && SlowRenju.supports(gameSize)) {
                    SlowRenju.think(moves, gameRule, time, t.cancel, onProgress)
                } else null)
                ?: Engine(gameSize, gameRule).run {
                    setPosition(moves)
                    think(side, time, t.cancel, onProgress)
                }
            handler.post { onThinkingDone(t, result) }
        }
        // Large stack: the native SlowRenju search recurses deeply.
        Thread(null, work, "yixin-engine", SlowRenju.STACK_SIZE).apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun onThinkingDone(t: Thinking, r: SearchResult) {
        if (thinking === t) thinking = null
        if (t.discard || t.version != version) {
            refresh()
            return
        }
        showEngineInfo(t.engineName, r)
        if (t.hint) {
            boardView.hint = r.move
            infoText.text = getString(R.string.hint_result, game.coord(r.move)) + "  ·  " + infoText.text
            refresh()
            return
        }
        if (game.play(r.move) != null) {
            refresh()
            return
        }
        afterChange()
    }

    private fun abandonThinking() {
        thinking?.let { it.discard = true; it.cancel.set(true) }
        thinking = null
        version++
    }

    private fun showEngineInfo(engine: String, r: SearchResult) {
        if (r.vcf) {
            infoText.text = engine + " · " + getString(R.string.engine_vcf)
            return
        }
        val eval = when {
            r.isWin -> getString(R.string.engine_win)
            r.isLoss -> getString(R.string.engine_loss)
            else -> "%+d".format(r.score / 10)
        }
        val nodes = when {
            r.nodes >= 1_000_000 -> "%.1fM".format(r.nodes / 1e6)
            r.nodes >= 1_000 -> "%dk".format(r.nodes / 1000)
            else -> r.nodes.toString()
        }
        infoText.text = engine + " · " + getString(R.string.engine_info, r.depth, eval, nodes, r.elapsedMs / 1000.0)
    }

    // ------------------------------------------------------------------ UI refresh

    private fun refresh() {
        val t = thinking
        statusText.text = when {
            game.result == BLACK -> getString(R.string.black_wins)
            game.result == WHITE -> getString(R.string.white_wins)
            game.result == 3 -> getString(R.string.draw)
            t != null && t.hint -> getString(R.string.hint_thinking)
            t != null -> getString(if (game.sideToMove == BLACK) R.string.thinking_black else R.string.thinking_white)
            else -> getString(if (game.sideToMove == BLACK) R.string.turn_black else R.string.turn_white)
        }
        title = ruleShortName(rule) + " · " + size + "×" + size + " · " + engineName()
        thinkBtn.text = getString(if (t != null) R.string.stop else R.string.play_now)
        undoBtn.isEnabled = game.moves.isNotEmpty()
        redoBtn.isEnabled = game.canRedo
        boardView.forbidden =
            if (showForbidden && rule == Rule.RENJU && game.sideToMove == BLACK && !game.isOver) game.forbiddenPoints()
            else emptyList()
        boardView.invalidate()
    }

    private fun ruleShortName(r: Rule) = getString(
        when (r) {
            Rule.FREESTYLE -> R.string.rule_short_0
            Rule.STANDARD -> R.string.rule_short_1
            Rule.RENJU -> R.string.rule_short_2
        }
    )

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_numbers).isChecked = boardView.showNumbers
        menu.findItem(R.id.menu_coords).isChecked = boardView.showCoords
        menu.findItem(R.id.menu_forbidden).isChecked = showForbidden
        menu.findItem(R.id.menu_confirm).isChecked = confirmMove
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_rule -> chooseRule()
            R.id.menu_size -> chooseSize()
            R.id.menu_level -> chooseLevel()
            R.id.menu_engine -> chooseEngine()
            R.id.menu_numbers -> boardView.showNumbers = !boardView.showNumbers
            R.id.menu_coords -> boardView.showCoords = !boardView.showCoords
            R.id.menu_forbidden -> showForbidden = !showForbidden
            R.id.menu_confirm -> { confirmMove = !confirmMove; boardView.preview = -1 }
            R.id.menu_copy -> copyRecord()
            R.id.menu_paste -> pasteRecord()
            R.id.menu_about -> AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_text) + "\n\n" + getString(R.string.about_engines) + "\n\nv" + appVersion())
                .setPositiveButton(R.string.ok, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        refresh()
        saveState()
        return true
    }

    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun chooseRule() {
        val names = arrayOf(getString(R.string.rule_freestyle), getString(R.string.rule_standard), getString(R.string.rule_renju))
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_rule)
            .setSingleChoiceItems(names, rule.id) { d, which ->
                d.dismiss()
                val newRule = Rule.fromId(which)
                if (newRule != rule) applyBoardChange { rule = newRule }
            }
            .show()
    }

    private fun chooseSize() {
        val names = SIZES.map { "$it × $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_size)
            .setSingleChoiceItems(names, SIZES.indexOf(size)) { d, which ->
                d.dismiss()
                val newSize = SIZES[which]
                if (newSize != size) applyBoardChange { size = newSize }
            }
            .show()
    }

    /** Rule and size changes restart the game; ask first if a game is in progress. */
    private fun applyBoardChange(change: () -> Unit) {
        val run = { change(); newGame() }
        if (game.moves.isEmpty() || game.isOver) run() else confirm(getString(R.string.change_needs_new_game), run)
    }

    private fun chooseEngine() {
        val names = arrayOf(
            getString(R.string.engine_rapfi_long),
            getString(R.string.engine_slowrenju_long),
            getString(R.string.engine_builtin_long),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_engine)
            .setSingleChoiceItems(names, engineChoice) { d, which ->
                engineChoice = which
                saveState()
                refresh()
                d.dismiss()
                when {
                    which == 0 && !RapfiRuntime.isAvailable(this) -> toast(getString(R.string.engine_rapfi_unsupported))
                    which == 1 && !SlowRenju.supports(size) -> toast(getString(R.string.engine_slowrenju_unsupported))
                }
            }
            .show()
    }

    private fun chooseLevel() {
        val names = LEVELS_MS.map { getString(R.string.seconds, if (it < 1000) "%.1f".format(it / 1000.0) else (it / 1000).toString()) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_level)
            .setSingleChoiceItems(names, levelIdx) { d, which ->
                levelIdx = which
                saveState()
                d.dismiss()
            }
            .show()
    }

    private fun copyRecord() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("gomoku", game.recordString()))
        toast(getString(R.string.record_copied))
    }

    private fun pasteRecord() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val moves = text?.let { Game.parseRecord(it, size) }
        if (moves == null) {
            toast(getString(R.string.record_invalid))
            return
        }
        val g = Game(size, rule)
        for (m in moves) if (g.play(m) != null) {
            toast(getString(R.string.record_invalid))
            return
        }
        abandonThinking()
        game = g
        boardView.game = g
        paused = true
        toast(getString(R.string.record_loaded, moves.size))
        afterChange()
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
