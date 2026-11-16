package pub.moskwin.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme

/*
 * Оформление «жидкое стекло»: полупрозрачные поверхности со светлой кромкой сверху поверх
 * тёмного фона с цветными пятнами. Настоящего размытия нет намеренно: на Android 11 его
 * нет в системе, а программное процессор телевизора не тянет. Глубину дают градиент
 * заливки, кромка и фон.
 */

internal val GlassShape = RoundedCornerShape(28.dp)
internal val RowShape = RoundedCornerShape(18.dp)

private val GlassFill = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x12FFFFFF)))
private val GlassEdge = Brush.verticalGradient(listOf(Color(0x73FFFFFF), Color(0x14FFFFFF)))
private val PanelFill = Brush.verticalGradient(listOf(Color(0xE0262630), Color(0xE0121218)))
internal val FocusFill = Color(0xEBFFFFFF)
internal val OnFocus = Color(0xFF101014)

/** Стеклянная поверхность: кнопка не в фокусе, плашка, поле ввода. */
internal fun Modifier.glass(shape: Shape = CircleShape): Modifier =
    clip(shape).background(GlassFill).border(1.dp, GlassEdge, shape)

/** Плотное стекло для панелей поверх содержимого: под ним текст должен читаться. */
internal fun Modifier.glassPanel(shape: Shape = GlassShape): Modifier =
    clip(shape).background(PanelFill).border(1.dp, GlassEdge, shape)

/** Подсветка строки списка в фокусе. */
internal fun Modifier.glassRow(focused: Boolean): Modifier =
    if (focused) clip(RowShape).background(Color(0x2EFFFFFF)).border(1.dp, GlassEdge, RowShape) else this

/** Фон приложения: почти чёрный с мягкими цветными пятнами — на нём стекло выглядит стеклом. */
internal fun Modifier.glassBackdrop(): Modifier = drawBehind {
    drawRect(Background)
    drawCircle(
        Brush.radialGradient(listOf(Color(0x593A4BD8), Color.Transparent), Offset(size.width * 0.12f, size.height * 0.05f), size.width * 0.55f),
        size.width * 0.55f, Offset(size.width * 0.12f, size.height * 0.05f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0x4DB03A8C), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.9f), size.width * 0.5f),
        size.width * 0.5f, Offset(size.width * 0.95f, size.height * 0.9f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0x3318A0A8), Color.Transparent), Offset(size.width * 0.7f, size.height * 0.1f), size.width * 0.35f),
        size.width * 0.35f, Offset(size.width * 0.7f, size.height * 0.1f),
    )
}

/**
 * Кнопка-капсула одной высоты для любого содержимого. В фокусе заливается светлым, но не увеличивается: увеличение размывало
 * текст и наезжало на соседние кнопки. [selected] — текущее значение (выбранный сезон).
 */
@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .then(
                when {
                    focused -> Modifier.background(FocusFill)
                    selected -> Modifier.background(Color(0x52FFFFFF)).border(1.dp, GlassEdge, CircleShape)
                    else -> Modifier.glass()
                },
            )
            // Высота задана, а не складывается из содержимого: кнопки с текстом, со значком и
            // с символом из запасного шрифта получаются одного роста и стоят на одной линии.
            .height(46.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (focused) OnFocus else Foreground,
            LocalTextStyle provides MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        ) { content() }
    }
}

/**
 * Прокрутка с опорной точкой: элемент в фокусе всегда встаёт на одно и то же место
 * ([pivot] от начала области), а содержимое едет под ним. Без этого ряд то стоит, то
 * рывком сдвигается, когда следующая карточка видна не целиком.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PivotScroll(pivot: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { pivot.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
