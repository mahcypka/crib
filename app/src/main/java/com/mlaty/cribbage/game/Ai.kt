package com.mlaty.cribbage.game

import com.mlaty.cribbage.model.Card
import com.mlaty.cribbage.model.Difficulty
import kotlin.random.Random

/**
 * Компьютерный соперник. Ему передаются только собственные карты и открытые карты
 * стола — карты игрока он не видит и вычислить не может.
 */
object Ai {

    // ------------------------------------------------------------ отбор в к crib

    /**
     * Выбирает две карты из шести, которые уйдут в к crib. Возвращает индексы в [0, hand.size).
     * Для оценки используется пул карт, которые компьютеру неизвестны: он не знает,
     * какая придёт стартовая и что соперник сбросит, и не имеет права угадывать точнее.
     */
    fun chooseDiscards(hand: List<Card>, pool: List<Card>, difficulty: Difficulty, rng: Random): IntArray {
        if (hand.size < 2) return IntArray(0)
        if (difficulty == Difficulty.EASY) {
            val a = rng.nextInt(hand.size)
            var b = rng.nextInt(hand.size)
            if (b == a) b = (a + 1) % hand.size
            return intArrayOf(a, b)
        }

        var best = intArrayOf(0, 1)
        var bestValue = Double.NEGATIVE_INFINITY
        for (i in 0 until hand.size - 1) for (j in i + 1 until hand.size) {
            val kept = hand.filterIndexed { h, _ -> h != i && h != j }
            val thrown = listOf(hand[i], hand[j])
            val v = if (difficulty == Difficulty.HARD)
                expectedValue(kept, thrown, pool, rng)
            else
                greedyValue(kept, thrown)
            if (v > bestValue) { bestValue = v; best = intArrayOf(i, j) }
        }
        return best
    }

    /** Дешёвая оценка: что ценного остаётся в руке плюс что уходит в к crib. */
    private fun greedyValue(kept: List<Card>, thrown: List<Card>): Double {
        var v = 0.0
        for (c in kept) v += keepValue(c, kept)
        for (c in thrown) v += cribValue(c)
        return v
    }

    /** Полная оценка: рука и к crib считаются со случайными стартовыми и отбросами соперника. */
    private fun expectedValue(kept: List<Card>, thrown: List<Card>, pool: List<Card>, rng: Random): Double {
        if (pool.size < 4) return greedyValue(kept, thrown)
        val samples = 12
        var total = 0.0
        for (s in 0 until samples) {
            val starter = pool[rng.nextInt(pool.size)]
            var v = Scoring.hand(kept, starter).total.toDouble()
            val theirs = listOf(pool[rng.nextInt(pool.size)], pool[rng.nextInt(pool.size)])
            v += Scoring.crib(thrown + theirs, starter).total
            total += v
        }
        return total / samples
    }

    /** Насколько карта ценна в руке: пары, тузы, середина колоды и соседи по рангу. */
    private fun keepValue(card: Card, kept: List<Card>): Double {
        var v = when (card.rank) {
            1 -> 3.0
            2, 3 -> 1.0
            4, 5 -> 3.5
            6, 7, 8 -> 3.0
            9, 10 -> 2.5
            else -> 1.0
        }
        for (o in kept) {
            if (o.rank == card.rank) v += 2.5
            if (Math.abs(o.rank - card.rank) == 1) v += 0.8
        }
        return v
    }

    /** В к crib фигуры выгоднее (нобс, меньше мёртвых карт), а туз — наоборот. */
    private fun cribValue(card: Card): Double = when (card.rank) {
        1 -> 0.0
        11, 12, 13 -> 2.0
        else -> 0.8
    }

    // ------------------------------------------------------------ ход до 31

    /**
     * Выбирает карту для хода. Возвращает индекс в [0, hand.size) или -1, если ходить нечем.
     * Видит только свои карты и открытую последовательность с текущим счётом.
     */
    fun choosePlay(hand: List<Card>, seq: List<Card>, count: Int, difficulty: Difficulty, rng: Random): Int {
        val legal = hand.indices.filter { count + Scoring.value(hand[it].rank) <= 31 }
        if (legal.isEmpty()) return -1

        if (difficulty == Difficulty.EASY) return legal[rng.nextInt(legal.size)]

        if (difficulty == Difficulty.HARD) {
            var best = legal[0]
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in legal) {
                val s = lookahead(i, hand, seq, count)
                if (s > bestScore) { bestScore = s; best = i }
            }
            return best
        }

        // Средний: сначала ищем немедленные очки, иначе кладём минимальную карту.
        var best = legal[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in legal) {
            val c = hand[i]
            val seq2 = seq + c
            val count2 = count + Scoring.value(c.rank)
            var s = 0.0
            if (count2 == 15) s += 4.0
            s += 2.0 * Scoring.pegPair(seq2)
            s += 1.5 * Scoring.pegRun(seq2)
            s -= 0.2 * Scoring.value(c.rank)
            if (s > bestScore) { bestScore = s; best = i }
        }
        return best
    }

    /** Оценка хода с прикидкой продолжения: сколько карт останется играбельными. */
    private fun lookahead(i: Int, hand: List<Card>, seq: List<Card>, count: Int): Double {
        val card = hand[i]
        val seq2 = seq + card
        val count2 = count + Scoring.value(card.rank)
        var s = 0.0
        if (count2 == 15) s += 4.0
        s += 2.0 * Scoring.pegPair(seq2)
        s += 1.5 * Scoring.pegRun(seq2)
        var future = 0
        for (k in hand.indices) {
            if (k != i && count2 + Scoring.value(hand[k].rank) <= 31) future++
        }
        s += 0.6 * future
        // Фигуры и тузы придерживаем: они дают нобс и длинные серии.
        if (card.rank == 1 || card.rank >= 11) s -= 1.0
        return s
    }
}
