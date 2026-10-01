package com.mlaty.cribbage.game

import com.mlaty.cribbage.model.Card
import com.mlaty.cribbage.model.Rules
import com.mlaty.cribbage.model.WinMode
import kotlin.random.Random

enum class Phase { DISCARD, PLAY, SHOW, OVER }
enum class Seat { PLAYER, AI }

/** Что раскрыто на текущем шаге розыгрыша. */
data class ShowStep(
    val title: String,
    val cards: List<Card>,
    val breakdown: Breakdown,
    val seat: Seat,
    val isCrib: Boolean = false
)

/**
 * Вся партия. Все случайные числа идут от одного seed, поэтому состояние полностью
 * восстанавливается из сохранения — иначе партия рассыпалась бы после выгрузки памяти.
 */
class Game(val rules: Rules, val seed: Long) {

    /**
     * Счётчик обращений к случайности. Сохраняется вместе с партией, поэтому
     * продолжение после восстановления выдаёт ровно ту же последовательность ходов ИИ.
     */
    private var draws = 0L

    // kotlin.random.Random умеет seed только типа Int, поэтому режем Long.
    private fun rnd(): Random = Random((seed * 1000003L + (++draws)).toInt())

    var round = 1; private set
    var playerScore = 0; private set
    var aiScore = 0; private set

    var deck: MutableList<Card> = mutableListOf()
    var playerSix: MutableList<Card> = mutableListOf()
    var aiSix: MutableList<Card> = mutableListOf()
    var crib: MutableList<Card> = mutableListOf()
    var playerFour: MutableList<Card> = mutableListOf()
    var aiFour: MutableList<Card> = mutableListOf()

    var starter: Card? = null; private set
    var aiIsDealer = true; private set

    var phase = Phase.DISCARD; private set
    var playerDiscardsLeft = 2; private set
    var aiDiscardsLeft = 2; private set

    var playerLeft: MutableList<Card> = mutableListOf()
    var aiLeft: MutableList<Card> = mutableListOf()
    var sequence: MutableList<Card> = mutableListOf()
    var count = 0; private set
    var turn: Seat = Seat.PLAYER; private set
    var announce = ""; private set

    var showIndex = 0; private set
    var roundPlayerPoints = 0; private set
    var roundAiPoints = 0; private set
    var lastShowBonus = ""; private set
    var winner = ""; private set

    /**
     * Розыгрыш окончен и карты кончились, но экран показа ещё не открыт: игрок должен
     * сначала увидеть, чем закончился ход. Длительность паузы задаёт интерфейс — сама
     * модель про время не знает, иначе она перестала бы восстанавливаться из сохранения.
     * Пока флаг стоит, ходы больше не принимаются: [enterShow] переводит игру в показ.
     */
    var playOver = false; private set

    /**
     * Счёт закрыт, но партия продолжается: карты, которыми его закрыли, и сам счёт
     * остаются на экране, пока интерфейс не досчитает паузу и не вызовет
     * [enterNextCount]. Без этого стек карт исчезал бы в тот же кадр, когда
     * прилетает последняя карта, и её нельзя было бы рассмотреть.
     * Как и [playOver], живёт в сохранении: после перезапуска пауза досчитывается заново.
     */
    var newCountWaiting = false; private set

    /**
     * Ход пропускается сам, потому что карт нет. Паузу перед этим задаёт интерфейс
     * ([enterPass]): иначе «го» и очки последней карты показывались бы в один кадр
     * с ходом компьютера, и последние очки раунда не успели бы увидеть.
     */
    var passWaiting = false; private set

    /** Чей ход пропускается, пока стоит [passWaiting]. */
    var passSeat: Seat? = null; private set

    /**
     * Очки за последний сыгранный ход — для всплывающей отметки. Это чисто
     * украшение розыгрыша, поэтому в сохранение не попадает.
     */
    var pegPoints = 0; private set
    var pegLabel = ""; private set
    var pegSeat: Seat? = null; private set
    /** Растёт на каждом начислении: по нему интерфейс понимает, что значок надо показать снова. */
    var pegSeq = 0; private set

