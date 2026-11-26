package ua.svetlo.monitor.data

import android.content.Context
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * Источник данных приложения.
 * Сейчас это тестовые данные. В рабочей версии те же методы будут брать данные с нашего сервера.
 */
interface OutageRepository {
    val queues: List<String>
    fun schedule(queue: String, date: LocalDate, scenario: Scenario): DaySchedule
    fun now(queue: String, at: LocalDateTime, scenario: Scenario): NowState
    fun regions(scenario: Scenario): List<RegionStatus>
    fun area(scenario: Scenario): AreaStats
    fun grid(scenario: Scenario): GridState
    fun queueOffNow(queue: String, at: LocalDateTime, scenario: Scenario): OutageType
    fun powerWindows(queue: String, date: LocalDate, scenario: Scenario): List<PowerWindow>
}

class MockRepository : OutageRepository {

    override val queues = listOf("1.1", "1.2", "2.1", "2.2", "3.1", "3.2", "4.1", "4.2", "5.1", "5.2", "6.1", "6.2")

    private fun queueIndex(q: String) = queues.indexOf(q).coerceAtLeast(0)

    override fun schedule(queue: String, date: LocalDate, scenario: Scenario): DaySchedule {
        val today = LocalDate.now()
        val forecast = date.isAfter(today.plusDays(1))
        if (scenario == Scenario.Calm) return DaySchedule(date, List(48) { Slot.On }, forecast)
        val i = queueIndex(queue)
        val group = i / 2
        val sub = i % 2
        val day = (date.toEpochDay() % 7).toInt()
        // Два окна по 4 часа в сутки, сдвиг зависит от очереди и дня.
        val startHour = (group * 4 + sub * 2 + day * 2 + 2) % 12
        val slots = MutableList(48) { Slot.On }
        for (w in 0..1) {
            val s = ((startHour + w * 12) % 24) * 2
            for (k in 0 until 8) slots[(s + k) % 48] = Slot.Off
        }
        // Возможное отключение на 2 часа между окнами (только в ближайшие два дня).
        if (!forecast) {
            val m = ((startHour + 7) % 24) * 2
            for (k in 0 until 4) if (slots[(m + k) % 48] == Slot.On) slots[(m + k) % 48] = Slot.Maybe
        }
        return DaySchedule(date, slots, forecast)
    }

    private fun slotAt(t: LocalDateTime) = t.hour * 2 + t.minute / 30

    private fun slotStart(date: LocalDate, slot: Int): LocalDateTime =
        date.atStartOfDay().plusMinutes(slot * 30L)

    override fun now(queue: String, at: LocalDateTime, scenario: Scenario): NowState {
        val i = queueIndex(queue)
        when (scenario) {
            Scenario.Emergency -> {
                val since = at.toLocalDate().atTime(LocalTime.of(13, 10)).let { if (it.isAfter(at)) at.minusMinutes(50) else it }
                return NowState(
                    type = OutageType.Emergency, lightOn = false,
                    nextChange = since.plusHours(5).plusMinutes(20), since = since, nextWindow = null,
                    factReports = 41 + i * 3, schedulesSuspended = true,
                )
            }
            Scenario.Blackout -> {
                val since = at.minusHours(2).minusMinutes(15)
                return NowState(OutageType.Blackout, false, null, since, null, 128 + i, true)
            }
            Scenario.Calm -> return NowState(OutageType.None, true, null, null, null, null, false)
            Scenario.Normal -> Unit
        }
        val today = schedule(queue, at.toLocalDate(), scenario)
        val tomorrow = schedule(queue, at.toLocalDate().plusDays(1), scenario)
        val all = today.slots + tomorrow.slots
        val cur = slotAt(at)
        val off = all[cur] == Slot.Off
        var j = cur
        while (j < all.size && (all[j] == Slot.Off) == off) j++
        val next = if (j < all.size) slotStart(at.toLocalDate(), j) else null
        // Ближайшее окно отключения (текущее или следующее).
        var s = cur
        if (!off) { while (s < all.size && all[s] != Slot.Off) s++ } else { while (s > 0 && all[s - 1] == Slot.Off) s-- }
        var e = s
        while (e < all.size && all[e] == Slot.Off) e++
        val window = if (s < all.size) slotStart(at.toLocalDate(), s) to slotStart(at.toLocalDate(), e) else null
        return NowState(
            type = OutageType.Stabilization, lightOn = !off, nextChange = next,
            since = if (off) slotStart(at.toLocalDate(), s) else null,
            nextWindow = window, factReports = null, schedulesSuspended = false,
        )
    }

    override fun queueOffNow(queue: String, at: LocalDateTime, scenario: Scenario): OutageType = when (scenario) {
        Scenario.Emergency -> if (queueIndex(queue) % 3 != 1) OutageType.Emergency else OutageType.None
        Scenario.Blackout -> OutageType.Blackout
        Scenario.Calm -> OutageType.None
        Scenario.Normal -> if (schedule(queue, at.toLocalDate(), scenario).slots[slotAt(at)] == Slot.Off) OutageType.Stabilization else OutageType.None
    }

