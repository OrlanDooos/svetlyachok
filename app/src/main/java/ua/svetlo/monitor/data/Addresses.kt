package ua.svetlo.monitor.data

import org.json.JSONObject

/**
 * Адрес → подочередь по официальным спискам облэнерго (файл v1/addr/{region}.json от сборщика).
 * Нормализация улиц и домов повторяет collector/addresses.py — менять их нужно вместе.
 * Ничего не угадываем: дом без точного совпадения в списках считается «очередь неизвестна».
 */
class AddressIndex(val title: String, val source: String, private val streets: Map<String, Map<String, String>>, private val aliases: Map<String, String>) {

    /** Подочереди дома («6.1» или «1.2,5.1», если подъезды в разных) или null, если дома нет в списках. */
    fun queuesFor(address: String?): List<String>? {
        if (address.isNullOrBlank()) return null
        val cut = address.lastIndexOf(',')
        if (cut <= 0) return null
        val street = address.substring(0, cut); val house = normHouse(address.substring(cut + 1))
        if (house.isEmpty() || !house[0].isDigit()) return null
        for (k in streetKeys(street)) {
            val houses = streets[aliases[k] ?: k] ?: continue
            // Только точное совпадение: «17а» и «17» — разные дома, «142/1» и «142» — тоже.
            houses[house]?.let { return it.split(',') }
        }
        return null
    }

    companion object {
        private val TypeWords = setOf(
            "вул", "вулиця", "вулица", "ул", "улица", "бул", "бульв", "бульвар", "просп", "пр", "пр-т", "проспект",
            "пров", "провулок", "переулок", "пер", "пл", "площа", "площадь", "шосе", "ш", "узвіз", "узв", "тупик",
            "проїзд", "проїз", "набережна", "наб", "алея", "сел", "селище", "м-н", "мікрорайон", "мкр", "дорога", "в'їзд", "вʼїзд",
        )
        private const val LatFrom = "aceiopxykmhtbABCEHIKMOPTXY"
        private const val LatTo = "асеіорхукмнтвАВСЕНІКМОРТХУ"
        private fun lat(s: String) = buildString(s.length) { s.forEach { ch -> val i = LatFrom.indexOf(ch); append(if (i >= 0) LatTo[i] else ch) } }

        fun streetKeys(name: String): List<String> {
            val n = name.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace("«", "").replace("»", "").replace("\"", "")
            val parts = listOf(n.substringBefore('(')) + Regex("\\(([^)]*)\\)").findAll(n).map { it.groupValues[1] }
            return parts.mapNotNull { p ->
                lat(p.lowercase()).split(Regex("[\\s.,]+")).filter { it.isNotEmpty() && it !in TypeWords }
                    .joinToString(" ").replace("'", "").takeIf { it.isNotEmpty() }
            }
        }

        fun normHouse(h: String): String {
            var s = lat(h.lowercase())
            s = s.replace(Regex("\\(.*?\\)"), " ")
            s = s.replace(Regex("\\bп\\.?\\s*\\d.*$"), " ")
            s = s.replace(Regex("корп(ус)?\\.?\\s*"), "к")
            s = s.replace(Regex("\\s+"), "").replace('–', '-').replace('—', '-')
            return s.trim('.', ',', ';')
        }

        fun parse(json: String): AddressIndex {
            val o = JSONObject(json)
            val st = o.getJSONObject("streets")
            val streets = HashMap<String, Map<String, String>>(st.length() * 2)
            st.keys().forEach { k ->
                val q = st.getJSONObject(k).getJSONObject("q")
                val houses = HashMap<String, String>()
                q.keys().forEach { qs -> q.getString(qs).split('|').forEach { h -> if (h.isNotEmpty()) houses[h] = qs } }
                streets[k] = houses
            }
            val al = o.optJSONObject("aliases")
            val aliases = HashMap<String, String>()
            al?.keys()?.forEach { aliases[it] = al.getString(it) }
            return AddressIndex(o.optString("title"), o.optString("source"), streets, aliases)
        }
    }
}
