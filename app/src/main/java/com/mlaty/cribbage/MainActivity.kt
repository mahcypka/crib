package com.mlaty.cribbage

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mlaty.cribbage.data.SaveStore
import com.mlaty.cribbage.game.Game
import com.mlaty.cribbage.game.Phase
import com.mlaty.cribbage.game.Seat
import com.mlaty.cribbage.model.Difficulty
import com.mlaty.cribbage.model.Rules
import com.mlaty.cribbage.model.WinMode
import com.mlaty.cribbage.ui.GameView
import com.mlaty.cribbage.ui.Theme
import kotlin.math.roundToInt

class MainActivity : Activity(), GameView.Host {

    private lateinit var root: FrameLayout
    private lateinit var board: GameView
    private val handler = Handler(Looper.getMainLooper())

    private var game: Game? = null
    private var rules = Rules()
    private var savedGame: Game? = null
    private var idleTicks = 0

    private val goals = listOf(
        Goal("121 очко", WinMode.SCORE, 121),
        Goal("61 очко", WinMode.SCORE, 61),
        Goal("50 очков", WinMode.SCORE, 50),
        Goal("13 раундов", WinMode.ROUNDS, 13)
    )

    private class Goal(val label: String, val mode: WinMode, val target: Int)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        board = GameView(this, this)
        setContentView(root)

