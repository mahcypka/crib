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

    /**
     * Рука игрока: четыре карты плюс стартовая. Считаются все категории сразу —
     * пятнашки, пары, серии, флеш и нобс. Взаимная исключаемость серии и пары
     * действует только при розыгрыше, здесь они не мешают друг другу.
     */
    fun hand(handCards: List<Card>, starter: Card): Breakdown {
        val all = handCards + starter
        val suits = HashSet<Int>(4)
        for (c in handCards) suits.add(c.suit)
        val flush = if (suits.size == 1) (if (starter.suit in suits) 5 else 4) else 0
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
     * Серия во время розыгрыша. Считаются карты, сыгранные с последнего сброса счёта;
     * порядок их хода значения не имеет, серия считается по номиналам. Серия и пара
     * на одной карте не засчитываются одновременно: нашлась серия — пара не считается.
     */
    fun pegRun(cardsSinceReset: List<Card>): Int {
        // Серия всегда заканчивается последней сыгранной картой. Берём последние
        // три карты, сортируем по возрастанию; если соседние номиналы идут подряд —
        // это серия длиной 3. Пробуем так же 4, 5 и дальше, пока серия получается
        // и хватает карт. Порядок хода не важен: 6-7-5 это та же серия, что 5-6-7.
        val n = cardsSinceReset.size
        var len = 0
        for (k in 3..n) {
            val ranks = cardsSinceReset.subList(n - k, n).map { it.rank }.sorted()
            var consecutive = true
            for (i in 1 until ranks.size) {
                if (ranks[i] != ranks[i - 1] + 1) { consecutive = false; break }
            }
            if (!consecutive) break
            len = k
        }
        return len
    }

    /**
     * Очки за повтор номинала во время розыгрыша. Считаются только карты, стоящие
     * подряд в конце последовательности: берём последнюю и идём назад, пока номинал
     * совпадает. Первая же карта с другим номиналом останавливает отсчёт, поэтому в
     * раскладе 2-3-4-3 на четвёртом ходу двойки нет: между тройками стоит четвёрка.
     * Две карты — 2 очка, три — 6, четыре — 12. Больше четырёх не бывает: столько
     * карт одного номинала в колоде всего четыре.
     */
    fun pegPair(cardsSinceReset: List<Card>): Int {
        val n = cardsSinceReset.size
        if (n < 2) return 0
        val last = cardsSinceReset[n - 1].rank
        var same = 1
        var i = n - 2
        while (i >= 0 && same < 4 && cardsSinceReset[i].rank == last) {
            same++
            i--
        }
        // Ветка same == 1 обязательна: без неё карта, не совпавшая с предыдущей,
        // попадала в else и приносила 12 очков за каре, которого не было.
        return when (same) { 1 -> 0; 2 -> 2; 3 -> 6; else -> 12 }
    }
}
