package ua.svetlo.monitor.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.svetlo.monitor.data.Address
import ua.svetlo.monitor.data.MapAssets
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.Scenario
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.between
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.Routes
import ua.svetlo.monitor.ui.components.BackHeader
import ua.svetlo.monitor.ui.components.DayBar
import ua.svetlo.monitor.ui.components.Dot
import ua.svetlo.monitor.ui.components.IconTile
import ua.svetlo.monitor.ui.components.NavRow
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.PrimaryButton
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.SectionHeader
import ua.svetlo.monitor.ui.components.Segmented
import ua.svetlo.monitor.ui.components.ToggleRow
import ua.svetlo.monitor.ui.components.color
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.ThemeMode
import ua.svetlo.monitor.ui.theme.Type
import java.time.LocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(s: Settings, nav: (String) -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    val w = LocalWindow.current
    val premium: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF16181D)).clickable { nav(Routes.Premium) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Status.Yellow), contentAlignment = Alignment.Center) { Icon(Bolt, null, tint = Color(0xFF16181D)) }
            Column(Modifier.weight(1f)) {
                Text("Светлячок Premium", style = Type.bodyBold.copy(fontWeight = FontWeight.ExtraBold, fontSize = 16.sp), color = Color.White)
                Text("Без рекламы, больше адресов, виджеты", style = Type.small, color = Color(0xFFC9CCD3))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White)
        }
    }
    val tools: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
            val rows = listOf(
                Triple("Мои адреса", "${s.addresses.size}", Routes.Addresses) to (Icons.Rounded.Home to (Status.Green to c.greenSoft)),
                Triple("Свет по факту и датчик", "", Routes.Report) to (Icons.Rounded.Groups to (c.accent to c.accentSoft)),
                Triple("Окна света", "сегодня", Routes.Windows) to (Bolt to (Status.Yellow to c.yellowSoft)),
                Triple("Энергосистема Украины", "дефицит", Routes.Grid) to (Icons.Rounded.ElectricBolt to (Status.Red to c.redSoft)),
                Triple("Виджеты", "", Routes.Widgets) to (Icons.Rounded.Widgets to (Status.Blue to Color(0x223B82F6))),
            )
            rows.forEachIndexed { i, (r, ic) ->
                NavRow(r.first, r.second.ifEmpty { null }, divider = i < rows.lastIndex, leading = { IconTile(ic.first, ic.second.first, ic.second.second, 32.dp) }) { nav(r.third) }
            }
        }
    }
    val settings: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
            NavRow("Уведомления", if (s.remindMinutes > 0) "за ${s.remindMinutes} мин" else "выкл.") { nav(Routes.Notif) }
            NavRow("Язык", "Русский", divider = true) {}
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тема", style = Type.bodyBold, color = c.ink)
                Segmented(listOf("Системная", "Светлая", "Тёмная"), s.theme.ordinal, { i -> update { it.copy(theme = ThemeMode.entries[i]) } }, Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow("Крупный шрифт", "Упрощённая главная, крупные кнопки", s.largeText, { on -> update { it.copy(largeText = on) } })
        }
    }
    val demo: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth(), radius = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тестовые данные", style = Type.section, color = c.ink, modifier = Modifier.weight(1f))
                Pill("тестовая сборка", bg = c.yellowSoft, fg = c.yellowInk, border = null)
            }
            Text("Переключите ситуацию, чтобы посмотреть, как приложение ведёт себя в разные дни.", style = Type.small, color = c.ink2)
            Scenario.entries.forEach { sc ->
                val on = sc == s.scenario
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) c.accentSoft else Color.Transparent)
                        .selectable(on, role = Role.RadioButton) { update { it.copy(scenario = sc) } }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val dot = when (sc) { Scenario.Normal -> Status.Yellow; Scenario.Emergency -> Status.Red; Scenario.Blackout -> Status.Black; Scenario.Calm -> Status.Green }
                    Dot(dot, 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(sc.title, style = Type.bodyBold, color = c.ink)
                        Text(sc.hint, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
                    }
                    if (on) Icon(Icons.Rounded.Check, null, tint = c.accent)
                }
            }
        }
        Text("Карта областей: @svg-maps/ukraine (CC BY 4.0). Контуры домов: © участники OpenStreetMap (ODbL). Шрифты Onest и Manrope (OFL).",
            style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
    }
    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Ещё", style = Type.h1, color = c.ink); premium(); tools()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.height(30.dp)); settings(); demo()
            }
        }
    } else ScreenColumn { Text("Ещё", style = Type.h1, color = c.ink); premium(); tools(); settings(); demo() }
}