        val loaded = SaveStore.load(this)
        if (loaded != null) {
            rules = loaded.rules
            savedGame = loaded.game
        }
        showMenu()
    }

    override fun onResume() {
        super.onResume()
        // onPause снимает очередь ходов компьютера — без этого партия встала бы после возврата.
        val g = game
        if (g != null && needsAi(g)) handler.postDelayed(aiLoop, 500L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(aiLoop)
        // В меню активной партии нет, но сохранённая ещё должна пережить уход из приложения.
        SaveStore.save(this, rules, game ?: savedGame)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (game != null) showMenu() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- ход компьютера

    private val aiLoop = object : Runnable {
        override fun run() {
            val g = game ?: return
            if (board.parent == null) return
            var acted = false
            when {
                g.phase == Phase.DISCARD && g.aiDiscardsLeft > 0 -> { g.aiDiscard(); acted = true }
                g.phase == Phase.PLAY && g.turn == Seat.AI -> acted = g.aiAct()
            }
            if (acted) { idleTicks = 0; refresh() } else idleTicks++
            if (needsAi(g) && idleTicks < 5) handler.postDelayed(this, 750L)
        }
    }

    private fun needsAi(g: Game) =
        (g.phase == Phase.DISCARD && g.aiDiscardsLeft > 0) ||
            (g.phase == Phase.PLAY && g.turn == Seat.AI)

    private fun afterPlayerMove() {
        val g = game ?: return
        board.clearSelection()
        refresh()
        if (needsAi(g)) handler.postDelayed(aiLoop, 650L)
    }

    // ---------------------------------------------------------------- действия игрока

    override fun onConfirmDiscard(indices: List<Int>) {
        val g = game ?: return
        if (indices.size != g.playerDiscardsLeft) {
            board.setNote("Выберите ${g.playerDiscardsLeft} карт")
            board.invalidate()
            return
        }
        for (i in indices.sortedDescending()) g.playerDiscard(i)
        afterPlayerMove()
    }

    override fun onPlayCard(index: Int) {
        val g = game ?: return
        g.playerPlay(index)
        afterPlayerMove()
    }

    override fun onSayGo() {
        val g = game ?: return
        g.playerGo()
        afterPlayerMove()
    }

    override fun onShowNext() {
        val g = game ?: return
        if (g.phase != Phase.SHOW) return
        g.advanceShow()
        afterPlayerMove()
    }

    override fun onOpenMenu() = showMenu()

    // ---------------------------------------------------------------- экраны

    private fun startGame(g: Game) {
        game = g
        root.removeAllViews()
        root.addView(board, FrameLayout.LayoutParams(MATCH, MATCH))
        idleTicks = 0
        refresh()
        if (needsAi(g)) handler.postDelayed(aiLoop, 700L)
    }

    private fun refresh() {
        val g = game ?: return
        board.game = g
        board.invalidate()
        SaveStore.save(this, rules, g)
    }

    private fun showMenu() {
        handler.removeCallbacks(aiLoop)
        // Текущая партия и есть та, которую предложит «Продолжить»: нельзя
        // подменять её старой сохранённой и затирать свежий прогресс.
        val keep = game ?: savedGame
        game = null
        savedGame = keep
        SaveStore.save(this, rules, keep)
        root.removeAllViews()
        root.addView(buildMenu(), FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun buildMenu(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Theme.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(26), dp(20), dp(28))
        scroll.addView(col)

        col.addView(label("КРИББЕЖ", 27f, Theme.TEXT, true))
        col.addView(label("Офлайн-игра против компьютера", 13f, Theme.DIM))
        col.addView(space(18))

        col.addView(label("Сложность", 14f, Theme.GOLD, true))
        col.addView(space(8))
        col.addView(chips(Difficulty.values().map { it.title }, Difficulty.values().indexOf(rules.difficulty)) { i ->
            rules = rules.copy(difficulty = Difficulty.values()[i])
            showMenu()
        })
        col.addView(space(6))
        col.addView(label(rules.difficulty.hint, 12f, Theme.DIM))

        col.addView(space(20))
        col.addView(label("Победа", 14f, Theme.GOLD, true))
        col.addView(space(8))
        val goalIndex = goals.indexOfFirst { it.mode == rules.winMode && it.target == rules.target }
            .let { if (it < 0) 0 else it }
        col.addView(chips(goals.map { it.label }, goalIndex) { i ->
            val g = goals[i]
            rules = rules.copy(winMode = g.mode, target = g.target)
            showMenu()
        })
        col.addView(space(6))
        col.addView(label("Цель: ${rules.goalText}", 12f, Theme.DIM))

        col.addView(space(20))
        col.addView(label("Дополнительные правила", 14f, Theme.GOLD, true))
        col.addView(space(8))
        col.addView(label("Лоуболл: проигрывает тот, кто первым достиг цели", 12f, Theme.TEXT))
        col.addView(chips(listOf("нет", "да"), if (rules.lowball) 1 else 0) { i ->
            rules = rules.copy(lowball = i == 1)
            showMenu()
        })
        col.addView(space(12))
        col.addView(label("Штраф за нулевой раунд: сопернику 1 очко", 12f, Theme.TEXT))
        col.addView(chips(listOf("нет", "да"), if (rules.zeroPenalty) 1 else 0) { i ->
            rules = rules.copy(zeroPenalty = i == 1)
            showMenu()
        })
        col.addView(space(12))
        col.addView(label("Бонус за идеальную руку в 29 очков", 12f, Theme.TEXT))
        col.addView(chips(listOf("нет", "+2 очка"), if (rules.bonus29 == 2) 1 else 0) { i ->
            rules = rules.copy(bonus29 = if (i == 1) 2 else 0)
            showMenu()
        })

        col.addView(space(26))
        if (savedGame != null) {
            col.addView(button("Продолжить партию", true) {
                savedGame?.let { startGame(it) }
            })
            col.addView(space(10))
        }
        col.addView(button("Новая партия", true) {
            startGame(Game(rules, System.nanoTime()))
        })
        col.addView(space(10))
        col.addView(button("Как играть", false) { showHelp() })
        col.addView(space(20))
        col.addView(label("Партия сохраняется после каждого хода и не пропадёт, если Android выгрузит приложение из памяти.", 11f, Theme.DIM))
        return scroll
    }

    private fun showHelp() {
        root.removeAllViews()
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Theme.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(26), dp(20), dp(28))
        scroll.addView(col)
        col.addView(label("Как играть", 24f, Theme.TEXT, true))
        col.addView(space(16))
        col.addView(label(HELP, 14f, Theme.TEXT))
        col.addView(space(24))
        col.addView(button("Назад", true) { showMenu() })
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- элементы меню

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = lp(width = MATCH, top = 2)
    }

    private fun space(dp: Int) = View(this).apply { layoutParams = lp(height = dp) }

    private fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (i in options.indices) {
            val c = chip(options[i], i == selected) { onPick(i) }
            c.layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                if (i > 0) marginStart = dp(8)
            }
            row.addView(c)
        }
        row.layoutParams = lp(width = MATCH, top = 8)
        return row
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(if (selected) Theme.INK else Theme.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) Theme.GREEN else Theme.PANEL)
            if (!selected) setStroke(dp(1), Theme.LINE)
        }
        setOnClickListener { onClick() }
    }

    private fun button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(if (filled) Theme.TEXT else Theme.DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12), dp(16), dp(12), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (filled) Theme.GREEN_D else Theme.PANEL)
            setStroke(dp(1), if (filled) Theme.GREEN else Theme.LINE)
        }
        setOnClickListener { onClick() }
        layoutParams = lp(width = MATCH, top = 4)
    }

    private fun lp(width: Int, height: Int = WRAP, top: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val HELP = """
Цель — набрать 121 очко раньше компьютера.

РАУНД

1. Сдача. Каждому по 6 карт, ещё 3 уходят в к crib. Из шести оставьте себе четыре, а две сбросьте в к crib — их достанет сдающий.
2. Стартовая карта. Колоду подрезают, сдающий открывает карту из нижней половины и кладёт её рядом. Если это валет — сдающему 2 очка за пятки.
3. Розыгрыш. Игроки по очереди кладут карту открытой и называют счёт вслух. Счёт не может превысить 31, тузы считаются за 1, валеты, дамы и короли — за 10. Если подходящей карты нет, говорите «го» и пропускаете ход. Последний, кто сыграл, получает 1 очко, а если счёт остановился ровно на 31 — 2 очка. Потом счёт обнуляется, и игра продолжается.
4. Подсчёт. Сначала раскрывает руку тот, кто не сдавал, потом сдающий, и в конце к crib. Каждая рука считается вместе со стартовой картой.

ЧТО ПРИНОСИТ ОЧКИ

• 15 — 2 очка за каждое сочетание карт на сумму 15.
• Пара равных карт — 2 очка, тройка — 6, четвёрка — 12.
• Серия из трёх и более карт подряд — по 1 очку за карту за каждую серию: 2-3-4 это 3 очка, а 2-3-4-5 это две серии и 7 очков.
• Флеш — 4 очка, и ещё 1, если стартовая карта той же масти. В к crib флеш засчитывается только сразу за 5 очков.
• Валет той же масти, что и стартовая карта, — 1 очко.

Максимум за руку — 29 очков. Его дают 5, 5, 5, валет и стартовая пятёрка его масти: восемь пятнадцаток, шесть пар и нобс.

ПОДСКАЗКИ

Карты, дающие 15, и валет обычно оставляют в руку, а в к crib сбрасывают то, что не жалко. Компьютер не видит ваших карт: он считает только по своим и по открытым на столе, поэтому «подсмотреть» он не может.

Игра полностью офлайн, интернет не нужен.
""".trimIndent()
    }
}