    /**
     * Кто сыграл последнюю карту. В отличие от pegSeat не обнуляется, когда ход
     * ничего не принёс: интерфейсу нужно знать, из чьей руки вести карту к столу.
     */
    var lastPegSeat: Seat? = null; private set

    private var passes = 0
    private var lastPlayedBy: Seat? = null
    private var pendingGoSeat: Seat? = null

    /** Кто первым набрал целевой счёт: партия обрывается в тот же момент. */
    private var targetHitBy: Seat? = null

    init {
        startRound()
    }

    // ---------------------------------------------------------------- раунд

    /**
     * Кто сдаёт: каждый вытягивает по карте, и чей номинал ниже — тот сдающий,
     * а значит и первый криб его. Порядок вытягивания задан [rnd], поэтому после
     * восстановления из сохранения сдающий выходит тот же. При равных номиналах
     * тянут заново — иначе пришлось бы решать костьми, а это нечестно.
     */
    private fun drawForDealer(): Pair<Seat, String> {
        val all = Card.deck()
        var p: Card
        var a: Card
        do {
            p = all.random(rnd())
            a = all.random(rnd())
        } while (p.rank == a.rank)
        val who = if (p.rank < a.rank) "ваш" else "компьютера"
        val text = "Первый криб $who: ${Card.rankLabel(p.rank)}${Card.suitSymbol(p.suit)}" +
            " против ${Card.rankLabel(a.rank)}${Card.suitSymbol(a.suit)}"
        return if (p.rank < a.rank) Seat.PLAYER to text else Seat.AI to text
    }

    private fun startRound() {
        val (dealer, dealt) = drawForDealer()
        aiIsDealer = dealer == Seat.AI
        deck = Card.deck().toMutableList()
        deck.shuffle(rnd())
        playerSix = mutableListOf()
        aiSix = mutableListOf()
        crib = mutableListOf()
        repeat(6) {
            playerSix.add(deck.removeAt(0))
            aiSix.add(deck.removeAt(0))
        }
        // Рука игрока сразу по номиналу: туз, двойка, ... король. Дальше порядок
        // сохраняется сам — отбросы и сыгранные карты просто удаляются из списка.
        playerSix.sortWith(compareBy({ it.rank }, { it.suit }))
        playerFour = mutableListOf()
        aiFour = mutableListOf()
        playerLeft = mutableListOf()
        aiLeft = mutableListOf()
        sequence = mutableListOf()
        count = 0
        passes = 0
        lastPlayedBy = null
        pendingGoSeat = null
        targetHitBy = null
        starter = null
        showIndex = 0
        roundPlayerPoints = 0
        roundAiPoints = 0
        lastShowBonus = ""
        announce = ""
        pegPoints = 0
        pegLabel = ""
        pegSeat = null
        lastPegSeat = null
        playOver = false
        newCountWaiting = false
        passWaiting = false
        passSeat = null
        playerDiscardsLeft = 2
        aiDiscardsLeft = 2
        // Ставим в самом конце: выше announce ещё обнуляется.
        announce = dealt
        phase = Phase.DISCARD
    }

    // ---------------------------------------------------------------- отбросы в к crib

    fun playerDiscard(index: Int) {
        if (phase != Phase.DISCARD || playerDiscardsLeft <= 0) return
        if (index !in playerSix.indices) return
        crib.add(playerSix.removeAt(index))
        playerDiscardsLeft--
        if (playerDiscardsLeft == 0 && aiDiscardsLeft == 0) beginPlay()
    }

