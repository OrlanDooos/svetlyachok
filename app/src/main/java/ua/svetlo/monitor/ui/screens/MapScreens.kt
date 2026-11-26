package ua.svetlo.monitor.ui.screens

import ua.svetlo.monitor.data.tr

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

private class CityIndex(city: CityMap) {
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
    val paths = HashMap<Long, Pair<Path, Path>>()
    fun pathsFor(key: Long, city: CityMap): Pair<Path, Path>? {
        paths[key]?.let { return it }
        val idx = cells[key] ?: return null
        val normal = Path(); val crit = Path()
        idx.forEach { i ->
            val b = city.buildings[i]; val p = if (b.critical) crit else normal
            val n = b.points.size / 2
            for (k in 0 until n) { val x = b.points[2 * k]; val y = b.points[2 * k + 1]; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close()
        }
        return (normal to crit).also { paths[key] = it }
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
    onBack: () -> Unit, onArea: () -> Unit, onMyHome: (street: String, city: CityMap) -> Unit,
) {
    val ctx = LocalContext.current
    val city = remember(cityId) { MapAssets.city(ctx, cityId ?: MapAssets.cityIdFor(s.selected.region)) }
    val index = remember(city) { CityIndex(city) }
    // Мои адреса в этом городе: только их очередь мы знаем точно.
    val mine = remember(city, s.addresses) {
        s.addresses.filter { it.region == city.region && it.street.isNotBlank() }
            .mapNotNull { a -> city.buildings.indexOfFirst { it.address.equals(a.street, ignoreCase = true) }.takeIf { it >= 0 }?.let { it to a } }.toMap()
    }
    val home = remember(city, mine) {
        mine.keys.firstOrNull()?.let { city.buildings[it] } ?: city.buildings.firstOrNull { it.address == city.homeAddress }
    }
    var picked by remember { mutableStateOf<Int?>(mine.keys.firstOrNull()) }
    var query by remember { mutableStateOf("") }
    val w = LocalWindow.current
    val c = Svetlo.colors
    val neutral = if (c.dark) Color(0xFF3A3E48) else Color(0xFFD3D0C6)
    val critical = if (c.dark) Color(0xFF2A2D35) else Color(0xFFE6E3DA)

    // Камера: масштаб в пикселях на метр и центр в метрах.
    var zoom by remember { mutableFloatStateOf(0f) }
    var center by remember(city) { mutableStateOf(Offset(home?.cx ?: (city.minX + city.maxX) / 2, home?.cy ?: (city.minY + city.maxY) / 2)) }
    val scope = rememberCoroutineScope()
    val zoomAnim = remember { Animatable(1f) }

    val suggestions = remember(query, city) {
        if (query.length < 2) emptyList() else city.buildings.indices.filter { city.buildings[it].address?.contains(query.trim(), true) == true }.distinctBy { city.buildings[it].address }.take(5)
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
                                picked = index.hit(city, mx, my)
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
                        for (cx in cx0..cx1) for (cy in cy0..cy1) {
                            val (normal, crit) = index.pathsFor(cellKey(cx, cy), city) ?: continue
                            drawPath(normal, neutral)
                            drawPath(crit, critical)
                        }
                        // Мои дома — цветом статуса своей очереди.
                        mine.forEach { (i, a) ->
                            val b = city.buildings[i]
                            val p = Path().apply { val n = b.points.size / 2; for (k in 0 until n) { if (k == 0) moveTo(b.points[0], b.points[1]) else lineTo(b.points[2 * k], b.points[2 * k + 1]) }; close() }
                            val t = repo.queueOffNow(a.region, a.queue, now)
                            drawPath(p, if (t == OutageType.None) Status.Green else t.color)
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
                            suggestions.forEach { i ->
                                val b = city.buildings[i]
                                Text(b.address ?: "", style = Type.bodyBold, color = c.ink, modifier = Modifier.fillMaxWidth().clickable {
                                    picked = i; center = Offset(b.cx, b.cy); zoom = max(zoom, 2.2f); query = ""
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
                    ZoomButton(Icons.Rounded.Home, tr("Мій дім", "Мой дом"), tint = c.accent) {
                        mine.keys.firstOrNull()?.let { i -> val b = city.buildings[i]; center = Offset(b.cx, b.cy); picked = i }
                        scope.launch { zoomAnim.snapTo(0.92f); zoomAnim.animateTo(1f, spring(dampingRatio = 0.5f)) }
                    }
                }
                if (w.twoPane) MapLegend(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 32.dp))
                Text(tr("© учасники OpenStreetMap", "© участники OpenStreetMap"), style = Type.tiny, color = c.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (!w.twoPane && picked != null) 8.dp else if (w.useRail) 12.dp else BottomBarSpace + 4.dp))
            }
            if (w.twoPane) {
                Column(
                    Modifier.width(if (w.shortHeight) 320.dp else 380.dp).fillMaxHeight().background(c.bg).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    picked?.let { BuildingPanel(city.buildings[it], city, mine[it], repo, now, onMyHome, onArea) }
                        ?: Text(tr("Натисніть на будинок, щоб побачити адресу. Ваші адреси підсвічені кольором графіка.", "Нажмите на дом, чтобы увидеть адрес. Ваши адреса подсвечены цветом графика."), style = Type.body, color = c.ink2)
                }
            }
        }

        // Нижняя шторка на телефоне.
        if (!w.twoPane) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                MapLegend(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).align(Alignment.CenterHorizontally))
                AnimatedVisibility(picked != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 640.dp)
                            .shadow(24.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = if (w.useRail) 20.dp else BottomBarSpace),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape).background(c.line))
                        picked?.let { BuildingPanel(city.buildings[it], city, mine[it], repo, now, onMyHome, onArea) }
                    }
                }
                if (picked == null && !w.useRail) Box(Modifier.height(BottomBarSpace))
            }
        }
    }
}

