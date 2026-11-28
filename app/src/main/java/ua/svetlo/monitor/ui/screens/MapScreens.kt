package ua.svetlo.monitor.ui.screens

import androidx.compose.material.icons.rounded.Layers
import ua.svetlo.monitor.data.VisicomTiles
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import ua.svetlo.monitor.data.tr
import ua.svetlo.monitor.data.Visicom
import ua.svetlo.monitor.data.Pins

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.svetlo.monitor.data.Building
import ua.svetlo.monitor.data.CityMap
import ua.svetlo.monitor.data.MapAssets
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.RegionStatus
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.between
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.formatShort
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.ui.BottomBarSpace
import ua.svetlo.monitor.ui.LocalWindow
import ua.svetlo.monitor.ui.components.Dot
import ua.svetlo.monitor.ui.components.LegendItem
import ua.svetlo.monitor.ui.components.Pill
import ua.svetlo.monitor.ui.components.PrimaryButton
import ua.svetlo.monitor.ui.components.SCard
import ua.svetlo.monitor.ui.components.SectionHeader
import ua.svetlo.monitor.ui.components.Segmented
import ua.svetlo.monitor.ui.components.color
import ua.svetlo.monitor.ui.theme.Status
import ua.svetlo.monitor.ui.theme.Svetlo
import ua.svetlo.monitor.ui.theme.Type
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Карта Украины
// ---------------------------------------------------------------------------------------------

private class RegionShape(val id: String, val path: Path, val bounds: android.graphics.RectF, val region: android.graphics.Region)

@Composable
fun MapUkraineScreen(repo: OutageRepository, s: Settings, onCity: (String) -> Unit) {
    val ctx = LocalContext.current
    val map = remember { MapAssets.ukraine(ctx) }
    val shapes = remember(map) {
        map.regions.map { r ->
            val p = PathParser().parsePathString(r.pathData).toPath().apply { fillType = PathFillType.EvenOdd }
            val b = android.graphics.RectF(); p.asAndroidPath().computeBounds(b, true)
            val reg = android.graphics.Region().apply { setPath(p.asAndroidPath(), android.graphics.Region(0, 0, map.width.toInt(), map.height.toInt())) }
            RegionShape(r.id, p, b, reg)
        }.sortedBy { if (it.id == "kyiv-city") 1 else 0 }
    }
    val ver by repo.version.collectAsState()
    val statuses = remember(ver) { repo.regions().associateBy { it.id } }
    var selected by remember { mutableStateOf<String?>(s.selected.region.takeIf { r -> r in MapAssets.CityRegions } ?: "kyiv-city") }
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val w = LocalWindow.current

    val search: @Composable () -> Unit = {
        SearchField(query, { query = it }, tr("Місто, область або адреса", "Город, область или адрес"))
        Segmented(listOf(tr("Україна", "Украина"), tr("Моє місто", "Мой город")), 0, { if (it == 1) onCity(MapAssets.cityIdFor(s.selected.region)) }, Modifier.fillMaxWidth())
    }
    val mapCard: @Composable () -> Unit = {
        SCard(padding = PaddingValues(10.dp)) {
            UkraineCanvas(map.width, map.height, shapes, statuses, selected) { id ->
                if (id in MapAssets.CityRegions && selected == id) onCity(MapAssets.cityIdFor(id)) else selected = id
            }
            FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendItem(Status.Green, tr("Ні", "Нет")); LegendItem(Status.Yellow, tr("Стабіліз.", "Стабилиз.")); LegendItem(Status.Red, tr("Аварійні", "Аварийные"))
                LegendItem(Status.Black, tr("Блекаут", "Блэкаут")); LegendItem(Status.NoData, tr("Немає даних", "Нет данных"))
            }
            Text(tr("Статус за графіками обленерго. Сірі області — поки без даних.", "Статус по графикам облэнерго. Серые области — пока без данных."),
                style = Type.caption.copy(fontWeight = FontWeight.Medium), color = Svetlo.colors.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
        selected?.let { id -> statuses[id]?.let { RegionCard(it, onCity = if (id in MapAssets.CityRegions) ({ onCity(MapAssets.cityIdFor(id)) }) else null) } }
    }
    val list: @Composable () -> Unit = {
        val sorted = statuses.values.filter { it.avgMinutes != null }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedByDescending { it.avgMinutes }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(tr("Регіони · без світла сьогодні", "Регионы · без света сегодня"), if (showAll) tr("Згорнути", "Свернуть") else tr("Усі ${sorted.size}", "Все ${sorted.size}")) { showAll = !showAll }
            (if (showAll || query.isNotBlank()) sorted else sorted.take(5)).forEach { r ->
                RegionRow(r) { selected = r.id }
            }
        }
    }

    if (w.twoPane) {
        Row(Modifier.fillMaxSize().padding(horizontal = w.gutter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { search(); mapCard() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp)) { list() }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = w.gutter, end = w.gutter, top = 16.dp, bottom = if (w.useRail) 24.dp else BottomBarSpace + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { search(); mapCard(); list() }
        }
    }
}

@Composable
private fun UkraineCanvas(
    mw: Float, mh: Float, shapes: List<RegionShape>, statuses: Map<String, RegionStatus>, selected: String?, onTap: (String) -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val pulse = rememberInfiniteTransition(label = "sel").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    val c = Svetlo.colors
    val outline = c.surface
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(mw / mh)) {
        val scale = constraints.maxWidth / mw
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = tr("Мапа України: області забарвлені за поточним типом відключень", "Карта Украины: области окрашены по текущему типу отключений") }
                .pointerInput(shapes) {
                    detectTapGestures { o ->
                        val x = (o.x / scale).toInt(); val y = (o.y / scale).toInt()
                        shapes.lastOrNull { it.region.contains(x, y) }?.let { onTap(it.id) }
                    }
                },
        ) {
            withTransform({ scale(scale, scale, Offset.Zero) }) {
                shapes.forEachIndexed { i, sh ->
                    val st = statuses[sh.id]?.type ?: OutageType.NoData
                    val a = ((appear.value * shapes.size * 1.4f) - i * 0.9f).coerceIn(0f, 1f)
                    val base = st.color
                    val col = if (st == OutageType.Blackout || st == OutageType.NoData) base else base.copy(alpha = 0.88f)
                    drawPath(sh.path, col.copy(alpha = col.alpha * a))
                    drawPath(sh.path, outline, style = Stroke(width = 2.2f, join = StrokeJoin.Round))
                }
                shapes.firstOrNull { it.id == selected }?.let { sh ->
                    drawPath(sh.path, c.ink, style = Stroke(width = 4f + 3f * pulse.value, join = StrokeJoin.Round))
                    drawPath(sh.path, c.ink.copy(alpha = 0.25f * (1f - pulse.value)), style = Stroke(width = 14f * pulse.value + 4f, join = StrokeJoin.Round))
                }
            }
        }
    }
}

