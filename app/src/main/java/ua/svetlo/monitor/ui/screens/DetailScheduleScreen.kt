package ua.svetlo.monitor.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.RegionMode
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.Slot
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.data.ruDayMonth
import ua.svetlo.monitor.data.tr
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.WidthClass
import ua.svetlo.monitor.ui.components.BackHeader
import ua.svetlo.monitor.ui.components.LegendItem
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.Segmented
import ua.svetlo.monitor.ui.theme.Onest
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type
import java.time.LocalDateTime

/** «5 год», «13,5 год»: длительность окна из получасовых слотов. */
private fun halfHours(n: Int): String {
    val h = n / 2
    val v = if (n % 2 == 0) "$h" else "$h,5"
    return tr("$v год", "$v ч")
}

/**
 * Детальный почасовой график: все очереди области строками на шкале 00–24,
 * моя очередь крупнее и с длительностями окон, линия «сейчас», Вчора/Сьогодні/Завтра.
 * [startDay]: -1 вчера, 0 сегодня, 1 завтра.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScheduleScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, startDay: Int = 0, onBack: () -> Unit) {
    val c = Svetlo.colors
    val w = LocalWindow.current
    val region = s.selected.region
    val mine = s.selected.queue
    var day by remember { mutableIntStateOf(startDay.coerceIn(-1, 1) + 1) }
    var picked by remember(mine) { mutableStateOf(mine) }
    val ver by repo.version.collectAsState()
    val date = now.toLocalDate().plusDays(day - 1L)
    val queues = remember(ver, region) { repo.queues(region).ifEmpty { listOf(mine) } }
    val rows = remember(ver, region, date) { queues.map { it to repo.schedule(region, it, date) } }
    val known = rows.any { it.second.known }
    val mode = remember(ver, region) { repo.summary(region)?.mode }
    // Цвет отключения зависит от режима: стабилизационные — жёлтый, аварийные — красный, блэкаут — чёрный.
    val offColor = when (mode) { RegionMode.Emergency -> Status.Red; RegionMode.Blackout -> if (c.dark) Color(0xFF55555C) else Status.Black; else -> Status.Yellow }
    val offInk = when (mode) { RegionMode.Emergency, RegionMode.Blackout -> Color.White; else -> Color(0xFF3D2C00) }

    ScreenColumn(maxWidth = 1100.dp) {
        BackHeader(tr("Детальний графік", "Детальный график"), "${date.ruDayMonth()} · ${s.selected.cityName}", onBack)
        Segmented(listOf(tr("Вчора", "Вчера"), tr("Сьогодні", "Сегодня"), tr("Завтра", "Завтра")), day, { day = it }, Modifier.fillMaxWidth())
        if (mode == RegionMode.Emergency || mode == RegionMode.Blackout) {
            Pill(if (mode == RegionMode.Blackout) tr("Зараз графіки не діють: блекаут", "Сейчас графики не действуют: блэкаут") else tr("Аварійні відключення: графік може не діяти", "Аварийные отключения: график может не действовать"),
                bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
        }
        if (!known) {
            SCard(Modifier.fillMaxWidth()) {
                Text(when (day) {
                    0 -> tr("Графіка за вчора немає", "Графика за вчера нет")
                    1 -> tr("Графік на сьогодні ще не опубліковано", "График на сегодня ещё не опубликован")
                    else -> tr("Графік на завтра ще не опубліковано", "График на завтра ещё не опубликован")
                }, style = Type.section, color = c.ink)
                Text(if (day == 2) tr("Обленерго зазвичай публікує його ввечері, ближче до 23:00. Ми перевіряємо кожні 10 хвилин.", "Облэнерго обычно публикует его вечером, ближе к 23:00. Мы проверяем каждые 10 минут.")
                    else tr("Ми зберігаємо графіки за останній тиждень з моменту, коли почали збирати дані по вашій області.", "Мы храним графики за последнюю неделю с момента, когда начали собирать данные по вашей области."),
                    style = Type.small, color = c.ink2)
            }
            return@ScreenColumn
        }
        val compact = w.widthClass == WidthClass.Compact
        val labelW = if (compact) 40.dp else 52.dp
        val rowH = if (compact) 22.dp else 28.dp
        val myH = if (compact) 34.dp else 40.dp
        val gap = 5.dp
        val grow = remember { Animatable(0f) }
        LaunchedEffect(day) { grow.snapTo(0f); grow.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        val nowFrac = if (day == 1) (now.hour * 60 + now.minute) / 1440f else null

        SCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row {
                // Подписи очередей.
                Column(Modifier.width(labelW), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.height(18.dp))
                    rows.forEach { (q, _) ->
                        val isMine = q == mine
                        val isPicked = q == picked
                        Box(
                            Modifier.height(if (isPicked) myH else rowH).fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isPicked) c.inverse else Color.Transparent).clickable { picked = q },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(q, style = Type.caption.copy(fontWeight = if (isMine || isPicked) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = if (q.length > 4) 11.sp else 12.sp),
                                color = if (isPicked) c.onInverse else if (isMine) c.accent else c.muted, maxLines = 1)
                        }
                    }
                    Box(Modifier.height(18.dp))
                }
                Box(Modifier.weight(1f).padding(start = 6.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        HourAxis(nowFrac, now)
                        rows.forEach { (q, d) ->
                            val isPicked = q == picked
                            QueueTimeline(d.slots, d.known, isPicked, if (isPicked) myH else rowH, offColor, offInk, grow.value, Modifier.clickable { picked = q })
                        }
                        HourAxis(null, now)
                    }
                    // Сетка по часам и линия «сейчас» поверх всех строк.
                    val grid = c.ink.copy(alpha = if (c.dark) 0.10f else 0.07f)
                    val ink = c.ink
                    Canvas(Modifier.matchParentSize().padding(vertical = 18.dp + gap)) {
                        for (hh in 1 until 24) {
                            val x = size.width * hh / 24f
                            drawLine(if (hh % 3 == 0) grid.copy(alpha = grid.alpha * 2f) else grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }
                        if (nowFrac != null) {
                            val x = size.width * nowFrac
                            drawRoundRect(ink, Offset(x - 1.25.dp.toPx(), -4.dp.toPx()), Size(2.5.dp.toPx(), size.height + 8.dp.toPx()), CornerRadius(2.dp.toPx()))
                        }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            LegendItem(Status.Green, tr("Світло є", "Свет есть"))
            LegendItem(offColor, when (mode) { RegionMode.Emergency -> tr("Відключення (аварійні)", "Отключение (аварийные)"); RegionMode.Blackout -> tr("Відключення (блекаут)", "Отключение (блэкаут)"); else -> tr("Відключення", "Отключение") })
            LegendItem(offColor.copy(alpha = 0.45f), tr("Можливо", "Возможно"))
        }
        Text(tr("Торкніться черги, щоб побачити її години.", "Нажмите на очередь, чтобы увидеть её часы."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
        rows.firstOrNull { it.first == picked }?.second?.let { d -> WindowsCard(picked, picked == mine, d.slots, date, offColor, nowFrac, now) }
    }
}

@Composable
private fun HourAxis(nowFrac: Float?, now: LocalDateTime) {
    val c = Svetlo.colors
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
        val compact = maxWidth < 420.dp
        val step = if (compact) 3 else if (maxWidth < 760.dp) 2 else 1
        for (hh in 0..24 step step) {
            // Подпись часа под плашкой «сейчас» не рисуем.
            if (nowFrac != null && kotlin.math.abs(hh / 24f - nowFrac) * maxWidth.value < 30f) continue
            val x = maxWidth * (hh / 24f)
            val label = "%02d".format(hh)
            val shift: Dp = when (hh) { 0 -> 0.dp; 24 -> (-15).dp; else -> (-7).dp }
            Text(label, style = Type.tiny, color = c.muted, modifier = Modifier.offset(x = x + shift).align(Alignment.CenterStart))
        }
        if (nowFrac != null) {
            Box(Modifier.offset(x = (maxWidth * nowFrac - 20.dp).coerceIn(0.dp, maxWidth - 40.dp)).width(40.dp).height(18.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) {
                Text(now.toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }, style = Type.tiny.copy(fontWeight = FontWeight.ExtraBold), color = c.bg)
            }
        }
    }
}

/** Одна строка очереди: подложка «свет есть», окна отключений, у выбранной — длительности. */
@Composable
private fun QueueTimeline(slots: List<Slot>, known: Boolean, picked: Boolean, height: Dp, offColor: Color, offInk: Color, grow: Float, modifier: Modifier = Modifier) {
    val c = Svetlo.colors
    val measurer = rememberTextMeasurer()
    val label = Type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
    val onBg = if (picked) Status.Green else if (c.dark) Color(0xFF1E3A2A) else Color(0xFFD7F0E0)
    val off = if (picked) offColor else offColor.copy(alpha = if (c.dark) 0.75f else 0.8f)
    val desc = tr("Графік черги", "График очереди")
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }) {
        val r = CornerRadius(if (picked) 9.dp.toPx() else 6.dp.toPx())
        if (!known) {
            drawRoundRect(c.track, size = size, cornerRadius = r)
            return@Canvas
        }
        val visible = size.width * grow
        drawRoundRect(onBg, size = Size(visible, size.height), cornerRadius = r)
        val cell = size.width / 48f
        // Окна одного состояния подряд.
        var i = 0
        while (i < 48) {
            val st = slots.getOrElse(i) { Slot.On }
            var j = i
            while (j < 48 && slots.getOrElse(j) { Slot.On } == st) j++
            val x0 = i * cell; val x1 = j * cell
            if (x0 < visible) {
                val w = (minOf(x1, visible) - x0)
                if (st != Slot.On) {
                    val col = if (st == Slot.Maybe) offColor.copy(alpha = 0.45f) else off
                    drawRoundRect(col, Offset(x0, 0f), Size(w, size.height), r)
                }
                if (picked && grow >= 1f) drawDuration(measurer, halfHours(j - i), label, x0, x1 - x0, if (st == Slot.On) Color.White else offInk)
            }
            i = j
        }
    }
}

