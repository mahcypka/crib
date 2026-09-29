package com.mlaty.cribbage.ui

/** Палитра приложения. Фон — зелёное сукно карточного стола. */
object Theme {
    /** Сукно стола. */
    const val BG = 0xFF14532D.toInt()
    /** Панели поверх сукна: то же зелёное, но темнее. */
    const val PANEL = 0xFF0B3A1C.toInt()
    const val LINE = 0xFF2C6B4A.toInt()
    const val TEXT = 0xFFF2F2EF.toInt()
    const val DIM = 0xFFA6CDB6.toInt()
    /** Акцент игрока: светлый, иначе сливается с сукном. */
    const val GREEN = 0xFF7FD89A.toInt()
    const val GREEN_D = 0xFF2E7D4F.toInt()
    const val GOLD = 0xFFF0C860.toInt()
    const val RED = 0xFFE8645A.toInt()
    const val FACE = 0xFFF7F6F1.toInt()
    const val BACK = 0xFF2B4C7E.toInt()
    const val BACK_D = 0xFF1A3459.toInt()
    const val INK = 0xFF1A1A1A.toInt()
    // Цвета категорий при подсчёте — по ним видно, за что начислено.
    const val CAT_FIFTEEN = 0xFFF0C860.toInt()
    const val CAT_PAIR = 0xFF7FD89A.toInt()
    const val CAT_RUN = 0xFF7FB8FF.toInt()
    const val CAT_FLUSH = 0xFFC79BFF.toInt()
    const val CAT_NOBS = 0xFFE8645A.toInt()
}
