package com.mlaty.cribbage.game

import com.mlaty.cribbage.model.Card

/** Разбивка очков по категориям — показывается игроку при розыгрыше. */
data class Breakdown(
    val fifteens: Int = 0,
    val pairs: Int = 0,
    val runs: Int = 0,
    val flush: Int = 0,
    val nobs: Int = 0
) {
    val total: Int get() = fifteens + pairs + runs + flush + nobs

    fun summary(): String {
        val parts = ArrayList<String>(5)
        if (fifteens > 0) parts += "15 — $fifteens"
        if (pairs > 0) parts += "пары — $pairs"
        if (runs > 0) parts += "серии — $runs"
        if (flush > 0) parts += "флеш — $flush"
        if (nobs > 0) parts += "валет — $nobs"
        return if (parts.isEmpty()) "0" else parts.joinToString("   ")
    }
}

object Scoring {

    fun value(rank: Int): Int = if (rank == 1) 1 else minOf(rank, 10)

    /** Каждое сочетание из двух и более карт на сумму 15 — 2 очка. */
    fun fifteens(cards: List<Card>): Int {
        val n = cards.size
        var combos = 0
        for (mask in 0 until (1 shl n)) {
            var bits = 0
            var sum = 0
            for (i in 0 until n) {
                if (mask and (1 shl i) != 0) { bits++; sum += value(cards[i].rank) }
            }
            if (bits >= 2 && sum == 15) combos++
        }
        return combos * 2
    }

    /** Каждая пара равных карт — 2 очка, поэтому тройка это 3 пары (6), а четвёрка 6 пар (12). */
    fun pairs(cards: List<Card>): Int {
        val counts = HashMap<Int, Int>(8)
        for (c in cards) counts[c.rank] = (counts[c.rank] ?: 0) + 1
        var p = 0
        for (n in counts.values) p += n * (n - 1) / 2
        return p * 2
    }

    /**
     * Серия — непрерывный блок различных рангов длиной от трёх. Считается столько раз,
     * сколько способов выбрать по карте каждого ранга: 2,3,4 и вторая 2 дают две серии
     * на 6 очков, а 2,2,2,3,4 — три серии на 9 очков.
     */
    fun runs(cards: List<Card>): Int {
        val counts = HashMap<Int, Int>(8)
        for (c in cards) counts[c.rank] = (counts[c.rank] ?: 0) + 1
        val ranks = counts.keys.sorted()
        var pts = 0
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            val len = j - i + 1
            if (len >= 3) {
                var ways = 1
                for (k in i..j) ways *= counts[ranks[k]] ?: 0
                pts += len * ways
            }
            i = j + 1
        }
        return pts
    }

    /** Рука игрока: четыре карты плюс стартовая. */
    fun hand(handCards: List<Card>, starter: Card): Breakdown {
        val all = handCards + starter
        val suits = HashSet<Int>(4)
        for (c in handCards) suits.add(c.suit)
        var flush = 0
        if (suits.size == 1) flush = if (starter.suit in suits) 5 else 4
        val nobs = if (handCards.any { it.rank == 11 && it.suit == starter.suit }) 1 else 0
        return Breakdown(fifteens(all), pairs(all), runs(all), flush, nobs)
    }

    /** К crib: флеш засчитывается только когда все пять карт одной масти. */
    fun crib(cribCards: List<Card>, starter: Card): Breakdown {
        val all = cribCards + starter
        val flush = if (all.all { it.suit == starter.suit }) 5 else 0
        return Breakdown(fifteens(all), pairs(all), runs(all), flush, 0)
    }

    /**
     * Серия во время розыгрыша. Считаются все карты, сыгранные с последнего сброса счёта,
     * независимо от порядка. Пара прерывает серию: через неё серия не проходит, поэтому
     * в раскладе 2-3-3-4 засчитывается только пара, а не серия из трёх.
     */
    fun pegRun(cardsSinceReset: List<Card>): Int {
        if (cardsSinceReset.isEmpty()) return 0
        val last = cardsSinceReset[cardsSinceReset.size - 1]
        var start = 0
        for (i in 0 until cardsSinceReset.size - 1) {
            if (cardsSinceReset[i].rank == cardsSinceReset[i + 1].rank) start = i + 1
        }
        val counts = HashMap<Int, Int>(8)
        for (i in start until cardsSinceReset.size) {
            val r = cardsSinceReset[i].rank
            counts[r] = (counts[r] ?: 0) + 1
        }
        if ((counts[last.rank] ?: 0) > 1) return 0
        val ranks = counts.keys.sorted()
        var lo = 0
        var hi = 0
        var found = false
        for (k in ranks.indices) {
            if (ranks[k] == last.rank) { lo = k; hi = k; found = true }
        }
        if (!found) return 0
        while (lo - 1 >= 0 && ranks[lo - 1] == ranks[lo] - 1) lo--
        while (hi + 1 < ranks.size && ranks[hi + 1] == ranks[hi] + 1) hi++
        for (k in lo..hi) if ((counts[ranks[k]] ?: 0) > 1) return 0
        val len = hi - lo + 1
        return if (len >= 3) len else 0
    }

    /** Очки за повтор ранга во время розыгрыша: вторая карта 2, третья 6, четвёртая 12. */
    fun pegPair(cardsSinceReset: List<Card>): Int {
        if (cardsSinceReset.isEmpty()) return 0
        val last = cardsSinceReset[cardsSinceReset.size - 1]
        var same = 0
        for (c in cardsSinceReset) if (c.rank == last.rank) same++
        return when (same) { 2 -> 2; 3 -> 6; 4 -> 12; else -> 0 }
    }
}
