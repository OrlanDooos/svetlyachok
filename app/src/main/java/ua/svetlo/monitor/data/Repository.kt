package ua.svetlo.monitor.data

import android.content.Context
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Источник данных приложения. Рабочая реализация — [LiveRepository] (графики из ветки data). */
interface OutageRepository {
    val version: kotlinx.coroutines.flow.StateFlow<Long>
    fun summary(region: String): RegionSummary?
    fun addresses(region: String): AddressIndex?
    suspend fun refreshAddresses(region: String)
    suspend fun refreshMap(id: String): Boolean
    fun region(region: String): RegionData?
    fun queues(region: String): List<String>
    fun schedule(region: String, queue: String, date: LocalDate): DaySchedule
    fun now(region: String, queue: String, at: LocalDateTime): NowState
    fun regions(): List<RegionStatus>
    fun area(region: String, queue: String): AreaStats?
    fun queueOffNow(region: String, queue: String, at: LocalDateTime): OutageType
    fun powerWindows(region: String, queue: String, date: LocalDate): List<PowerWindow>
}

fun java.time.DayOfWeek.ruShort(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> tr("Пн", "Пн"); java.time.DayOfWeek.TUESDAY -> tr("Вт", "Вт"); java.time.DayOfWeek.WEDNESDAY -> tr("Ср", "Ср")
    java.time.DayOfWeek.THURSDAY -> tr("Чт", "Чт"); java.time.DayOfWeek.FRIDAY -> tr("Пт", "Пт"); java.time.DayOfWeek.SATURDAY -> tr("Сб", "Сб")
    java.time.DayOfWeek.SUNDAY -> tr("Нд", "Вс")
}

fun java.time.DayOfWeek.ruLong(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> tr("Понеділок", "Понедельник"); java.time.DayOfWeek.TUESDAY -> tr("Вівторок", "Вторник"); java.time.DayOfWeek.WEDNESDAY -> tr("Середа", "Среда")
    java.time.DayOfWeek.THURSDAY -> tr("Четвер", "Четверг"); java.time.DayOfWeek.FRIDAY -> tr("П'ятниця", "Пятница"); java.time.DayOfWeek.SATURDAY -> tr("Субота", "Суббота")
    java.time.DayOfWeek.SUNDAY -> tr("Неділя", "Воскресенье")
}

private val MonthsRu = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val MonthsUk = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
fun LocalDate.ruDayMonth() = "$dayOfMonth ${(if (I18n.lang == Lang.Uk) MonthsUk else MonthsRu)[monthValue - 1]}"

fun formatDuration(minutes: Long): String {
    val h = minutes / 60; val m = minutes % 60
    return when {
        h == 0L -> tr("$m хв", "$m мин")
        m == 0L -> tr("$h год", "$h ч")
        else -> tr("$h год $m хв", "$h ч $m мин")
    }
}

fun formatShort(minutes: Int?): String {
    if (minutes == null) return "—"
    val h = minutes / 60; val m = minutes % 60
    return if (h == 0) tr("$m хв", "$m м") else tr("$h год %02d хв", "$h ч %02d м").format(m)
}

fun between(a: LocalDateTime, b: LocalDateTime) = Duration.between(a, b).toMinutes().coerceAtLeast(0)

fun LocalDateTime.hm(): String = "%02d:%02d".format(hour, minute)

/** Загрузка карт из assets. Контуры домов: © участники OpenStreetMap (ODbL). Карта областей: @svg-maps/ukraine (CC BY 4.0). */
object MapAssets {
    @Volatile private var ukraine: UkraineMap? = null
    private val cities = mutableMapOf<String, CityMap>()

    /** Города, для которых есть карта домов: id → файл в assets (формат см. tools/gen_city_bin.py). */
    val CityFiles = linkedMapOf("kyiv" to "kyiv_pechersk.bin", "zaporizhzhia" to "zaporizhzhia.bin")
    val CityRegions = mapOf("kyiv-city" to "kyiv", "zaporizhia" to "zaporizhzhia")

    fun ukraine(ctx: Context): UkraineMap = ukraine ?: synchronized(this) {
        ukraine ?: run {
            val o = JSONObject(ctx.assets.open("ukraine.json").bufferedReader().readText())
            val arr = o.getJSONArray("regions")
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map { Region(it.getString("id"), it.getString("name"), it.getString("d")) }
            UkraineMap(o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), list).also { ukraine = it }
        }
    }

    fun cityIdFor(region: String?): String = CityRegions[region] ?: "kyiv"

    fun allCities(ctx: Context): List<CityMap> = CityFiles.keys.map { city(ctx, it) }

    /** Свежая карта, скачанная с сервера (еженедельная пересборка из OpenStreetMap). */
    fun downloaded(ctx: Context, id: String) = java.io.File(ctx.filesDir, "data/v1/maps/$id.bin")

    fun invalidate(id: String) = synchronized(this) { cities.remove(id) }

    fun city(ctx: Context, id: String = "kyiv"): CityMap = synchronized(this) {
        cities[id]?.let { return it }
        val file = downloaded(ctx, id)
        if (file.exists()) runCatching { return read(file.inputStream(), id) }.onFailure { file.delete() }
        read(ctx.assets.open(CityFiles[id] ?: CityFiles.getValue("kyiv")), id)
    }

    private fun read(stream: java.io.InputStream, id: String): CityMap =
        java.io.DataInputStream(java.io.BufferedInputStream(stream, 1 shl 16)).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            check(String(magic) == "SVC2") { "bad map file" }
            val meta = JSONObject(String(ByteArray(inp.readInt()).also { inp.readFully(it) }, Charsets.UTF_8))
            val strings = Array(inp.readInt()) { String(ByteArray(inp.readUnsignedShort()).also { b -> inp.readFully(b) }, Charsets.UTF_8) }
            fun points(): FloatArray {
                val n = inp.readUnsignedShort()
                val out = FloatArray(n * 2)
                var x = inp.readInt(); var y = inp.readInt()
                out[0] = x / 10f; out[1] = y / 10f
                for (k in 1 until n) { x += inp.readShort(); y += inp.readShort(); out[2 * k] = x / 10f; out[2 * k + 1] = y / 10f }
                return out
            }
            val nb = inp.readInt()
            val buildings = ArrayList<Building>(nb)
            for (i in 0 until nb) {
                val si = inp.readInt(); val flags = inp.readByte().toInt()
                buildings += Building(points(), if (si >= 0) strings[si] else null, flags and 1 == 1, null)
            }
            val kinds = arrayOf("primary", "secondary", "tertiary", "residential", "pedestrian", "park", "water")
            val nl = inp.readInt()
            val lines = ArrayList<MapLine>(nl)
            for (i in 0 until nl) {
                val k = inp.readByte().toInt(); val si = inp.readInt()
                lines += MapLine(kinds.getOrElse(k) { "residential" }, points(), if (si >= 0) strings[si] else null)
            }
            val minX = buildings.minOf { it.minX }; val minY = buildings.minOf { it.minY }
            val maxX = buildings.maxOf { it.maxX }; val maxY = buildings.maxOf { it.maxY }
            CityMap(meta.getString("id"), meta.getString("region"), meta.getString("city"), meta.getString("city_ru"),
                meta.getString("district"), meta.getString("district_ru"), meta.optString("home"),
                buildings, lines, minX, minY, maxX, maxY,
                meta.optJSONArray("origin")?.optDouble(0) ?: Double.NaN, meta.optJSONArray("origin")?.optDouble(1) ?: Double.NaN).also { synchronized(this) { cities[id] = it } }
        }

    const val HOME_ADDRESS = "вулиця Лєскова, 9"
}