    fun aiDiscard() {
        if (phase != Phase.DISCARD || aiDiscardsLeft <= 0) return
        val pick = Ai.chooseDiscards(aiSix, unknownPool(), rules.difficulty, rnd())
        for (i in pick.sortedDescending()) if (i in aiSix.indices) crib.add(aiSix.removeAt(i))
        aiDiscardsLeft = 0
        if (playerDiscardsLeft == 0) beginPlay()
    }

    private fun beginPlay() {
        playerFour = playerSix.toMutableList()
        aiFour = aiSix.toMutableList()
        // Колоду подрезают пополам, и сдающий открывает верхнюю карту нижней половины.
        val half = deck.size / 2
        deck = (deck.subList(half, deck.size) + deck.subList(0, half)).toMutableList()
        val cut = deck.removeAt(0)
        starter = cut
        if (cut.rank == 11) {
            val seat = if (aiIsDealer) Seat.AI else Seat.PLAYER
            addScore(seat, 2)
            roundPoints(seat, 2)
            announce = "Валет в отрезе — сдающему 2 очка"
        }
        playerLeft = playerFour.toMutableList()
        aiLeft = aiFour.toMutableList()
        turn = if (aiIsDealer) Seat.PLAYER else Seat.AI
        phase = Phase.PLAY
        if (announce.isEmpty()) {
            announce = if (turn == Seat.PLAYER) "Ваш ход" else "Ход компьютера"
        }
    }

    // ---------------------------------------------------------------- розыгрыш до 31

    private fun playable(c: Card) = count + Scoring.value(c.rank) <= 31

    fun playerHasMove(): Boolean = phase == Phase.PLAY && !newCountWaiting && !passWaiting && playerLeft.any { playable(it) }

    fun playerCanPlay(index: Int): Boolean =
        phase == Phase.PLAY && !newCountWaiting && !passWaiting && turn == Seat.PLAYER &&
            index in playerLeft.indices && playable(playerLeft[index])

    fun playerPlay(index: Int) {
        if (phase != Phase.PLAY || newCountWaiting || passWaiting || turn != Seat.PLAYER) return
        if (index !in playerLeft.indices || !playable(playerLeft[index])) return
        playCard(Seat.PLAYER, playerLeft.removeAt(index))
    }

    fun playerGo() {
        if (phase != Phase.PLAY || playOver || newCountWaiting || passWaiting || turn != Seat.PLAYER) return
        if (playerLeft.any { playable(it) }) return
        pass(Seat.PLAYER)
    }

    fun aiAct(): Boolean {
        if (phase != Phase.PLAY || playOver || newCountWaiting || passWaiting || turn != Seat.AI) return false
        val i = Ai.choosePlay(aiLeft, sequence, count, rules.difficulty, rnd())
        if (i < 0) {
            if (aiLeft.any { playable(it) }) return false
            pass(Seat.AI)
        } else {
            playCard(Seat.AI, aiLeft.removeAt(i))
        }
        return true
    }

    private fun playCard(seat: Seat, card: Card) {
        passes = 0
        lastPlayedBy = seat
        lastPegSeat = seat
        count += Scoring.value(card.rank)
        sequence.add(card)

        val parts = ArrayList<String>(3)
        var pts = 0
        if (count == 15) { pts += 2; parts += "15 — 2" }
        // Серия и пара на одной карте вместе не засчитываются: нашлась серия — пара не считается.
        val run = Scoring.pegRun(sequence)
        val pair = if (run > 0) 0 else Scoring.pegPair(sequence)
        if (run > 0) { pts += run; parts += "серия $run — $run" }
        if (pair > 0) {
            pts += pair
            parts += when (pair) { 2 -> "пара — 2"; 6 -> "тройка — 6"; else -> "четвёрка — 12" }
        }

        addScore(seat, pts)
        roundPoints(seat, pts)
        pegPoints = pts
        pegLabel = parts.joinToString(", ")
        pegSeat = if (pts > 0) seat else null
        pegSeq++
        announce = buildString {
            append(count)
            if (parts.isNotEmpty()) append("   ").append(parts.joinToString(", "))
        }
        turn = if (seat == Seat.PLAYER) Seat.AI else Seat.PLAYER

        if (count == 31) finishCount(2)
        else if (playerLeft.isEmpty() && aiLeft.isEmpty()) finishCount(1)
        else skipEmptyTurn()
        if (checkTarget()) return
    }

