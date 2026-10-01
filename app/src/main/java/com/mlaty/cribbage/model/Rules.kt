package com.mlaty.cribbage.model

enum class Difficulty(val title: String, val hint: String) {
    EASY("Лёгкий", "Играет наугад, не бережёт пары"),
    NORMAL("Средний", "Держит пары и ходит низкими картами"),
    HARD("Сложный", "Считает ходы наперёд, охотится за 15 и нобсом")
}

enum class WinMode { SCORE, ROUNDS }

/** Рисунок рубашки. Сама рубашка всегда синяя, меняется только узор. */
enum class BackStyle(val title: String) {
    PLAIN("Ровная"),
    DIAMOND("Ромб"),
    LATTICE("Сетка"),
    RING("Кольцо")
}

/** Цвет сукна. Набор цветов лежит в ui/Theme.kt — здесь только название для меню и сохранения. */
enum class TableStyle(val title: String) {
    GREEN("Зелёный"),
    BLUE("Синий"),
    BORDEAUX("Бордо"),
    SLATE("Серый")
}

/** Длительность полёта карты к столу в миллисекундах. 0 — карта кладётся мгновенно. */
object FlySpeed {
    const val NONE = 0
    const val FAST = 300
    const val NORMAL = 600
    const val SLOW = 1000
    val choices = listOf(NONE, FAST, NORMAL, SLOW)
    fun title(ms: Int): String = when (ms) {
        NONE -> "нет"
        FAST -> "0,3"
        NORMAL -> "0,6"
        else -> "1,0"
    }
}

/**
 * Пауза перед показом рук: сколько ждать после последней карты розыгрыша.
 * 0 — переходить к подсчёту сразу. Действует только на паузу, но не меньше
 * времени полёта карты, иначе последняя карта улетела бы прямо на экране показа.
 */
object ShowPause {
    const val NONE = 0
    const val SHORT = 500
    const val NORMAL = 1000
    const val LONG = 1500
    const val LONGEST = 2000
    val choices = listOf(NONE, SHORT, NORMAL, LONG, LONGEST)
    fun title(ms: Int): String = when (ms) {
        NONE -> "нет"
        SHORT -> "0,5"
        NORMAL -> "1,0"
        LONG -> "1,5"
        else -> "2,0"
    }
}

data class Rules(
    val winMode: WinMode = WinMode.SCORE,
    val target: Int = 121,
    val lowball: Boolean = false,
    val zeroPenalty: Boolean = false,
    val bonus29: Int = 2,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val flyMs: Int = FlySpeed.NORMAL,
    val showPauseMs: Int = ShowPause.NORMAL,
    val back: BackStyle = BackStyle.PLAIN,
    val table: TableStyle = TableStyle.GREEN
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
                   bonus29: Int, diff: String?, flyMs: Int, showPauseMs: Int,
                   back: String?, table: String?): Rules = Rules(
            if (mode == "ROUNDS") WinMode.ROUNDS else WinMode.SCORE,
            target, lowball, zeroPenalty, bonus29,
            when (diff) {
                "EASY" -> Difficulty.EASY
                "HARD" -> Difficulty.HARD
                else -> Difficulty.NORMAL
            },
            if (flyMs in FlySpeed.choices) flyMs else FlySpeed.NORMAL,
            if (showPauseMs in ShowPause.choices) showPauseMs else ShowPause.NORMAL,
            pick(BackStyle.values(), back, BackStyle.PLAIN),
            pick(TableStyle.values(), table, TableStyle.GREEN)
        )

        /**
         * Имя из сохранения может быть от прежней сборки или дописанной руками.
         * Неизвестное значение молча берёт вариант по умолчанию, а не роняет загрузку.
         */
        private fun <T : Enum<T>> pick(all: Array<T>, name: String?, fallback: T): T =
            all.firstOrNull { it.name == name } ?: fallback
    }
}
