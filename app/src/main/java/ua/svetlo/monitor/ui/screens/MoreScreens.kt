package ua.svetlo.monitor.ui.screens

import ua.svetlo.monitor.data.tr
import ua.svetlo.monitor.data.Lang

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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ua.svetlo.monitor.data.LiveRepository
import ua.svetlo.monitor.data.RegionMode
import ua.svetlo.monitor.data.DataSource
import ua.svetlo.monitor.BuildConfig
import java.time.Duration
import java.time.OffsetDateTime

/** Где узнать свою очередь по адресу: официальные страницы операторов. */
val LookupUrls = mapOf(
    "kyiv-city" to "https://www.dtek-kem.com.ua/ua/shutdowns",
    "kyiv" to "https://www.dtek-krem.com.ua/ua/shutdowns",
    "dnipropetrovsk" to "https://www.dtek-dnem.com.ua/ua/shutdowns",
    "odessa" to "https://www.dtek-oem.com.ua/ua/shutdowns",
    "lviv" to "https://poweron.loe.lviv.ua/",
    "zaporizhia" to "https://www.zoe.com.ua/",
    "zhytomyr" to "https://www.ztoe.com.ua/unhooking-search.php",
    "mykolaiv" to "https://off.energy.mk.ua/",
    "kirovohrad" to "https://kiroe.com.ua/energy/plan",
    "rivne" to "https://www.roe.vsei.ua/disconnections",
    "khmelnytskyi" to "https://hoe.com.ua/page/pogodinni-vidkljuchennja",
    "cherkasy" to "https://www.cherkasyoblenergo.com/",
    "kharkiv" to "https://oblenergo.kharkiv.ua/",
    "ivano-frankivsk" to "https://www.oe.if.ua/",
    "ternopil" to "https://www.toe.com.ua/",
    "sumy" to "https://www.soe.com.ua/consumers/shutdowns",
    "chernihiv" to "https://www.energy.cn.ua/",
    "chernivtsi" to "https://oblenergo.cv.ua/shutdowns/",
    "volyn" to "https://energy.volyn.ua/",
    "zakarpattia" to "https://zakarpat.energy/",
    "vinnytsia" to "https://www.voe.com.ua/",
    "poltava" to "https://www.poe.pl.ua/disconnection/power-outages/",
    "kherson" to "https://www.ksoe.com.ua/",
    "donetsk" to "https://www.dtek-dem.com.ua/ua/shutdowns",
)

/** «5 хв тому» для времени обновления. */
fun ago(t: OffsetDateTime?): String {
    if (t == null) return tr("ще не оновлювалось", "ещё не обновлялось")
    val m = Duration.between(t, OffsetDateTime.now()).toMinutes().coerceAtLeast(0)
    return when {
        m < 1 -> tr("щойно", "только что")
        m < 60 -> tr("$m хв тому", "$m мин назад")
        m < 24 * 60 -> tr("${m / 60} год тому", "${m / 60} ч назад")
        else -> tr("${m / 1440} дн тому", "${m / 1440} дн назад")
    }
}

