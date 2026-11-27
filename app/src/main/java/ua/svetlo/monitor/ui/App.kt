@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.svetlo.monitor.ui

import ua.svetlo.monitor.data.tr

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import ua.svetlo.monitor.AppViewModel
import ua.svetlo.monitor.data.I18n
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.Settings
import java.time.LocalDateTime
import ua.svetlo.monitor.ui.screens.AddressesScreen
import ua.svetlo.monitor.ui.screens.AreaScreen
import ua.svetlo.monitor.ui.screens.CityMapScreen
import ua.svetlo.monitor.ui.screens.SourcesScreen
import ua.svetlo.monitor.ui.screens.HomeScreen
import ua.svetlo.monitor.ui.screens.MapUkraineScreen
import ua.svetlo.monitor.ui.screens.MoreScreen
import ua.svetlo.monitor.ui.screens.NotifScreen
import ua.svetlo.monitor.ui.screens.FeedbackScreen
import ua.svetlo.monitor.ui.screens.OnboardingScreen
import ua.svetlo.monitor.ui.screens.ScheduleScreen
import ua.svetlo.monitor.ui.screens.WidgetsScreen
import ua.svetlo.monitor.ui.screens.WindowsScreen
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.SvetloTheme
import ua.svetlo.monitor.ui.theme.Type

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Map = "map"
    const val City = "city?queue={queue}&id={id}"
    fun city(queue: String? = null, id: String? = null) = listOfNotNull(queue?.let { "queue=$it" }, id?.let { "id=$it" }).joinToString("&").let { if (it.isEmpty()) "city" else "city?$it" }
    const val Schedule = "schedule"
    const val More = "more"
    const val Area = "area"
    const val Addresses = "addresses"
    const val Notif = "notif"
    const val Sources = "sources"
    const val Windows = "windows"
    const val Widgets = "widgets"
    const val Feedback = "feedback"
}

private data class Tab(val route: String, val title: String, val icon: ImageVector, val matches: Set<String>)

private val Tabs: List<Tab> get() = listOf(
    Tab(Routes.Home, tr("Головна", "Главная"), Icons.Rounded.Home, setOf(Routes.Home)),
    Tab(Routes.Map, tr("Мапа", "Карта"), Icons.Rounded.Map, setOf(Routes.Map, Routes.City, Routes.Area)),
    Tab(Routes.Schedule, tr("Графік", "График"), Icons.Rounded.CalendarMonth, setOf(Routes.Schedule, Routes.Windows)),
    Tab(Routes.More, tr("Ще", "Ещё"), Icons.Rounded.Tune, setOf(Routes.More, Routes.Addresses, Routes.Notif, Routes.Sources, Routes.Widgets)),
)

/** Высота нижней панели вместе с системной навигацией (меряется при отрисовке; крупный шрифт делает её выше). */
private val measuredBar = androidx.compose.runtime.mutableStateOf(96.dp)

/** Сколько места снизу оставлять под нижнюю панель. Пока открыта клавиатура, панель скрыта — места не нужно. */
val BottomBarSpace: androidx.compose.ui.unit.Dp
    @Composable get() = if (WindowInsets.isImeVisible) 0.dp else maxOf(96.dp, measuredBar.value)

@Composable
fun SvetloRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val s = settings ?: return
    SvetloContent(vm.repo, s, now, vm::update, onRefresh = vm::refresh)
}

/** Всё приложение без ViewModel: так его можно отрисовать в тестах со скриншотами. */
@Composable
fun SvetloContent(
    repo: OutageRepository,
    s: Settings,
    now: LocalDateTime,
    update: ((Settings) -> Settings) -> Unit,
    startRoute: String? = null,
    onRefresh: () -> Unit = {},
) {
    I18n.lang = s.lang
    // При смене языка пересоздаём интерфейс, чтобы все строки перечитались, и остаёмся в «Ещё».
    var lastLang by remember { mutableStateOf(s.lang) }
    var langStart by remember { mutableStateOf<String?>(null) }
    if (lastLang != s.lang) { lastLang = s.lang; langStart = if (s.onboarded) Routes.More else null }
    SvetloTheme(mode = s.theme, largeText = s.largeText) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Svetlo.colors.bg)) {
            CompositionLocalProvider(LocalWindow provides WindowLayout(maxWidth, maxHeight)) {
                key(s.lang) { AppShell(repo, s, now, update, langStart ?: startRoute, onRefresh) }
            }
        }
    }
}

