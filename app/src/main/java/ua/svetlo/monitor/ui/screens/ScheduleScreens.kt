package ua.svetlo.monitor.ui.screens

import ua.svetlo.monitor.data.tr
import ua.svetlo.monitor.data.MapAssets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.Slot
import ua.svetlo.monitor.data.between
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.formatShort
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.data.ruShort
import ua.svetlo.monitor.ui.BottomBarSpace
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.components.BackHeader
import ua.svetlo.monitor.ui.components.DayBar
import ua.svetlo.monitor.ui.components.Dot
import ua.svetlo.monitor.ui.components.LegendItem
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.SectionHeader
import ua.svetlo.monitor.ui.components.Segmented
import ua.svetlo.monitor.ui.components.SquareButton
import ua.svetlo.monitor.ui.components.ToggleRow
import ua.svetlo.monitor.ui.components.color
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type
import java.time.LocalDateTime

/** Общий каркас экрана: прокрутка, отступы по размеру окна, ограничение ширины, место под нижнюю панель. */
@Composable
fun ScreenColumn(maxWidth: androidx.compose.ui.unit.Dp = 720.dp, content: @Composable ColumnScope.() -> Unit) {
    val w = LocalWindow.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = w.gutter + 4.dp, end = w.gutter + 4.dp, top = 16.dp, bottom = if (w.useRail) 24.dp else BottomBarSpace + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/** Элемент появляется снизу с небольшой задержкой (как .rise в дизайне). */
@Composable
fun Rise(delay: Int = 0, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(500, delayMillis = delay, easing = FastOutSlowInEasing)) }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 16.dp.toPx() }) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onRefresh: () -> Unit = {}, onWindows: () -> Unit, onRemind: (Boolean) -> Unit, onDetail: (Int) -> Unit = {}) {
    // Открыли график — сразу проверяем, нет ли нового (сама проверка не чаще раза в минуту).
    LaunchedEffect(Unit) { onRefresh() }
    var queue by remember(s.selected.queue) { mutableStateOf(s.selected.queue) }
    val c = Svetlo.colors
    val w = LocalWindow.current
    val region = s.selected.region
    val ver by repo.version.collectAsState()
    val days = remember(ver, queue, region, now.toLocalDate()) { (0..1).map { repo.schedule(region, queue, now.toLocalDate().plusDays(it.toLong())) } }
    val mode = remember(ver, region) { repo.summary(region)?.mode }
    val queueList = remember(ver, region) { repo.queues(region) }
    val today = days.first()
    val nowSlot = now.hour * 2 + now.minute / 30

    val weekCard: @Composable () -> Unit = {
        Rise {
            SCard(Modifier.fillMaxWidth()) {
                SectionHeader(tr("Сьогодні і завтра", "Сегодня и завтра"), tr("Детально по годинах", "Подробно по часам")) { onDetail(0) }
                days.forEachIndexed { i, d ->
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDetail(i) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (i == 0) tr("Сьогодні", "Сегодня") else tr("Завтра", "Завтра"),
                            style = Type.caption.copy(fontWeight = if (i == 0) FontWeight.ExtraBold else FontWeight.SemiBold),
                            color = if (i == 0) c.ink else c.muted, modifier = Modifier.width(60.dp))
                        if (!d.known) Box(Modifier.weight(1f).height(if (w.widthClass == ua.svetlo.monitor.ui.WidthClass.Compact) 20.dp else 26.dp).clip(RoundedCornerShape(3.dp)).background(c.divider), contentAlignment = Alignment.Center) {
                            Text(if (i == 0) tr("графіка ще немає", "графика ещё нет") else tr("з'явиться ввечері", "появится вечером"), style = Type.caption, color = c.muted)
                        } else Box(Modifier.weight(1f).graphicsLayer { alpha = if (d.forecast) 0.75f else 1f }) {
                            DayBar(d.slots, height = if (w.widthClass == ua.svetlo.monitor.ui.WidthClass.Compact) 20.dp else 26.dp, forecast = d.forecast, gap = 1.dp, radius = 3.dp,
                                nowFraction = if (i == 0) (now.hour * 60 + now.minute) / 1440f else null)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    LegendItem(Status.Green, tr("Світло є", "Свет есть")); LegendItem(Status.Yellow, tr("Відключення", "Отключение")); LegendItem(Status.Maybe, tr("Можливо", "Возможно"))
                }
                Text(tr("Графік на завтра обленерго зазвичай публікує ввечері, ближче до 23:00. Ми перевіряємо кожні 10 хвилин і повідомимо, щойно він з'явиться.", "График на завтра облэнерго обычно публикует вечером, ближе к 23:00. Мы проверяем каждые 10 минут и сообщим, как только он появится."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
            }
        }
    }
    val todayList: @Composable () -> Unit = {
        Rise(80) {
            SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                val windows = (today.windows(Slot.Off).map { it to false } + today.windows(Slot.Maybe).map { it to true }).sortedBy { it.first.first }
                if (!today.known) Text(tr("Графік на сьогодні ще не опубліковано", "График на сегодня ещё не опубликован"), style = Type.body, color = c.ink2, modifier = Modifier.padding(vertical = 12.dp))
                else if (windows.isEmpty()) Text(tr("Сьогодні відключень немає", "Сегодня отключений нет"), style = Type.body, color = c.ink2, modifier = Modifier.padding(vertical = 12.dp))
                windows.forEachIndexed { i, (r, maybe) ->
                    val start = now.toLocalDate().atStartOfDay().plusMinutes(r.first * 30L)
                    val end = now.toLocalDate().atStartOfDay().plusMinutes((r.last + 1) * 30L)
                    val past = r.last < nowSlot
                    val active = nowSlot in r
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(6.dp).height(36.dp).clip(CircleShape).background(if (maybe) Status.Maybe else Status.Yellow))
                        Column(Modifier.weight(1f)) {
                            Text("${start.hm()} – ${end.hm()}", style = Type.bodyBold.copy(fontFamily = ua.svetlo.monitor.ui.theme.Onest, fontWeight = FontWeight.SemiBold), color = c.ink)
                            val note = when {
                                past -> if (maybe) tr("Можливе відключення · минуло", "Возможное отключение · прошло") else tr("Минуло · ${formatDuration((r.last - r.first + 1) * 30L)}", "Прошло · ${formatDuration((r.last - r.first + 1) * 30L)}")
                                active -> tr("Триває зараз · увімкнуть о ${end.hm()}", "Идёт сейчас · включат в ${end.hm()}")
                                maybe -> tr("Можливе відключення", "Возможное отключение")
                                else -> tr("Через ${formatDuration(between(now, start))}", "Через ${formatDuration(between(now, start))}")
                            }
                            Text(note, style = if (!past) Type.smallBold else Type.small, color = if (!past) c.yellowInk else c.muted)
                        }
                        if (!past && !maybe && s.remindMinutes > 0) Pill(tr("за ${s.remindMinutes} хв", "за ${s.remindMinutes} мин"), bg = c.accentSoft, fg = c.accent, border = null, icon = Icons.Rounded.NotificationsActive)
                    }
                    if (i < windows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
        }
    }
    val remind: @Composable () -> Unit = {
        Rise(160) {
            SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                ToggleRow(tr("Нагадувати перед відключенням", "Напоминать перед отключением"), tr("І повідомляти, коли графік змінюється", "И сообщать, когда график меняется"), s.remindMinutes > 0, onRemind)
            }
        }
        SCard(Modifier.fillMaxWidth(), onClick = onWindows) {
            SectionHeader(tr("Вікна світла і підготовка", "Окна света и подготовка"), tr("Відкрити", "Открыть"), onWindows)
            Text(tr("Коли можна випрати, зарядити техніку і приготувати їжу.", "Когда можно постирать, зарядить технику и приготовить еду."), style = Type.small, color = c.ink2)
        }
    }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Графік", "График"), style = Type.h1, color = c.ink, modifier = Modifier.weight(1f))
            Pill("${s.selected.labelName} · ${s.selected.queue}", border = c.line, style = Type.smallBold.copy(fontSize = Type.body.fontSize * 0.93f))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(queueList) { q ->
                val on = q == queue
                Box(
                    Modifier.heightIn(min = 40.dp).widthIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.inverse else c.surface).then(if (on) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp)))
                        .clickable { queue = q }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center,
                ) { Text(q, style = Type.bodyBold.copy(fontSize = Type.body.fontSize * 0.93f), color = if (on) c.onInverse else c.ink) }
            }
        }
        if (mode == ua.svetlo.monitor.data.RegionMode.Emergency || mode == ua.svetlo.monitor.data.RegionMode.Blackout) {
            Pill(if (mode == ua.svetlo.monitor.data.RegionMode.Blackout) tr("Зараз графіки не діють: блекаут", "Сейчас графики не действуют: блэкаут") else tr("Аварійні відключення: графік може не діяти", "Аварийные отключения: график может не действовать"), bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
        }
    }
    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.4f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { header(); weekCard() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { todayList(); remind() }
        }
    } else {
        ScreenColumn { header(); weekCard(); todayList(); remind() }
    }
}