@Composable
fun AddressesScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    var adding by remember { mutableStateOf(false) }
    ScreenColumn {
        BackHeader("Мои адреса", "Свет у вас и у близких", onBack)
        s.addresses.forEach { a ->
            val st = repo.now(a.queue, now, s.scenario)
            val on = a.id == s.selectedId
            SCard(Modifier.fillMaxWidth().then(if (on) Modifier.border(2.dp, c.accent, RoundedCornerShape(22.dp)) else Modifier), radius = 22.dp, padding = PaddingValues(16.dp),
                onClick = { update { it.copy(selectedId = a.id) }; onBack() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val col = when { st.type == OutageType.Blackout -> Status.Black; st.type == OutageType.Emergency -> Status.Red; st.lightOn -> Status.Green; else -> Status.Yellow }
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(col), contentAlignment = Alignment.Center) {
                        Icon(if (st.lightOn) Bolt else BoltOff, null, tint = if (col == Status.Yellow) Color(0xFF16181D) else Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = Type.bodyBold, color = c.ink)
                        Text("${a.city} · ${a.street.shortStreet()} · ${a.queue}", style = Type.small, color = c.muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (st.lightOn) "Свет есть" else "Света нет", style = Type.smallBold, color = c.ink)
                        Text(st.nextChange?.let { (if (st.lightOn) "до " else "включат ") + it.hm() } ?: if (st.schedulesSuspended) "аварийные" else "без отключений", style = Type.caption, color = c.muted)
                    }
                }
                if (s.addresses.size > 1 && a.id != "home") {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { update { st2 -> st2.copy(addresses = st2.addresses.filter { it.id != a.id }) } }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Delete, null, tint = c.muted, modifier = Modifier.size(16.dp)); Text("Удалить", style = Type.caption, color = c.muted)
                    }
                }
            }
        }
        if (adding) {
            AddressPicker(repo) { addr ->
                update { it.copy(addresses = it.addresses + addr.copy(id = "a" + System.currentTimeMillis(), label = "Адрес ${it.addresses.size + 1}")) }
                adding = false
            }
        } else {
            PrimaryButton("+ Добавить адрес", { adding = true }, Modifier.fillMaxWidth(), outlined = true)
        }
        Text("В бесплатной версии до 3 адресов. В Premium без ограничений.", style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
    }
}

/** Выбор адреса: поиск по домам из базы. Сейчас база — дома Печерского района из OpenStreetMap. */
@Composable
fun AddressPicker(repo: OutageRepository, onPick: (Address) -> Unit) {
    val ctx = LocalContext.current
    val city = remember { MapAssets.city(ctx, repo.queues) }
    val c = Svetlo.colors
    var q by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(q, { q = it }, "Улица и номер дома")
        val found = if (q.length < 2) emptyList() else city.buildings.filter { it.queue != null && it.address?.contains(q.trim(), true) == true }.distinctBy { it.address }.take(6)
        if (q.length >= 2 && found.isEmpty()) Text("В тестовой базе пока только Печерский район Киева. Попробуйте «Лєскова» или «Мечникова».", style = Type.small, color = c.ink2)
        found.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).clickable { onPick(Address("home", "Дом", b.address!!, "Киев", "kyiv-city", b.queue!!)) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.LocationOn, null, tint = c.accent)
                Text(b.address!!, style = Type.bodyBold, color = c.ink, modifier = Modifier.weight(1f))
                Pill("очередь ${b.queue}", bg = c.accentSoft, fg = c.accent, border = null)
            }
        }
    }
}

