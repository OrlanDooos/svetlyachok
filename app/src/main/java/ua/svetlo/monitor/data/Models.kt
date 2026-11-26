package ua.svetlo.monitor.data

import java.time.LocalDate
import java.time.LocalDateTime

/** Вид отключения. Цвет задаётся в UI (см. [ua.svetlo.monitor.ui.components.color]). */
enum class OutageType(val title: String, val short: String) {
    None("Без отключений", "Нет"),
    Stabilization("Стабилизационные", "Стабилиз."),
    Emergency("Аварийные", "Аварийные"),
    Blackout("Блэкаут", "Блэкаут"),
    Planned("Плановые работы", "Плановые"),
    LocalFault("Локальная авария", "Локальная"),
    NoData("Нет данных", "Нет данных"),
}

/** Состояние получасового слота графика. */
enum class Slot { On, Off, Maybe }

/** Сценарий тестовых данных. В рабочей версии режим приходит с сервера. */
enum class Scenario(val title: String, val hint: String) {
    Normal("Обычный день", "Действуют графики стабилизационных отключений"),
    Emergency("Аварийные", "В Киеве аварийные отключения, графики не действуют"),
    Blackout("Блэкаут", "Масштабное отключение по области"),
    Calm("Без отключений", "Графики не применяются"),
}

data class DaySchedule(
    val date: LocalDate,
    /** 48 слотов по 30 минут, начиная с 00:00. */
    val slots: List<Slot>,
    val forecast: Boolean,
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
)

/** Что показывать на главном экране прямо сейчас. */
data class NowState(
    val type: OutageType,
    val lightOn: Boolean,
    /** Время следующей смены состояния, если известно. */
    val nextChange: LocalDateTime?,
    /** Начало текущего отключения (для аварийных и блэкаута). */
    val since: LocalDateTime?,
    /** Ближайшее окно отключения по графику. */
    val nextWindow: Pair<LocalDateTime, LocalDateTime>?,
    /** Подтверждения «по факту». Показываем только в аварийном режиме и при блэкауте. */
    val factReports: Int?,
    val schedulesSuspended: Boolean,
)

data class RegionStatus(
    val id: String,
    val name: String,
    val type: OutageType,
    val avgMinutes: Int?,
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
)

data class GridState(
    val deficitMw: Int,
    val consumptionMw: Int,
    val peakHours: String,
    val message: String,
    val regionsWithSchedules: Int,
    val savingTips: List<String>,
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
    val city: String,
    val district: String,
    val buildings: List<Building>,
    val lines: List<MapLine>,
    val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
)

data class Region(val id: String, val name: String, val pathData: String)

data class UkraineMap(val width: Float, val height: Float, val regions: List<Region>)