private val PartlyOccupied = setOf("zaporizhia", "kherson", "donetsk", "kharkiv")

@Composable
private fun RegionCard(r: RegionStatus, onCity: (() -> Unit)?) {
    val c = Svetlo.colors
    SCard(Modifier.fillMaxWidth(), radius = 20.dp, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(r.type.color))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = Type.bodyBold, color = c.ink)
                Text(if (r.hasData) r.type.title else tr("Немає даних", "Нет данных"), style = Type.small, color = c.muted)
            }
            if (r.avgMinutes != null) Column(horizontalAlignment = Alignment.End) {
                Text(formatShort(r.avgMinutes), style = Type.number.copy(fontSize = Type.section.fontSize * 1.1f), color = c.ink)
                Text(tr("сьогодні в сер.", "сегодня в сред."), style = Type.caption, color = c.muted)
            }
        }
        if (!r.hasData) Text(tr("Графіки цієї області ще не підключені або сайт обленерго недоступний. Ми додаємо джерела поступово.", "Графики этой области ещё не подключены или сайт облэнерго недоступен. Мы добавляем источники постепенно."), style = Type.small, color = c.ink2)
        if (r.id in PartlyOccupied) Text(tr("Дані лише для підконтрольної Україні території.", "Данные только для подконтрольной Украине территории."), style = Type.small, color = c.ink2)
        if (onCity != null) PrimaryButton(if (r.id == "zaporizhia") tr("Відкрити мапу будинків Запоріжжя", "Открыть карту домов Запорожья") else tr("Відкрити мапу будинків Києва", "Открыть карту домов Киева"), onCity, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RegionRow(r: RegionStatus, onClick: () -> Unit) {
    val c = Svetlo.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(r.type.color))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Type.smallBold.copy(fontSize = Type.body.fontSize * 0.93f), color = c.ink)
            Text(r.type.title, style = Type.caption.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
        Text(formatShort(r.avgMinutes), style = Type.bodyBold.copy(fontFamily = ua.svetlo.monitor.ui.theme.Onest), color = c.ink)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, elevated: Boolean = false) {
    val c = Svetlo.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp)
            .then(if (elevated) Modifier.shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x2016181D), spotColor = Color(0x2016181D)) else Modifier)
            .clip(RoundedCornerShape(16.dp)).background(c.surface)
            .then(if (!elevated) Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.muted, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = Type.body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = placeholder })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Карта города: дома, окрашенные по статусу своей очереди (как на карте ЛУН)
// ---------------------------------------------------------------------------------------------

/** Сетка для быстрой отрисовки большого города: дома и линии разложены по клеткам 500×500 м. */
private const val CELL = 500f
private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

