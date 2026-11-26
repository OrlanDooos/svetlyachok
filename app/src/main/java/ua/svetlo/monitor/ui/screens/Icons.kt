package ua.svetlo.monitor.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Молния из логотипа «Светлячка». */
val Bolt: ImageVector by lazy {
    ImageVector.Builder("Bolt", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 2f); lineTo(4f, 14f); horizontalLineTo(11f); lineTo(10f, 22f); lineTo(19f, 10f); horizontalLineTo(12f); close()
        }
    }.build()
}

/** Перечёркнутая молния: света нет. */
val BoltOff: ImageVector by lazy {
    ImageVector.Builder("BoltOff", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 2f); lineTo(8.2f, 8.4f); lineTo(15.4f, 15.6f); lineTo(19f, 10f); horizontalLineTo(12f); close()
            moveTo(6.6f, 10.6f); lineTo(4f, 14f); horizontalLineTo(11f); lineTo(10f, 22f); lineTo(13.9f, 16.8f); close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f) {
            moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f)
        }
    }.build()
}