@Composable
fun NotifScreen(s: Settings, onBack: () -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    ScreenColumn {
        BackHeader("Уведомления", "Для всех адресов", onBack)
        SCard(Modifier.fillMaxWidth()) {
            Text("Напомнить до отключения", style = Type.section, color = c.ink)
            val opts = listOf(0, 5, 15, 30, 60)
            Segmented(opts.map { if (it == 0) "Нет" else if (it == 60) "1 ч" else "$it мин" }, opts.indexOf(s.remindMinutes).coerceAtLeast(0), { i -> update { it.copy(remindMinutes = opts[i]) } }, Modifier.fillMaxWidth())
        }
        SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
            ToggleRow("График изменился", "Облэнерго опубликовало новый график", s.notifyChanges, { v -> update { it.copy(notifyChanges = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow("Свет включили", "Сразу после включения", s.notifyPowerBack, { v -> update { it.copy(notifyPowerBack = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow("Аварийные отключения", "Даже в тихие часы", s.notifyEmergency, { v -> update { it.copy(notifyEmergency = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow("Тихие часы 23:00–07:00", "Без звука, кроме аварийных", s.quietNight, { v -> update { it.copy(quietNight = v) } })
        }
        SCard(Modifier.fillMaxWidth()) {
            Text("Как будет выглядеть", style = Type.section, color = c.ink)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface2).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16181D)), contentAlignment = Alignment.Center) { Icon(Bolt, null, tint = Status.Yellow, modifier = Modifier.size(20.dp)) }
                Column(Modifier.weight(1f)) {
                    Text("Через ${s.remindMinutes.takeIf { it > 0 } ?: 15} мин отключат свет", style = Type.bodyBold, color = c.ink)
                    Text("${s.selected.label} · очередь ${s.selected.queue}. Включат по графику.", style = Type.small, color = c.ink2)
                }
            }
        }
    }
}

@Composable
fun GridScreen(repo: OutageRepository, s: Settings, onBack: () -> Unit) {
    val c = Svetlo.colors
    val g = repo.grid(s.scenario)
    ScreenColumn {
        BackHeader("Энергосистема Украины", "По данным Укрэнерго", onBack)
        SCard(Modifier.fillMaxWidth()) {
            val bad = g.deficitMw > 0
            Pill(if (bad) "Дефицит мощности" else "Баланс", bg = if (bad) c.redSoft else c.greenSoft, fg = if (bad) c.redInk else c.greenInk, border = null, dot = if (bad) Status.Red else Status.Green, blink = bad)
            Text(if (bad) "−%,d МВт".format(java.util.Locale("ru"), g.deficitMw) else "0 МВт", style = Type.bigNumber, color = c.ink)
            Text("не хватает в часы пик", style = Type.body, color = c.ink2)
            Text(g.message, style = Type.small.copy(lineHeight = 20.sp), color = c.ink2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ua.svetlo.monitor.ui.components.StatTile("Потребление", "${g.consumptionMw / 1000},${(g.consumptionMw % 1000) / 100} ГВт", modifier = Modifier.weight(1f))
            ua.svetlo.monitor.ui.components.StatTile("Часы пик", g.peakHours, modifier = Modifier.weight(1f))
        }
        SCard(Modifier.fillMaxWidth()) {
            SectionHeader("Регионы с графиками", "${g.regionsWithSchedules} из 24")
            val regs = repo.regions(s.scenario).filter { it.type != OutageType.NoData }
            Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape)) {
                OutageType.entries.forEach { t ->
                    val n = regs.count { it.type == t }
                    if (n > 0) Box(Modifier.weight(n.toFloat()).height(12.dp).background(t.color))
                }
            }
        }
        SCard(Modifier.fillMaxWidth()) {
            SectionHeader("Как помочь энергосистеме")
            g.savingTips.forEach { t -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Dot(c.accent); Text(t, style = Type.body, color = c.ink) } }
        }
    }
}

@Composable
fun ReportScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit) {
    val c = Svetlo.colors
    var sent by remember { mutableIntStateOf(-1) }
    var sensor by remember { mutableStateOf(false) }
    val st = repo.now(s.selected.queue, now, s.scenario)
    ScreenColumn {
        BackHeader("Свет по факту", "${s.selected.label} · ${s.selected.street.shortStreet()}", onBack)
        SCard(Modifier.fillMaxWidth()) {
            Text("Статус по умолчанию задаёт график", style = Type.section, color = c.ink)
            Text("Отметки соседей и датчики учитываются только при аварийных отключениях и блэкауте, когда график не действует. Они помогают понять, где свет уже вернули.",
                style = Type.small.copy(lineHeight = 20.sp), color = c.ink2)
            if (st.factReports != null) Pill("Сейчас аварийный режим · ${st.factReports} отметок рядом", bg = c.redSoft, fg = c.redInk, border = null, dot = Status.Red, blink = true)
            else Pill("Сейчас действует график · отметки не нужны", bg = c.greenSoft, fg = c.greenInk, border = null, dot = Status.Green)
        }
        SCard(Modifier.fillMaxWidth()) {
            Text("Что у вас сейчас?", style = Type.section, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Свет есть" to Status.Green, "Света нет" to Status.Black).forEachIndexed { i, (t, col) ->
                    val on = sent == i
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (on) col else c.surface2).clickable { sent = i }.padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(if (i == 0) Bolt else BoltOff, null, tint = if (on) Color.White else col, modifier = Modifier.size(30.dp))
                        Text(t, style = Type.bodyBold, color = if (on) Color.White else c.ink)
                    }
                }
            }
            if (sent >= 0) Text("Спасибо! Отметка анонимная и видна соседям 15 минут.", style = Type.small, color = c.greenInk)
        }
        SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                ua.svetlo.monitor.ui.components.IconTile(Icons.Rounded.BatteryChargingFull, c.accent, c.accentSoft)
                Column(Modifier.weight(1f)) {
                    Text("Датчик «телефон на зарядке»", style = Type.bodyBold, color = c.ink)
                    Text("Старый телефон дома на зарядке сам сообщит, когда пропадёт питание", style = Type.small, color = c.muted)
                }
            }
            ToggleRow("Этот телефон — датчик", null, sensor, { sensor = it })
        }
    }
}

