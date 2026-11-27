package ua.svetlo.monitor.ui.screens

import ua.svetlo.monitor.data.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsNone
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.svetlo.monitor.data.NowState
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.between
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.data.ruDayMonth
import ua.svetlo.monitor.data.ruLong
import ua.svetlo.monitor.ui.BottomBarSpace
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.components.DayBar
import ua.svetlo.monitor.ui.components.Dot
import ua.svetlo.monitor.ui.components.HourLabels
import ua.svetlo.monitor.ui.components.LegendItem
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.PrimaryButton
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.SectionHeader
import ua.svetlo.monitor.ui.components.SquareButton
import ua.svetlo.monitor.ui.components.StatTile
import ua.svetlo.monitor.ui.components.StatusOrb
import ua.svetlo.monitor.ui.theme.LocalLargeText
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type
import java.time.LocalDateTime

@Composable
fun HomeScreen(
    repo: OutageRepository,
    s: Settings,
    now: LocalDateTime,
    onAddresses: () -> Unit,
    onSchedule: () -> Unit,
    onArea: () -> Unit,
    onDetail: () -> Unit = onSchedule,
    onSources: () -> Unit,
    onNotif: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onRefresh() }
    val addr = s.selected
    val state = repo.now(addr.region, addr.queue, now)
    val today = repo.schedule(addr.region, addr.queue, now.toLocalDate())
    val tomorrow = repo.schedule(addr.region, addr.queue, now.toLocalDate().plusDays(1))
    val supported = repo.summary(addr.region) != null
    if (LocalLargeText.current) {
        LargeHome(s, state, now, onAddresses, onSchedule, onDetail)
        return
    }
    val w = LocalWindow.current
    val header: @Composable () -> Unit = {
        HomeHeader(s, now, onAddresses, onNotif)
        AddressChips(s, onSelect)
        StatusChips(s, state, onRefresh)
    }
    val status: @Composable () -> Unit = { StatusCard(state, now, supported, s.selected.queue) }
    val todayCard: @Composable () -> Unit = {
        SCard(onClick = onDetail) {
            SectionHeader(tr("Сьогодні за графіком", "Сегодня по графику"), tr("Детально", "Подробно"), onDetail)
            val frac = (now.hour * 60 + now.minute) / 1440f
            if (state.schedulesSuspended) {
                Text(tr("Діють аварійні відключення: графік може не діяти, світло можуть вимкнути й поза ним.", "Действуют аварийные отключения: график может не действовать, свет могут выключить и вне его."), style = Type.small, color = Svetlo.colors.redInk)
            }
            if (!today.known) {
                Text(tr("Графік на сьогодні ще не опубліковано.", "График на сегодня ещё не опубликован."), style = Type.small, color = Svetlo.colors.ink2)
            }
            DayBar(today.slots, nowFraction = frac, override = if ((state.schedulesSuspended && state.lightOn == null) || !today.known) Svetlo.colors.track else null)
            HourLabels()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendItem(Status.Green, tr("Світло є", "Свет есть")); LegendItem(Status.Yellow, tr("Відключення", "Отключение")); LegendItem(Status.Maybe, tr("Можливо", "Возможно"))
            }
        }
    }
    val stats: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(tr("Без світла сьогодні", "Без света сегодня"), formatDuration(today.offMinutes.toLong()),
                if (today.maybeMinutes > 0) tr("+ ${formatDuration(today.maybeMinutes.toLong())} можливо", "+ ${formatDuration(today.maybeMinutes.toLong())} возможно") else null, modifier = Modifier.weight(1f))
            if (tomorrow.known && !tomorrow.forecast) {
                StatTile(tr("Завтра без світла", "Завтра без света"), formatDuration(tomorrow.offMinutes.toLong()),
                    if (tomorrow.maybeMinutes > 0) tr("+ ${formatDuration(tomorrow.maybeMinutes.toLong())} можливо", "+ ${formatDuration(tomorrow.maybeMinutes.toLong())} возможно") else null, modifier = Modifier.weight(1f), onClick = onSchedule)
            } else {
                StatTile(tr("Завтра", "Завтра"), "—", tr("графік ще не опубліковано", "график ещё не опубликован"), modifier = Modifier.weight(1f), onClick = onSchedule)
            }
        }
    }
    val why: @Composable () -> Unit = {
        val att = state.attention
        if (state.schedulesSuspended || !att.isNullOrBlank()) {
            SCard(color = if (state.schedulesSuspended) Svetlo.colors.redSoft else Svetlo.colors.surface) {
                Text(if (state.schedulesSuspended) tr("Чому графік може не діяти?", "Почему график может не действовать?") else tr("Повідомлення оператора", "Сообщение оператора"),
                    style = Type.section, color = Svetlo.colors.ink, modifier = Modifier.semantics { heading() })
                Text(att ?: tr("Оператор оголосив аварійні відключення. Поки вони діють, світло можуть вимикати поза графіком черги.", "Оператор объявил аварийные отключения. Пока они действуют, свет могут выключать вне графика очереди."),
                    style = Type.small.copy(lineHeight = 21.sp), color = Svetlo.colors.ink2)
                Text(tr("Джерело: ${state.operator ?: "обленерго"}", "Источник: ${state.operator ?: "облэнерго"}"), style = Type.caption, color = Svetlo.colors.muted,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSources).padding(vertical = 4.dp))
            }
        }
    }

    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                header(); status()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                why(); todayCard(); stats()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = w.gutter + 4.dp, end = w.gutter + 4.dp, top = 20.dp, bottom = if (w.useRail) 24.dp else BottomBarSpace + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // На большом телефоне и складном экране держим колонку читаемой ширины.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                header(); status(); why(); todayCard(); stats()
            }
        }
    }
}