@Composable
fun AreaScreen(repo: OutageRepository, s: Settings, onBack: () -> Unit) {
    val ver by repo.version.collectAsState()
    val a0 = remember(ver, s.selected) { repo.area(s.selected.region, s.selected.queue) }
    val c = Svetlo.colors
    if (a0 == null) {
        ScreenColumn {
            BackHeader(s.selected.cityName, tr("Статистика району", "Статистика района"), onBack)
            SCard(Modifier.fillMaxWidth()) { Text(tr("Для цієї області ще немає даних. Статистика з'явиться, щойно ми почнемо збирати графіки.", "Для этой области ещё нет данных. Статистика появится, как только мы начнём собирать графики."), style = Type.body, color = c.ink2) }
        }
        return
    }
    val a = a0
    var period by remember { mutableIntStateOf(0) }
    val w = LocalWindow.current
    val totals = Triple(a.days.sumOf { it.first }, a.days.sumOf { it.second }, a.days.sumOf { it.third })
    val all = (totals.first + totals.second + totals.third).coerceAtLeast(1)

    val head: @Composable () -> Unit = {
        BackHeader(a.title, a.city, onBack)
        SCard(Modifier.fillMaxWidth()) {
            val soft = when (a.type) { OutageType.Emergency -> c.redSoft; OutageType.Blackout -> c.blackSoft; OutageType.None -> c.greenSoft; else -> c.yellowSoft }
            val ink = when (a.type) { OutageType.Emergency -> c.redInk; OutageType.Blackout -> c.ink; OutageType.None -> c.greenInk; else -> c.yellowInk }
            Pill(tr("Зараз: ${a.type.title.lowercase()}", "Сейчас: ${a.type.title.lowercase()}"), bg = soft, fg = ink, border = null, dot = a.type.color, blink = a.type == OutageType.Emergency)
            Text(formatDuration(a.avgMinutes.toLong()), style = Type.bigNumber, color = c.ink)
            Text(tr("в середньому без світла на день за ${a.days.size} дн. (усі черги області)", "в среднем без света в день за ${a.days.size} дн. (все очереди области)"), style = Type.body, color = c.ink2)
        }
    }
    val chart: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Години без світла", "Часы без света"), style = Type.section, color = c.ink, modifier = Modifier.weight(1f))
                Segmented(listOf(tr("7 днів", "7 дней"), tr("30 днів", "30 дней")), period, { period = it }, Modifier.width(190.dp))
            }
            val grow = remember { Animatable(0f) }
            LaunchedEffect(period) { grow.snapTo(0f); grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            val maxMin = a.days.maxOf { it.first + it.second + it.third }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                a.days.forEachIndexed { i, d ->
                    val k = ((grow.value * 1.4f) - i * 0.06f).coerceIn(0f, 1f)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom)) {
                        listOf(d.third to Status.Black, d.second to Status.Red, d.first to Status.Yellow).forEach { (m, col) ->
                            if (m > 0) Box(Modifier.fillMaxWidth().height(100.dp * (m.toFloat() / maxMin) * k).clip(RoundedCornerShape(6.dp)).background(col))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                a.dayLabels.forEachIndexed { i, l -> Text(l, style = Type.caption, color = if (i == a.dayLabels.lastIndex) c.ink else c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            if (period == 1) Text(tr("За 30 днів даних поки мало, показуємо останній тиждень.", "За 30 дней данных пока мало, показываем последнюю неделю."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
    }
    val types: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth()) {
            Text(tr("За типами відключень", "По типам отключений"), style = Type.section, color = c.ink)
            listOf(Triple(tr("Стабілізаційні", "Стабилизационные"), totals.first, Status.Yellow), Triple(tr("Аварійні", "Аварийные"), totals.second, Status.Red), Triple(tr("Блекаут", "Блэкаут"), totals.third, Status.Black)).forEachIndexed { i, (n, m, col) ->
                val share = m.toFloat() / all
                val anim = remember { Animatable(0f) }
                LaunchedEffect(Unit) { anim.animateTo(share, tween(800, delayMillis = i * 100, easing = FastOutSlowInEasing)) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row { Text(n, style = Type.bodyBold.copy(fontSize = Type.body.fontSize * 0.93f), color = c.ink, modifier = Modifier.weight(1f)); Text("${formatShort(m / a.days.size.coerceAtLeast(1))} · ${(share * 100).toInt()}%", style = Type.small, color = c.ink2) }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.divider)) { Box(Modifier.fillMaxWidth(anim.value).height(8.dp).clip(CircleShape).background(col)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(Status.Blue); Text(tr("Планових робіт не було", "Плановых работ не было"), style = Type.small, color = c.ink2) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(Status.Orange); Text(tr("Локальних аварій не було", "Локальных аварий не было"), style = Type.small, color = c.ink2) }
        }
    }
    val queues: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth()) {
            SectionHeader(tr("Черги області", "Очереди области"), tr("зараз", "сейчас"))
            a.queueOff.entries.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (q, off) ->
                        Box(Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).background(if (off) a.type.takeIf { it != OutageType.None }?.color ?: Status.Yellow else c.greenSoft), contentAlignment = Alignment.Center) {
                            Text(q, style = Type.caption.copy(fontWeight = FontWeight.ExtraBold), color = if (off) (if (a.type == OutageType.Stabilization) c.ink else androidx.compose.ui.graphics.Color.White) else c.greenInk)
                        }
                    }
                }
            }
        }
    }
    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { head(); chart() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Box(Modifier.height(56.dp)); types(); queues() }
        }
    } else ScreenColumn { head(); chart(); types(); queues() }
}

