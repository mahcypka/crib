package com.mlaty.cribbage.ui

import com.mlaty.cribbage.model.TableStyle

/**
 * Цвета, которые зависят от сукна. Остальные (текст, золото, карты) одинаковы
 * на любом столе и лежат в [Theme] константами.
 *
 * Системные панели повторяют `statusBar`, иначе при запуске мелькает чёрная полоса.
 */
data class TableColors(
    val bg: Int,
    val panel: Int,
    val line: Int,
    val dim: Int,
    val player: Int,
    val playerD: Int,
    val statusBar: Int
)

/** Не зависят от сукна: текст, золото, масти, лицевая сторона карт. */
object Theme {
    const val TEXT = 0xFFF2F2EF.toInt()
    const val GOLD = 0xFFF0C860.toInt()
    const val RED = 0xFFE8645A.toInt()
    const val FACE = 0xFFF7F6F1.toInt()
    const val INK = 0xFF1A1A1A.toInt()
    const val BACK = 0xFF2B4C7E.toInt()
    const val BACK_D = 0xFF1A3459.toInt()
    // Цвета категорий при подсчёте — по ним видно, за что начислено.
    const val CAT_FIFTEEN = 0xFFF0C860.toInt()
    const val CAT_PAIR = 0xFF7FD89A.toInt()
    const val CAT_RUN = 0xFF7FB8FF.toInt()
    const val CAT_FLUSH = 0xFFC79BFF.toInt()
    const val CAT_NOBS = 0xFFE8645A.toInt()

    /**
     * Акцент игрока специально светлый на каждом сукне: насыщенный зелёный на
     * зелёном фоне нечитаем — это уже стоило одного перекрашивания.
     */
    fun palette(style: TableStyle): TableColors = when (style) {
        TableStyle.GREEN -> TableColors(
            bg = 0xFF14532D.toInt(), panel = 0xFF0B3A1C.toInt(), line = 0xFF2C6B4A.toInt(),
            dim = 0xFFA6CDB6.toInt(), player = 0xFF7FD89A.toInt(), playerD = 0xFF2E7D4F.toInt(),
            statusBar = 0xFF0B3A1C.toInt()
        )
        TableStyle.BLUE -> TableColors(
            bg = 0xFF123A61.toInt(), panel = 0xFF0B2440.toInt(), line = 0xFF2C628F.toInt(),
            dim = 0xFFA6C6E2.toInt(), player = 0xFF7FD8E4.toInt(), playerD = 0xFF2A7086.toInt(),
            statusBar = 0xFF0B2440.toInt()
        )
        TableStyle.BORDEAUX -> TableColors(
            bg = 0xFF5E2027.toInt(), panel = 0xFF3E1319.toInt(), line = 0xFF8C3B44.toInt(),
            dim = 0xFFE3AFB4.toInt(), player = 0xFFF0B08A.toInt(), playerD = 0xFF8A4A32.toInt(),
            statusBar = 0xFF3E1319.toInt()
        )
        TableStyle.SLATE -> TableColors(
            bg = 0xFF2B2F33.toInt(), panel = 0xFF1B1E21.toInt(), line = 0xFF4E555C.toInt(),
            dim = 0xFFB6BFC6.toInt(), player = 0xFF8FD3B0.toInt(), playerD = 0xFF3A6B52.toInt(),
            statusBar = 0xFF1B1E21.toInt()
        )
    }
}