@Composable
private fun BuildingPanel(
    b: Building, city: CityMap, mine: ua.svetlo.monitor.data.Address?, repo: OutageRepository, now: LocalDateTime,
    onMyHome: (String, CityMap) -> Unit, onArea: () -> Unit,
) {
    val c = Svetlo.colors
    val ctx = LocalContext.current
    val st = mine?.let { repo.now(it.region, it.queue, now) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.address ?: tr("Будинок без адреси в OpenStreetMap", "Дом без адреса в OpenStreetMap"), style = Type.h2, color = c.ink)
            Text(
                when {
                    b.critical -> tr("Об'єкт критичної інфраструктури", "Объект критической инфраструктуры")
                    mine != null -> tr("${mine.labelName} · черга ${mine.queue}", "${mine.labelName} · очередь ${mine.queue}")
                    else -> city.city
                },
                style = Type.small, color = c.muted,
            )
        }
        if (st != null) {
            val (col, label) = stateLook(st)
            Pill(label, bg = c.surface2, fg = c.ink, border = null, dot = col)
        }
    }
    if (mine != null && st != null) {
        val today = repo.schedule(mine.region, mine.queue, now.toLocalDate())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStat(if (st.lightOn == true) tr("Вимкнуть", "Отключат") else tr("Увімкнуть", "Включат"), st.nextChange?.let { tr("о ${it.hm()}", "в ${it.hm()}") } ?: "—", Modifier.weight(1f))
            MiniStat(tr("Сьогодні без світла", "Сегодня без света"), if (today.known) formatDuration(today.offMinutes.toLong()) else "—", Modifier.weight(1f), onArea)
        }
        return
    }
    if (b.critical) {
        Text(tr("Такі будівлі зазвичай не відключають за графіком.", "Такие здания обычно не отключают по графику."), style = Type.small, color = c.ink2)
        return
    }
    Text(tr("Черга цього будинку нам поки невідома: базу «адреса → черга» ми ще збираємо. Свою чергу можна дізнатися на сайті оператора.",
        "Очередь этого дома нам пока неизвестна: базу «адрес → очередь» мы ещё собираем. Свою очередь можно узнать на сайте оператора."), style = Type.small, color = c.ink2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (b.address != null) PrimaryButton(tr("Це мій дім", "Это мой дом"), { onMyHome(b.address, city) }, Modifier.weight(1f))
        LookupUrls[city.region]?.let { url ->
            PrimaryButton(tr("Дізнатися чергу", "Узнать очередь"), {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.weight(1f), outlined = true)
        }
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

@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    val c = Svetlo.colors
    FlowRow(
        modifier.shadow(6.dp, CircleShape).clip(RoundedCornerShape(18.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem(Status.Green, tr("Мій дім: світло є", "Мой дом: свет есть")); LegendItem(Status.Yellow, tr("відключення", "отключение")); LegendItem(if (c.dark) Color(0xFF3A3E48) else Color(0xFFD3D0C6), tr("інші будинки", "другие дома"))
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