/** queueKeys[i]: "crit" — критическая инфраструктура, "" — очередь неизвестна, иначе «6.1» или «1.2,5.1». */
private class CityIndex(city: CityMap, private val queueKeys: Array<String>) {
    val cells = HashMap<Long, IntArray>()
    val lines: List<Triple<String, Path, android.graphics.RectF>>
    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        city.buildings.forEachIndexed { i, b -> tmp.getOrPut(cellKey(kotlin.math.floor(b.cx / CELL).toInt(), kotlin.math.floor(b.cy / CELL).toInt())) { mutableListOf() } += i }
        tmp.forEach { (k, v) -> cells[k] = v.toIntArray() }
        lines = city.lines.map { l ->
            val p = Path(); val n = l.points.size / 2
            var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
            for (k in 0 until n) {
                val x = l.points[2 * k]; val y = l.points[2 * k + 1]
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                if (x < a) a = x; if (y < b) b = y; if (x > c) c = x; if (y > d) d = y
            }
            if (l.kind == "park" || l.kind == "water") p.close()
            Triple(l.kind, p, android.graphics.RectF(a, b, c, d))
        }
    }
    /** Пути домов клетки строим лениво, при первом показе. */
    val paths = HashMap<Long, Map<String, Path>>()
    fun pathsFor(key: Long, city: CityMap): Map<String, Path>? {
        paths[key]?.let { return it }
        val idx = cells[key] ?: return null
        val out = HashMap<String, Path>()
        idx.forEach { i ->
            val b = city.buildings[i]; val p = out.getOrPut(queueKeys[i]) { Path() }
            val n = b.points.size / 2
            for (k in 0 until n) { val x = b.points[2 * k]; val y = b.points[2 * k + 1]; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close()
        }
        return out.also { paths[key] = it }
    }
    fun hit(city: CityMap, x: Float, y: Float): Int? {
        val cx = kotlin.math.floor(x / CELL).toInt(); val cy = kotlin.math.floor(y / CELL).toInt()
        for (dx in -1..1) for (dy in -1..1) cells[cellKey(cx + dx, cy + dy)]?.forEach { i -> if (city.buildings[i].contains(x, y)) return i }
        return null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CityMapScreen(
    repo: OutageRepository, s: Settings, now: LocalDateTime, cityId: String?,
    onBack: () -> Unit, onArea: () -> Unit, onMyHome: (street: String, city: CityMap, queue: String?) -> Unit,
    onSetAddress: (houseKey: String, address: String?) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val cid = cityId ?: MapAssets.cityIdFor(s.selected.region)
    var mapVer by remember { mutableIntStateOf(0) }
    LaunchedEffect(cid) { if (repo.refreshMap(cid)) { MapAssets.invalidate(cid); mapVer++ } }
    val city = remember(cid, mapVer) { MapAssets.city(ctx, cid) }
    val ver by repo.version.collectAsState()
    LaunchedEffect(city.region) { repo.refreshAddresses(city.region) }
    val addrIdx = remember(ver, city.region) { repo.addresses(city.region) }
    // Адреса из Visicom для домов без адреса в OSM: только те, на которые нажимали в этот раз (хранить их нельзя).
    var visicomAddr by remember(city) { mutableStateOf(mapOf<Int, String>()) }
    var lookingUp by remember(city) { mutableStateOf<Int?>(null) }
    // Очередь каждого дома — только по точному совпадению адреса со списками облэнерго.
    val baseKeys = remember(city, addrIdx) {
        Array(city.buildings.size) { i -> val b = city.buildings[i]; if (b.critical) "crit" else addrIdx?.queuesFor(s.houseAddr[houseKey(city, b)] ?: b.address)?.joinToString(",") ?: "" }
    }
    val queueKeys = remember(baseKeys, visicomAddr) {
        if (visicomAddr.isEmpty()) baseKeys else baseKeys.copyOf().also { k ->
            visicomAddr.forEach { (i, a) -> if (k[i] == "") k[i] = addrIdx?.queuesFor(a)?.joinToString(",") ?: "" }
        }
    }
    // Адрес дома: указанный жителем важнее адреса из OpenStreetMap, а тот — адреса из Visicom.
    fun addrOf(i: Int): String? = city.buildings[i].let { b -> s.houseAddr[houseKey(city, b)] ?: b.address ?: visicomAddr[i] }
    val index = remember(city, queueKeys) { CityIndex(city, queueKeys) }
    val minute = now.withSecond(0).withNano(0)
    val keyColors = remember(ver, queueKeys, minute) {
        queueKeys.toHashSet().filter { it != "" && it != "crit" }.associateWith { k -> houseLook(repo, city.region, k.split(','), now).first }
    }
    // Мои адреса в этом городе: только их очередь мы знаем точно.
    val mine = remember(city, s.addresses, s.houseAddr) {
        s.addresses.filter { it.region == city.region && it.street.isNotBlank() }
            .mapNotNull { a -> city.buildings.indices.firstOrNull { addrOf(it).equals(a.street, ignoreCase = true) }?.let { it to a } }.toMap()
    }
    val home = remember(city, mine) {
        mine.keys.firstOrNull()?.let { city.buildings[it] } ?: city.buildings.firstOrNull { it.address == city.homeAddress }
    }
    var picked by remember { mutableStateOf<Int?>(mine.keys.firstOrNull()) }
    LaunchedEffect(picked, city) {
        val i = picked ?: return@LaunchedEffect
        val b = city.buildings[i]
        if (!Visicom.enabled || !city.hasOrigin || b.critical || addrOf(i) != null) return@LaunchedEffect
        lookingUp = i
        val a = Visicom.addressOf(city.ringLonLat(b), city.insideLonLat(b))
        if (a != null) visicomAddr = visicomAddr + (i to a)
        if (lookingUp == i) lookingUp = null
    }
    var query by remember { mutableStateOf("") }
    val w = LocalWindow.current
    val c = Svetlo.colors
    // Подложка Visicom (улицы, подписи, номера домов) — только в светлой теме: менять цвета их тайлов нельзя.
    // Кнопка «слои»: житель может выключить подложку и смотреть нашу схему (выбор запоминаем).
    var tilesWanted by remember { mutableStateOf(VisicomTiles.wanted(ctx)) }
    val tilesOn = VisicomTiles.enabled && tilesWanted && !c.dark && city.hasOrigin
    val tileTick by VisicomTiles.loaded.collectAsState()
    LaunchedEffect(Unit) { VisicomTiles.trim(ctx) }
    val density = LocalDensity.current.density
    val neutral = if (c.dark) Color(0xFF3A3E48) else Color(0xFFD3D0C6)
    val critical = if (c.dark) Color(0xFF2A2D35) else Color(0xFFE6E3DA)

    // Камера: масштаб в пикселях на метр и центр в метрах.
    var zoom by remember { mutableFloatStateOf(0f) }
    var center by remember(city) { mutableStateOf(Offset(home?.cx ?: (city.minX + city.maxX) / 2, home?.cy ?: (city.minY + city.maxY) / 2)) }
    val scope = rememberCoroutineScope()
    val zoomAnim = remember { Animatable(1f) }

    // Подсказки: дома на карте и адреса из списка облэнерго, даже если дома на карте нет (null вместо индекса).
    val suggestions: List<Pair<Int?, String>> = remember(query, city, addrIdx, visicomAddr) {
        if (query.length < 2) emptyList() else {
            val onMap = city.buildings.indices.filter { addrOf(it)?.contains(query.trim(), true) == true }.distinctBy { addrOf(it) }.take(5).map { it to addrOf(it)!! }
            val fromList = addrIdx?.search(query)?.filter { a -> onMap.none { addrIdx.same(it.second, a) } }?.map { a ->
                city.buildings.indices.firstOrNull { addrIdx.same(addrOf(it), a) } to a
            } ?: emptyList()
            (onMap + fromList).distinctBy { it.second }.take(6)
        }
    }
    // Адрес из списка, которого нет на карте: житель может отметить свой дом.
    var pending by remember(city) { mutableStateOf<String?>(null) }
    var placing by remember(city) { mutableStateOf(false) }
    fun place(i: Int) {
        val a = pending ?: return
        val b = city.buildings[i]
        onSetAddress(houseKey(city, b), a)
        if (city.hasOrigin) { val (lon, lat) = city.insideLonLat(b); scope.launch { Pins.submit(ctx, city.region, a, lon, lat) } }
        pending = null; placing = false; picked = i
    }

    Box(Modifier.fillMaxSize().clipToBounds().background(c.mapBg)) {
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                val wpx = constraints.maxWidth.toFloat(); val hpx = constraints.maxHeight.toFloat()
                if (zoom == 0f) zoom = (min(wpx, hpx) / 650f)
                // Не даём отдалиться дальше ~12 км: иначе на экране слишком много домов.
                val minZoom = max(min(wpx / (city.maxX - city.minX), hpx / (city.maxY - city.minY)) * 0.9f, max(wpx, hpx) / 12000f)
                val drop = remember { Animatable(0f) }
                LaunchedEffect(picked) { drop.snapTo(0f); drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
                val halo = rememberInfiniteTransition(label = "halo").animateFloat(0f, 1f, infiniteRepeatable(tween(2000)), label = "h")
                val pinColor = c.ink
                Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = tr("Мапа будинків міста", "Карта домов города") }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gz, _ ->
                                zoom = (zoom * gz).coerceIn(minZoom, 12f)
                                center -= pan / zoom
                            }
                        }
                        .pointerInput(index) {
                            detectTapGestures { o ->
                                val mx = center.x + (o.x - wpx / 2f) / zoom
                                val my = center.y + (o.y - hpx / 2f) / zoom
                                val hit = index.hit(city, mx, my)
                                if (placing) { if (hit != null) place(hit) } else { picked = hit; if (hit != null) pending = null }
                            }
                        },
                ) {
                    val z = zoom * zoomAnim.value
                    val left = center.x - wpx / 2f / z; val right = center.x + wpx / 2f / z
                    val top = center.y - hpx / 2f / z; val bottom = center.y + hpx / 2f / z
                    withTransform({
                        translate(wpx / 2f, hpx / 2f); scale(z, z, Offset.Zero); translate(-center.x, -center.y)
                    }) {
                        val visible = index.lines.filter { (_, _, r) -> r.right >= left && r.left <= right && r.bottom >= top && r.top <= bottom }
                        visible.forEach { (k, p, _) ->
                            when (k) { "park" -> drawPath(p, c.mapPark); "water" -> drawPath(p, c.mapWater) }
                        }
                        // Ширина дорог в метрах, но не тоньше 1,5 пикселя.
                        visible.forEach { (k, p, _) ->
                            val width = when (k) { "primary" -> 14f; "secondary" -> 11f; "tertiary" -> 9f; "pedestrian" -> 5f; else -> 7f }
                            if (k != "park" && k != "water") drawPath(p, c.mapRoad, style = Stroke(max(width, 1.5f / z), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        val cx0 = kotlin.math.floor(left / CELL).toInt(); val cx1 = kotlin.math.floor(right / CELL).toInt()
                        val cy0 = kotlin.math.floor(top / CELL).toInt(); val cy1 = kotlin.math.floor(bottom / CELL).toInt()
                        // Дома без известной очереди — часть «нашей» подложки: под тайлами Visicom их не видно.
                        for (cx in cx0..cx1) for (cy in cy0..cy1) {
                            index.pathsFor(cellKey(cx, cy), city)?.forEach { (k, p) ->
                                if (k == "" || k == "crit" || !tilesOn) drawPath(p, when (k) { "" -> neutral; "crit" -> critical; else -> keyColors[k] ?: neutral })
                            }
                        }
                    }
                    if (tilesOn) {
                        tileTick  // перерисовать, когда догрузился тайл
                        drawVisicomTiles(ctx, city, center, z, wpx, hpx, left, top, right, bottom, density)
                    }
                    withTransform({
                        translate(wpx / 2f, hpx / 2f); scale(z, z, Offset.Zero); translate(-center.x, -center.y)
                    }) {
                        val cx0 = kotlin.math.floor(left / CELL).toInt(); val cx1 = kotlin.math.floor(right / CELL).toInt()
                        val cy0 = kotlin.math.floor(top / CELL).toInt(); val cy1 = kotlin.math.floor(bottom / CELL).toInt()
                        // Поверх подложки Visicom — дома с известной очередью, полупрозрачно: номера домов Visicom видны.
                        if (tilesOn) for (cx in cx0..cx1) for (cy in cy0..cy1) {
                            index.pathsFor(cellKey(cx, cy), city)?.forEach { (k, p) ->
                                if (k != "" && k != "crit") keyColors[k]?.let { col ->
                                    drawPath(p, col, alpha = HouseAlpha)
                                    drawPath(p, col, style = Stroke(1.6f / z, join = StrokeJoin.Round))
                                }
                            }
                        }
                        // Мои дома — цветом своей очереди и с обводкой.
                        mine.forEach { (i, a) ->
                            val b = city.buildings[i]
                            val p = Path().apply { val n = b.points.size / 2; for (k in 0 until n) { if (k == 0) moveTo(b.points[0], b.points[1]) else lineTo(b.points[2 * k], b.points[2 * k + 1]) }; close() }
                            drawPath(p, houseLook(repo, a.region, listOf(a.queue), now).first, alpha = if (tilesOn) HouseAlpha else 1f)
                            drawPath(p, c.ink, style = Stroke(2.4f / z))
                        }
                        picked?.let { i ->
                            val b = city.buildings[i]
                            val p = Path().apply { val n = b.points.size / 2; for (k in 0 until n) { if (k == 0) moveTo(b.points[0], b.points[1]) else lineTo(b.points[2 * k], b.points[2 * k + 1]) }; close() }
                            drawPath(p, c.accent, style = Stroke(3.5f / z, join = StrokeJoin.Round))
                        }
                    }
                    // Метка выбранного дома (в экранных координатах, чтобы не масштабировалась).
                    picked?.let { i ->
                        val b = city.buildings[i]
                        val sx = wpx / 2f + (b.cx - center.x) * z
                        val sy = hpx / 2f + (b.cy - center.y) * z
                        val r = 12.dp.toPx()
                        drawCircle(pinColor.copy(alpha = 0.4f * (1f - halo.value)), r * (0.6f + 1.6f * halo.value), Offset(sx, sy))
                        val lift = (1f - drop.value) * 28.dp.toPx()
                        val topY = sy - 40.dp.toPx() - lift
                        val pin = Path().apply {
                            moveTo(sx, sy - lift)
                            cubicTo(sx - 4.dp.toPx(), sy - 10.dp.toPx() - lift, sx - 16.dp.toPx(), topY + 22.dp.toPx(), sx - 16.dp.toPx(), topY + 16.dp.toPx())
                            cubicTo(sx - 16.dp.toPx(), topY + 6.dp.toPx(), sx - 8.dp.toPx(), topY, sx, topY)
                            cubicTo(sx + 8.dp.toPx(), topY, sx + 16.dp.toPx(), topY + 6.dp.toPx(), sx + 16.dp.toPx(), topY + 16.dp.toPx())
                            cubicTo(sx + 16.dp.toPx(), topY + 22.dp.toPx(), sx + 4.dp.toPx(), sy - 10.dp.toPx() - lift, sx, sy - lift)
                            close()
                        }
                        drawPath(pin, pinColor)
                        drawCircle(Status.Yellow, 6.dp.toPx(), Offset(sx, topY + 16.dp.toPx()))
                    }
                }
                // Верхние элементы управления.
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        FloatingSquare(Icons.AutoMirrored.Rounded.ArrowBack, tr("Назад до мапи України", "Назад к карте Украины"), onBack)
                        SearchField(query, { query = it }, tr("Вулиця і будинок · ${city.city}", "Улица и дом · ${city.city}"), Modifier.weight(1f), elevated = true)
                    }
                    AnimatedVisibility(suggestions.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface)) {
                            suggestions.forEach { (i, a) ->
                                Text(a, style = Type.bodyBold, color = c.ink, modifier = Modifier.fillMaxWidth().clickable {
                                    if (i != null) { val b = city.buildings[i]; picked = i; pending = null; center = Offset(b.cx, b.cy); zoom = max(zoom, 2.2f) }
                                    else { picked = null; pending = a; placing = false }
                                    query = ""
                                }.padding(horizontal = 16.dp, vertical = 14.dp))
                            }
                        }
                    }
                }
                // Масштаб и «мой дом».
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(horizontal = 16.dp)
                        .shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface),
                ) {
                    ZoomButton(Icons.Rounded.Add, tr("Наблизити", "Приблизить")) { zoom = (zoom * 1.5f).coerceAtMost(12f) }
                    Box(Modifier.width(44.dp).height(1.dp).background(c.divider))
                    ZoomButton(Icons.Rounded.Remove, tr("Віддалити", "Отдалить")) { zoom = (zoom / 1.5f).coerceAtLeast(minZoom) }
                    Box(Modifier.width(44.dp).height(1.dp).background(c.divider))
                    if (VisicomTiles.enabled && !c.dark) {
                        ZoomButton(Icons.Rounded.Layers, if (tilesWanted) tr("Показати схему", "Показать схему") else tr("Показати мапу Візіком", "Показать карту Визиком"),
                            tint = if (tilesWanted) c.accent else c.ink) {
                            tilesWanted = !tilesWanted; VisicomTiles.setWanted(ctx, tilesWanted)
                        }
                        Box(Modifier.width(44.dp).height(1.dp).background(c.divider))
                    }
                    ZoomButton(Icons.Rounded.Home, tr("Мій дім", "Мой дом"), tint = c.accent) {
                        mine.keys.firstOrNull()?.let { i -> val b = city.buildings[i]; center = Offset(b.cx, b.cy); picked = i }
                        scope.launch { zoomAnim.snapTo(0.92f); zoomAnim.animateTo(1f, spring(dampingRatio = 0.5f)) }
                    }
                }
                if (w.twoPane) MapLegend(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 32.dp), repo.summary(city.region)?.mode)
                val uri = LocalUriHandler.current
                // Подпись Visicom (оферта п. 5.5) должна быть видна всегда: под строкой поиска, а не под карточкой дома.
                if (tilesOn) {
                    if (suggestions.isEmpty()) Text("${VisicomTiles.Attribution} · " + "контури © OpenStreetMap",
                        style = Type.tiny, color = c.ink,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 82.dp, end = 16.dp)
                            .clip(RoundedCornerShape(6.dp)).background(c.surface.copy(alpha = 0.85f))
                            .clickable { uri.openUri(VisicomTiles.Site) }.padding(horizontal = 6.dp, vertical = 2.dp))
                } else Text(tr("© учасники OpenStreetMap · адреси © Візіком", "© участники OpenStreetMap · адреса © Визиком"), style = Type.tiny, color = c.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (!w.twoPane && picked != null) 8.dp else if (w.useRail) 12.dp else BottomBarSpace + 4.dp))
            }
            if (w.twoPane) {
                Column(
                    Modifier.width(if (w.shortHeight) 320.dp else 380.dp).fillMaxHeight().background(c.bg).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pending?.let { a -> PendingPanel(a, addrIdx?.queuesFor(a), city, repo, now, placing, onPlace = { placing = true }, onCancel = { pending = null; placing = false }) }
                        ?: picked?.let { BuildingPanel(city.buildings[it], addrOf(it), s.houseAddr.containsKey(houseKey(city, city.buildings[it])), city, mine[it], queueKeys[it].takeIf { k -> k != "" && k != "crit" }?.split(','), addrIdx, repo, now, onMyHome, onArea, fromVisicom = addrOf(it) != null && addrOf(it) == visicomAddr[it], lookingUp = lookingUp == it) { a -> onSetAddress(houseKey(city, city.buildings[it]), a) } }
                        ?: Text(tr("Натисніть на будинок, щоб побачити адресу і чергу. Колір — чи є зараз світло за графіком.", "Нажмите на дом, чтобы увидеть адрес и очередь. Цвет — есть ли сейчас свет по графику."), style = Type.body, color = c.ink2)
                }
            }
        }

        // Нижняя шторка на телефоне.
        if (!w.twoPane) {
            // imePadding: шторка поднимается над клавиатурой (окно edge-to-edge, само оно не сжимается).
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
                MapLegend(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).align(Alignment.CenterHorizontally), repo.summary(city.region)?.mode)
                AnimatedVisibility(picked != null || pending != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 640.dp)
                            .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.75f).dp)
                            .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = if (w.useRail) 20.dp else BottomBarSpace + 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape).background(c.line))
                        pending?.let { a -> PendingPanel(a, addrIdx?.queuesFor(a), city, repo, now, placing, onPlace = { placing = true }, onCancel = { pending = null; placing = false }) }
                        ?: picked?.let { BuildingPanel(city.buildings[it], addrOf(it), s.houseAddr.containsKey(houseKey(city, city.buildings[it])), city, mine[it], queueKeys[it].takeIf { k -> k != "" && k != "crit" }?.split(','), addrIdx, repo, now, onMyHome, onArea, fromVisicom = addrOf(it) != null && addrOf(it) == visicomAddr[it], lookingUp = lookingUp == it) { a -> onSetAddress(houseKey(city, city.buildings[it]), a) } }
                    }
                }
                if (picked == null && pending == null && !w.useRail) Box(Modifier.height(BottomBarSpace))
            }
        }
    }
}

