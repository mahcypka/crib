package com.mlaty.cribbage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.mlaty.cribbage.game.Breakdown
import com.mlaty.cribbage.game.Combo
import com.mlaty.cribbage.game.ComboKind
import com.mlaty.cribbage.game.Game
import com.mlaty.cribbage.game.Phase
import com.mlaty.cribbage.game.Scoring
import com.mlaty.cribbage.game.Seat
import com.mlaty.cribbage.model.Card
import kotlin.math.max
import kotlin.math.min

/**
 * Игровое поле. Всё рисуется на c: экран узкий (336 dp по ширине на Pixel 9 Pro XL),
 * и вложенные вьюхи с фиксированными размерами тут только мешают.
 */
class GameView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onConfirmDiscard(indices: List<Int>)
        fun onPlayCard(index: Int)
        fun onSayGo()
        fun onShowNext()
        fun onOpenMenu()
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val hits = ArrayList<Hit>()

    private class Hit(val r: RectF, val action: Int, val index: Int)

    companion object {
        const val A_SELECT = 1
        const val A_PLAY = 2
        const val A_GO = 3
        const val A_NEXT = 4
        const val A_CONFIRM = 5
        const val A_MENU = 6
        const val TOP_BAR = 54f
        const val PEG_FADE = 1400f
    }

    var game: Game? = null

    private val selected = ArrayList<Int>()
    private var note = ""
    private var lastSeq = -1
    private var pegAt = 0L

    fun setNote(msg: String) {
        note = msg
        postDelayed({ note = ""; invalidate() }, 1800L)
    }

    fun clearSelection() = selected.clear()

    fun selection(): List<Int> = ArrayList(selected)

    // ---------------------------------------------------------------- сцена

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.BG)
        hits.clear()
        val g = game ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)
        when (g.phase) {
            Phase.OVER -> drawGameOver(c, g, w, h)
            Phase.DISCARD -> drawDiscard(c, g, w, h, pad)
            Phase.PLAY -> drawPlay(c, g, w, h, pad)
            Phase.SHOW -> drawShow(c, g, w, h, pad)
        }
        drawTopBar(c, g, w)
    }

    // ---------------------------------------------------------------- верхняя панель

    private fun drawTopBar(c: Canvas, g: Game, w: Float) {
        fill.color = Theme.PANEL
        c.drawRect(0f, 0f, w, dp(TOP_BAR), fill)
        fill.color = Theme.LINE
        c.drawRect(0f, dp(TOP_BAR) - dp(1f), w, dp(TOP_BAR), fill)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(19f)
        text.color = Theme.GREEN
        c.drawText(g.playerScore.toString(), dp(36f), dp(23f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = Theme.DIM
        c.drawText("ВЫ", dp(36f), dp(37f), text)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(19f)
        text.color = Theme.RED
        c.drawText(g.aiScore.toString(), w - dp(36f), dp(23f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = Theme.DIM
        c.drawText("КОМПЬЮТЕР", w - dp(36f), dp(37f), text)

        // Метка сдающего: к crib достаётся именно ему, а он меняется каждый раунд.
        text.textSize = dp(9f)
        text.color = Theme.GOLD
        val dealerTag = if (g.aiIsDealer) "ВЫ СДАЁТЕ" else "СДАЁТ"
        val tagX = if (g.aiIsDealer) dp(46f) else w - dp(42f)
        c.drawText(dealerTag, tagX, dp(50f), text)

        text.textSize = dp(12f)
        c.drawText("раунд ${g.round}", w / 2f, dp(22f), text)
        text.textSize = dp(11f)
        c.drawText(g.rules.goalText, w / 2f, dp(37f), text)

        val barW = w * 0.30f
        val left = w / 2f - barW / 2f
        val top = dp(43f)
        fill.color = Theme.LINE
        rect.set(left, top, left + barW, top + dp(4f))
        c.drawRoundRect(rect, dp(2f), dp(2f), fill)
        val frac = (g.playerScore.toFloat() / g.rules.goalValue).coerceIn(0f, 1f)
        fill.color = Theme.GREEN
        rect.set(left, top, left + barW * frac, top + dp(4f))
        c.drawRoundRect(rect, dp(2f), dp(2f), fill)
    }

    // ---------------------------------------------------------------- отброс в к crib

    private fun drawDiscard(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        drawOpponent(c, g, w, pad, g.aiSix.size)

        val handY = handTop(h)
        val mid = dp(TOP_BAR) + dp(88f) + dp(8f)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(14f)
        text.color = Theme.TEXT
        c.drawText(
            "Выберите ${g.playerDiscardsLeft} карт в к crib",
            w / 2f, mid + dp(16f), text
        )

        if (g.crib.isNotEmpty()) {
            val cribTop = mid + dp(30f)
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(12f)
            text.color = Theme.GOLD
            c.drawText("${cribOwner(g)}: ${g.crib.size}", w / 2f, cribTop + dp(10f), text)
            val xs = fitRow(g.crib.size, w - pad * 2, dp(30f), dp(4f), w / 2f, cribTop + dp(18f))
            // к crib лежит рубашкой: чужие отбросы игроку показывать рано
            g.crib.indices.forEach { i ->
                drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], null, false, false, false)
            }
        }

        text.typeface = Typeface.DEFAULT
        text.textSize = dp(12f)
        text.color = Theme.DIM
        c.drawText("Ваши карты", w / 2f, handY - dp(8f), text)
        val xs = fitRow(g.playerSix.size, w - pad * 2, dp(56f), dp(5f), w / 2f, handY)
        g.playerSix.forEachIndexed { i, card ->
            val sel = selected.contains(i)
            drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], card, true, sel, true)
            addCardHit(xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], A_SELECT, i)
        }

        val ok = selected.size == g.playerDiscardsLeft && g.playerDiscardsLeft > 0
        if (note.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(12f)
            text.color = Theme.GOLD
            c.drawText(note, w / 2f, h - dp(122f), text)
        }
        button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
            if (g.playerDiscardsLeft == 2) "Сбросить 2 карты" else "Сбросить карту", ok, A_CONFIRM, -1)
    }

    // ---------------------------------------------------------------- розыгрыш до 31

    private fun drawPlay(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        drawOpponent(c, g, w, pad, g.aiLeft.size)

        val handY = handTop(h)
        val top = dp(TOP_BAR) + dp(88f) + dp(10f)
        val tableBottom = handY - dp(48f)

        val boxH = min(dp(56f), max(dp(46f), (tableBottom - top) * 0.42f))
        fill.color = Theme.PANEL
        rect.set(pad, top, w - pad, top + boxH)
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(32f)
        text.color = if (g.turn == Seat.PLAYER) Theme.GOLD else Theme.DIM
        c.drawText(if (g.count == 0) "0" else g.count.toString(), w / 2f, top + dp(38f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(10f)
        text.color = Theme.DIM
        c.drawText("счёт вслух", w / 2f, top + dp(50f), text)
        // Счёт за ход показываем на той же стороне, с ходившей стороны стола.
        val aiSide = g.pegSeat == Seat.AI
        drawPegBadge(c, g, if (aiSide) w - pad else pad, top + dp(6f), aiSide)

        val seqTop = top + boxH + dp(12f)
        if (g.sequence.isNotEmpty()) {
            val last = g.sequence.size - 1
            val xs = fitRow(g.sequence.size, w - pad * 2, dp(38f), dp(4f), w / 2f, seqTop)
            g.sequence.forEachIndexed { i, card ->
                drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], card, true, false, true,
                    if (i == last) Theme.GOLD else 0)
            }
        } else {
            text.textSize = dp(12f)
            text.color = Theme.DIM
            c.drawText("карты на столе", w / 2f, seqTop + dp(22f), text)
        }

        val annTop = handY - dp(40f)
        drawCrib(c, g, w, annTop - dp(44f))

        fill.color = Theme.PANEL
        rect.set(pad, annTop, w - pad, annTop + dp(32f))
        c.drawRoundRect(rect, dp(8f), dp(8f), fill)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(13f)
        text.color = Theme.TEXT
        c.drawText(g.announce, w / 2f, annTop + dp(21f), text)

        val xs = fitRow(g.playerLeft.size, w - pad * 2, dp(56f), dp(5f), w / 2f, handY)
        g.playerLeft.forEachIndexed { i, card ->
            val can = g.turn == Seat.PLAYER && g.count + Scoring.value(card.rank) <= 31
            drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], card, true, can, true)
            if (can) addCardHit(xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], A_PLAY, i)
        }

        when {
            g.turn != Seat.PLAYER -> button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
                "Ход компьютера", false, A_NEXT, -1)
            g.playerHasMove() -> button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
                "Нажмите на карту", false, A_NEXT, -1)
            else -> button(c, w / 2f - dp(70f), h - dp(106f), dp(140f), dp(48f),
                "Го", true, A_GO, -1)
        }
    }

    // ---------------------------------------------------------------- подсчёт очков

    private fun drawShow(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        drawOpponent(c, g, w, pad, 0)

        val step = g.showStep()
        val starter = g.starter!!
        val top = dp(TOP_BAR) + dp(60f)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(15f)
        text.color = Theme.DIM
        c.drawText(step.title, w / 2f, top + dp(12f), text)

        // Рука показана компактно: главное место на экране отдано комбинациям.
        val xs = fitRow(step.cards.size, w - pad * 2, dp(44f), dp(5f), w / 2f, top + dp(22f))
        step.cards.forEachIndexed { i, card ->
            drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], card, true, false, true)
        }
        val starterY = top + dp(22f) + dp(44f) * 1.4f + dp(6f)
        val stSize = dp(20f)
        drawCard(c, w / 2f - stSize, stSize, starterY, starter, true, false, true)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(10f)
        text.color = Theme.DIM
        c.drawText("стартовая", w / 2f, starterY + stSize * 1.4f + dp(10f), text)

        val combos = Scoring.combos(step.cards, starter, step.isCrib)
        val afterCombos = drawCombos(c, combos, pad, starterY + stSize * 1.4f + dp(18f), w - pad * 2)
        drawTotal(c, pad, afterCombos + dp(6f), w - pad * 2, step.breakdown, g.lastShowBonus)

        button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f), "Дальше", true, A_NEXT, -1)
    }

    /**
     * Засчитанные комбинации — картами, а не названиями категорий.
     * Каждая пятнашка и каждая серия показаны своей группой мини-карт, рядом —
     * очки за неё. Совпадающие по номиналу карты собраны в одну группу сразу со
     * всеми очками: пара это 2, тройка 6, четвёрка 12. Флеш и нобс тоже
     * раскладываются на карты, поэтому отдельной строкой им места не остаётся.
     * Возвращает y под последним рядом.
     */
    private fun drawCombos(
        c: Canvas, combos: List<Combo>, x0: Float, y0: Float, width: Float
    ): Float {
        val cw = dp(19f)
        val gap = dp(1.5f)
        val rowH = dp(31f)
        val maxY = y0 + dp(217f)
        var x = x0
        var y = y0
        var shown = 0
        for (cb in combos) {
            val chipW = cb.cards.size * cw + (cb.cards.size - 1) * gap + dp(8f) + dp(24f)
            if (x > x0 && x + chipW > x0 + width) { x = x0; y += rowH }
            if (y > maxY) break
            drawCombo(c, x, y, cw, gap, cb)
            x += chipW + dp(5f)
            shown++
        }
        if (shown == 0) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(12f)
            text.color = Theme.DIM
            c.drawText("ничего не засчитано", x0 + width / 2f, y0 + dp(16f), text)
            return y0 + rowH
        }
        if (shown < combos.size) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(11f)
            text.color = Theme.DIM
            c.drawText("и ещё ${combos.size - shown}", x0 + width / 2f, y + rowH - dp(6f), text)
        }
        return y + rowH
    }

    private fun drawCombo(c: Canvas, x: Float, y: Float, cw: Float, gap: Float, cb: Combo) {
        val ch = dp(26f)
        var cx = x
        for (card in cb.cards) {
            fill.color = Theme.FACE
            rect.set(cx, y, cx + cw, y + ch)
            c.drawRoundRect(rect, dp(2.5f), dp(2.5f), fill)
            val ink = if (Card.isRed(card)) Theme.RED else Theme.INK
            text.textAlign = Paint.Align.CENTER
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = cw * 0.50f
            text.color = ink
            c.drawText(Card.rankLabel(card.rank), cx + cw / 2f, y + ch * 0.50f, text)
            text.textSize = cw * 0.38f
            c.drawText(Card.suitSymbol(card.suit), cx + cw / 2f, y + ch * 0.86f, text)
            cx += cw + gap
        }
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(13f)
        text.color = comboColor(cb.kind)
        c.drawText("+${cb.points}", cx + dp(10f), y + ch * 0.70f, text)
    }

    private fun comboColor(kind: ComboKind): Int = when (kind) {
        ComboKind.FIFTEEN -> Theme.CAT_FIFTEEN
        ComboKind.PAIR -> Theme.CAT_PAIR
        ComboKind.RUN -> Theme.CAT_RUN
        ComboKind.FLUSH -> Theme.CAT_FLUSH
        ComboKind.NOBS -> Theme.CAT_NOBS
    }

    /**
     * Итог подсчёта. Все очки уже показаны картами выше, здесь только сумма.
     * Отдельной строкой идёт бонус — за 29 и за флеш.
     */
    private fun drawTotal(
        c: Canvas, x0: Float, y0: Float, width: Float, b: Breakdown, bonus: String
    ) {
        val panelH = dp(if (bonus.isEmpty()) 50f else 64f)
        fill.color = Theme.PANEL
        rect.set(x0, y0, x0 + width, y0 + panelH)
        c.drawRoundRect(rect, dp(12f), dp(12f), fill)

        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(14f)
        text.color = Theme.TEXT
        c.drawText("Всего", x0 + dp(16f), y0 + dp(32f), text)
        if (bonus.isNotEmpty()) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(10f)
            text.color = Theme.GREEN
            c.drawText(bonus, x0 + dp(16f), y0 + dp(48f), text)
        }

        text.textAlign = Paint.Align.RIGHT
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(32f)
        text.color = Theme.GOLD
        c.drawText(b.total.toString(), x0 + width - dp(16f), y0 + dp(38f), text)
        text.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- конец партии

    private fun drawGameOver(c: Canvas, g: Game, w: Float, h: Float) {
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(25f)
        text.color = Theme.GOLD
        c.drawText(g.winner, w / 2f, h * 0.36f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(15f)
        text.color = Theme.DIM
        c.drawText("Вы ${g.playerScore} : ${g.aiScore} компьютер", w / 2f, h * 0.36f + dp(30f), text)
        button(c, w / 2f - dp(80f), h * 0.36f + dp(58f), dp(160f), dp(48f), "В меню", true, A_MENU, -1)
    }

    // ---------------------------------------------------------------- общие элементы

    private fun drawOpponent(c: Canvas, g: Game, w: Float, pad: Float, cardsLeft: Int) {
        fill.color = Theme.PANEL
        c.drawRect(0f, dp(TOP_BAR), w, dp(TOP_BAR) + dp(88f), fill)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(12f)
        text.color = Theme.DIM
        c.drawText("Компьютер", w / 2f, dp(TOP_BAR) + dp(18f), text)
        val n = max(cardsLeft, 1)
        val xs = fitRow(n, w - pad * 2, dp(28f), dp(4f), w / 2f, dp(TOP_BAR) + dp(28f))
        repeat(n) { i -> drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], null, false, false, false) }
    }

    private fun handTop(h: Float) = h - dp(116f) - dp(104f) - dp(10f)

    private fun cardH(w: Float) = min(w * 1.4f, dp(88f))

    /**
     * Золотая отметка «+N» рядом со счётом: показывает, сколько только что
     * начислено за сыгранную карту, и плавно гаснет.
     */
    private fun drawPegBadge(c: Canvas, g: Game, near: Float, y: Float, anchorRight: Boolean) {
        if (g.pegSeq != lastSeq) {
            lastSeq = g.pegSeq
            pegAt = System.currentTimeMillis()
            if (g.pegPoints > 0) postDelayed({ invalidate() }, 60L)
        }
        val age = System.currentTimeMillis() - pegAt
        if (g.pegPoints <= 0 || age > PEG_FADE) return
        val alpha = (255 * (1f - age.toFloat() / PEG_FADE)).toInt().coerceIn(0, 255)
        val label = "+${g.pegPoints}"
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(18f)
        text.color = Theme.INK
        val bw = text.measureText(label) + dp(20f)
        val bh = dp(27f)
        val x = if (anchorRight) near - bw else near
        text.alpha = alpha
        fill.alpha = alpha
        fill.color = Theme.GOLD
        rect.set(x, y, x + bw, y + bh)
        c.drawRoundRect(rect, dp(13f), dp(13f), fill)
        c.drawText(label, x + bw / 2f, y + dp(19f), text)
        text.alpha = 255
        fill.alpha = 255
        text.color = Theme.TEXT
    }

    /** К crib достаётся сдающему, а сдающий меняется каждый раунд. */
    private fun cribOwner(g: Game): String =
        if (g.aiIsDealer) "к crib компьютера" else "ваш к crib"

    /**
     * К crib рубашкой рядом со стартовой картой — как на настоящем столе.
     * Подписи вынесены наверх: снизу им не поместиться, на невысоких экранах
     * они наезжали бы на панель объявления.
     */
    private fun drawCrib(c: Canvas, g: Game, w: Float, y: Float) {
        val cw = dp(24f)
        val gap = dp(3f)
        val sw = dp(28f)
        val n = g.crib.size
        val cribW = if (n > 0) n * cw + (n - 1) * gap else 0f
        val spacer = if (n > 0) dp(12f) else 0f
        val left = w / 2f - (cribW + spacer + sw) / 2f
        var x = left
        for (i in 0 until n) {
            drawCard(c, x, cw, y, null, false, false, false)
            x += cw + gap
        }
        val sx = left + cribW + spacer
        g.starter?.let { drawCard(c, sx, sw, y, it, true, false, true) }

        text.typeface = Typeface.DEFAULT
        text.textSize = dp(10f)
        text.color = Theme.DIM
        if (n > 0) {
            text.textAlign = Paint.Align.LEFT
            c.drawText(cribOwner(g), left, y - dp(5f), text)
        }
        text.textAlign = Paint.Align.CENTER
        c.drawText("стартовая", sx + sw / 2f, y - dp(5f), text)
    }

    private fun addCardHit(x: Float, w: Float, top: Float, action: Int, index: Int) {
        val h = cardH(w)
        hits.add(Hit(RectF(x, top, x + w, top + h), action, index))
    }

    private fun button(
        c: Canvas, x: Float, y: Float, w: Float, h: Float,
        label: String, enabled: Boolean, action: Int, index: Int
    ) {
        fill.color = if (enabled) Theme.GREEN_D else Theme.PANEL
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, dp(12f), dp(12f), fill)
        stroke.strokeWidth = dp(1.4f)
        stroke.color = if (enabled) Theme.GREEN else Theme.LINE
        c.drawRoundRect(rect, dp(12f), dp(12f), stroke)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(15f)
        text.color = if (enabled) Theme.TEXT else Theme.DIM
        c.drawText(label, x + w / 2f, y + h / 2f + dp(5f), text)
        if (enabled) hits.add(Hit(RectF(x, y, x + w, y + h), action, index))
    }

    /**
     * Раскладывает n карт по центру, при нехватке места ужимая их по ширине.
     * Возвращает тройки [x, ширина, верх] для каждой карты.
     */
    private fun fitRow(n: Int, avail: Float, maxW: Float, gap: Float, cx: Float, top: Float): FloatArray {
        if (n <= 0) return FloatArray(0)
        var w = maxW
        if (n * maxW + (n - 1) * gap > avail) w = (avail - (n - 1) * gap) / n
        val total = n * w + (n - 1) * gap
        var x = cx - total / 2f
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            out[i * 3] = x
            out[i * 3 + 1] = w
            out[i * 3 + 2] = top
            x += w + gap
        }
        return out
    }

    private fun drawCard(
        c: Canvas, x: Float, w: Float, top: Float, card: Card?,
        faceUp: Boolean, highlight: Boolean, selectable: Boolean,
        accent: Int = 0
    ) {
        val h = cardH(w)
        val r = dp(5f)
        if (card == null || !faceUp) {
            fill.color = Theme.BACK
            rect.set(x, top, x + w, top + h)
            c.drawRoundRect(rect, r, r, fill)
            fill.color = Theme.BACK_D
            val inset = min(w, h) * 0.16f
            rect.set(x + inset, top + inset, x + w - inset, top + h - inset)
            c.drawRoundRect(rect, r * 0.7f, r * 0.7f, fill)
        } else {
            fill.color = Theme.FACE
            rect.set(x, top, x + w, top + h)
            c.drawRoundRect(rect, r, r, fill)
            val ink = if (Card.isRed(card)) Theme.RED else Theme.INK
            text.typeface = Typeface.DEFAULT_BOLD
            text.textSize = w * 0.34f
            text.color = ink
            c.drawText(Card.rankLabel(card.rank), x + w * 0.15f, top + w * 0.40f, text)
            text.textSize = w * 0.24f
            c.drawText(Card.suitSymbol(card.suit), x + w * 0.16f, top + w * 0.63f, text)
            text.textSize = w * 0.50f
            c.drawText(Card.suitSymbol(card.suit), x + w / 2f, top + h * 0.70f, text)
            if (!selectable) {
                fill.color = 0x55000000
                rect.set(x, top, x + w, top + h)
                c.drawRoundRect(rect, r, r, fill)
            }
        }
        if (highlight) {
            stroke.color = Theme.GREEN
            stroke.strokeWidth = dp(2.4f)
            rect.set(x - dp(2f), top - dp(2f), x + w + dp(2f), top + h + dp(2f))
            c.drawRoundRect(rect, r, r, stroke)
        }
        if (accent != 0) {
            stroke.color = accent
            stroke.strokeWidth = dp(2.4f)
            rect.set(x - dp(2f), top - dp(2f), x + w + dp(2f), top + h + dp(2f))
            c.drawRoundRect(rect, r, r, stroke)
        }
    }

    // ---------------------------------------------------------------- касания

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val g = game ?: return true
        for (i in hits.indices.reversed()) {
            val hit = hits[i]
            if (!hit.r.contains(event.x, event.y)) continue
            when (hit.action) {
                A_SELECT -> toggleSelect(hit.index, g)
                A_PLAY -> host.onPlayCard(hit.index)
                A_GO -> host.onSayGo()
                A_NEXT -> host.onShowNext()
                A_CONFIRM -> host.onConfirmDiscard(selection())
                A_MENU -> host.onOpenMenu()
            }
            return true
        }
        return true
    }

    private fun toggleSelect(index: Int, g: Game) {
        if (g.phase != Phase.DISCARD) return
        if (selected.contains(index)) {
            selected.remove(index)
        } else if (selected.size < g.playerDiscardsLeft) {
            selected.add(index)
        } else {
            setNote("Уже выбрано ${g.playerDiscardsLeft} карт")
        }
        invalidate()
    }
}