@Composable
fun PremiumScreen(onClose: () -> Unit) {
    val glow = rememberInfiniteTransition(label = "glow").animateFloat(1f, 1.12f, infiniteRepeatable(tween(1750, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    var plan by remember { mutableIntStateOf(1) }
    val w = LocalWindow.current
    Box(Modifier.fillMaxSize().background(Color(0xFF0F1014))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = w.gutter + 4.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1C22)).clickable(onClickLabel = "Закрыть", onClick = onClose), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Закрыть", tint = Color(0xFFF2F2F4))
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(96.dp).scale(glow.value).clip(RoundedCornerShape(32.dp)).background(Status.Yellow.copy(alpha = 0.5f + (glow.value - 1f) * 2.5f)))
                        Box(Modifier.size(80.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF16181D)), contentAlignment = Alignment.Center) {
                            Icon(Bolt, null, tint = Status.Yellow, modifier = Modifier.size(42.dp))
                        }
                    }
                    Text("Светлячок Premium", style = Type.display.copy(fontWeight = FontWeight.ExtraBold), color = Color(0xFFF2F2F4), textAlign = TextAlign.Center)
                    Text("Всё о свете у вас и у близких, без рекламы", style = Type.body, color = Color(0xFFC9CCD3), textAlign = TextAlign.Center)
                }
                listOf("Без рекламы", "Неограниченно адресов", "Все виджеты и живое уведомление", "Интеграция с инвертором и зарядной станцией", "История и статистика за год").forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF24233F)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Color(0xFFB3AFFF), modifier = Modifier.size(18.dp)) }
                        Text(p, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFFF2F2F4))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Месяц", "Год").forEachIndexed { i, t ->
                        val on = plan == i
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (on) Color(0xFF24233F) else Color(0xFF1A1C22))
                                .border(if (on) 2.dp else 1.dp, if (on) Color(0xFF8B85FF) else Color(0xFF2A2D35), RoundedCornerShape(20.dp))
                                .selectable(on, role = Role.RadioButton) { plan = i }.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t, style = Type.smallBold, color = if (on) Color(0xFFC9C6FF) else Color(0xFFA0A4AE), modifier = Modifier.weight(1f))
                                if (i == 1) Pill("Выгоднее", bg = Status.Yellow, fg = Color(0xFF16181D), border = null, style = Type.tiny.copy(fontWeight = FontWeight.ExtraBold))
                            }
                            Text("[ЦЕНА] ₴", style = Type.number.copy(fontSize = 20.sp), color = Color(0xFFF2F2F4))
                        }
                    }
                }
                PrimaryButton("Оформить Premium", {}, Modifier.fillMaxWidth().heightIn(min = 56.dp), bg = Status.Yellow, fg = Color(0xFF16181D))
                Text("Основные функции бесплатны всегда. В тестовой сборке оплата отключена.", style = Type.caption.copy(fontWeight = FontWeight.Medium), color = Color(0xFFA0A4AE), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetsScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit) {
    val c = Svetlo.colors
    val st = repo.now(s.selected.queue, now, s.scenario)
    val today = repo.schedule(s.selected.queue, now.toLocalDate(), s.scenario)
    val green = st.lightOn
    ScreenColumn {
        BackHeader("Виджеты", "Скоро на главном экране телефона", onBack)
        Text("Так будут выглядеть виджеты. В тестовой сборке их пока нельзя добавить на рабочий стол.", style = Type.small, color = c.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.size(160.dp).clip(RoundedCornerShape(26.dp)).background(if (green) Status.Green else Status.Black).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(if (green) Bolt else BoltOff, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Column {
                    Text(if (green) "Свет есть" else "Света нет", style = Type.h2, color = Color.White)
                    Text(st.nextChange?.let { (if (green) "до " else "включат в ") + it.hm() } ?: "", style = Type.smallBold, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Column(Modifier.widthIn(min = 280.dp).weight(1f).height(160.dp).clip(RoundedCornerShape(26.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(if (green) Status.Green else Status.Yellow, 10.dp)
                    Text("${s.selected.label} · ${s.selected.queue}", style = Type.bodyBold, color = c.ink, modifier = Modifier.weight(1f))
                    Text(st.nextChange?.let { formatDuration(between(now, it)) } ?: "", style = Type.smallBold, color = c.ink2)
                }
                DayBar(today.slots, height = 26.dp, nowFraction = (now.hour * 60 + now.minute) / 1440f)
                ua.svetlo.monitor.ui.components.HourLabels()
            }
        }
    }
}