/** Цвет и подпись дома по его подочередям: все со светом — зелёный, все без — цвет отключения, по-разному — «частично». */
@Composable
private fun PendingPanel(
    address: String, queues: List<String>?, city: CityMap, repo: OutageRepository, now: LocalDateTime, placing: Boolean,
    onPlace: () -> Unit, onCancel: () -> Unit,
) {
    val c = Svetlo.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(address, style = Type.h2, color = c.ink)
            Text(when {
                queues == null -> city.city
                queues.size == 1 -> tr("Черга ${queues[0]}", "Очередь ${queues[0]}")
                else -> tr("Під'їзди в різних чергах: ${queues.joinToString(", ")}", "Подъезды в разных очередях: ${queues.joinToString(", ")}")
            }, style = Type.small, color = c.muted)
        }
        if (queues != null) { val (col, label) = houseLook(repo, city.region, queues, now); Pill(label, bg = c.surface2, fg = c.ink, border = null, dot = col) }
    }
    if (placing) {
        Text(tr("Натисніть на свій будинок на мапі.", "Нажмите на свой дом на карте."), style = Type.bodyBold, color = c.accent)
        Text(tr("Скасувати", "Отмена"), style = Type.smallBold, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(vertical = 8.dp))
    } else {
        Text(tr("Цей будинок ще не позначений на мапі. Знайдіть його і позначте — він з'явиться і в інших.", "Этот дом ещё не отмечен на карте. Найдите его и отметьте — он появится и у других."), style = Type.small, color = c.ink2)
        PrimaryButton(tr("Вказати мій дім на мапі", "Указать мой дом на карте"), onPlace, Modifier.fillMaxWidth())
    }
}