    private fun pass(seat: Seat) {
        if (lastPlayedBy != null) pendingGoSeat = lastPlayedBy
        passes++
        turn = if (seat == Seat.PLAYER) Seat.AI else Seat.PLAYER
        if (passes >= 2) {
            finishCount(if (count == 31) 2 else 1)
        } else {
            announce = "го"
            skipEmptyTurn()
        }
    }

    /** Игрок, у которого не осталось карт, не ходит — его ход пропускается сразу. */
    private fun skipEmptyTurn() {
        if (phase != Phase.PLAY || newCountWaiting || passWaiting) return
        if (turn == Seat.PLAYER && playerLeft.isEmpty()) askPass(Seat.PLAYER)
        if (phase == Phase.PLAY && !passWaiting && turn == Seat.AI && aiLeft.isEmpty()) askPass(Seat.AI)
    }

    /**
     * Автопропуск не отдаёт ход сразу: сначала «го» должно повисеть на экране,
     * и только потом ход уходит дальше. Длительность паузы задаёт интерфейс.
     */
    private fun askPass(seat: Seat) {
        passSeat = seat
        passWaiting = true
        announce = "го"
    }

    /** Пауза выдержана — ход действительно отдаётся. */
    fun enterPass() {
        val seat = passSeat ?: return
        if (phase != Phase.PLAY || !passWaiting) return
        passWaiting = false
        passSeat = null
        pass(seat)
    }

    private fun finishCount(points: Int) {
        if (points > 0) {
            val seat = if (count == 31) lastPlayedBy else (pendingGoSeat ?: lastPlayedBy)
            if (seat != null) {
                addScore(seat, points)
                roundPoints(seat, points)
                announce = if (count == 31) "31 — $points очка" else "последняя карта — $points очко"
                pegPoints = points
                pegLabel = if (count == 31) "31" else "последняя карта"
                pegSeat = seat
                pegSeq++
            }
        }
        if (playerLeft.isEmpty() && aiLeft.isEmpty()) {
            count = 0
            sequence = mutableListOf()
            passes = 0
            pendingGoSeat = null
            playOver = true
        } else {
            // Счёт закрыт, а игра продолжается: счёт и стек карт остаются на экране,
            // пока интерфейс не вызовет enterNextCount.
            newCountWaiting = true
        }
    }

    /**
     * Начало нового счёта. Стек карт и сам счёт убираются только здесь — вызвать
     * раньше нельзя, иначе карта, которой закрыли счёт, пропадёт с экрана.
     */
    fun enterNextCount() {
        if (phase != Phase.PLAY || !newCountWaiting) return
        newCountWaiting = false
        count = 0
        sequence = mutableListOf()
        passes = 0
        pendingGoSeat = null
        if (lastPlayedBy != null) turn = if (lastPlayedBy == Seat.PLAYER) Seat.AI else Seat.PLAYER
        // Ход мог достаться тому, у кого карт не осталось: пропускаем сразу,
        // иначе игрок ждал бы нажатия на GO, которого сделать нечем.
        skipEmptyTurn()
    }

    // ---------------------------------------------------------------- подсчёт очков

    /**
     * Переход к показу. Отдельный шаг, а не хвост [finishCount], потому что между
     * последней картой и подсчётом игроку нужна пауза — величину её задаёт интерфейс.
     * Партия, уже доигравшая до конца (цель набрана), показ не открывает.
     */
    fun enterShow() {
        if (!playOver || phase != Phase.PLAY) return
        playOver = false
        beginShow()
    }