@Composable
fun MoreScreen(s: Settings, nav: (String) -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    val w = LocalWindow.current
    val tools: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
            val rows = listOf(
                Triple(tr("Мої адреси", "Мои адреса"), "${s.addresses.size}", Routes.Addresses) to (Icons.Rounded.Home to (Status.Green to c.greenSoft)),
                Triple(tr("Вікна світла", "Окна света"), tr("сьогодні", "сегодня"), Routes.Windows) to (Bolt to (Status.Yellow to c.yellowSoft)),
                Triple(tr("Області і джерела даних", "Области и источники данных"), "", Routes.Sources) to (Icons.Rounded.Public to (c.accent to c.accentSoft)),
                Triple(tr("Віджет", "Виджет"), "", Routes.Widgets) to (Icons.Rounded.Widgets to (Status.Blue to Color(0x223B82F6))),
                Triple(tr("Написати відгук", "Написать отзыв"), "", Routes.Feedback) to (Icons.Rounded.Feedback to (Status.Orange to Color(0x22F97316))),
            )
            rows.forEachIndexed { i, (r, ic) ->
                NavRow(r.first, r.second.ifEmpty { null }, divider = i < rows.lastIndex, leading = { IconTile(ic.first, ic.second.first, ic.second.second, 32.dp) }) { nav(r.third) }
            }
        }
    }
    val settings: @Composable () -> Unit = {
        SCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
            NavRow(tr("Сповіщення", "Уведомления"), if (s.remindMinutes > 0) tr("за ${s.remindMinutes} хв", "за ${s.remindMinutes} мин") else tr("вимк.", "выкл.")) { nav(Routes.Notif) }
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Мова", "Язык"), style = Type.bodyBold, color = c.ink)
                Segmented(listOf("Українська", "Русский"), s.lang.ordinal, { i -> update { it.copy(lang = Lang.entries[i]) } }, Modifier.fillMaxWidth())
            }
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Тема", "Тема"), style = Type.bodyBold, color = c.ink)
                Segmented(listOf(tr("Системна", "Системная"), tr("Світла", "Светлая"), tr("Темна", "Тёмная")), s.theme.ordinal, { i -> update { it.copy(theme = ThemeMode.entries[i]) } }, Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow(tr("Великий шрифт", "Крупный шрифт"), tr("Спрощена головна, великі кнопки", "Упрощённая главная, крупные кнопки"), s.largeText, { on -> update { it.copy(largeText = on) } })
        }
    }
    val about: @Composable () -> Unit = {
        Text(
            tr("Світлячок ${BuildConfig.VERSION_NAME}. Відкритий код: github.com/OrlanDooos/svetlyachok. Графіки — з сайтів і каналів обленерго; для ДТЕК резервне джерело — outage-data-ua (MIT). Мапа областей: @svg-maps/ukraine (CC BY 4.0). Контури будинків: © учасники OpenStreetMap (ODbL). Адреси будинків, яких немає в OpenStreetMap: © АТ «Візіком» (Visicom), з дозволу компанії. Шрифти Onest і Manrope (OFL).",
                "Светлячок ${BuildConfig.VERSION_NAME}. Открытый код: github.com/OrlanDooos/svetlyachok. Графики — с сайтов и каналов облэнерго; для ДТЭК запасной источник — outage-data-ua (MIT). Карта областей: @svg-maps/ukraine (CC BY 4.0). Контуры домов: © участники OpenStreetMap (ODbL). Адреса домов, которых нет в OpenStreetMap: © АО «Визиком» (Visicom), с разрешения компании. Шрифты Onest и Manrope (OFL)."),
            style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted,
        )
    }
    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("Ще", "Ещё"), style = Type.h1, color = c.ink); tools(); about()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.height(30.dp)); settings()
            }
        }
    } else ScreenColumn { Text(tr("Ще", "Ещё"), style = Type.h1, color = c.ink); tools(); settings(); about() }
}

/** Цвет и подпись статуса адреса для карточек. */
@Composable
fun stateLook(st: ua.svetlo.monitor.data.NowState): Pair<Color, String> = when {
    st.type == OutageType.Blackout -> Status.Black to tr("Блекаут", "Блэкаут")
    st.type == OutageType.Emergency -> Status.Red to tr("Аварійні", "Аварийные")
    st.lightOn == null -> Status.NoData to tr("Немає графіка", "Нет графика")
    st.lightOn -> Status.Green to tr("Світло є", "Свет есть")
    else -> Status.Yellow to tr("Світла немає", "Света нет")
}