    override fun powerWindows(queue: String, date: LocalDate, scenario: Scenario): List<PowerWindow> {
        val d = schedule(queue, date, scenario)
        return d.windows(Slot.On).map { PowerWindow(slotStart(date, it.first), slotStart(date, it.last + 1)) }
    }

    override fun regions(scenario: Scenario): List<RegionStatus> {
        val base = linkedMapOf(
            "kyiv-city" to (OutageType.Stabilization to 260), "kyiv" to (OutageType.Stabilization to 300),
            "chernihiv" to (OutageType.Emergency to 380), "sumy" to (OutageType.Blackout to 450),
            "kharkiv" to (OutageType.Blackout to 485), "poltava" to (OutageType.Stabilization to 290),
            "dnipropetrovsk" to (OutageType.Emergency to 330), "zaporizhia" to (OutageType.Emergency to 360),
            "donetsk" to (OutageType.Blackout to 540), "kherson" to (OutageType.Emergency to 400),
            "mykolaiv" to (OutageType.Stabilization to 310), "odessa" to (OutageType.Emergency to 340),
            "kirovohrad" to (OutageType.Stabilization to 280), "cherkasy" to (OutageType.Stabilization to 270),
            "vinnytsia" to (OutageType.Stabilization to 250), "zhytomyr" to (OutageType.Stabilization to 240),
            "khmelnytskyi" to (OutageType.None to 90), "rivne" to (OutageType.Stabilization to 200),
            "volyn" to (OutageType.None to 70), "lviv" to (OutageType.None to 60),
            "ternopil" to (OutageType.Stabilization to 180), "ivano-frankivsk" to (OutageType.None to 75),
            "zakarpattia" to (OutageType.None to 40), "chernivtsi" to (OutageType.None to 55),
            "crimea" to (OutageType.NoData to null), "luhansk" to (OutageType.NoData to null),
        )
        when (scenario) {
            Scenario.Emergency -> { base["kyiv-city"] = OutageType.Emergency to 320; base["kyiv"] = OutageType.Emergency to 350 }
            Scenario.Blackout -> { base["kyiv-city"] = OutageType.Blackout to 600; base["kyiv"] = OutageType.Blackout to 620 }
            Scenario.Calm -> base.keys.toList().forEach { k -> if (base[k]!!.first != OutageType.NoData) base[k] = OutageType.None to 20 }
            Scenario.Normal -> Unit
        }
        return base.map { (id, v) -> RegionStatus(id, RegionNames[id] ?: id, v.first, v.second) }
    }

    override fun area(scenario: Scenario): AreaStats {
        val type = when (scenario) {
            Scenario.Normal -> OutageType.Stabilization
            Scenario.Emergency -> OutageType.Emergency
            Scenario.Blackout -> OutageType.Blackout
            Scenario.Calm -> OutageType.None
        }
        val raw = listOf(Triple(3, 0, 0), Triple(4, 1, 0), Triple(2, 0, 0), Triple(3, 2, 0), Triple(4, 0, 1), Triple(2, 3, 0), Triple(1, 4, 0))
        val today = LocalDate.now()
        val labels = (6 downTo 0).map { today.minusDays(it.toLong()).dayOfWeek.ruShort() }
        val days = raw.map { Triple(it.first * 60, it.second * 60, it.third * 40) }
        val avg = days.sumOf { it.first + it.second + it.third } / 7
        val at = LocalDateTime.now()
        return AreaStats("Печерский район", "Киев", type, avg, days, labels, queues.associateWith { queueOffNow(it, at, scenario) != OutageType.None })
    }

    override fun grid(scenario: Scenario): GridState = when (scenario) {
        Scenario.Calm -> GridState(0, 11800, "—", "Энергосистема сбалансирована, отключений нет.", 0, listOf("Можно пользоваться техникой как обычно"))
        Scenario.Blackout -> GridState(4200, 9100, "весь день", "После массированной атаки энергосистема работает в аварийном режиме. Графики не действуют.", 24,
            listOf("Отключите мощные приборы", "Зарядите павербанки, когда дадут свет", "Следите за сообщениями оператора"))
        else -> GridState(if (scenario == Scenario.Emergency) 2600 else 1400, 12600, "17:00 – 22:00",
            "Дефицит мощности в энергосистеме. Применяются графики отключений во всех регионах, кроме части запада.", 19,
            listOf("Не включайте одновременно бойлер и духовку с 17:00 до 22:00", "Стирку и зарядку лучше перенести на день", "Выключайте свет в пустых комнатах"))
    }

