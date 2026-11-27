package ua.svetlo.monitor.data

import java.time.LocalDate
import java.time.LocalDateTime

/** Вид отключения. Цвет задаётся в UI (см. [ua.svetlo.monitor.ui.components.color]). */
enum class OutageType(private val titleUk: String, private val titleRu: String, private val shortUk: String, private val shortRu: String) {
    None("Без відключень", "Без отключений", "Ні", "Нет"),
    Stabilization("Стабілізаційні", "Стабилизационные", "Стабіліз.", "Стабилиз."),
    Emergency("Аварійні", "Аварийные", "Аварійні", "Аварийные"),
    Blackout("Блекаут", "Блэкаут", "Блекаут", "Блэкаут"),
    Planned("Планові роботи", "Плановые работы", "Планові", "Плановые"),
    LocalFault("Локальна аварія", "Локальная авария", "Локальна", "Локальная"),
    NoData("Немає даних", "Нет данных", "Немає даних", "Нет данных");

    val title: String get() = tr(titleUk, titleRu)
    val short: String get() = tr(shortUk, shortRu)
}

/** Состояние получасового слота графика. */
enum class Slot { On, Off, Maybe }

data class DaySchedule(
    val date: LocalDate,
    /** 48 слотов по 30 минут, начиная с 00:00. */
    val slots: List<Slot>,
    val forecast: Boolean,
    /** false — графика на этот день ещё нет. */
    val known: Boolean = true,
) {
    val offMinutes: Int get() = slots.count { it == Slot.Off } * 30
    val maybeMinutes: Int get() = slots.count { it == Slot.Maybe } * 30

    /** Непрерывные окна одного состояния. */
    fun windows(state: Slot): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        for (i in 0..slots.size) {
            val on = i < slots.size && slots[i] == state
            if (on && start < 0) start = i
            if (!on && start >= 0) { out += start until i; start = -1 }
        }
        return out
    }
}

data class Address(
    val id: String,
    val label: String,
    val street: String,
    val city: String,
    val region: String,
    val queue: String,
) {
    /** Название города на языке интерфейса. Адреса храним по-украински, как в реестрах. */
    val cityName: String get() = if (I18n.lang == Lang.Uk) city else CityRu[city] ?: city
    /** Стандартные подписи переводим, свои подписи пользователя показываем как есть. */
    val labelName: String get() = when (label) { "Дім", "Дом" -> tr("Дім", "Дом"); else -> label }
}

private val CityRu = mapOf("Київ" to "Киев", "Запоріжжя" to "Запорожье", "Бровари" to "Бровары", "Львів" to "Львов", "Киев" to "Киев", "Львов" to "Львов", "Бровары" to "Бровары")

/** Что показывать на главном экране прямо сейчас. */
data class NowState(
    val type: OutageType,
    /** null — неизвестно (аварийные отключения, блэкаут или графика нет). */
    val lightOn: Boolean?,
    /** Время следующей смены состояния, если известно. */
    val nextChange: LocalDateTime?,
    /** Начало текущего отключения (для аварийных и блэкаута). */
    val since: LocalDateTime?,
    /** Ближайшее окно отключения по графику. */
    val nextWindow: Pair<LocalDateTime, LocalDateTime>?,
    /** Подтверждения «по факту». Показываем только в аварийном режиме и при блэкауте. */
    val factReports: Int?,
    val schedulesSuspended: Boolean,
    /** Объявление оператора («Шановні клієнти!…»). */
    val attention: String? = null,
    val updatedAt: java.time.OffsetDateTime? = null,
    val operator: String? = null,
)

data class RegionStatus(
    val id: String,
    val name: String,
    val type: OutageType,
    val avgMinutes: Int?,
    val hasData: Boolean = true,
)

data class AreaStats(
    val title: String,
    val city: String,
    val type: OutageType,
    val avgMinutes: Int,
    /** 7 дней: минуты стабилизационных, аварийных, блэкаута. */
    val days: List<Triple<Int, Int, Int>>,
    val dayLabels: List<String>,
    val queueOff: Map<String, Boolean>,
    /** Минуты без света по моей очереди в те же дни. */
    val myMinutes: List<Int> = emptyList(),
)

data class PowerWindow(val start: LocalDateTime, val end: LocalDateTime)

data class Building(
    val points: FloatArray,
    val address: String?,
    val critical: Boolean,
    val queue: String?,
) {
    val cx: Float
    val cy: Float
    val minX: Float; val minY: Float; val maxX: Float; val maxY: Float
    init {
        var sx = 0f; var sy = 0f
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        val n = points.size / 2
        for (i in 0 until n) {
            val x = points[2 * i]; val y = points[2 * i + 1]
            sx += x; sy += y
            if (x < a) a = x; if (y < b) b = y; if (x > c) c = x; if (y > d) d = y
        }
        cx = if (n > 0) sx / n else 0f; cy = if (n > 0) sy / n else 0f
        minX = a; minY = b; maxX = c; maxY = d
    }

    fun contains(x: Float, y: Float): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        var inside = false
        val n = points.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = points[2 * i]; val yi = points[2 * i + 1]
            val xj = points[2 * j]; val yj = points[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

data class MapLine(val kind: String, val points: FloatArray, val name: String?)

data class CityMap(
    val id: String,
    val region: String,
    val cityUk: String, val cityRu: String,
    val districtUk: String, val districtRu: String,
    val homeAddress: String,
    val buildings: List<Building>,
    val lines: List<MapLine>,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
    /** Широта и долгота начала координат карты (meta.origin); NaN у старых файлов карт. */
    val originLat: Double = Double.NaN, val originLon: Double = Double.NaN,
) {
    val city: String get() = tr(cityUk, cityRu)
    val hasOrigin: Boolean get() = !originLat.isNaN() && !originLon.isNaN()

    /** Метры карты → (долгота, широта). Обратное к dm() в collector/maps/gen_city_bin.py. */
    fun toLonLat(x: Float, y: Float): Pair<Double, Double> {
        val kx = 111320.0 * kotlin.math.cos(Math.toRadians(originLat))
        return (originLon + x / kx) to (originLat - y / 110540.0)
    }

    fun ringLonLat(b: Building): List<Pair<Double, Double>> = (0 until b.points.size / 2).map { toLonLat(b.points[2 * it], b.points[2 * it + 1]) }

    /** Точка внутри контура дома: центр, а если он снаружи (дом буквой Г), — первая подходящая точка сетки. */
    fun insideLonLat(b: Building): Pair<Double, Double> {
        if (b.contains(b.cx, b.cy)) return toLonLat(b.cx, b.cy)
        for (i in 1..9) for (j in 1..9) {
            val x = b.minX + (b.maxX - b.minX) * i / 10f; val y = b.minY + (b.maxY - b.minY) * j / 10f
            if (b.contains(x, y)) return toLonLat(x, y)
        }
        return toLonLat(b.cx, b.cy)
    }
    val district: String get() = tr(districtUk, districtRu)
}

data class Region(val id: String, val name: String, val pathData: String)

data class UkraineMap(val width: Float, val height: Float, val regions: List<Region>)