@Composable
fun AddressesScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    var editing by remember { mutableStateOf<Address?>(null) }
    var adding by remember { mutableStateOf(false) }
    ScreenColumn {
        BackHeader(tr("Мої адреси", "Мои адреса"), tr("Світло у вас і в близьких", "Свет у вас и у близких"), onBack)
        s.addresses.forEach { a ->
            val st = repo.now(a.region, a.queue, now)
            val on = a.id == s.selectedId
            if (editing?.id == a.id) {
                AddressEditor(repo, a, onCancel = { editing = null }) { na -> update { it.copy(addresses = it.addresses.map { x -> if (x.id == a.id) na else x }) }; editing = null }
                return@forEach
            }
            SCard(Modifier.fillMaxWidth().then(if (on) Modifier.border(2.dp, c.accent, RoundedCornerShape(22.dp)) else Modifier), radius = 22.dp, padding = PaddingValues(16.dp),
                onClick = { update { it.copy(selectedId = a.id) }; onBack() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (col, label) = stateLook(st)
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(col), contentAlignment = Alignment.Center) {
                        Icon(if (st.lightOn == true) Bolt else BoltOff, null, tint = if (col == Status.Yellow || col == Status.NoData) Color(0xFF16181D) else Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(a.labelName, style = Type.bodyBold, color = c.ink)
                        Text(listOf(a.cityName, a.street.shortStreet(), tr("черга ${a.queue}", "очередь ${a.queue}")).filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = c.muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(label, style = Type.smallBold, color = c.ink)
                        st.nextChange?.let { Text((if (st.lightOn == true) tr("до ", "до ") else tr("увімкнуть ", "включат ")) + it.hm(), style = Type.caption, color = c.muted) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { editing = a }.padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Check, null, tint = c.muted, modifier = Modifier.size(16.dp)); Text(tr("Змінити", "Изменить"), style = Type.caption, color = c.muted)
                    }
                    if (s.addresses.size > 1) {
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { update { st2 -> st2.copy(addresses = st2.addresses.filter { it.id != a.id }, selectedId = if (st2.selectedId == a.id) st2.addresses.first { it.id != a.id }.id else st2.selectedId) } }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.Delete, null, tint = c.muted, modifier = Modifier.size(16.dp)); Text(tr("Видалити", "Удалить"), style = Type.caption, color = c.muted)
                        }
                    }
                }
            }
        }
        if (adding) {
            AddressEditor(repo, null, onCancel = { adding = false }) { addr ->
                update { it.copy(addresses = it.addresses + addr.copy(id = "a" + System.currentTimeMillis())) }
                adding = false
            }
        } else {
            PrimaryButton(tr("+ Додати адресу", "+ Добавить адрес"), { adding = true }, Modifier.fillMaxWidth(), outlined = true)
        }
    }
}

