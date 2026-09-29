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

enum class ComboKind { FIFTEEN, PAIR, RUN, FLUSH, NOBS }

/** Одна засчитанная комбинация: конкретные карты и очки за них. */
data class Combo(val cards: List<Card>, val points: Int, val kind: ComboKind)

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

    /**
     * Все засчитанные комбинации по отдельности, чтобы показать их картами,
     * а не названиями категорий. Пятнашки и пары перечислены каждая своим набором
     * карт, серия — столько раз, сколько способов выбрать по карте каждого ранга.
     * Флеш и нобс тоже раскладываются на карты: флеш — это четыре (пять) карты
     * одной масти, нобс — одна карта. Сумма combo совпадает с breakdown(...).total.
     */
    fun combos(handCards: List<Card>, starter: Card, isCrib: Boolean = false): List<Combo> {
        val all = handCards + starter
        val out = ArrayList<Combo>()
        for (mask in 0 until (1 shl all.size)) {
            var bits = 0
            var sum = 0
            val set = ArrayList<Card>()
            for (i in all.indices) {
                if (mask and (1 shl i) != 0) {
                    bits++
                    sum += value(all[i].rank)
                    set.add(all[i])
                }
            }
            if (bits >= 2 && sum == 15) out.add(Combo(set, 2, ComboKind.FIFTEEN))
        }
        // Совпадающие по номиналу карты — одна группа сразу со своими очками:
        // пара 2, тройка 6, четвёрка 12. Именно так это и объявляют за столом,
        // хотя очки те же, что при попарном счёте.
        val byRank = LinkedHashMap<Int, MutableList<Card>>()
        for (c in all) byRank.getOrPut(c.rank) { ArrayList() }.add(c)
        for (list in byRank.values) {
            if (list.size >= 2) {
                out.add(Combo(list, list.size * (list.size - 1), ComboKind.PAIR))
            }
        }
        out.addAll(runsOf(all))

        val suits = HashSet<Int>(4)
        for (c in handCards) suits.add(c.suit)
        if (suits.size == 1) {
            val five = starter.suit in suits
            when {
                five -> out.add(Combo(handCards + starter, 5, ComboKind.FLUSH))
                // Четырёхкарточный флеш в к crib не засчитывается — только пять карт одной масти.
                !isCrib -> out.add(Combo(handCards, 4, ComboKind.FLUSH))
            }
        }
        if (!isCrib) {
            for (c in handCards) {
                if (c.rank == 11 && c.suit == starter.suit) {
                    out.add(Combo(listOf(c), 1, ComboKind.NOBS))
                    break
                }
            }
        }
        return out
    }

    private fun runsOf(cards: List<Card>): List<Combo> {
        val byRank = LinkedHashMap<Int, MutableList<Card>>()
        for (c in cards) byRank.getOrPut(c.rank) { ArrayList() }.add(c)
        val ranks = byRank.keys.sorted()
        val out = ArrayList<Combo>()
        var i = 0
        while (i < ranks.size) {
            var j = i
            while (j + 1 < ranks.size && ranks[j + 1] == ranks[j] + 1) j++
            val len = j - i + 1
            if (len >= 3) {
                val picks = (i..j).map { byRank[ranks[it]]!! }
                val idx = IntArray(picks.size)
                while (true) {
                    val set = ArrayList<Card>(picks.size)
                    for (k in picks.indices) set.add(picks[k][idx[k]])
                    out.add(Combo(set, len, ComboKind.RUN))
                    var k = picks.size - 1
                    while (k >= 0) {
                        idx[k]++
                        if (idx[k] < picks[k].size) break
                        idx[k] = 0
                        k--
                    }
                    if (k < 0) break
                }
            }
            i = j + 1
        }
        return out
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
     * Серия во время розыгрыша. Считаются карты, сыгранные с последнего сброса счёта,
     * в том порядке, в каком они легли на стол. Пара обрывает серию, поэтому в раскладе
     * 2-3-3-4 засчитывается только пара, а не серия из трёх, но 3-3-4-5 серию даёт:
     * карты после пары собирают её заново.
     */
    fun pegRun(cardsSinceReset: List<Card>): Int {
        if (cardsSinceReset.isEmpty()) return 0
        // Пара обрывает серию в любом месте, а не только когда карты стоят вплотную:
        // повторившийся номинал начинает серию заново. Поэтому 3-3-4-5 даёт серию
        // из трёх, а 3-4-5-4 — только пару: последняя карта разорвала серию.
        val run = ArrayList<Int>(8)
        for (c in cardsSinceReset) {
            if (run.contains(c.rank)) run.clear()
            run.add(c.rank)
        }
        var len = 1
        while (len < run.size && run[run.size - 1 - len] == run[run.size - len] - 1) len++
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