private fun houseLook(repo: OutageRepository, region: String, queues: List<String>, now: LocalDateTime): Pair<Color, String> {
    val types = queues.map { repo.queueOffNow(region, it, now) }
    return when {
        types.all { it == OutageType.NoData } -> Status.NoData to tr("Немає графіка", "Нет графика")
        types.all { it == OutageType.None } -> Status.Green to tr("Світло є", "Свет есть")
        types.none { it == OutageType.None || it == OutageType.NoData } -> types.first().color to tr("Світла немає", "Света нет")
        else -> Status.Maybe to tr("Частково", "Частично")
    }
}

@Composable
private fun BuildingPanel(
    b: Building, address: String?, userSet: Boolean, city: CityMap, mine: ua.svetlo.monitor.data.Address?, queues: List<String>?, addrIdx: ua.svetlo.monitor.data.AddressIndex?,
    repo: OutageRepository, now: LocalDateTime, onMyHome: (String, CityMap, String?) -> Unit, onArea: () -> Unit,
    fromVisicom: Boolean = false, lookingUp: Boolean = false,
    onSetAddress: (String?) -> Unit,
) {
    val c = Svetlo.colors
    var picking by remember(b) { mutableStateOf(false) }
    if (picking && addrIdx != null) {
        HouseAddressPicker(addrIdx, address, onCancel = { picking = false }) { a -> picking = false; onSetAddress(a) }
        return
    }
    val ctx = LocalContext.current
    val qs = mine?.let { listOf(it.queue) } ?: queues
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(address ?: if (lookingUp) tr("Шукаємо адресу…", "Ищем адрес…") else tr("Адреса невідома", "Адрес неизвестен"), style = Type.h2, color = c.ink)
            if (fromVisicom) Text(Visicom.Attribution, style = Type.caption, color = c.muted, modifier = Modifier.clickable {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Visicom.Site)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            Text(
                when {
                    b.critical -> tr("Об'єкт критичної інфраструктури", "Объект критической инфраструктуры")
                    mine != null -> tr("${mine.labelName} · черга ${mine.queue}", "${mine.labelName} · очередь ${mine.queue}")
                    qs != null && qs.size == 1 -> tr("Черга ${qs[0]}", "Очередь ${qs[0]}")
                    qs != null -> tr("Під'їзди в різних чергах: ${qs.joinToString(", ")}", "Подъезды в разных очередях: ${qs.joinToString(", ")}")
                    else -> city.city
                },
                style = Type.small, color = c.muted,
            )
        }
        if (qs != null) {
            val (col, label) = houseLook(repo, city.region, qs, now)
            Pill(label, bg = c.surface2, fg = c.ink, border = null, dot = col)
        }
    }
    if (qs != null) {
        if (qs.size == 1) {
            val st = repo.now(city.region, qs[0], now)
            val today = repo.schedule(city.region, qs[0], now.toLocalDate())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(if (st.lightOn == true) tr("Вимкнуть", "Отключат") else tr("Увімкнуть", "Включат"), st.nextChange?.let { tr("о ${it.hm()}", "в ${it.hm()}") } ?: "—", Modifier.weight(1f))
                MiniStat(tr("Сьогодні без світла", "Сегодня без света"), if (today.known) formatDuration(today.offMinutes.toLong()) else "—", Modifier.weight(1f), onArea)
            }
        }
        if (mine == null) {
            Text(tr("Черга — з переліків адрес ${addrIdx?.title?.substringBefore(':') ?: "обленерго"}.", "Очередь — из списков адресов ${addrIdx?.title?.substringBefore(':') ?: "облэнерго"}."), style = Type.caption, color = c.muted)
            if (address != null) PrimaryButton(tr("Це мій дім", "Это мой дом"), { onMyHome(address, city, qs.singleOrNull()) }, Modifier.fillMaxWidth())
            if (userSet) UserAddressNote({ picking = true }, { onSetAddress(null) })
            else if (addrIdx != null) FixAddressLink { picking = true }
        }
        return
    }
    if (b.critical) {
        Text(tr("Такі будівлі зазвичай не відключають за графіком.", "Такие здания обычно не отключают по графику."), style = Type.small, color = c.ink2)
        return
    }
    Text(
        when {
            address == null && lookingUp -> tr("Шукаємо адресу цього будинку у Visicom…", "Ищем адрес этого дома в Visicom…")
            address == null && !Visicom.enabled -> tr("У відкритих картах для цього будинку немає адреси, тому чергу не показуємо.", "В открытых картах у этого дома нет адреса, поэтому очередь не показываем.")
            address == null -> tr("Для цього будинку немає точної адреси ні у відкритих картах, ні у Visicom, тому чергу не показуємо.", "Для этого дома нет точного адреса ни в открытых картах, ни в Visicom, поэтому очередь не показываем.")
            addrIdx == null -> tr("Для цього міста ще немає переліку адрес за чергами. Свою чергу можна дізнатися на сайті оператора.", "Для этого города ещё нет списка адресов по очередям. Свою очередь можно узнать на сайте оператора.")
            else -> tr("Цієї адреси немає в переліках обленерго для населення. Свою чергу можна уточнити на сайті оператора.", "Этого адреса нет в списках облэнерго для населения. Свою очередь можно уточнить на сайте оператора.")
        }, style = Type.small, color = c.ink2,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (address != null) PrimaryButton(tr("Це мій дім", "Это мой дом"), { onMyHome(address, city, null) }, Modifier.weight(1f))
        LookupUrls[city.region]?.let { url ->
            PrimaryButton(tr("Дізнатися чергу", "Узнать очередь"), {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.weight(1f), outlined = true)
        }
    }
    if (addrIdx != null) {
        if (userSet) UserAddressNote({ picking = true }, { onSetAddress(null) })
        else if (address != null) FixAddressLink { picking = true }
        else Text(tr("Знаєте адресу цього будинку? Вкажіть її", "Знаете адрес этого дома? Укажите его"), style = Type.smallBold, color = c.accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(vertical = 8.dp))
    }
}