@Composable
fun WindowsScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit) {
    val c = Svetlo.colors
    val windows = repo.powerWindows(s.selected.region, s.selected.queue, now.toLocalDate()).filter { it.end.isAfter(now) }
    val tasks = remember { mutableStateOf(setOf(1)) }
    ScreenColumn {
        BackHeader(tr("Вікна світла", "Окна света"), tr("${s.selected.labelName} · черга ${s.selected.queue}", "${s.selected.labelName} · очередь ${s.selected.queue}"), onBack)
        SCard(Modifier.fillMaxWidth()) {
            SectionHeader(tr("Сьогодні світло буде", "Сегодня свет будет"))
            if (windows.isEmpty()) Text(tr("Сьогодні вікон світла більше немає.", "Сегодня окон света больше нет."), style = Type.body, color = c.ink2)
            windows.forEach { pw ->
                val active = !now.isBefore(pw.start)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (active) c.greenSoft else c.surface2).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Bolt, null, tint = Status.Green, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${pw.start.hm()} – ${pw.end.hm()}", style = Type.bodyBold.copy(fontFamily = ua.svetlo.monitor.ui.theme.Onest), color = c.ink)
                        Text(if (active) tr("Зараз · залишилося ${formatDuration(between(now, pw.end))}", "Сейчас · осталось ${formatDuration(between(now, pw.end))}") else tr("Через ${formatDuration(between(now, pw.start))}", "Через ${formatDuration(between(now, pw.start))}"), style = Type.small, color = if (active) c.greenInk else c.muted)
                    }
                    Text(formatDuration(between(pw.start, pw.end)), style = Type.smallBold, color = c.ink2)
                }
            }
        }
        SCard(Modifier.fillMaxWidth()) {
            SectionHeader(tr("Встигнути, поки є світло", "Успеть, пока есть свет"))
            listOf(tr("Зарядити телефон і павербанк", "Зарядить телефон и павербанк"), tr("Запустити прання", "Запустить стирку"), tr("Набрати воду", "Набрать воду"), tr("Приготувати їжу на вечір", "Приготовить еду на вечер"), tr("Зарядити ліхтарик", "Зарядить фонарик")).forEachIndexed { i, t ->
                val done = i in tasks.value
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { tasks.value = if (done) tasks.value - i else tasks.value + i }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (done) c.accent else c.surface).border(2.dp, if (done) c.accent else c.line, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                    Text(t, style = Type.body, color = if (done) c.muted else c.ink)
                }
            }
        }
    }
}