/**
 * Выбор адреса: область → очередь (+ необязательные подпись и улица).
 * Очередь пока выбирает человек: базу «адрес → очередь» по всем областям собираем отдельно.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddressEditor(repo: OutageRepository, initial: Address?, compact: Boolean = false, onCancel: (() -> Unit)? = null, onDone: (Address) -> Unit) {
    val c = Svetlo.colors
    val ctx = LocalContext.current
    var region by remember { mutableStateOf(initial?.region) }
    var queue by remember { mutableStateOf(initial?.queue) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var street by remember { mutableStateOf(initial?.street ?: "") }
    var showAll by remember { mutableStateOf(false) }
    val regions = repo.regions().filter { it.id != "crimea" && it.id != "luhansk" }
    SCard(Modifier.fillMaxWidth(), radius = 22.dp) {
        Text(tr("1. Область", "1. Область"), style = Type.section, color = c.ink)
        val withData = regions.filter { it.hasData }
        val shown = if (showAll || region != null && withData.none { it.id == region }) regions else withData
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { r ->
                val on = r.id == region
                Pill(r.name.replace(tr(" область", " область"), ""), bg = if (on) c.inverse else c.surface, fg = if (on) c.onInverse else if (r.hasData) c.ink else c.muted,
                    border = if (on) null else c.line, dot = if (r.hasData) null else Status.NoData,
                    onClick = { if (region != r.id) { region = r.id; queue = null } })
            }
            if (!showAll && shown.size < regions.size) Pill(tr("Інші області…", "Другие области…"), fg = c.accent, border = c.line, onClick = { showAll = true })
        }
        region?.let { rid ->
            if (repo.summary(rid) == null) {
                Text(tr("Для цієї області ми ще не збираємо графіки. Ви можете зберегти адресу — щойно джерело запрацює, графік з'явиться сам.",
                    "Для этой области мы ещё не собираем графики. Можно сохранить адрес — как только источник заработает, график появится сам."), style = Type.small, color = c.ink2)
            }
            Box(Modifier.height(6.dp))
            Text(tr("2. Ваша черга", "2. Ваша очередь"), style = Type.section, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repo.queues(rid).forEach { q ->
                    val on = q == queue
                    Box(Modifier.widthIn(min = 56.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) c.accent else c.surface2).clickable { queue = q }, contentAlignment = Alignment.Center) {
                        Text(q, style = Type.bodyBold, color = if (on) c.onAccent else c.ink, modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
            }
            LookupUrls[rid]?.let { url ->
                Text(tr("Не знаєте чергу? Дізнайтеся за адресою на сайті оператора →", "Не знаете очередь? Узнайте по адресу на сайте оператора →"), style = Type.smallBold, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 6.dp))
            }
            if (!compact) {
                Box(Modifier.height(6.dp))
                Text(tr("3. Підпис і адреса (необов'язково)", "3. Подпись и адрес (необязательно)"), style = Type.section, color = c.ink)
                InputField(label, { label = it }, tr("Наприклад: Дім, Мама, Офіс", "Например: Дом, Мама, Офис"))
                InputField(street, { street = it }, tr("Вулиця і будинок", "Улица и дом"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onCancel?.let { PrimaryButton(tr("Скасувати", "Отмена"), it, Modifier.weight(1f), outlined = true) }
            val ready = region != null && queue != null
            PrimaryButton(tr("Зберегти", "Сохранить"), {
                val r = region ?: return@PrimaryButton; val q = queue ?: return@PrimaryButton
                onDone(Address(initial?.id ?: "home", label.trim().ifEmpty { initial?.label ?: tr("Дім", "Дом") }, street.trim(),
                    LiveRepository.RegionCity[r] ?: "", r, q))
            }, Modifier.weight(1f), bg = if (ready) c.accent else c.track, fg = if (ready) c.onAccent else c.muted)
        }
    }
}

@Composable
fun InputField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Svetlo.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.muted)
        BasicTextField(value, onChange, singleLine = true, textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun NotifScreen(s: Settings, onBack: () -> Unit, update: ((Settings) -> Settings) -> Unit) {
    val c = Svetlo.colors
    val ctx = LocalContext.current
    ScreenColumn {
        BackHeader(tr("Сповіщення", "Уведомления"), tr("Для всіх адрес", "Для всех адресов"), onBack)
        if (!ua.svetlo.monitor.notify.Notifications.allowed(ctx)) {
            SCard(Modifier.fillMaxWidth(), color = c.yellowSoft) {
                Text(tr("Сповіщення вимкнені в налаштуваннях телефона", "Уведомления выключены в настройках телефона"), style = Type.bodyBold, color = c.yellowInk)
                PrimaryButton(tr("Відкрити налаштування", "Открыть настройки"), {
                    runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.fillMaxWidth())
            }
        }
        SCard(Modifier.fillMaxWidth()) {
            Text(tr("Нагадати до відключення", "Напомнить до отключения"), style = Type.section, color = c.ink)
            val opts = listOf(0, 5, 15, 30, 60)
            Segmented(opts.map { if (it == 0) tr("Ні", "Нет") else if (it == 60) tr("1 год", "1 ч") else tr("$it хв", "$it мин") }, opts.indexOf(s.remindMinutes).coerceAtLeast(0), { i -> update { it.copy(remindMinutes = opts[i]) } }, Modifier.fillMaxWidth())
            Text(tr("Нагадування приходять для всіх ваших адрес.", "Напоминания приходят для всех ваших адресов."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
        SCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
            ToggleRow(tr("Графік змінився", "График изменился"), tr("Обленерго опублікувало новий графік", "Облэнерго опубликовало новый график"), s.notifyChanges, { v -> update { it.copy(notifyChanges = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow(tr("Світло увімкнуть за графіком", "Свет включат по графику"), tr("Коли закінчується відключення", "Когда заканчивается отключение"), s.notifyPowerBack, { v -> update { it.copy(notifyPowerBack = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow(tr("Аварійні відключення", "Аварийные отключения"), tr("Навіть у тихі години", "Даже в тихие часы"), s.notifyEmergency, { v -> update { it.copy(notifyEmergency = v) } })
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            ToggleRow(tr("Тихі години 23:00–07:00", "Тихие часы 23:00–07:00"), tr("Без звуку, крім аварійних", "Без звука, кроме аварийных"), s.quietNight, { v -> update { it.copy(quietNight = v) } })
        }
        Text(tr("Графіки перевіряємо приблизно кожні 15 хвилин. Android може відкладати перевірку, якщо телефон в режимі економії енергії.",
            "Графики проверяем примерно каждые 15 минут. Android может откладывать проверку, если телефон в режиме экономии энергии."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
    }
}

/** Какие области покрыты, откуда данные и когда обновлялись. */
@Composable
fun SourcesScreen(repo: OutageRepository, onBack: () -> Unit) {
    val c = Svetlo.colors
    val ctx = LocalContext.current
    val regions = repo.regions().filter { it.id != "crimea" && it.id != "luhansk" }
    ScreenColumn {
        BackHeader(tr("Області і джерела", "Области и источники"), tr("Звідки беремо графіки", "Откуда берём графики"), onBack)
        Text(tr("Графіки збираємо кожні 10 хвилин з офіційних сайтів і каналів обленерго. Статус задає графік; інший статус — лише коли оператор оголошує аварійні відключення.",
            "Графики собираем каждые 10 минут с официальных сайтов и каналов облэнерго. Статус задаёт график; другой статус — только когда оператор объявляет аварийные отключения."), style = Type.small, color = c.ink2)
        val (with, without) = regions.partition { it.hasData }
        SectionHeader(tr("Є графіки · ${with.size}", "Есть графики · ${with.size}"))
        with.forEach { r ->
            val sm = repo.summary(r.id)
            val rd = repo.region(r.id)
            SCard(Modifier.fillMaxWidth(), radius = 18.dp, padding = PaddingValues(14.dp), onClick = rd?.source?.let { src -> { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(r.type.color))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = Type.bodyBold, color = c.ink)
                        Text(listOfNotNull(sm?.operator, r.type.title).joinToString(" · "), style = Type.small, color = c.muted)
                        rd?.sourceNote?.let { Text(it, style = Type.caption, color = c.muted) }
                    }
                    Text(if (sm?.ok == false) tr("помилка", "ошибка") else ago(sm?.checkedAt), style = Type.caption, color = if (sm?.ok == false) c.redInk else c.muted)
                }
            }
        }
        SectionHeader(tr("Поки без даних · ${without.size}", "Пока без данных · ${without.size}"))
        Text(tr("Сайти цих обленерго недоступні для нашого збирача або публікують графіки картинками. Додаємо їх по черзі.",
            "Сайты этих облэнерго недоступны для нашего сборщика или публикуют графики картинками. Добавляем их по очереди."), style = Type.small, color = c.ink2)
        Text(without.joinToString(", ") { it.name.replace(" область", "") }, style = Type.small, color = c.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetsScreen(repo: OutageRepository, s: Settings, now: LocalDateTime, onBack: () -> Unit) {
    val c = Svetlo.colors
    val st = repo.now(s.selected.region, s.selected.queue, now)
    val today = repo.schedule(s.selected.region, s.selected.queue, now.toLocalDate())
    val (col, label) = stateLook(st)
    ScreenColumn {
        BackHeader(tr("Віджет", "Виджет"), tr("Світло на головному екрані", "Свет на главном экране"), onBack)
        Text(tr("Затисніть порожнє місце на головному екрані → Віджети → Світлячок. Віджет показує обрану адресу і оновлюється разом із графіками.",
            "Зажмите пустое место на главном экране → Виджеты → Светлячок. Виджет показывает выбранный адрес и обновляется вместе с графиками."), style = Type.small, color = c.ink2)
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(col, 10.dp)
                Text("${s.selected.labelName} · ${s.selected.queue}", style = Type.bodyBold, color = c.ink, modifier = Modifier.weight(1f))
                Text(label, style = Type.smallBold, color = c.ink2)
            }
            Text(st.nextChange?.let { (if (st.lightOn == true) tr("Вимкнуть о ", "Выключат в ") else tr("Увімкнуть о ", "Включат в ")) + it.hm() + " · " + formatDuration(between(now, it)) } ?: "", style = Type.small, color = c.muted)
            DayBar(today.slots, height = 22.dp, nowFraction = (now.hour * 60 + now.minute) / 1440f)
        }
    }
}

