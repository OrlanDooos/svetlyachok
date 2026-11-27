package ua.svetlo.monitor.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Откуда берём графики: сборщик (collector/) каждую минуту кладёт их на наш сервер Cloudflare и в ветку data. */
object DataSource {
    /** Сервер Cloudflare: свежие графики через ~20 секунд после сбора (кеш GitHub — до 10 минут). */
    const val Fast = "https://svetlyachok-pins.svetlyachok.workers.dev/data/v1/"
    /** GitHub Pages из ветки data (CDN, рассчитан на раздачу), запасной вариант — raw.githubusercontent. */
    val Bases = listOf(
        "https://orlandooos.github.io/svetlyachok/v1/",
        "https://raw.githubusercontent.com/OrlanDooos/svetlyachok/data/v1/",
    )
    val Kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
}

/** Режим в области: по графику, аварийные (графики не действуют), блэкаут, без отключений, нет данных. */
enum class RegionMode { Schedule, Emergency, Blackout, None, Unknown;
    companion object {
        fun of(s: String?) = when (s) {
            "schedule" -> Schedule; "emergency" -> Emergency; "blackout" -> Blackout; "none" -> None; else -> Unknown
        }
    }
    val type: OutageType get() = when (this) {
        Schedule -> OutageType.Stabilization; Emergency -> OutageType.Emergency; Blackout -> OutageType.Blackout
        None -> OutageType.None; Unknown -> OutageType.NoData
    }
}

data class RegionSummary(
    val id: String, val name: String, val operator: String, val mode: RegionMode, val ok: Boolean,
    val hash: String?, val changedAt: OffsetDateTime?, val checkedAt: OffsetDateTime?, val avgOffMinutesToday: Int?, val queues: List<String>,
)

data class RegionData(
    val id: String, val name: String, val operator: String, val source: String, val mode: RegionMode,
    val attention: String?, val updatedAtSource: String?,
    /** Дата → очередь → 48 символов ('1' свет есть, '0' отключение, '2' возможно). */
    val days: Map<LocalDate, Map<String, String>>,
    /** Недельный шаблон оператора (пн…нд), если он его публикует. */
    val week: Map<String, List<String>>,
    val hash: String?, val changedAt: OffsetDateTime?, val checkedAt: OffsetDateTime?,
)

/** Кэш на диске + загрузка из сети. Всё, что видит интерфейс, читается из кэша. */
class ScheduleStore(private val dir: File) {
    constructor(ctx: Context) : this(File(ctx.filesDir, "data/v1"))

    private val _version = MutableStateFlow(0L)
    /** Меняется после каждого обновления данных — интерфейс перерисовывается. */
    val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile private var summaries: List<RegionSummary>? = null
    private val regions = HashMap<String, RegionData?>()

    @Volatile var lastError: String? = null
        private set

    fun summaries(): List<RegionSummary> = summaries ?: synchronized(this) {
        summaries ?: readSummaries().also { summaries = it }
    }

    fun region(id: String): RegionData? = synchronized(this) {
        if (!regions.containsKey(id)) regions[id] = readRegion(id)
        regions[id]
    }

    val generatedAt: OffsetDateTime?
        get() = runCatching { OffsetDateTime.parse(JSONObject(File(dir, "regions.json").readText()).getString("generatedAt")) }.getOrNull()

