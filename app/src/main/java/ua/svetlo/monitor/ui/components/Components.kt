package ua.svetlo.monitor.ui.components

import ua.svetlo.monitor.data.tr

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.Slot
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type

val OutageType.color: Color
    get() = when (this) {
        OutageType.None -> Status.Green
        OutageType.Stabilization -> Status.Yellow
        OutageType.Emergency -> Status.Red
        OutageType.Blackout -> Status.Black
        OutageType.Planned -> Status.Blue
        OutageType.LocalFault -> Status.Orange
        OutageType.NoData -> Status.NoData
    }

@Composable
fun SCard(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    color: Color = Svetlo.colors.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.section, color = Svetlo.colors.ink, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action, style = Type.smallBold, color = Svetlo.colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = Svetlo.colors.surface,
    fg: Color = Svetlo.colors.ink,
    border: Color? = Svetlo.colors.line,
    dot: Color? = null,
    blink: Boolean = false,
    icon: ImageVector? = null,
    style: TextStyle = Type.smallBold,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Dot(dot, blink = blink)
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = style, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, blink: Boolean = false) {
    val alpha = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
    } else 1f
    Box(Modifier.size(size).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(text, style = Type.caption, color = Svetlo.colors.ink2, maxLines = 1)
    }
}

fun slotColor(s: Slot, forecast: Boolean = false): Color = when {
    forecast && s != Slot.On -> Status.Forecast
    s == Slot.Off -> Status.Yellow
    s == Slot.Maybe -> Status.Maybe
    else -> Status.Green
}

/**
 * Полоса суток: 24 ячейки (каждая = 2 слота по 30 минут) и маркер «сейчас».
 * Ячейки появляются по очереди при первом показе.
 */
@Composable
fun DayBar(
    slots: List<Slot>,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    nowFraction: Float? = null,
    forecast: Boolean = false,
    override: Color? = null,
    gap: Dp = 2.dp,
    radius: Dp = 4.dp,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "bar")
    val pulse = rememberInfiniteTransition(label = "now").animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "p")
    val ink = Svetlo.colors.ink
    val desc = if (nowFraction != null) tr("Графік на добу", "График на сутки") else tr("Графік", "График")
    Canvas(modifier.fillMaxWidth().height(height + if (nowFraction != null) 12.dp else 0.dp).semantics { contentDescription = desc }) {
        val top = if (nowFraction != null) 6.dp.toPx() else 0f
        val h = height.toPx()
        val g = gap.toPx()
        val cell = (size.width - g * 23) / 24f
        for (i in 0 until 24) {
            val a = slots.getOrElse(i * 2) { Slot.On }
            val b = slots.getOrElse(i * 2 + 1) { Slot.On }
            val x = i * (cell + g)
            val appear = ((progress * 24f) - i).coerceIn(0f, 1f)
            if (appear <= 0f) continue
            val ch = h * (0.6f + 0.4f * appear)
            val y = top + (h - ch) / 2f
            val ca = override ?: slotColor(a, forecast)
            val cb = override ?: slotColor(b, forecast)
            if (ca == cb) {
                drawRoundRect(ca.copy(alpha = appear), Offset(x, y), Size(cell, ch), CornerRadius(radius.toPx()))
            } else {
                drawRoundRect(ca.copy(alpha = appear), Offset(x, y), Size(cell / 2f + 1f, ch), CornerRadius(radius.toPx()))
                drawRoundRect(cb.copy(alpha = appear), Offset(x + cell / 2f, y), Size(cell / 2f, ch), CornerRadius(radius.toPx()))
            }
        }
        if (nowFraction != null) {
            val x = nowFraction * size.width
            drawRoundRect(ink, Offset(x - 1.5.dp.toPx(), top - 5.dp.toPx()), Size(3.dp.toPx(), h + 10.dp.toPx()), CornerRadius(2.dp.toPx()))
            val r = 4.5.dp.toPx()
            drawCircle(ink.copy(alpha = 0.35f * (1f - pulse.value)), r + 6.dp.toPx() * pulse.value, Offset(x, top - 5.dp.toPx()))
            drawCircle(ink, r, Offset(x, top - 5.dp.toPx()))
        }
    }
}

@Composable
fun HourLabels(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("00", "06", "12", "18", "24").forEach { Text(it, style = Type.tiny, color = Svetlo.colors.muted) }
    }
}