/** Житель видит у своего дома не тот номер (14 вместо 14А) — выбирает правильный из списка облэнерго. */
@Composable
private fun FixAddressLink(onClick: () -> Unit) {
    Text(tr("Адреса неправильна? Виправити", "Адрес неправильный? Исправить"), style = Type.smallBold, color = Svetlo.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp))
}

private fun houseKey(city: CityMap, b: Building) = "${city.id}:${b.cx.toInt()}:${b.cy.toInt()}"

@Composable
private fun UserAddressNote(onChange: () -> Unit, onRemove: () -> Unit) {
    val c = Svetlo.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Адресу вказали ви", "Адрес указали вы"), style = Type.caption, color = c.muted, modifier = Modifier.weight(1f))
        Text(tr("Змінити", "Изменить"), style = Type.smallBold, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onChange).padding(8.dp))
        Text(tr("Прибрати", "Убрать"), style = Type.smallBold, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRemove).padding(8.dp))
    }
}

/** Выбор адреса дома из официального списка облэнерго: улица, потом номер. Ничего вписать «от себя» нельзя. */
@Composable
private fun HouseAddressPicker(idx: ua.svetlo.monitor.data.AddressIndex, current: String? = null, onCancel: () -> Unit, onPick: (String) -> Unit) {
    val c = Svetlo.colors
    var street by remember { mutableStateOf(idx.streetOf(current)) }
    var q by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (street == null) tr("Вулиця будинку", "Улица дома") else street!!.second, style = Type.h2, color = c.ink, modifier = Modifier.weight(1f))
        Text(tr("Скасувати", "Отмена"), style = Type.smallBold, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(8.dp))
    }
    val st = street
    if (st == null) {
        SearchField(q, { q = it }, tr("Почніть вводити назву вулиці", "Начните вводить название улицы"))
        // «хлібна 47», «вул. Хлібна, 47» — сразу готовые адреса; «хлібна» — улицы.
        val houses = remember(q) { if (q.any { it.isDigit() }) idx.search(q, limit = 6) else emptyList() }
        val found = remember(q) {
            val k = q.lowercase().replace(Regex("\\d+\\s*[а-яіїєґa-z]?\\b"), " ")
                .replace(Regex("^\\s*(вул\\.?|вулиця|пров\\.?|провулок|просп\\.?|проспект|бул\\.?|бульвар|пл\\.?|площа|ул\\.?|улица)\\s+"), "")
                .replace(Regex("[,.]"), " ").trim().replace(Regex("\\s+"), " ")
            if (k.length < 2) emptyList() else idx.streetNames.filter { it.second.lowercase().contains(k) }.take(6)
        }
        houses.forEach { a ->
            Text(a, style = Type.bodyBold, color = c.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(a) }.padding(horizontal = 4.dp, vertical = 12.dp))
        }
        found.filter { houses.isEmpty() || houses.none { a -> a.startsWith(it.second) } }.forEach { s ->
            Text(s.second, style = Type.bodyBold, color = c.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { street = s; q = "" }.padding(horizontal = 4.dp, vertical = 12.dp))
        }
        if (q.trim().length >= 2 && houses.isEmpty() && found.isEmpty()) {
            Text(tr("Нічого не знайдено в переліку обленерго", "Ничего не найдено в списке облэнерго"), style = Type.small, color = c.ink2)
        }
        Text(tr("Вулиці — з переліку обленерго. Якщо вашої немає, будинок не входить у графіки для населення.", "Улицы — из списка облэнерго. Если вашей нет, дом не входит в графики для населения."), style = Type.caption, color = c.muted)
    } else {
        Text(tr("Оберіть номер будинку", "Выберите номер дома"), style = Type.small, color = c.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            idx.houses(st.first).forEach { h ->
                Box(Modifier.heightIn(min = 40.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).clickable { onPick("${st.second}, $h") }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(h, style = Type.bodyBold, color = c.ink)
                }
            }
        }
        Text(tr("Інша вулиця", "Другая улица"), style = Type.smallBold, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { street = null }.padding(vertical = 8.dp))
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Svetlo.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = Type.tiny, color = c.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(value, style = Type.bodyBold.copy(fontFamily = ua.svetlo.monitor.ui.theme.Onest), color = c.ink, maxLines = 1)
    }
}

