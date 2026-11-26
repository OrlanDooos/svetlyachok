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
import ua.svetlo.monitor.data.Scenario
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
    onReport: () -> Unit,
    onNotif: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val addr = s.selected
    val state = repo.now(addr.queue, now, s.scenario)
    val today = repo.schedule(addr.queue, now.toLocalDate(), s.scenario)
    if (LocalLargeText.current) {
        LargeHome(s, state, now, onAddresses, onReport, onSchedule)
        return
    }
    val w = LocalWindow.current
    val header: @Composable () -> Unit = {
        HomeHeader(s, now, onAddresses, onNotif)
        AddressChips(s, onSelect)
        StatusChips(s, state)
    }
    val status: @Composable () -> Unit = { StatusCard(state, now, onReport) }
    val todayCard: @Composable () -> Unit = {
        SCard {
            SectionHeader(tr("Сьогодні за графіком", "Сегодня по графику"), tr("Увесь тиждень", "Вся неделя"), onSchedule)
            val frac = (now.hour * 60 + now.minute) / 1440f
            if (state.schedulesSuspended) {
                Text(tr("Поки діють аварійні відключення, графік черги не застосовується.", "Пока действуют аварийные отключения, график очереди не применяется."), style = Type.small, color = Svetlo.colors.ink2)
            }
            DayBar(today.slots, nowFraction = frac, override = if (state.schedulesSuspended) Svetlo.colors.track else null)
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
            StatTile(tr("Середнє за тиждень", "Среднее за неделю"), tr("5 год 40 хв", "5 ч 40 м"), tr("на 30 хв менше", "на 30 мин меньше"), Svetlo.colors.greenInk, Modifier.weight(1f), onArea)
        }
    }
    val why: @Composable () -> Unit = {
        if (state.schedulesSuspended) {
            SCard {
                Text(tr("Чому немає світла?", "Почему нет света?"), style = Type.section, color = Svetlo.colors.ink, modifier = Modifier.semantics { heading() })
                Text(
                    if (state.type == OutageType.Blackout) tr("Масштабна аварія в енергосистемі. Енергетики відновлюють живлення по черзі, спершу критичну інфраструктуру. Час увімкнення невідомий.", "Масштабная авария в энергосистеме. Энергетики восстанавливают питание по очереди, сначала критическую инфраструктуру. Время включения неизвестно.")
                    else tr("Після пошкодження енергосистеми в регіоні запровадили аварійні відключення. Поки вони діють, графіки черг не застосовуються. Час увімкнення може змінюватися.", "После повреждения энергосистемы в регионе ввели аварийные отключения. Пока они действуют, графики очередей не применяются. Время включения может меняться."),
                    style = Type.small.copy(lineHeight = 21.sp), color = Svetlo.colors.ink2,
                )
                Text(tr("Джерело: оператор мережі та Укренерго", "Источник: оператор сети и Укрэнерго"), style = Type.caption, color = Svetlo.colors.muted)
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
                Text("${s.selected.labelName} · ${s.selected.street.shortStreet()}", style = Type.h2, color = c.ink, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
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
private fun StatusChips(s: Settings, state: NowState) {
    val c = Svetlo.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(tr("Черга ${s.selected.queue}", "Очередь ${s.selected.queue}"))
        Pill(s.selected.cityName, icon = Icons.Rounded.LocationOn, fg = c.ink2)
        if (state.schedulesSuspended) {
            Pill(tr("Графіки не діють", "Графики не действуют"), bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
        } else {
            Text(tr("оновлено 2 хв тому", "обновлено 2 мин назад"), style = Type.caption, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun StatusCard(state: NowState, now: LocalDateTime, onReport: () -> Unit) {
    val c = Svetlo.colors
    val (color, soft) = when {
        state.type == OutageType.Blackout -> Status.Black to c.blackSoft
        state.type == OutageType.Emergency -> Status.Red to c.redSoft
        state.lightOn -> Status.Green to c.greenSoft
        else -> Status.Yellow to c.yellowSoft
    }
    SCard(Modifier.fillMaxWidth().animateContentSize(), radius = 28.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusOrb(color, soft, if (state.lightOn) Bolt else BoltOff, calm = state.lightOn)
            AnimatedContent(state.lightOn, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "title") { on ->
                Text(if (on) tr("Світло є", "Свет есть") else tr("Світла немає", "Света нет"), style = Type.display, color = c.ink, modifier = Modifier.semantics { heading() })
            }
            when {
                state.schedulesSuspended -> EmergencyBlock(state, now, color)
                state.type == OutageType.None -> Text(tr("Відключень за графіком немає", "Отключений по графику нет"), style = Type.body, color = c.ink2)
                else -> ScheduledBlock(state, now)
            }
            // «По факту» показываем только при аварийных и блэкауте: в обычный день статус задаёт график.
            if (state.factReports != null) FactStrip(state, onReport)
        }
    }
}

@Composable
private fun ScheduledBlock(state: NowState, now: LocalDateTime) {
    val c = Svetlo.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (state.lightOn) tr("Наступне відключення через", "Следующее отключение через") else tr("Світло увімкнуть через", "Свет включат через"), style = Type.body, color = c.ink2, textAlign = TextAlign.Center)
        val left = state.nextChange?.let { between(now, it) } ?: 0
        Text(formatDuration(left), style = Type.number, color = c.ink)
    }
    state.nextWindow?.let { (a, b) ->
        Pill(tr("${a.hm()}–${b.hm()} · стабілізаційне", "${a.hm()}–${b.hm()} · стабилизационное"), bg = c.yellowSoft, fg = c.yellowInk, border = null, dot = Status.Yellow)
    }
}

@Composable
private fun EmergencyBlock(state: NowState, now: LocalDateTime, color: Color) {
    val c = Svetlo.colors
    val soft = if (state.type == OutageType.Blackout) c.blackSoft else c.redSoft
    val ink = if (state.type == OutageType.Blackout) c.ink else c.redInk
    Pill(if (state.type == OutageType.Blackout) tr("Блекаут", "Блэкаут") else tr("Аварійне відключення", "Аварийное отключение"), bg = soft, fg = ink, border = null, dot = color, blink = true)
    val since = state.since ?: now
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(tr("Відключили о ${since.hm()}", "Отключили в ${since.hm()}"), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted, modifier = Modifier.weight(1f))
            Text(state.nextChange?.let { tr("Орієнтовно до ${it.hm()}", "Ориентировочно до ${it.hm()}") } ?: tr("Час увімкнення невідомий", "Время включения неизвестно"), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.muted, textAlign = TextAlign.End)
        }
        val total = state.nextChange?.let { between(since, it) } ?: 0
        val passed = between(since, now)
        val target = if (total > 0) (passed.toFloat() / total).coerceIn(0.03f, 1f) else 0.5f
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val p by animateFloatAsState(if (shown) target else 0f, tween(900), label = "p")
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.track)) {
            Box(Modifier.fillMaxWidth(p).height(10.dp).clip(CircleShape).background(color))
        }
        val text = buildAnnotatedString {
            if (state.nextChange != null) {
                append(tr("Залишилося приблизно ", "Осталось примерно "))
                withStyle(SpanStyle(fontFamily = ua.svetlo.monitor.ui.theme.Onest, fontWeight = FontWeight.SemiBold)) { append(formatDuration(between(now, state.nextChange))) }
            } else {
                append(tr("Без світла вже ", "Без света уже "))
                withStyle(SpanStyle(fontFamily = ua.svetlo.monitor.ui.theme.Onest, fontWeight = FontWeight.SemiBold)) { append(formatDuration(passed)) }
            }
        }
        Text(text, style = Type.body, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FactStrip(state: NowState, onReport: () -> Unit) {
    val c = Svetlo.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).clickable(onClick = onReport).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            listOf(Color(0xFFC7D2FE), Color(0xFFFDE68A), Color(0xFFBBF7D0)).forEachIndexed { i, col ->
                Box(Modifier.offset(x = (-8 * i).dp).size(22.dp).clip(CircleShape).background(c.surface2).padding(2.dp).clip(CircleShape).background(col))
            }
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(tr("Фактично світла немає. ", "По факту света нет. ")) }
                append(tr("Повідомили ${state.factReports} сусідів і датчиків", "Сообщили ${state.factReports} соседей и датчиков"))
            },
            style = Type.small, color = c.ink, modifier = Modifier.weight(1f).offset(x = (-12).dp),
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}

/** Упрощённая главная для режима крупного шрифта: контрастно, только главное. */
@Composable
private fun LargeHome(s: Settings, state: NowState, now: LocalDateTime, onAddresses: () -> Unit, onReport: () -> Unit, onSchedule: () -> Unit) {
    val c = Svetlo.colors
    val w = LocalWindow.current
    val bg = when {
        state.type == OutageType.Blackout -> Status.Black
        state.type == OutageType.Emergency -> Color(0xFFB91C1F)
        state.lightOn -> Color(0xFF1E8E57)
        else -> Color(0xFF8A6A00)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = w.gutter + 4.dp, end = w.gutter + 4.dp, top = 20.dp, bottom = if (w.useRail) 24.dp else BottomBarSpace + 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAddresses).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.selected.labelName} · ${s.selected.street.shortStreet()}", style = Type.h2, color = c.ink, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.ink, modifier = Modifier.size(32.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (state.lightOn) Bolt else BoltOff, null, tint = Color.White, modifier = Modifier.size(48.dp))
                Text(if (state.lightOn) tr("Світло є", "Свет есть") else tr("Світла немає", "Света нет"), style = Type.display.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold), color = Color.White)
            }
            val line1 = when {
                state.schedulesSuspended -> if (state.type == OutageType.Blackout) tr("Блекаут", "Блэкаут") else tr("Аварійне відключення", "Аварийное отключение")
                state.type == OutageType.None -> tr("Відключень немає", "Отключений нет")
                state.lightOn -> tr("Вимкнуть о ${state.nextChange?.hm()}", "Выключат в ${state.nextChange?.hm()}")
                else -> tr("Увімкнуть о ${state.nextChange?.hm()}", "Включат в ${state.nextChange?.hm()}")
            }
            Text(line1, style = Type.h2.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Color.White)
            state.nextChange?.let {
                Text(tr("Через ${formatDuration(between(now, it))}", "Через ${formatDuration(between(now, it))}"), style = Type.bodyBold.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Color.White)
            }
        }
        state.nextWindow?.takeIf { !state.schedulesSuspended }?.let { (a, b) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).border(3.dp, c.ink, RoundedCornerShape(28.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.lightOn) tr("Світла не буде", "Света не будет") else tr("Зараз без світла", "Сейчас без света"), style = Type.bodyBold.copy(fontSize = 20.sp), color = c.ink)
                Text("${a.hm()} — ${b.hm()}", style = Type.bigNumber.copy(fontSize = 34.sp), color = c.ink)
                Text(tr("Увімкнуть о ${b.hm()}", "Включат в ${b.hm()}"), style = Type.bodyBold.copy(fontSize = 18.sp), color = c.ink2)
            }
        }
        PrimaryButton(tr("У мене немає світла", "У меня нет света"), onReport, Modifier.fillMaxWidth().heightIn(min = 72.dp))
        PrimaryButton(tr("Графік на тиждень", "График на неделю"), onSchedule, Modifier.fillMaxWidth().heightIn(min = 72.dp), outlined = true)
    }
}

val Scenario.isAlert get() = this == Scenario.Emergency || this == Scenario.Blackout