    private fun beginShow() {
        phase = Phase.SHOW
        showIndex = 0
        announce = ""
    }

    fun showStep(): ShowStep {
        val st = starter!!
        val first = if (aiIsDealer) Seat.PLAYER else Seat.AI
        val second = if (first == Seat.PLAYER) Seat.AI else Seat.PLAYER
        val dealer = if (aiIsDealer) Seat.AI else Seat.PLAYER
        return when (showIndex) {
            0 -> ShowStep(name(first) + ": рука", cardsOf(first), Scoring.hand(cardsOf(first), st), first)
            1 -> ShowStep(name(second) + ": рука", cardsOf(second), Scoring.hand(cardsOf(second), st), second)
            else -> ShowStep(name(dealer) + ": к crib", crib, Scoring.crib(crib, st), dealer, true)
        }
    }

    private fun name(seat: Seat) = if (seat == Seat.PLAYER) "Вы" else "Компьютер"
    private fun cardsOf(seat: Seat) = if (seat == Seat.PLAYER) playerFour else aiFour

    fun advanceShow() {
        val step = showStep()
        var pts = step.breakdown.total
        val seat = step.seat
        var bonus = ""
        if (pts == 29 && rules.bonus29 > 0) {
            pts += rules.bonus29
            bonus = " + бонус за 29 (${rules.bonus29})"
        }
        if (pts == 0 && rules.zeroPenalty) {
            addScore(other(seat), 1)
            roundPoints(other(seat), 1)
            bonus += " + сопернику 1 за ноль"
        }
        addScore(seat, pts)
        roundPoints(seat, pts)
        lastShowBonus = bonus
        showIndex++
        if (showIndex >= 3) endRound() else checkTarget()
    }

    /**
     * Партия обрывается в тот момент, когда кто-то набрал целевой счёт,
     * а не в конце раунда. Возвращает true, если игра только что закончилась.
     */
    private fun checkTarget(): Boolean {
        val hit = targetHitBy ?: return false
        if (rules.winMode != WinMode.SCORE) return false
        phase = Phase.OVER
        // Партия кончилась на последней карте розыгрыша — показа не будет, и ждать его нельзя,
        // иначе ожидание показа будет переноситься на уже закрытую партию.
        playOver = false
        winner = when {
            rules.lowball && hit == Seat.PLAYER ->
                "Первым ${rules.target} очков набрали вы — по лоуболлу вы проиграли"
            rules.lowball -> "Первым ${rules.target} очков набрал компьютер — вы выиграли"
            hit == Seat.PLAYER -> "Вы победили"
            else -> "Победил компьютер"
        }
        return true
    }

    private fun endRound() {
        if (checkTarget()) return
        if (rules.winMode == WinMode.ROUNDS && round >= rules.target) {
            phase = Phase.OVER
            winner = when {
                playerScore > aiScore -> "Сыграно ${rules.target} раундов — победили вы"
                playerScore < aiScore -> "Сыграно ${rules.target} раундов — победил компьютер"
                else -> "Сыграно ${rules.target} раундов — ничья"
            }
            return
        }
        round++
        startRound()
    }

    // ---------------------------------------------------------------- вспомогательное

    private fun addScore(seat: Seat, pts: Int) {
        if (seat == Seat.PLAYER) playerScore += pts else aiScore += pts
        if (targetHitBy == null && rules.winMode == WinMode.SCORE) {
            val s = if (seat == Seat.PLAYER) playerScore else aiScore
            if (s >= rules.target) targetHitBy = seat
        }
    }

    private fun roundPoints(seat: Seat, pts: Int) {
        if (seat == Seat.PLAYER) roundPlayerPoints += pts else roundAiPoints += pts
    }

    private fun other(seat: Seat) = if (seat == Seat.PLAYER) Seat.AI else Seat.PLAYER

