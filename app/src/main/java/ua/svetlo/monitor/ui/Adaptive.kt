package ua.svetlo.monitor.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WidthClass { Compact, Medium, Expanded }

/**
 * Размер окна по правилам Material: до 600 dp телефон, 600–840 dp большой телефон, складной или малый планшет,
 * от 840 dp планшет или десктоп. Высота меньше 480 dp означает телефон в альбомной ориентации.
 */
@Immutable
data class WindowLayout(val width: Dp, val height: Dp) {
    val widthClass = when {
        width < 600.dp -> WidthClass.Compact
        width < 840.dp -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
    val shortHeight = height < 480.dp

    /** Навигация сбоку вместо нижней панели. */
    val useRail = widthClass != WidthClass.Compact || shortHeight

    /** Два столбца контента. */
    val twoPane = widthClass == WidthClass.Expanded || (widthClass == WidthClass.Medium && shortHeight)

    val gutter: Dp = when (widthClass) {
        WidthClass.Compact -> 16.dp
        WidthClass.Medium -> 24.dp
        WidthClass.Expanded -> 32.dp
    }
}

val LocalWindow = staticCompositionLocalOf { WindowLayout(390.dp, 844.dp) }
