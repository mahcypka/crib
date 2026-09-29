package com.mlaty.cribbage.model

/**
 * Игральная карта. rank 1..13 (1 = туз, 11 = валет, 12 = дама, 13 = король),
 * suit 0..3 (пики, черви, бубны, трефы).
 */
data class Card(val rank: Int, val suit: Int) {

    val id: Int get() = suit * 13 + rank - 1

    companion object {
        fun fromId(id: Int) = Card(id % 13 + 1, id / 13)

        fun deck(): List<Card> = (0 until 52).map { fromId(it) }

        fun rankLabel(r: Int) = when (r) {
            1 -> "A"; 11 -> "J"; 12 -> "Q"; 13 -> "K"; else -> r.toString()
        }

        fun suitSymbol(s: Int) = when (s) {
            0 -> "♠"; 1 -> "♥"; 2 -> "♦"; else -> "♣"
        }

        fun isRed(c: Card) = c.suit == 1 || c.suit == 2
    }
}