@Composable
private fun HomeHeader(s: Settings, now: LocalDateTime, onAddresses: () -> Unit, onNotif: () -> Unit) {
    val c = Svetlo.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${now.dayOfWeek.ruLong()}, ${now.toLocalDate().ruDayMonth()}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = tr("Змінити адресу", "Сменить адрес"), onClick = onAddresses).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(listOf(s.selected.labelName, s.selected.street.shortStreet()).filter { it.isNotBlank() }.joinToString(" · "), style = Type.h2, color = c.ink, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.ink)
            }
        }
        SquareButton(Icons.Rounded.NotificationsNone, tr("Сповіщення", "Уведомления"), onNotif, size = 48.dp, badge = true)
    }
}

/** «вулиця Лєскова, 9» → «Лєскова, 9». */
fun String.shortStreet(): String = replace(Regex("^(вулиця|вул\\.|улица|ул\\.|бульвар|провулок|проспект)\\s+"), "")
    .replace(Regex("\\s+(вулиця|улица|провулок|узвіз)(,)"), "$2")

@Composable
private fun AddressChips(s: Settings, onSelect: (String) -> Unit) {
    if (s.addresses.size < 2) return
    val c = Svetlo.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.addresses, key = { it.id }) { a ->
            val on = a.id == s.selectedId
            Pill(
                a.labelName, bg = if (on) c.inverse else c.surface, fg = if (on) c.onInverse else c.ink,
                border = if (on) null else c.line, onClick = { onSelect(a.id) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(s: Settings, state: NowState, onRefresh: () -> Unit) {
    val c = Svetlo.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(tr("Черга ${s.selected.queue}", "Очередь ${s.selected.queue}"))
        Pill(s.selected.cityName, icon = Icons.Rounded.LocationOn, fg = c.ink2)
        if (state.schedulesSuspended) {
            Pill(if (state.lightOn != null) tr("Графік може не діяти", "График может не действовать") else tr("Графіки не діють", "Графики не действуют"), bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
        }
        run {
            Text(tr("оновлено ", "обновлено ") + ago(state.updatedAt), style = Type.caption, color = c.muted,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = tr("Оновити", "Обновить"), onClick = onRefresh).padding(horizontal = 4.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun StatusCard(state: NowState, now: LocalDateTime, supported: Boolean, queue: String) {
    val c = Svetlo.colors
    val (color, soft) = when {
        state.type == OutageType.Blackout -> Status.Black to c.blackSoft
        state.type == OutageType.Emergency -> Status.Red to c.redSoft
        state.regionOnly && state.type == OutageType.Stabilization -> Status.Yellow to c.yellowSoft
        state.regionOnly && state.type == OutageType.None -> Status.Green to c.greenSoft
        state.lightOn == null -> Status.NoData to c.track
        state.lightOn -> Status.Green to c.greenSoft
        else -> Status.Yellow to c.yellowSoft
    }
    val title = when {
        state.regionOnly -> regionOnlyTitle(state)
        state.type == OutageType.Blackout -> tr("Блекаут", "Блэкаут")
        state.type == OutageType.Emergency && state.lightOn == true -> tr("За графіком світло є", "По графику свет есть")
        state.type == OutageType.Emergency && state.lightOn == false -> tr("За графіком світла немає", "По графику света нет")
        state.type == OutageType.Emergency -> tr("Аварійні відключення", "Аварийные отключения")
        state.lightOn == null -> tr("Немає графіка", "Нет графика")
        state.lightOn -> tr("Світло є", "Свет есть")
        else -> tr("Світла немає", "Света нет")
    }
    SCard(Modifier.fillMaxWidth().animateContentSize(), radius = 28.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusOrb(color, soft, if (state.lightOn == true) Bolt else BoltOff, calm = state.lightOn != false)
            AnimatedContent(title, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "title") { t ->
                Text(t, style = Type.display, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            }
            when {
                state.schedulesSuspended && state.lightOn != null -> { ScheduledBlock(state, now); EmergencyNote() }
                state.schedulesSuspended -> EmergencyBlock(state, color)
                state.regionOnly -> Text(regionOnlyNote(state), style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
                state.lightOn == null -> Text(
                    if (!supported) tr("Для цієї області ми ще не збираємо графіки. Щойно джерело запрацює, статус з'явиться тут.", "Для этой области мы ещё не собираем графики. Как только источник заработает, статус появится здесь.")
                    else tr("Оператор ще не опублікував графік для черги $queue на сьогодні.", "Оператор ещё не опубликовал график для очереди $queue на сегодня."),
                    style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
                state.type == OutageType.None -> Text(tr("Відключень за графіком немає", "Отключений по графику нет"), style = Type.body, color = c.ink2)
                else -> ScheduledBlock(state, now)
            }
        }
    }
}

@Composable
private fun ScheduledBlock(state: NowState, now: LocalDateTime) {
    val c = Svetlo.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (state.lightOn == true) tr("Наступне відключення через", "Следующее отключение через") else tr("Світло увімкнуть через", "Свет включат через"), style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
        val left = state.nextChange?.let { between(now, it) } ?: 0
        Text(formatDuration(left), style = Type.number, color = c.ink)
    }
    state.nextWindow?.let { (a, b) ->
        Pill(tr("${a.hm()}–${b.hm()} · стабілізаційне", "${a.hm()}–${b.hm()} · стабилизационное"), bg = c.yellowSoft, fg = c.yellowInk, border = null, dot = Status.Yellow)
    }
}

/** Аварийные при известном графике: график показан, но может не действовать. */
@Composable
private fun EmergencyNote() {
    val c = Svetlo.colors
    Pill(tr("Аварійні відключення · графік може не діяти", "Аварийные отключения · график может не действовать"), bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
}

@Composable
private fun EmergencyBlock(state: NowState, color: Color) {
    val c = Svetlo.colors
    val soft = if (state.type == OutageType.Blackout) c.blackSoft else c.redSoft
    val ink = if (state.type == OutageType.Blackout) c.ink else c.redInk
    Pill(tr("Графіки не діють", "Графики не действуют"), bg = soft, fg = ink, border = null, dot = color, blink = true)
    Text(tr("Світло можуть вимкнути або увімкнути в будь-який момент. Стежте за повідомленнями оператора.", "Свет могут выключить или включить в любой момент. Следите за сообщениями оператора."),
        style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
}

/** Упрощённая главная для режима крупного шрифта: контрастно, только главное. */
@Composable
private fun LargeHome(s: Settings, state: NowState, now: LocalDateTime, onAddresses: () -> Unit, onSchedule: () -> Unit, onDetail: () -> Unit) {
    val c = Svetlo.colors
    val w = LocalWindow.current
    val bg = when {
        state.type == OutageType.Blackout -> Status.Black
        state.type == OutageType.Emergency -> Color(0xFFB91C1F)
        state.regionOnly && state.type == OutageType.Stabilization -> Color(0xFF8A6A00)
        state.regionOnly && state.type == OutageType.None -> Color(0xFF1E8E57)
        state.lightOn == null -> Color(0xFF4A4E57)
        state.lightOn -> Color(0xFF1E8E57)
        else -> Color(0xFF8A6A00)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = w.gutter + 4.dp, end = w.gutter + 4.dp, top = 20.dp, bottom = if (w.useRail) 24.dp else BottomBarSpace + 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAddresses).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(s.selected.labelName, s.selected.street.shortStreet()).filter { it.isNotBlank() }.joinToString(" · "), style = Type.h2, color = c.ink, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.ink, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (state.lightOn == true) Bolt else BoltOff, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text(when (state.lightOn) { true -> tr("Світло є", "Свет есть"); false -> tr("Світла немає", "Света нет"); null -> tr("Невідомо", "Неизвестно") }, style = Type.display.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold), color = Color.White)
            }
            val line1 = when {
                state.schedulesSuspended && state.lightOn != null -> (if (state.lightOn == true) tr("Вимкнуть о ${state.nextChange?.hm()}", "Выключат в ${state.nextChange?.hm()}") else tr("Увімкнуть о ${state.nextChange?.hm()}", "Включат в ${state.nextChange?.hm()}")) + tr(" за графіком · аварійні, графік може не діяти", " по графику · аварийные, график может не действовать")
                state.schedulesSuspended -> if (state.type == OutageType.Blackout) tr("Блекаут", "Блэкаут") else tr("Аварійні відключення", "Аварийные отключения")
                state.regionOnly -> regionOnlyTitle(state)
                state.lightOn == null -> tr("Графіка ще немає", "Графика ещё нет")
                state.type == OutageType.None -> tr("Відключень немає", "Отключений нет")
                state.lightOn == true -> tr("Вимкнуть о ${state.nextChange?.hm()}", "Выключат в ${state.nextChange?.hm()}")
                else -> tr("Увімкнуть о ${state.nextChange?.hm()}", "Включат в ${state.nextChange?.hm()}")
            }
            Text(line1, style = Type.h2.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Color.White)
            state.nextChange?.let {
                Text(tr("Через ${formatDuration(between(now, it))}", "Через ${formatDuration(between(now, it))}"), style = Type.bodyBold.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Color.White)
            }
        }
        state.nextWindow?.takeIf { !state.schedulesSuspended || state.lightOn != null }?.let { (a, b) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).border(3.dp, c.ink, RoundedCornerShape(28.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.lightOn == true) tr("Світла не буде", "Света не будет") else tr("Зараз без світла", "Сейчас без света"), style = Type.bodyBold.copy(fontSize = 20.sp), color = c.ink)
                Text("${a.hm()} — ${b.hm()}", style = Type.bigNumber.copy(fontSize = 34.sp), color = c.ink)
                Text(tr("Увімкнуть о ${b.hm()}", "Включат в ${b.hm()}"), style = Type.bodyBold.copy(fontSize = 18.sp), color = c.ink2)
            }
        }
        PrimaryButton(tr("Графік по годинах", "График по часам"), onDetail, Modifier.fillMaxWidth().heightIn(min = 72.dp), outlined = true)
        PrimaryButton(tr("Графік на завтра", "График на завтра"), onSchedule, Modifier.fillMaxWidth().heightIn(min = 72.dp), outlined = true)
    }
}


/** Заголовок для области, где известен только режим (график по очередям оператор публикует картинкой). */
fun regionOnlyTitle(state: NowState): String = when (state.type) {
    OutageType.Stabilization -> tr("Діють графіки відключень", "Действуют графики отключений")
    OutageType.None -> tr("Відключень не заплановано", "Отключений не запланировано")
    else -> tr("Немає даних", "Нет данных")
}

fun regionOnlyNote(state: NowState): String {
    val op = state.operator ?: tr("обленерго", "облэнерго")
    return when (state.type) {
        OutageType.Stabilization -> tr("У вашій області застосовуються графіки погодинних відключень. Години для вашої черги $op публікує лише картинкою — подивіться їх на сайті чи в каналі оператора.",
            "В вашей области применяются графики почасовых отключений. Часы для вашей очереди $op публикует только картинкой — посмотрите их на сайте или в канале оператора.")
        OutageType.None -> tr("$op не повідомляв про відключення на сьогодні.", "$op не сообщал об отключениях на сегодня.")
        else -> tr("$op ще не повідомив, чи діють сьогодні графіки.", "$op ещё не сообщил, действуют ли сегодня графики.")
    }
}
