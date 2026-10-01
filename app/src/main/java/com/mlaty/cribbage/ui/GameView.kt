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
import com.mlaty.cribbage.model.BackStyle
import com.mlaty.cribbage.model.Card
import com.mlaty.cribbage.model.FlySpeed
import com.mlaty.cribbage.model.Rules
import com.mlaty.cribbage.model.TableStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Игровое поле. Всё рисуется на c: экран узкий (336 dp по ширине на Pixel 9 Pro XL),
 * и вложенные вьюхи с фиксированными размерами тут только мешают.
 */
class GameView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onDealPick(cardId: Int)
        fun onConfirmDiscard(indices: List<Int>)
        fun onPlayCard(index: Int)
        fun onSayGo()
        fun onShowNext()
        fun onOpenMenu()
        fun onNewGame()
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
        const val A_NEWGAME = 7
        const val A_DEAL = 8
        const val TOP_BAR = 54f
        const val PEG_FADE = 1400f
    }

    /**
     * Полёт одной карты к столу. Ряды сдвигаются из раскладки до хода в раскладку
     * после него, а сама карта летит из той руки, которая её сыграла.
     */
    private class Flight(
        val opp: FloatArray, val seq: FloatArray, val hand: FloatArray,
        val from: FloatArray, val faceUp: Boolean, val startAt: Long
    )

    /** Раскладки для отрисовки: у каждой либо своя, либо промержуток между двумя. */
    private class Rows(
        val opp: FloatArray, val seq: FloatArray, val hand: FloatArray,
        val fly: FloatArray?, val flyFaceUp: Boolean
    )

    var game: Game? = null

    private val selected = ArrayList<Int>()
    private var note = ""
    private var lastSeq = -1
    private var pegAt = 0L

    // Раскладки рядов на прошлом кадре: из них анимация берёт начало полёта.
    private var prevOpp = FloatArray(0)
    private var prevSeq = FloatArray(0)
    private var prevHand = FloatArray(0)
    private var prevOppCards: List<Card> = emptyList()
    private var prevHandCards: List<Card> = emptyList()
    private var seqSig = ""
    private var flight: Flight? = null

    // Внешний вид задаётся хостом из настроек меню, а не берётся из партии:
    // оформление не относится к правилам, и смена цвета должна действовать и на сохранённую партию.
    var look: Rules = Rules()
        set(value) {
            field = value
            appliedTable = null
            applyLook(value)
            invalidate()
        }

    private var pal: TableColors = Theme.palette(TableStyle.GREEN)
    private var appliedTable: TableStyle? = null
    private var flyMs = FlySpeed.NORMAL.toFloat()
    private var backStyle = BackStyle.PLAIN

    private fun applyLook(r: Rules) {
        if (appliedTable != r.table) {
            appliedTable = r.table
            pal = Theme.palette(r.table)
        }
        flyMs = r.flyMs.toFloat()
        backStyle = r.back
    }

    /** Идёт ли анимация хода: до её конца карты не принимаются. */
    fun isBusy(): Boolean = flight != null &&
        System.currentTimeMillis() - flight!!.startAt < flyMs

    fun setNote(msg: String) {
        note = msg
        postDelayed({ note = ""; invalidate() }, 1800L)
    }

    fun clearSelection() = selected.clear()

    fun selection(): List<Int> = ArrayList(selected)

    // ---------------------------------------------------------------- сцена

    override fun onDraw(c: Canvas) {
        c.drawColor(pal.bg)
        hits.clear()
        val g = game ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)
        if (g.phase != Phase.PLAY) { flight = null; seqSig = "" }
        when (g.phase) {
            Phase.OVER -> drawGameOver(c, g, w, h)
            Phase.DEAL -> drawDeal(c, g, w, h, pad)
            Phase.DISCARD -> drawDiscard(c, g, w, h, pad)
            Phase.PLAY -> drawPlay(c, g, w, h, pad)
            Phase.SHOW -> drawShow(c, g, w, h, pad)
        }
        drawTopBar(c, g, w)
        if (flight != null) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- верхняя панель

    private fun drawTopBar(c: Canvas, g: Game, w: Float) {
        fill.color = pal.panel
        c.drawRect(0f, 0f, w, dp(TOP_BAR), fill)
        fill.color = pal.line
        c.drawRect(0f, dp(TOP_BAR) - dp(1f), w, dp(TOP_BAR), fill)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(19f)
        text.color = pal.player
        c.drawText(g.playerScore.toString(), dp(36f), dp(23f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = pal.dim
        c.drawText("ВЫ", dp(36f), dp(37f), text)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(19f)
        text.color = Theme.RED
        c.drawText(g.aiScore.toString(), w - dp(36f), dp(23f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = pal.dim
        c.drawText("КОМПЬЮТЕР", w - dp(36f), dp(37f), text)

        // Метка сдающего: к crib достаётся именно ему, а он меняется каждый раунд.
        // Пока идёт розыгрыш первого раунда, сдающего ещё нет — метка была бы враньём.
        if (g.phase != Phase.DEAL) {
            text.textSize = dp(9f)
            text.color = Theme.GOLD
            val dealerTag = if (g.aiIsDealer) "ВЫ СДАЁТЕ" else "СДАЁТ"
            val tagX = if (g.aiIsDealer) dp(46f) else w - dp(42f)
            c.drawText(dealerTag, tagX, dp(50f), text)
        }

        text.textSize = dp(12f)
        c.drawText("раунд ${g.round}", w / 2f, dp(22f), text)
        text.textSize = dp(11f)
        c.drawText(g.rules.goalText, w / 2f, dp(37f), text)

        val barW = w * 0.30f
        val left = w / 2f - barW / 2f
        val top = dp(43f)
        fill.color = pal.line
        rect.set(left, top, left + barW, top + dp(4f))
        c.drawRoundRect(rect, dp(2f), dp(2f), fill)
        val frac = (g.playerScore.toFloat() / g.rules.goalValue).coerceIn(0f, 1f)
        fill.color = pal.player
        rect.set(left, top, left + barW * frac, top + dp(4f))
        c.drawRoundRect(rect, dp(2f), dp(2f), fill)
    }

    // ---------------------------------------------------------------- розыгрыш первого раунда

    /**
     * Кто сдаёт в первом раунде, решает игрок: он выбирает карту из колоды сам,
     * компьютер берёт свою из остатка, и кто вытянул меньший номинал — получает
     * первый к crib. Сами вытянутые карты показываются крупно, а объявление о том,
     * кому к crib, живёт в строке g.announce — её потом видит и экран отбросов.
     */
    private fun drawDeal(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        val picked = g.dealPick != null
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(16f)
        text.color = Theme.TEXT
        c.drawText(
            if (picked) "Кто вытянул меньше" else "Вытяните карту из колоды",
            w / 2f, dp(TOP_BAR) + dp(34f), text
        )
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(12f)
        text.color = pal.dim
        c.drawText(
            if (picked) "Меньший номинал получает первый к crib"
            else "У кого номинал ниже, тот сдаёт и получает первый к crib",
            w / 2f, dp(TOP_BAR) + dp(56f), text
        )
        if (picked) drawDealt(c, g, w, pad) else drawDeck(c, g, w, pad)
    }

    /**
     * Колода разложена по мастям, четырьмя рядами по тринадцать карт. В один ряд
     * на таком экране поместилось бы пять карт по шесть с половиной dp шириной —
     * тапнуть в нужную было бы невозможно, а весь розыгрыш держится на одном тапе.
     */
    private fun drawDeck(c: Canvas, g: Game, w: Float, pad: Float) {
        val cards = g.deck.sortedWith(compareBy({ it.suit }, { it.rank }))
        val gap = dp(1.5f)
        val rowGap = dp(6f)
        val cellW = (w - pad * 2 - gap * 12) / 13f
        val ch = cardH(cellW)
        val top = dp(TOP_BAR) + dp(84f)
        for (i in cards.indices) {
            val x = pad + (i % 13) * (cellW + gap)
            val y = top + (i / 13) * (ch + rowGap)
            drawCard(c, x, cellW, y, cards[i], true, false, true)
            hits.add(Hit(RectF(x, y, x + cellW, y + ch), A_DEAL, cards[i].id))
        }
    }

    /** Две вытянутые карты рядом; та вытянула меньше, та обведена золотом. */
    private fun drawDealt(c: Canvas, g: Game, w: Float, pad: Float) {
        val p = g.dealPick
        val r = g.dealRival
        // При равных номиналах к crib остаётся у игрока, поэтому золотой не получает никто.
        val pA = if (p != null && r != null && p.rank < r.rank) Theme.GOLD else 0
        val rA = if (p != null && r != null && r.rank < p.rank) Theme.GOLD else 0
        val cw = dp(64f)
        val ch = cardH(cw)
        val gap = dp(30f)
        val top = dp(TOP_BAR) + dp(92f)
        val x0 = w / 2f - cw - gap / 2f
        val x1 = w / 2f + gap / 2f
        if (p != null) drawCard(c, x0, cw, top, p, true, false, true, pA)
        if (r != null) drawCard(c, x1, cw, top, r, true, false, true, rA)

        text.typeface = Typeface.DEFAULT
        text.textSize = dp(11f)
        text.color = pal.dim
        c.drawText("вы вытянули", x0 + cw / 2f, top - dp(8f), text)
        c.drawText("компьютер вытянул", x1 + cw / 2f, top - dp(8f), text)

        val ay = top + ch + dp(18f)
        fill.color = pal.panel
        rect.set(pad, ay, w - pad, ay + dp(40f))
        c.drawRoundRect(rect, dp(8f), dp(8f), fill)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(12f)
        text.color = Theme.TEXT
        c.drawText(g.announce, w / 2f, ay + dp(25f), text)
    }

    // ---------------------------------------------------------------- отброс в к crib

    private fun drawDiscard(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        drawOpponent(c, w, opponentRow(g.aiSix.size, w, pad))

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
        text.color = pal.dim
        c.drawText("Ваши карты", w / 2f, handY - dp(8f), text)
        // Кто вытянул карту ниже — видно сразу, иначе раздающий известен только
        // по подписи под рядом к crib, а она появляется лишь после первых отбросов.
        c.drawText(g.announce, w / 2f, handY - dp(26f), text)
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
        val handY = handTop(h)
        val top = dp(TOP_BAR) + dp(88f) + dp(10f)
        val tableBottom = handY - dp(48f)

        val boxH = min(dp(56f), max(dp(46f), (tableBottom - top) * 0.42f))
        val seqTop = top + boxH + dp(12f)

        val rows = animate(g,
            opponentRow(g.aiLeft.size, w, pad),
            fitRow(g.sequence.size, w - pad * 2, dp(38f), dp(4f), w / 2f, seqTop),
            fitRow(g.playerLeft.size, w - pad * 2, dp(56f), dp(5f), w / 2f, handY))

        drawOpponent(c, w, rows.opp)

        fill.color = pal.panel
        rect.set(pad, top, w - pad, top + boxH)
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(32f)
        text.color = if (g.turn == Seat.PLAYER) Theme.GOLD else pal.dim
        c.drawText(if (g.count == 0) "0" else g.count.toString(), w / 2f, top + dp(38f), text)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(10f)
        text.color = pal.dim
        c.drawText("счёт вслух", w / 2f, top + dp(50f), text)
        // Счёт за ход показываем на той же стороне, с ходившей стороны стола.
        val aiSide = g.pegSeat == Seat.AI
        drawPegBadge(c, g, if (aiSide) w - pad else pad, top + dp(6f), aiSide)

        val n = g.sequence.size
        // Пока карта летит, её место в ряду пустует — иначе она нарисовалась бы дважды.
        val flying = rows.fly != null
        val shown = if (flying) n - 1 else n
        if (shown > 0) {
            for (i in 0 until shown) {
                val accent = if (!flying && i == shown - 1) Theme.GOLD else 0
                drawCard(c, rows.seq[i * 3], rows.seq[i * 3 + 1], rows.seq[i * 3 + 2],
                    g.sequence[i], true, false, true, accent)
            }
        } else if (!flying) {
            text.textSize = dp(12f)
            text.color = pal.dim
            c.drawText("карты на столе", w / 2f, seqTop + dp(22f), text)
        }
        rows.fly?.let { fx ->
            drawCard(c, fx[0], fx[1], fx[2], g.sequence[n - 1], rows.flyFaceUp, false, true, Theme.GOLD)
        }

        val annTop = handY - dp(40f)
        drawCrib(c, g, w, annTop - dp(5f))

        fill.color = pal.panel
        rect.set(pad, annTop, w - pad, annTop + dp(32f))
        c.drawRoundRect(rect, dp(8f), dp(8f), fill)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(13f)
        text.color = Theme.TEXT
        c.drawText(g.announce, w / 2f, annTop + dp(21f), text)

        for (i in g.playerLeft.indices) {
            val can = g.turn == Seat.PLAYER && !g.newCountWaiting && !g.passWaiting &&
                g.count + Scoring.value(g.playerLeft[i].rank) <= 31
            val x = rows.hand[i * 3]
            val cw = rows.hand[i * 3 + 1]
            val top2 = rows.hand[i * 3 + 2]
            drawCard(c, x, cw, top2, g.playerLeft[i], true, can, true)
            if (can) addCardHit(x, cw, top2, A_PLAY, i)
        }

        when {
            // Пока идёт пауза перед показом, ходов уже нет — кнопка GO была бы враньём.
            g.playOver -> button(c, w / 2f - dp(90f), h - dp(106f), dp(180f), dp(48f),
                "Розыгрыш окончен", false, A_NEXT, -1)
            // Счёт закрыт и висит на паузе: ходов нет, а GO была бы враньём.
            g.newCountWaiting -> button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
                "Новый счёт", false, A_NEXT, -1)
            // Ход пропускается автоматически: показываем это, но кнопку не даём.
            g.passWaiting -> button(c, w / 2f - dp(70f), h - dp(106f), dp(140f), dp(48f),
                "GO", false, A_NEXT, -1)
            g.turn != Seat.PLAYER -> button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
                "Ход компьютера", false, A_NEXT, -1)
            g.playerHasMove() -> button(c, w / 2f - dp(80f), h - dp(106f), dp(160f), dp(48f),
                "Нажмите на карту", false, A_NEXT, -1)
            else -> button(c, w / 2f - dp(70f), h - dp(106f), dp(140f), dp(48f),
                "GO", true, A_GO, -1)
        }
    }

    // ---------------------------------------------------------------- полёт карты

    /**
     * Запускает анимацию, если на столе только что добавилась карта, и отдаёт
     * раскладки для отрисовки. Пока карта летит, ряды не прыгают на новые места,
     * а плавно сдвигаются, а сама карта идёт от руки к столу и ложится последней.
     */
    private fun animate(g: Game, opp: FloatArray, seq: FloatArray, hand: FloatArray): Rows {
        val sig = g.sequence.joinToString(",") { it.id.toString() }
        val grew = flyMs > 0f && sig.isNotEmpty() && (seqSig.isEmpty() || sig.startsWith("$seqSig,"))
        if (grew) {
            val card = g.sequence.last()
            val byAi = g.lastPegSeat == Seat.AI
            val fromCards = if (byAi) prevOppCards else prevHandCards
            val fromRow = if (byAi) prevOpp else prevHand
            val i = fromCards.indexOfFirst { it.id == card.id }
            if (i >= 0 && i * 3 + 2 < fromRow.size) {
                flight = Flight(prevOpp, prevSeq, prevHand,
                    floatArrayOf(fromRow[i * 3], fromRow[i * 3 + 1], fromRow[i * 3 + 2]),
                    !byAi, System.currentTimeMillis())
            }
        }
        seqSig = sig

        // Раскладки прошлого кадра всегда обновляем: пока идёт полёт, они нужны
        // как начало следующего, а не как то, что на самом деле нарисовано.
        prevOpp = opp; prevOppCards = g.aiLeft.toList()
        prevSeq = seq
        prevHand = hand; prevHandCards = g.playerLeft.toList()

        val f = flight ?: return Rows(opp, seq, hand, null, false)
        val t = ((System.currentTimeMillis() - f.startAt) / flyMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            flight = null
            return Rows(opp, seq, hand, null, false)
        }
        val e = ease(t)
        val last = (seq.size / 3 - 1).coerceAtLeast(0) * 3
        return Rows(slide(f.opp, opp, e), slide(f.seq, seq, e), slide(f.hand, hand, e),
            floatArrayOf(
                f.from[0] + (seq[last] - f.from[0]) * e,
                f.from[1] + (seq[last + 1] - f.from[1]) * e,
                f.from[2] + (seq[last + 2] - f.from[2]) * e
            ), f.faceUp)
    }

    /** Плавное замедление к концу полёта: карта мягко кладётся, а не останавливается рывком. */
    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    /** Промежуточная раскладка: у ряда, который стал короче, лишние слоты просто исчезают. */
    private fun slide(from: FloatArray, to: FloatArray, e: Float): FloatArray {
        if (e >= 1f) return to
        val out = FloatArray(to.size)
        for (i in to.indices) out[i] = if (i < from.size) from[i] + (to[i] - from[i]) * e else to[i]
        return out
    }

    // ---------------------------------------------------------------- подсчёт очков

    private fun drawShow(c: Canvas, g: Game, w: Float, h: Float, pad: Float) {
        drawOpponent(c, w, opponentRow(0, w, pad))

        val step = g.showStep()
        val starter = g.starter!!
        val top = dp(TOP_BAR) + dp(60f)

        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(15f)
        text.color = pal.dim
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
        text.color = pal.dim
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
            text.color = pal.dim
            c.drawText("ничего не засчитано", x0 + width / 2f, y0 + dp(16f), text)
            return y0 + rowH
        }
        if (shown < combos.size) {
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(11f)
            text.color = pal.dim
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
        fill.color = pal.panel
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
            text.color = pal.player
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
        text.color = pal.dim
        c.drawText("Вы ${g.playerScore} : ${g.aiScore} компьютер", w / 2f, h * 0.36f + dp(30f), text)
        // Две кнопки в ряд: экран узкий, 336 dp, поэтому ширина считается от него.
        val gap = dp(12f)
        val bw = (w - dp(20f) * 2 - gap) / 2f
        val by = h * 0.36f + dp(58f)
        button(c, w / 2f - bw - gap / 2f, by, bw, dp(48f), "В меню", true, A_MENU, -1)
        button(c, w / 2f + gap / 2f, by, bw, dp(48f), "Новая партия", true, A_NEWGAME, -1)
    }

    // ---------------------------------------------------------------- общие элементы

    private fun drawOpponent(c: Canvas, w: Float, xs: FloatArray) {
        fill.color = pal.panel
        c.drawRect(0f, dp(TOP_BAR), w, dp(TOP_BAR) + dp(88f), fill)
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(12f)
        text.color = pal.dim
        c.drawText("Компьютер", w / 2f, dp(TOP_BAR) + dp(18f), text)
        var i = 0
        while (i < xs.size / 3) {
            drawCard(c, xs[i * 3], xs[i * 3 + 1], xs[i * 3 + 2], null, false, false, false)
            i++
        }
    }

    /** Ряд рубашек компьютера: минимум одна, иначе строка схлопывается в точку. */
    private fun opponentRow(n: Int, w: Float, pad: Float) =
        fitRow(max(n, 1), w - pad * 2, dp(28f), dp(4f), w / 2f, dp(TOP_BAR) + dp(28f))

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
        if (g.aiIsDealer) "криб компьютера" else "криб ваш"

    /**
     * К crib рубашкой рядом со стартовой картой — как на настоящем столе.
     * Ряд привязан к низу: стартовая карта крупная и растёт вверх, на панель объявления
     * не наезжает. Подписи стоят над рядом — снизу им не поместиться.
     */
    private fun drawCrib(c: Canvas, g: Game, w: Float, bottom: Float) {
        val cw = dp(24f)
        val gap = dp(3f)
        val sw = dp(37f)
        val n = g.crib.size
        val cribW = if (n > 0) n * cw + (n - 1) * gap else 0f
        val spacer = if (n > 0) dp(12f) else 0f
        val left = w / 2f - (cribW + spacer + sw) / 2f
        // Подписи ряда стоят на одной высоте — по верху самой высокой карты, это стартовая.
        val top = bottom - cardH(sw)
        var x = left
        for (i in 0 until n) {
            drawCard(c, x, cw, bottom - cardH(cw), null, false, false, false)
            x += cw + gap
        }
        val sx = left + cribW + spacer
        g.starter?.let { drawCard(c, sx, sw, top, it, true, false, true) }

        text.typeface = Typeface.DEFAULT
        text.textSize = dp(10f)
        text.color = pal.dim
        if (n > 0) {
            text.textAlign = Paint.Align.LEFT
            c.drawText(cribOwner(g), left, top - dp(5f), text)
        }
        text.textAlign = Paint.Align.CENTER
        c.drawText("стартовая", sx + sw / 2f, top - dp(5f), text)
    }

    private fun addCardHit(x: Float, w: Float, top: Float, action: Int, index: Int) {
        val h = cardH(w)
        hits.add(Hit(RectF(x, top, x + w, top + h), action, index))
    }

    private fun button(
        c: Canvas, x: Float, y: Float, w: Float, h: Float,
        label: String, enabled: Boolean, action: Int, index: Int
    ) {
        fill.color = if (enabled) pal.playerD else pal.panel
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, dp(12f), dp(12f), fill)
        stroke.strokeWidth = dp(1.4f)
        stroke.color = if (enabled) pal.player else pal.line
        c.drawRoundRect(rect, dp(12f), dp(12f), stroke)
        text.typeface = Typeface.DEFAULT_BOLD
        text.textSize = dp(15f)
        text.color = if (enabled) Theme.TEXT else pal.dim
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
            drawBack(c, x, w, top, h, r)
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
            stroke.color = pal.player
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

    /**
     * Рубашка. Синий фон общий для всех вариантов, различается только узор —
     * иначе пришлось бы заводить ещё и палитру рубашек.
     */
    private fun drawBack(c: Canvas, x: Float, w: Float, top: Float, h: Float, r: Float) {
        val cx = x + w / 2f
        val cy = top + h / 2f
        val pad = min(w, h) * 0.16f
        val rd = min(w, h) * 0.5f - pad
        val ix0 = x + pad
        val ix1 = x + w - pad
        val iy0 = top + pad
        val iy1 = top + h - pad
        fill.color = Theme.BACK
        rect.set(x, top, x + w, top + h)
        c.drawRoundRect(rect, r, r, fill)
        fill.color = Theme.BACK_D
        when (backStyle) {
            BackStyle.PLAIN -> {
                rect.set(ix0, iy0, ix1, iy1)
                c.drawRoundRect(rect, r * 0.7f, r * 0.7f, fill)
            }
            BackStyle.DIAMOND -> {
                c.save()
                c.rotate(45f, cx, cy)
                rect.set(cx - rd, cy - rd, cx + rd, cy + rd)
                c.drawRect(rect, fill)
                c.restore()
            }
            BackStyle.LATTICE -> {
                stroke.color = Theme.BACK_D
                stroke.strokeWidth = max(dp(1f), w * 0.07f)
                val iw = ix1 - ix0
                for (k in 0..2) {
                    val a = iw * (k / 3f)
                    val b = iw * ((k + 1) / 3f)
                    c.drawLine(ix0 + a, iy0, ix0 + b, iy1, stroke)
                    c.drawLine(ix0 + a, iy1, ix0 + b, iy0, stroke)
                }
            }
            BackStyle.RING -> {
                c.drawCircle(cx, cy, rd, fill)
                fill.color = Theme.BACK
                c.drawCircle(cx, cy, rd * 0.45f, fill)
            }
        }
    }

    // ---------------------------------------------------------------- касания

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val g = game ?: return true
        // Пока карта летит, ход не принимается: иначе следующий начнётся поверх.
        if (flight != null) return true
        for (i in hits.indices.reversed()) {
            val hit = hits[i]
            if (!hit.r.contains(event.x, event.y)) continue
            when (hit.action) {
                A_DEAL -> host.onDealPick(hit.index)
                A_SELECT -> toggleSelect(hit.index, g)
                A_PLAY -> host.onPlayCard(hit.index)
                A_GO -> host.onSayGo()
                A_NEXT -> host.onShowNext()
                A_CONFIRM -> host.onConfirmDiscard(selection())
                A_MENU -> host.onOpenMenu()
                A_NEWGAME -> host.onNewGame()
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