/** «Написати відгук»: текст уходит автору приложения (в его Telegram через наш сервер). */
@Composable
fun FeedbackScreen(s: Settings, onBack: () -> Unit) {
    val c = Svetlo.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ua.svetlo.monitor.data.Feedback.Result?>(null) }
    ScreenColumn {
        BackHeader(tr("Написати відгук", "Написать отзыв"), tr("Автор прочитає кожен", "Автор прочитает каждый"), onBack)
        if (result == ua.svetlo.monitor.data.Feedback.Result.Ok) {
            SCard(Modifier.fillMaxWidth(), color = c.greenSoft) {
                Text(tr("Дякуємо! Відгук надіслано.", "Спасибо! Отзыв отправлен."), style = Type.bodyBold, color = c.greenInk)
                Text(tr("Якщо ви залишили контакт, вам можуть відповісти.", "Если вы оставили контакт, вам могут ответить."), style = Type.small, color = c.greenInk)
            }
            PrimaryButton(tr("Готово", "Готово"), onBack, Modifier.fillMaxWidth())
            return@ScreenColumn
        }
        Text(tr("Помилка в графіку, не та черга, ідея чи побажання — напишіть, що завгодно.",
            "Ошибка в графике, не та очередь, идея или пожелание — пишите что угодно."), style = Type.small, color = c.ink2)
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(14.dp)) {
            if (text.isEmpty()) Text(tr("Ваш відгук", "Ваш отзыв"), style = Type.body, color = c.muted)
            BasicTextField(text, { text = it.take(ua.svetlo.monitor.data.Feedback.MAX) }, textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 122.dp))
        }
        InputField(contact, { contact = it.take(100) }, tr("Telegram або email для відповіді (необов'язково)", "Telegram или email для ответа (необязательно)"))
        when (result) {
            ua.svetlo.monitor.data.Feedback.Result.TooMany -> Text(tr("Забагато відгуків за добу. Спробуйте завтра.", "Слишком много отзывов за сутки. Попробуйте завтра."), style = Type.small, color = c.redInk)
            ua.svetlo.monitor.data.Feedback.Result.Failed -> Text(tr("Не вдалося надіслати. Перевірте інтернет і спробуйте ще раз.", "Не удалось отправить. Проверьте интернет и попробуйте ещё раз."), style = Type.small, color = c.redInk)
            else -> {}
        }
        val ready = text.trim().length >= 3 && !sending
        PrimaryButton(if (sending) tr("Надсилаємо…", "Отправляем…") else tr("Надіслати", "Отправить"), {
            if (!ready) return@PrimaryButton
            sending = true
            scope.launch {
                result = ua.svetlo.monitor.data.Feedback.send(ctx, text, contact, s.selected.region)
                sending = false
            }
        }, Modifier.fillMaxWidth(), bg = if (ready) c.accent else c.surface2, fg = if (ready) c.onAccent else c.muted)
        Text(tr("Разом з текстом надсилаємо версію застосунку, область вибраної адреси і модель телефона. Більше нічого.",
            "Вместе с текстом отправляем версию приложения, область выбранного адреса и модель телефона. Больше ничего."), style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
    }
}