    companion object {
        val RegionNames = mapOf(
            "cherkasy" to "Черкасская область", "chernihiv" to "Черниговская область", "chernivtsi" to "Черновицкая область",
            "crimea" to "АР Крым", "dnipropetrovsk" to "Днепропетровская область", "donetsk" to "Донецкая область",
            "ivano-frankivsk" to "Ивано-Франковская область", "kharkiv" to "Харьковская область", "kherson" to "Херсонская область",
            "khmelnytskyi" to "Хмельницкая область", "kirovohrad" to "Кировоградская область", "kyiv" to "Киевская область",
            "kyiv-city" to "Киев", "luhansk" to "Луганская область", "lviv" to "Львовская область", "mykolaiv" to "Николаевская область",
            "odessa" to "Одесская область", "poltava" to "Полтавская область", "rivne" to "Ровенская область", "sumy" to "Сумская область",
            "ternopil" to "Тернопольская область", "vinnytsia" to "Винницкая область", "volyn" to "Волынская область",
            "zakarpattia" to "Закарпатская область", "zaporizhia" to "Запорожская область", "zhytomyr" to "Житомирская область",
        )
    }
}

fun java.time.DayOfWeek.ruShort(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "Пн"; java.time.DayOfWeek.TUESDAY -> "Вт"; java.time.DayOfWeek.WEDNESDAY -> "Ср"
    java.time.DayOfWeek.THURSDAY -> "Чт"; java.time.DayOfWeek.FRIDAY -> "Пт"; java.time.DayOfWeek.SATURDAY -> "Сб"
    java.time.DayOfWeek.SUNDAY -> "Вс"
}

fun java.time.DayOfWeek.ruLong(): String = when (this) {
    java.time.DayOfWeek.MONDAY -> "Понедельник"; java.time.DayOfWeek.TUESDAY -> "Вторник"; java.time.DayOfWeek.WEDNESDAY -> "Среда"
    java.time.DayOfWeek.THURSDAY -> "Четверг"; java.time.DayOfWeek.FRIDAY -> "Пятница"; java.time.DayOfWeek.SATURDAY -> "Суббота"
    java.time.DayOfWeek.SUNDAY -> "Воскресенье"
}

private val Months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
fun LocalDate.ruDayMonth() = "$dayOfMonth ${Months[monthValue - 1]}"

fun formatDuration(minutes: Long): String {
    val h = minutes / 60; val m = minutes % 60
    return when {
        h == 0L -> "$m мин"
        m == 0L -> "$h ч"
        else -> "$h ч $m мин"
    }
}

fun formatShort(minutes: Int?): String {
    if (minutes == null) return "—"
    val h = minutes / 60; val m = minutes % 60
    return if (h == 0) "$m м" else "$h ч %02d м".format(m)
}

fun between(a: LocalDateTime, b: LocalDateTime) = Duration.between(a, b).toMinutes().coerceAtLeast(0)

fun LocalDateTime.hm(): String = "%02d:%02d".format(hour, minute)

/** Загрузка карт из assets. Контуры домов: © участники OpenStreetMap (ODbL). Карта областей: @svg-maps/ukraine (CC BY 4.0). */
object MapAssets {
    @Volatile private var ukraine: UkraineMap? = null
    @Volatile private var city: CityMap? = null

    fun ukraine(ctx: Context): UkraineMap = ukraine ?: synchronized(this) {
        ukraine ?: run {
            val o = JSONObject(ctx.assets.open("ukraine.json").bufferedReader().readText())
            val arr = o.getJSONArray("regions")
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map { Region(it.getString("id"), it.getString("name"), it.getString("d")) }
            UkraineMap(o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), list).also { ukraine = it }
        }
    }

    fun city(ctx: Context, queues: List<String>): CityMap = city ?: synchronized(this) {
        city ?: run {
            val o = JSONObject(ctx.assets.open("kyiv_pechersk.json").bufferedReader().readText())
            val b = o.getJSONArray("buildings")
            val raw = (0 until b.length()).map { b.getJSONObject(it) }
            fun pts(a: org.json.JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            val tmp = raw.map { Building(pts(it.getJSONArray("p")), if (it.isNull("a")) null else it.getString("a"), it.getInt("c") == 1, null) }
            // Тестовое распределение очередей: соседние дома (квартал ~140 м) получают одну очередь.
            fun cellHash(x: Float, y: Float): Int {
                val cx = floor(x / 140f).toInt(); val cy = floor(y / 140f).toInt()
                return abs(cx * 73856093 xor cy * 19349663)
            }
            val home = tmp.firstOrNull { it.address == HOME_ADDRESS }
            val shift = if (home != null) (queues.indexOf("3.1") - cellHash(home.cx, home.cy) % queues.size + queues.size) % queues.size else 0
            val buildings = tmp.map { if (it.critical) it else it.copy(queue = queues[(cellHash(it.cx, it.cy) + shift) % queues.size]) }
            val l = o.getJSONArray("lines")
            val lines = (0 until l.length()).map { l.getJSONObject(it) }.map { MapLine(it.getString("k"), pts(it.getJSONArray("p")), it.optString("n").ifEmpty { null }) }
            val minX = buildings.minOf { it.minX }; val minY = buildings.minOf { it.minY }
            val maxX = buildings.maxOf { it.maxX }; val maxY = buildings.maxOf { it.maxY }
            CityMap(o.getString("city"), o.getString("district"), buildings, lines, minX, minY, maxX, maxY).also { city = it }
        }
    }

    const val HOME_ADDRESS = "вулиця Лєскова, 9"
}