    /** Скачивает список областей и файлы нужных областей. Возвращает id областей, где график изменился. */
    suspend fun refresh(regionIds: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val changed = mutableSetOf<String>()
        try {
            dir.mkdirs()
            val idx = download("regions.json")
            val old = summaries().associateBy { it.id }
            File(dir, "regions.json").writeText(idx)
            val fresh = parseSummaries(idx)
            for (r in fresh) {
                val need = r.id in regionIds || !File(dir, "${r.id}.json").exists()
                if (need && r.ok && (old[r.id]?.hash != r.hash || !File(dir, "${r.id}.json").exists())) {
                    runCatching { File(dir, "${r.id}.json").writeText(download("${r.id}.json")) }
                        .onSuccess { if (old[r.id] != null && old[r.id]?.hash != r.hash) changed += r.id }
                }
            }
            synchronized(this@ScheduleStore) { summaries = fresh; regions.clear() }
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
        _version.value = _version.value + 1
        changed
    }

    private val addr = HashMap<String, AddressIndex?>()

    /** Списки «адрес → подочередь» из кэша (null — для области их нет или ещё не скачаны). */
    fun addresses(region: String): AddressIndex? = synchronized(this) {
        if (!addr.containsKey(region)) addr[region] = runCatching { AddressIndex.parse(File(dir, "addr/$region.json").readText()) }.getOrNull()
        addr[region]
    }

    /** Скачивает списки адресов, если копия старше суток. Списки меняются редко, файл ~230 КБ. */
    suspend fun refreshAddresses(region: String): Unit = withContext(Dispatchers.IO) {
        val f = File(dir, "addr/$region.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return@withContext
        runCatching {
            val body = download("addr/$region.json")
            AddressIndex.parse(body)
            f.parentFile?.mkdirs(); f.writeText(body)
            synchronized(this@ScheduleStore) { addr.remove(region) }
            _version.value = _version.value + 1
        }
    }

    /**
     * Свежая карта города: раз в сутки смотрим maps/index.json и качаем карту (~1,6 МБ), только если она изменилась.
     * Возвращает true, если карта обновилась.
     */
    suspend fun refreshMap(id: String): Boolean = withContext(Dispatchers.IO) {
        val mapDir = File(dir, "maps").apply { mkdirs() }
        val mark = File(mapDir, "$id.hash")
        if (mark.exists() && System.currentTimeMillis() - mark.lastModified() < 24 * 3600_000L) return@withContext false
        runCatching {
            val remote = JSONObject(download("maps/index.json")).optJSONObject(id)?.optString("hash") ?: return@runCatching false
            if (mark.exists() && mark.readText() == remote) { mark.setLastModified(System.currentTimeMillis()); return@runCatching false }
            val bytes = downloadBytes("maps/$id.bin")
            check(bytes.size > 8 && String(bytes, 0, 4) == "SVC2") { "bad map" }
            val tmp = File(mapDir, "$id.bin.tmp"); tmp.writeBytes(bytes); tmp.renameTo(File(mapDir, "$id.bin"))
            mark.writeText(remote)
            true
        }.getOrDefault(false)
    }

    private fun downloadBytes(name: String): ByteArray {
        var last: Exception? = null
        for (base in DataSource.Bases) {
            val c = URL(base + name + "?t=" + System.currentTimeMillis() / 3600000).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Svetlyachok-Android")
            try {
                if (c.responseCode == 200) return c.inputStream.readBytes()
                last = IllegalStateException("HTTP ${c.responseCode}")
            } catch (e: Exception) { last = e } finally { c.disconnect() }
        }
        throw last ?: IllegalStateException("no source")
    }

    /** Для тестов: положить данные в кэш. */
    fun put(name: String, json: String) {
        File(dir, name).apply { parentFile?.mkdirs() }.writeText(json)
        synchronized(this) { summaries = null; regions.clear(); addr.clear() }
        _version.value = _version.value + 1
    }

    private fun download(name: String): String {
        var last: Exception? = null
        for (base in listOf(DataSource.Fast) + DataSource.Bases) {
            val c = URL(base + name + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Svetlyachok-Android")
            try {
                if (c.responseCode == 200) return c.inputStream.bufferedReader().readText()
                last = IllegalStateException("HTTP ${c.responseCode}")
            } catch (e: Exception) { last = e } finally { c.disconnect() }
        }
        throw last ?: IllegalStateException("no source")
    }

    private fun readSummaries(): List<RegionSummary> =
        runCatching { parseSummaries(File(dir, "regions.json").readText()) }.getOrDefault(emptyList())

    private fun readRegion(id: String): RegionData? =
        runCatching { parseRegion(File(dir, "$id.json").readText()) }.getOrNull()

    companion object {
        private fun time(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

        fun parseSummaries(json: String): List<RegionSummary> {
            val arr = JSONObject(json).getJSONArray("regions")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val q = o.optJSONArray("queues")
                RegionSummary(
                    o.getString("id"), o.optString("name"), o.optString("operator"), RegionMode.of(o.optString("mode")), o.optBoolean("ok"),
                    o.optString("hash").ifEmpty { null }, time(o, "changedAt"), time(o, "checkedAt"),
                    if (o.isNull("avgOffMinutesToday")) null else o.optInt("avgOffMinutesToday"),
                    if (q == null) emptyList() else (0 until q.length()).map { q.getString(it) },
                )
            }
        }

        fun parseRegion(json: String): RegionData {
            val o = JSONObject(json)
            val days = o.optJSONObject("days")?.let { d ->
                d.keys().asSequence().associate { k ->
                    val qs = d.getJSONObject(k)
                    LocalDate.parse(k) to qs.keys().asSequence().associateWith { qs.getString(it) }
                }
            } ?: emptyMap()
            val week = o.optJSONObject("week")?.let { w ->
                w.keys().asSequence().associateWith { q -> w.getJSONArray(q).let { a -> (0 until a.length()).map { a.getString(it) } } }
            } ?: emptyMap()
            return RegionData(
                o.getString("id"), o.optString("name"), o.optString("operator"), o.optString("source"), RegionMode.of(o.optString("mode")),
                o.optString("attention").takeIf { it.isNotEmpty() && it != "null" }, o.optString("updatedAtSource").takeIf { it.isNotEmpty() && it != "null" },
                days, week, o.optString("hash").ifEmpty { null }, time(o, "changedAt"), time(o, "checkedAt"),
            )
        }
    }
}

/** Главный источник данных интерфейса: графики из [ScheduleStore]. */
class LiveRepository(val store: ScheduleStore) : OutageRepository {

    override val version: StateFlow<Long> get() = store.version

    override fun summary(region: String) = store.summaries().firstOrNull { it.id == region }
    override fun addresses(region: String) = store.addresses(region)
    override suspend fun refreshAddresses(region: String) { store.refreshAddresses(region) }
    override suspend fun refreshMap(id: String) = store.refreshMap(id)
    override fun region(region: String) = store.region(region)

    override fun queues(region: String): List<String> =
        summary(region)?.queues?.takeIf { it.isNotEmpty() } ?: DefaultQueues

    override fun schedule(region: String, queue: String, date: LocalDate): DaySchedule {
        val r = store.region(region)
        r?.days?.get(date)?.get(queue)?.let { return DaySchedule(date, it.toSlots(), forecast = false, known = true) }
        // Недельный шаблон оператора не показываем: настоящий график на завтра появляется только вечером.
        return DaySchedule(date, List(48) { Slot.On }, forecast = date.isAfter(LocalDate.now(DataSource.Kyiv)), known = false)
    }

    private fun slotAt(t: LocalDateTime) = t.hour * 2 + t.minute / 30
    private fun slotStart(date: LocalDate, slot: Int): LocalDateTime = date.atStartOfDay().plusMinutes(slot * 30L)

    override fun now(region: String, queue: String, at: LocalDateTime): NowState {
        val sum = summary(region)
        val data = store.region(region)
        val mode = data?.mode ?: sum?.mode ?: RegionMode.Unknown
        val updated = data?.checkedAt ?: sum?.checkedAt
        if (mode == RegionMode.Emergency || mode == RegionMode.Blackout) {
            return NowState(mode.type, lightOn = null, nextChange = null, since = null, nextWindow = null, factReports = null,
                schedulesSuspended = true, attention = data?.attention, updatedAt = updated, operator = data?.operator ?: sum?.operator)
        }
        val today = schedule(region, queue, at.toLocalDate())
        if (!today.known) {
            return NowState(OutageType.NoData, null, null, null, null, null, false, data?.attention, updated, data?.operator ?: sum?.operator)
        }
        val tomorrow = schedule(region, queue, at.toLocalDate().plusDays(1)).let { if (it.known && !it.forecast) it.slots else List(48) { Slot.On } }
        val all = today.slots + tomorrow
        val cur = slotAt(at)
        val off = all[cur] == Slot.Off
        var j = cur
        while (j < all.size && (all[j] == Slot.Off) == off) j++
        val next = if (j < all.size) slotStart(at.toLocalDate(), j) else null
        var s = cur
        if (!off) { while (s < all.size && all[s] != Slot.Off) s++ } else { while (s > 0 && all[s - 1] == Slot.Off) s-- }
        var e = s
        while (e < all.size && all[e] == Slot.Off) e++
        val window = if (s < all.size) slotStart(at.toLocalDate(), s) to slotStart(at.toLocalDate(), e) else null
        val type = if (window == null && !off) OutageType.None else OutageType.Stabilization
        return NowState(type, lightOn = !off, nextChange = if (window == null && !off) null else next,
            since = if (off) slotStart(at.toLocalDate(), s) else null, nextWindow = window, factReports = null,
            schedulesSuspended = false, attention = data?.attention, updatedAt = updated, operator = data?.operator ?: sum?.operator)
    }

    override fun queueOffNow(region: String, queue: String, at: LocalDateTime): OutageType {
        val mode = summary(region)?.mode ?: RegionMode.Unknown
        if (mode == RegionMode.Emergency || mode == RegionMode.Blackout) return mode.type
        val d = schedule(region, queue, at.toLocalDate())
        if (!d.known) return OutageType.NoData
        return if (d.slots[slotAt(at)] == Slot.Off) OutageType.Stabilization else OutageType.None
    }

    override fun powerWindows(region: String, queue: String, date: LocalDate): List<PowerWindow> {
        val d = schedule(region, queue, date)
        if (!d.known) return emptyList()
        return d.windows(Slot.On).map { PowerWindow(slotStart(date, it.first), slotStart(date, it.last + 1)) }
    }

    override fun regions(): List<RegionStatus> {
        val known = store.summaries().associateBy { it.id }
        return RegionIds.map { id ->
            val s = known[id]
            RegionStatus(id, RegionNames[id] ?: id, s?.mode?.type ?: OutageType.NoData, s?.avgOffMinutesToday, hasData = s != null)
        }
    }

    override fun area(region: String, queue: String): AreaStats? {
        val r = store.region(region) ?: return null
        val today = LocalDate.now(DataSource.Kyiv)
        val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }.filter { r.days.containsKey(it) }
        if (dates.isEmpty()) return null
        // Минуты без света в среднем по всем очередям области: (стабилизационные, аварийные, блэкаут).
        val days = dates.map { d ->
            val qs = r.days.getValue(d).values
            val avg = if (qs.isEmpty()) 0 else qs.sumOf { s -> s.count { it == '0' } * 30 } / qs.size
            Triple(avg, 0, 0)
        }
        val mine = dates.map { d -> (r.days.getValue(d)[queue]?.count { it == '0' } ?: 0) * 30 }
        val queues = queues(region)
        val at = LocalDateTime.now(DataSource.Kyiv)
        return AreaStats(
            title = r.name, city = r.operator, type = r.mode.type,
            avgMinutes = days.sumOf { it.first } / days.size, days = days, dayLabels = dates.map { it.dayOfWeek.ruShort() },
            queueOff = queues.associateWith { queueOffNow(region, it, at) != OutageType.None },
            myMinutes = mine,
        )
    }

    companion object {
        val DefaultQueues = listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2")
        /** Подконтрольные области, которые показываем в списке. Крым и Луганщина — без данных. */
        val RegionIds = listOf(
            "kyiv-city", "kyiv", "chernihiv", "sumy", "kharkiv", "poltava", "dnipropetrovsk", "zaporizhia", "donetsk", "kherson",
            "mykolaiv", "odessa", "kirovohrad", "cherkasy", "vinnytsia", "zhytomyr", "khmelnytskyi", "rivne", "volyn", "lviv",
            "ternopil", "ivano-frankivsk", "zakarpattia", "chernivtsi", "crimea", "luhansk",
        )
        val RegionNames: Map<String, String> get() = mapOf(
            "cherkasy" to tr("Черкаська область", "Черкасская область"), "chernihiv" to tr("Чернігівська область", "Черниговская область"),
            "chernivtsi" to tr("Чернівецька область", "Черновицкая область"), "crimea" to tr("АР Крим", "АР Крым"),
            "dnipropetrovsk" to tr("Дніпропетровська область", "Днепропетровская область"), "donetsk" to tr("Донецька область", "Донецкая область"),
            "ivano-frankivsk" to tr("Івано-Франківська область", "Ивано-Франковская область"), "kharkiv" to tr("Харківська область", "Харьковская область"),
            "kherson" to tr("Херсонська область", "Херсонская область"), "khmelnytskyi" to tr("Хмельницька область", "Хмельницкая область"),
            "kirovohrad" to tr("Кіровоградська область", "Кировоградская область"), "kyiv" to tr("Київська область", "Киевская область"),
            "kyiv-city" to tr("Київ", "Киев"), "luhansk" to tr("Луганська область", "Луганская область"), "lviv" to tr("Львівська область", "Львовская область"),
            "mykolaiv" to tr("Миколаївська область", "Николаевская область"), "odessa" to tr("Одеська область", "Одесская область"),
            "poltava" to tr("Полтавська область", "Полтавская область"), "rivne" to tr("Рівненська область", "Ровенская область"),
            "sumy" to tr("Сумська область", "Сумская область"), "ternopil" to tr("Тернопільська область", "Тернопольская область"),
            "vinnytsia" to tr("Вінницька область", "Винницкая область"), "volyn" to tr("Волинська область", "Волынская область"),
            "zakarpattia" to tr("Закарпатська область", "Закарпатская область"), "zaporizhia" to tr("Запорізька область", "Запорожская область"),
            "zhytomyr" to tr("Житомирська область", "Житомирская область"),
        )
        /** Главный город области — для подписи адреса. */
        val RegionCity: Map<String, String> = mapOf(
            "kyiv-city" to "Київ", "kyiv" to "Київська обл.", "dnipropetrovsk" to "Дніпро", "odessa" to "Одеса", "lviv" to "Львів",
            "zaporizhia" to "Запоріжжя", "kharkiv" to "Харків", "cherkasy" to "Черкаси", "mykolaiv" to "Миколаїв", "rivne" to "Рівне",
            "zhytomyr" to "Житомир", "khmelnytskyi" to "Хмельницький", "ivano-frankivsk" to "Івано-Франківськ", "kirovohrad" to "Кропивницький",
            "poltava" to "Полтава", "sumy" to "Суми", "chernihiv" to "Чернігів", "vinnytsia" to "Вінниця", "ternopil" to "Тернопіль",
            "volyn" to "Луцьк", "zakarpattia" to "Ужгород", "chernivtsi" to "Чернівці", "kherson" to "Херсон", "donetsk" to "Донецька обл.",
        )
    }
}

private fun String.toSlots(): List<Slot> = List(48) { i -> when (getOrNull(i)) { '0' -> Slot.Off; '2' -> Slot.Maybe; else -> Slot.On } }