private fun DrawScope.drawDuration(m: androidx.compose.ui.text.TextMeasurer, text: String, style: androidx.compose.ui.text.TextStyle, x: Float, w: Float, color: Color) {
    val layout = m.measure(text, style.copy(color = color))
    val tw = layout.size.width
    val shown = if (tw + 8.dp.toPx() <= w) layout else {
        // Не влезает «5 год» — пробуем просто число.
        val short = m.measure(text.substringBefore(' '), style.copy(color = color))
        if (short.size.width + 6.dp.toPx() <= w) short else return
    }
    drawText(shown, topLeft = Offset(x + (w - shown.size.width) / 2f, (size.height - shown.size.height) / 2f))
}

/** Окна выбранной очереди списком: точные часы и длительность. */
@Composable
private fun WindowsCard(queue: String, mine: Boolean, slots: List<Slot>, date: java.time.LocalDate, offColor: Color, nowFrac: Float?, now: LocalDateTime) {
    val c = Svetlo.colors
    val offN = slots.count { it == Slot.Off }
    val maybeN = slots.count { it == Slot.Maybe }
    SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Черга $queue", "Очередь $queue") + if (mine) tr(" · ваша", " · ваша") else "", style = Type.section, color = c.ink)
                Text(tr("Без світла ${formatDuration(offN * 30L)}", "Без света ${formatDuration(offN * 30L)}") + if (maybeN > 0) tr(" · ще ${formatDuration(maybeN * 30L)} можливо", " · ещё ${formatDuration(maybeN * 30L)} возможно") else "",
                    style = Type.small, color = c.ink2)
            }
        }
        val start = date.atStartOfDay()
        val runs = mutableListOf<Triple<IntRange, Slot, Boolean>>()
        var i = 0
        while (i < 48) {
            val st = slots.getOrElse(i) { Slot.On }
            var j = i
            while (j < 48 && slots.getOrElse(j) { Slot.On } == st) j++
            runs += Triple(i until j, st, nowFrac != null && (nowFrac * 48).toInt() in i until j)
            i = j
        }
        runs.forEach { (r, st, current) ->
            val a = start.plusMinutes(r.first * 30L); val b = start.plusMinutes((r.last + 1) * 30L)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (current) c.accentSoft else Color.Transparent).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(5.dp).height(22.dp).clip(CircleShape).background(when (st) { Slot.On -> Status.Green; Slot.Off -> offColor; Slot.Maybe -> offColor.copy(alpha = 0.45f) }))
                Text("${a.hm()} – ${if (r.last == 47) "24:00" else b.hm()}", style = Type.bodyBold.copy(fontFamily = Onest, fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.width(112.dp))
                Text(when (st) { Slot.On -> tr("світло", "свет"); Slot.Off -> tr("відключення", "отключение"); Slot.Maybe -> tr("можливо", "возможно") } + " · " + halfHours(r.last - r.first + 1),
                    style = if (current) Type.smallBold else Type.small, color = if (current) c.accent else c.ink2, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}