/** Легенда карты домов: цвета зависят от режима области (аварийные — красный вместо жёлтого). */
@Composable
private fun MapLegend(modifier: Modifier = Modifier, mode: ua.svetlo.monitor.data.RegionMode? = null) {
    val c = Svetlo.colors
    val emergency = mode == ua.svetlo.monitor.data.RegionMode.Emergency
    val blackout = mode == ua.svetlo.monitor.data.RegionMode.Blackout
    Column(
        modifier.widthIn(max = 360.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (emergency || blackout) {
            Text(if (blackout) tr("Блекаут: графіки не діють", "Блэкаут: графики не действуют") else tr("Аварійні: графік може не діяти", "Аварийные: график может не действовать"),
                style = Type.caption.copy(fontWeight = FontWeight.Bold), color = c.redInk, maxLines = 1)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!blackout) LegendItem(Status.Green, tr("світло є", "свет есть"))
            LegendItem(if (emergency) Status.Red else if (blackout) Status.Black else Status.Yellow, tr("немає", "нет"))
            if (!blackout) LegendItem(Status.Maybe, tr("частково", "частично"))
            LegendItem(if (c.dark) Color(0xFF3A3E48) else Color(0xFFD3D0C6), tr("невідомо", "неизвестно"))
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val c = Svetlo.colors
    Row(
        Modifier.heightIn(min = 38.dp).shadow(if (selected) 0.dp else 4.dp, CircleShape).clip(CircleShape)
            .background(if (selected) c.inverse else c.surface).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) c.onInverse else c.accent, modifier = Modifier.size(16.dp))
        Text(text, style = Type.smallBold.copy(fontSize = Type.body.fontSize * 0.93f), color = if (selected) c.onInverse else c.ink, maxLines = 1)
    }
}