/** Круг статуса с «дыханием» и расходящимися волнами. */
@Composable
fun StatusOrb(color: Color, soft: Color, icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 136.dp, calm: Boolean = true) {
    val t = rememberInfiniteTransition(label = "orb")
    val wave1 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing)), label = "w1")
    val wave2 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, delayMillis = 0, easing = FastOutSlowInEasing), initialStartOffset = androidx.compose.animation.core.StartOffset(1200)), label = "w2")
    val breathe = t.animateFloat(1f, 1.05f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "b")
    val animated by animateColorAsState(color, tween(600), label = "c")
    val softAnimated by animateColorAsState(soft, tween(600), label = "s")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = this.size.minDimension / 2f - 22.dp.toPx()
            listOf(wave1.value, wave2.value).forEach { w ->
                drawCircle(animated.copy(alpha = 0.5f * (1f - w)), base * (1f + 0.75f * w).coerceAtMost(1.45f), style = Stroke(2.dp.toPx()))
            }
            drawCircle(softAnimated, this.size.minDimension / 2f - 10.dp.toPx())
        }
        Box(
            Modifier.size(size * 0.765f).scale(if (calm) breathe.value else 1f).clip(CircleShape).background(animated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (color == Status.Black || color == Status.Red || color == Status.Green) Color.White else Color(0xFF16181D), modifier = Modifier.size(size * 0.33f))
        }
    }
}

@Composable
fun BackHeader(title: String, subtitle: String? = null, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SquareButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад", "Назад"), onBack)
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle, style = Type.caption, color = Svetlo.colors.muted)
            Text(title, style = Type.h2.copy(fontSize = Type.h2.fontSize * 0.95f), color = Svetlo.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun SquareButton(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = Svetlo.colors.ink, size: Dp = 44.dp, badge: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Svetlo.colors.surface)
            .border(1.dp, Svetlo.colors.line, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = desc, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        if (badge) Box(Modifier.align(Alignment.TopEnd).offset((-10).dp, 10.dp).size(10.dp).clip(CircleShape).background(Svetlo.colors.surface).padding(2.dp).clip(CircleShape).background(Status.Red))
    }
}

@Composable
fun Toggle(checked: Boolean) {
    val c = Svetlo.colors
    val x by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "t")
    val bg by animateColorAsState(if (checked) c.accent else c.track, label = "bg")
    Box(Modifier.width(52.dp).height(32.dp).clip(CircleShape).background(bg)) {
        Box(
            Modifier.padding(4.dp).offset(x = 20.dp * x).size(24.dp).clip(CircleShape)
                .background(if (c.dark && checked) c.bg else Color.White),
        )
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyBold, color = Svetlo.colors.ink)
            if (subtitle != null) Text(subtitle, style = Type.small, color = Svetlo.colors.muted)
        }
        Toggle(checked)
    }
}

@Composable
fun NavRow(title: String, value: String? = null, divider: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Text(title, style = Type.bodyBold, color = Svetlo.colors.ink, modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = Type.small, color = Svetlo.colors.muted, textAlign = TextAlign.End)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Svetlo.colors.muted, modifier = Modifier.size(20.dp))
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Svetlo.colors.divider))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Svetlo.colors
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(c.track).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.surface else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(11.dp)).background(bg)
                    .clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = if (on) Type.smallBold.copy(fontSize = Type.body.fontSize * 0.93f) else Type.small.copy(fontSize = Type.body.fontSize * 0.93f), color = if (on) c.ink else c.ink2, maxLines = 1)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Color = Svetlo.colors.inverse, fg: Color = Svetlo.colors.onInverse, outlined: Boolean = false) {
    val c = Svetlo.colors
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (outlined) Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)) else Modifier.background(bg))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.bodyBold, color = if (outlined) c.ink else fg, textAlign = TextAlign.Center)
    }
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, bg: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun StatTile(label: String, value: String, note: String? = null, noteColor: Color = Svetlo.colors.muted, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SCard(modifier, radius = 22.dp, padding = PaddingValues(16.dp), onClick = onClick) {
        Text(label, style = Type.caption, color = Svetlo.colors.muted)
        Text(value, style = Type.number, color = Svetlo.colors.ink)
        if (note != null) Text(note, style = Type.caption, color = noteColor)
    }
}

/** Ограничивает ширину контента на планшетах и центрирует его. */
@Composable
fun Centered(maxWidth: Dp = 720.dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