@Composable
private fun AppShell(repo: OutageRepository, s: Settings, now: LocalDateTime, update: ((Settings) -> Settings) -> Unit, startRoute: String?, onRefresh: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val w = LocalWindow.current
    val showNav = route != null && route != Routes.Onboarding
    val start = startRoute ?: if (s.onboarded) Routes.Home else Routes.Onboarding

    Row(Modifier.fillMaxSize()) {
        if (showNav && w.useRail) NavRail(route, nav)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AppNavHost(repo, s, now, update, nav, start, onRefresh)
            if (showNav && !w.useRail && !WindowInsets.isImeVisible) {
                BottomBar(route, nav, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(Routes.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(route: String?, nav: NavHostController, modifier: Modifier) {
    val c = Svetlo.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(modifier.fillMaxWidth()
        .onSizeChanged { with(density) { measuredBar.value = it.height.toDp() } }
        .windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .shadow(if (c.dark) 0.dp else 16.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x2016181D), spotColor = Color(0x3016181D))
                .clip(RoundedCornerShape(24.dp))
                .background(c.surface)
                .then(if (c.dark) Modifier.border(1.dp, c.line, RoundedCornerShape(24.dp)) else Modifier)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Tabs.forEach { t -> NavItem(t, route in t.matches, Modifier.weight(1f)) { nav.goTab(t.route) } }
        }
    }
}

@Composable
private fun NavRail(route: String?, nav: NavHostController) {
    val c = Svetlo.colors
    Column(
        Modifier
            .fillMaxHeight()
            .background(c.surface)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .widthIn(min = 80.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(ua.svetlo.monitor.ui.theme.Status.Yellow), contentAlignment = Alignment.Center) {
            Icon(ua.svetlo.monitor.ui.screens.Bolt, null, tint = Color(0xFF16181D), modifier = Modifier.size(24.dp))
        }
        Box(Modifier.heightIn(min = 12.dp))
        Tabs.forEach { t -> NavItem(t, route in t.matches) { nav.goTab(t.route) } }
    }
}

@Composable
private fun NavItem(t: Tab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Svetlo.colors
    val fg by animateColorAsState(if (selected) c.accent else c.muted, label = "fg")
    val bg by animateColorAsState(if (selected) c.accentSoft else Color.Transparent, label = "bg")
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 18.dp, vertical = 4.dp)) {
            Icon(t.icon, null, tint = fg, modifier = Modifier.size(22.dp))
        }
        Text(t.title, style = if (selected) Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold) else Type.caption, color = fg, maxLines = 1)
    }
}

@Composable
private fun AppNavHost(repo: OutageRepository, s: Settings, now: LocalDateTime, update: ((Settings) -> Settings) -> Unit, nav: NavHostController, start: String, onRefresh: () -> Unit) {
    val enter = { _: AnimatedContentTransitionScope<*> -> fadeIn(tween(260)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.98f) }
    NavHost(
        nav, startDestination = start,
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End)),
        enterTransition = { enter(this) }, exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { enter(this) }, popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(repo, s, onLang = { l -> update { it.copy(lang = l) } }, onDone = { addr ->
                update { it.copy(onboarded = true, addresses = listOf(addr) + it.addresses.filter { a -> a.id != addr.id && a.id != "home" }, selectedId = addr.id) }
                nav.navigate(Routes.Home) { popUpTo(Routes.Onboarding) { inclusive = true } }
            })
        }
        composable(Routes.Home) {
            HomeScreen(repo, s, now,
                onAddresses = { nav.navigate(Routes.Addresses) },
                onSchedule = { nav.goTab(Routes.Schedule) },
                onArea = { nav.navigate(Routes.Area) },
                onSources = { nav.navigate(Routes.Sources) },
                onRefresh = onRefresh,
                onNotif = { nav.navigate(Routes.Notif) },
                onSelect = { id -> update { it.copy(selectedId = id) } },
            )
        }
        composable(Routes.Map) { MapUkraineScreen(repo, s, onCity = { id -> nav.navigate(Routes.city(id = id)) }) }
        composable(Routes.City) { e ->
            CityMapScreen(repo, s, now, cityId = e.arguments?.getString("id"),
                onBack = { nav.popBackStack() }, onArea = { nav.navigate(Routes.Area) },
                onMyHome = { street, city, queue ->
                    // Очередь из списков облэнерго, если дом в них есть; иначе просим проверить её в адресах.
                    update { st ->
                        val cur = st.selected
                        val q = queue ?: if (cur.region == city.region) cur.queue else "1.1"
                        val home = cur.copy(street = street, city = city.cityUk, region = city.region, queue = q)
                        st.copy(addresses = st.addresses.map { if (it.id == cur.id) home else it })
                    }
                    if (queue != null) nav.goTab(Routes.Home) else nav.navigate(Routes.Addresses)
                },
                onSetAddress = { key, addr -> update { st -> st.copy(houseAddr = if (addr == null) st.houseAddr - key else st.houseAddr + (key to addr)) } })
        }
        composable(Routes.Area) { AreaScreen(repo, s, onBack = { nav.popBackStack() }) }
        composable(Routes.Schedule) {
            ScheduleScreen(repo, s, now, onRefresh = onRefresh, onWindows = { nav.navigate(Routes.Windows) },
                onRemind = { on -> update { it.copy(remindMinutes = if (on) 15 else 0) } })
        }
        composable(Routes.More) { MoreScreen(s, nav = { nav.navigate(it) }, update = update) }
        composable(Routes.Addresses) { AddressesScreen(repo, s, now, onBack = { nav.popBackStack() }, update = update) }
        composable(Routes.Notif) { NotifScreen(s, onBack = { nav.popBackStack() }, update = update) }
        composable(Routes.Sources) { SourcesScreen(repo, onBack = { nav.popBackStack() }) }
        composable(Routes.Feedback) { FeedbackScreen(s, onBack = { nav.popBackStack() }) }
        composable(Routes.Windows) { WindowsScreen(repo, s, now, onBack = { nav.popBackStack() }) }
        composable(Routes.Widgets) { WidgetsScreen(repo, s, now, onBack = { nav.popBackStack() }) }
    }
}
