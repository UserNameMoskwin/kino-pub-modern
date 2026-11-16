package pub.moskwin.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor

// Значки кнопок нарисованы, а не набраны символами: в шрифте телевизора нужных символов
// может не быть. Цвет берётся у кнопки, поэтому в фокусе значок темнеет вместе с текстом.

/** Закладка: контур — не добавлено, залитая — добавлено. */
@Composable
internal fun BookmarkIcon(filled: Boolean) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(width = 13.dp, height = 17.dp)) {
        val line = size.width * 0.14f
        val path = Path().apply {
            moveTo(line / 2, line / 2)
            lineTo(size.width - line / 2, line / 2)
            lineTo(size.width - line / 2, size.height - line / 2)
            lineTo(size.width / 2, size.height * 0.72f)
            lineTo(line / 2, size.height - line / 2)
            close()
        }
        if (filled) drawPath(path, color)
        drawPath(path, color, style = Stroke(line))
    }
}

/** Лицо ребёнка: голова с хохолком, глаза и улыбка. */
@Composable
internal fun ChildIcon() {
    val color = LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val line = size.width * 0.09f
        val center = Offset(size.width / 2, size.height * 0.56f)
        val radius = size.width * 0.4f
        drawCircle(color, radius, center, style = Stroke(line))
        // Хохолок на макушке.
        drawArc(
            color, startAngle = 180f, sweepAngle = 250f, useCenter = false,
            topLeft = Offset(size.width * 0.42f, 0f), size = Size(size.width * 0.24f, size.height * 0.22f),
            style = Stroke(line, cap = StrokeCap.Round),
        )
        val eye = size.width * 0.055f
        drawCircle(color, eye, Offset(center.x - radius * 0.38f, center.y - radius * 0.12f))
        drawCircle(color, eye, Offset(center.x + radius * 0.38f, center.y - radius * 0.12f))
        drawArc(
            color, startAngle = 25f, sweepAngle = 130f, useCenter = false,
            topLeft = Offset(center.x - radius * 0.5f, center.y - radius * 0.35f), size = Size(radius, radius * 0.85f),
            style = Stroke(line, cap = StrokeCap.Round),
        )
    }
}
