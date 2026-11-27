package ua.svetlo.monitor.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.svetlo.monitor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Адрес дома без адреса в OpenStreetMap — по запросу к Visicom Data API, только когда человек нажал на дом.
 *
 * Условия Visicom (публичная оферта, п. 5.7) запрещают хранить их данные, кроме временного кеширования,
 * поэтому адреса не сохраняем ни в карту, ни на диск: только небольшой кеш в памяти на время работы приложения.
 * Адрес берём, только если ответ однозначный: ровно один адрес под точкой внутри дома,
 * ровно один адрес, пересекающий контур дома, или явно ближайшая адресная точка в пределах 12 м.
 * Иначе считаем адрес неизвестным.
 */
object Visicom {
    private val key: String get() = BuildConfig.VISICOM_KEY
    val enabled: Boolean get() = key.isNotBlank()
    const val Attribution = "Адреса: © АТ «Візіком»"
    const val Site = "https://api.visicom.ua"

    private const val None = "\u0000"
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 300
    }

    /**
     * Адрес дома: [ring] — контур (долгота, широта), [inside] — точка внутри контура.
     * null — Visicom не знает адреса, ответ неоднозначный или нет сети.
     */
    suspend fun addressOf(ring: List<Pair<Double, Double>>, inside: Pair<Double, Double>): String? {
        if (!enabled || ring.size < 3) return null
        val ck = "%.6f,%.6f".format(java.util.Locale.ROOT, inside.first, inside.second)
        synchronized(cache) { cache[ck] }?.let { return it.takeIf { v -> v != None } }
        val found: Result<String?> = withContext(Dispatchers.IO) {
            runCatching {
                // 1) Адрес, чья геометрия (контур дома у Visicom) содержит точку внутри нашего дома.
                val byPoint = addresses(request("intersect", ck), polygonsOnly = true)
                if (byPoint.size == 1) return@runCatching byPoint.first()
                if (byPoint.size > 1) return@runCatching null
                // 2) Адреса, чья геометрия пересекает контур нашего дома (контуры OSM и Visicom чуть сдвинуты).
                val byShape = addresses(request("intersect", wkt(ring)), polygonsOnly = false)
                if (byShape.size == 1) return@runCatching byShape.first()
                // 3) Ближайшая адресная точка: только если она рядом (≤ 12 м) и явно ближе остальных.
                nearest(request("near", ck, "&r=25"))
            }
        }
        // Сетевые ошибки не кешируем: в следующий раз попробуем снова.
        val value = found.getOrNull()
        if (found.isSuccess) synchronized(cache) { cache[ck] = value ?: None }
        return value
    }

    private fun request(param: String, geometry: String, extra: String = ""): String {
        val url = "https://api.visicom.ua/data-api/5.0/uk/geocode.json?categories=adr_address&$param=" +
            URLEncoder.encode(geometry, "UTF-8") + extra + "&limit=5&key=" + URLEncoder.encode(key, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Svetlyachok-Android")
        try {
            if (c.responseCode == 404) return "{}"  // «ничего не найдено»
            check(c.responseCode == 200) { "visicom ${c.responseCode}" }
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** WKT-полигон не больше 250 вершин (ограничение API). */
    fun wkt(ring: List<Pair<Double, Double>>): String {
        val step = (ring.size + 248) / 249
        val pts = ring.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (pts.first() != pts.last()) pts += pts.first()
        return pts.joinToString(",", "POLYGON((", "))") { (x, y) -> "%.7f %.7f".format(java.util.Locale.ROOT, x, y) }
    }

    /** Ближайший адрес из ответа near: ≤ 12 м и следующий другой адрес хотя бы вдвое дальше. Иначе null. */
    fun nearest(json: String): String? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val fs = when (o.optString("type")) {
            "Feature" -> listOf(o)
            "FeatureCollection" -> o.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            else -> emptyList()
        }
        val list = fs.mapNotNull { f ->
            val a = addresses(f.toString(), polygonsOnly = false).singleOrNull() ?: return@mapNotNull null
            val d = f.optJSONObject("properties")?.optDouble("dist_meters", Double.NaN) ?: Double.NaN
            if (d.isNaN()) null else a to d
        }.sortedBy { it.second }
        val first = list.firstOrNull() ?: return null
        val second = list.firstOrNull { it.first != first.first }
        if (first.second > 12.0) return null
        if (second != null && second.second < maxOf(2 * first.second, first.second + 6.0)) return null
        return first.first
    }

    /**
     * Разные адреса из ответа /geocode («вул. Літературна, 75»).
     * Ответ — один Feature или FeatureCollection. [polygonsOnly]: учитывать только адреса с контуром.
     */
    fun addresses(json: String, polygonsOnly: Boolean): List<String> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val features = when (o.optString("type")) {
            "Feature" -> listOf(o)
            "FeatureCollection" -> o.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            else -> emptyList()
        }
        return features.mapNotNull { f ->
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            if (!p.optString("categories").contains("adr_address")) return@mapNotNull null
            val gt = f.optJSONObject("geometry")?.optString("type").orEmpty()
            if (polygonsOnly && gt.isNotEmpty() && !gt.contains("Polygon")) return@mapNotNull null
            val name = p.optString("name").trim(); val street = p.optString("street").trim()
            if (name.isEmpty() || street.isEmpty() || !name[0].isDigit()) return@mapNotNull null
            val type = p.optString("street_type").trim()
            (if (type.isEmpty()) street else "$type $street") + ", " + name
        }.distinct()
    }
}