@Composable
private fun FloatingSquare(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = Svetlo.colors
    Box(
        Modifier.size(50.dp).shadow(10.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface)
            .clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = c.ink) }
}

@Composable
private fun ZoomButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = Svetlo.colors.ink, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClickLabel = desc, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint)
    }
}

/** Прозрачность заливки домов поверх подложки Visicom: цвет очереди виден, номера домов под ним читаются. */
private const val HouseAlpha = 0.55f

/**
 * Тайлы Visicom в экранных координатах. Наша карта — в метрах от начала координат города (равнопромежуточная),
 * тайлы — в веб-Меркаторе, поэтому углы каждого тайла пересчитываем через долготу/широту (внутри тайла разница < 1 пикселя).
 * Пока тайл грузится, рисуем увеличенный кусок уже загруженного тайла уровнем выше.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVisicomTiles(
    ctx: android.content.Context, city: CityMap, center: Offset, z: Float, wpx: Float, hpx: Float,
    left: Float, top: Float, right: Float, bottom: Float, density: Float,
) {
    val tz = VisicomTiles.zoomFor(z, density, city.originLat)
    val (lonL, latT) = city.toLonLat(left, top)
    val (lonR, latB) = city.toLonLat(right, bottom)
    val x0 = VisicomTiles.tileX(lonL, tz); val x1 = VisicomTiles.tileX(lonR, tz)
    val y0 = VisicomTiles.tileY(latT, tz); val y1 = VisicomTiles.tileY(latB, tz)
    if ((x1 - x0 + 1) * (y1 - y0 + 1) > 80) return
    fun scr(x: Int, y: Int): IntOffset {
        val (mx, my) = city.fromLonLat(VisicomTiles.lon(x, tz), VisicomTiles.lat(y, tz))
        return IntOffset((wpx / 2f + (mx - center.x) * z).roundToInt(), (hpx / 2f + (my - center.y) * z).roundToInt())
    }
    for (x in x0..x1) for (y in y0..y1) {
        val a = scr(x, y); val b = scr(x + 1, y + 1)
        val size = IntSize(b.x - a.x, b.y - a.y)
        if (size.width <= 0 || size.height <= 0) continue
        val img = VisicomTiles.get(ctx, tz, x, y)
        if (img != null) { drawImage(img, dstOffset = a, dstSize = size, filterQuality = FilterQuality.Medium); continue }
        for (up in 1..4) {
            val pz = tz - up; if (pz < VisicomTiles.MinZ) break
            val parent = VisicomTiles.cached(pz, x shr up, y shr up) ?: continue
            val part = 256 shr up
            drawImage(parent, srcOffset = IntOffset((x - ((x shr up) shl up)) * part, (y - ((y shr up) shl up)) * part),
                srcSize = IntSize(part, part), dstOffset = a, dstSize = size, filterQuality = FilterQuality.Medium)
            break
        }
    }
}