    /**
     * Карты, которые компьютеру честно не видно. Используется только при отборе в к crib:
     * это вся колода без его собственных шести карт. Карты соперника сюда входят,
     * потому что компьютер их не знает — подсматривать он не может.
     */
    fun unknownPool(): List<Card> {
        val mine = HashSet<Int>()
        for (c in aiSix) mine.add(c.id)
        return Card.deck().filter { !mine.contains(it.id) }
    }

    // ---------------------------------------------------------------- сохранение

    /** Полный слепок партии: всё, что нужно, чтобы продолжить с того же места. */
    data class State(
        val draws: Long,
        val round: Int,
        val playerScore: Int,
        val aiScore: Int,
        val deck: List<Card>,
        val playerSix: List<Card>,
        val aiSix: List<Card>,
        val crib: List<Card>,
        val playerFour: List<Card>,
        val aiFour: List<Card>,
        val starter: Card?,
        val aiIsDealer: Boolean,
        val phase: Phase,
        val playerDiscardsLeft: Int,
        val aiDiscardsLeft: Int,
        val playerLeft: List<Card>,
        val aiLeft: List<Card>,
        val sequence: List<Card>,
        val count: Int,
        val turn: Seat,
        val announce: String,
        val showIndex: Int,
        val roundPlayerPoints: Int,
        val roundAiPoints: Int,
        val lastShowBonus: String,
        val winner: String,
        val passes: Int,
        val lastPlayedBy: Seat?,
        val pendingGoSeat: Seat?,
        val targetHitBy: Seat?,
        val playOver: Boolean,
        val newCountWaiting: Boolean,
        val passWaiting: Boolean,
        val passSeat: Seat?
    )

    fun snapshot(): State = State(
        draws, round, playerScore, aiScore,
        deck.toList(), playerSix.toList(), aiSix.toList(), crib.toList(),
        playerFour.toList(), aiFour.toList(), starter, aiIsDealer, phase,
        playerDiscardsLeft, aiDiscardsLeft,
        playerLeft.toList(), aiLeft.toList(),
        sequence.toList(), count, turn, announce, showIndex,
        roundPlayerPoints, roundAiPoints,
        lastShowBonus, winner,
        passes, lastPlayedBy, pendingGoSeat, targetHitBy, playOver, newCountWaiting,
        passWaiting, passSeat
    )

    companion object {
        fun restore(rules: Rules, seed: Long, s: State): Game {
            val g = Game(rules, seed)
            g.draws = s.draws
            g.round = s.round
            g.playerScore = s.playerScore
            g.aiScore = s.aiScore
            g.deck = s.deck.toMutableList()
            g.playerSix = s.playerSix.toMutableList()
            g.aiSix = s.aiSix.toMutableList()
            g.crib = s.crib.toMutableList()
            g.playerFour = s.playerFour.toMutableList()
            g.aiFour = s.aiFour.toMutableList()
            g.starter = s.starter
            g.aiIsDealer = s.aiIsDealer
            g.phase = s.phase
            g.playerDiscardsLeft = s.playerDiscardsLeft
            g.aiDiscardsLeft = s.aiDiscardsLeft
            g.playerLeft = s.playerLeft.toMutableList()
            g.aiLeft = s.aiLeft.toMutableList()
            g.sequence = s.sequence.toMutableList()
            g.count = s.count
            g.turn = s.turn
            g.announce = s.announce
            g.showIndex = s.showIndex
            g.roundPlayerPoints = s.roundPlayerPoints
            g.roundAiPoints = s.roundAiPoints
            g.lastShowBonus = s.lastShowBonus
            g.winner = s.winner
            g.passes = s.passes
            g.lastPlayedBy = s.lastPlayedBy
            g.pendingGoSeat = s.pendingGoSeat
            g.targetHitBy = s.targetHitBy
            g.playOver = s.playOver
            g.newCountWaiting = s.newCountWaiting
            g.passWaiting = s.passWaiting
            g.passSeat = s.passSeat
            return g
        }
    }
}
