package com.mlaty.cribbage.model

enum class Difficulty(val title: String, val hint: String) {
    EASY("Лёгкий", "Играет наугад, не бережёт пары"),
    NORMAL("Средний", "Держит пары и ходит низкими картами"),
    HARD("Сложный", "Считает ходы наперёд, охотится за 15 и нобсом")
}

enum class WinMode { SCORE, ROUNDS }

data class Rules(
    val winMode: WinMode = WinMode.SCORE,
    val target: Int = 121,
    val lowball: Boolean = false,
    val zeroPenalty: Boolean = false,
    val bonus29: Int = 2,
    val difficulty: Difficulty = Difficulty.NORMAL
) {
    val goalText: String
        get() = when {
            lowball -> "не набрать $target"
            winMode == WinMode.SCORE -> "$target очков"
            else -> "$target раундов"
        }

    /** Порог для полоски прогресса. */
    val goalValue: Int get() = if (winMode == WinMode.SCORE) target else target * 29

    companion object {
        fun decode(mode: String?, target: Int, lowball: Boolean, zeroPenalty: Boolean,
                   bonus29: Int, diff: String?): Rules = Rules(
            if (mode == "ROUNDS") WinMode.ROUNDS else WinMode.SCORE,
            target, lowball, zeroPenalty, bonus29,
            when (diff) {
                "EASY" -> Difficulty.EASY
                "HARD" -> Difficulty.HARD
                else -> Difficulty.NORMAL
            }
        )
    }
}
