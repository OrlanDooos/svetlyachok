package ua.svetlo.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.svetlo.monitor.data.AddressIndex
import ua.svetlo.monitor.data.Building
import ua.svetlo.monitor.data.CityMap
import ua.svetlo.monitor.data.Visicom

/** Разбор ответов Visicom (org.json нужен настоящий, поэтому Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VisicomTest {
    private fun feature(name: String, street: String, type: String = "вул.", geom: String = "Polygon") =
        """{"type":"Feature","id":"ADR1","geometry":{"type":"$geom","coordinates":[]},"properties":{"name":"$name","categories":"adr_address","street":"$street","street_type":"$type","settlement":"Запоріжжя"}}"""

    @Test fun oneFeature() {
        assertEquals(listOf("вул. Літературна, 75"), Visicom.addresses(feature("75", "Літературна"), polygonsOnly = true))
    }

    @Test fun collectionDistinctAndAmbiguous() {
        val json = """{"type":"FeatureCollection","features":[${feature("14", "Дальня")},${feature("14", "Дальня")},${feature("16", "Дальня")}]}"""
        assertEquals(listOf("вул. Дальня, 14", "вул. Дальня, 16"), Visicom.addresses(json, polygonsOnly = false))
    }

    @Test fun pointsSkippedWhenPolygonsOnly() {
        assertTrue(Visicom.addresses(feature("3", "Цегельна", geom = "Point"), polygonsOnly = true).isEmpty())
        assertEquals(1, Visicom.addresses(feature("3", "Цегельна", geom = "Point"), polygonsOnly = false).size)
    }

    @Test fun emptyOrGarbage() {
        assertTrue(Visicom.addresses("{}", true).isEmpty())
        assertTrue(Visicom.addresses("not json", true).isEmpty())
        assertTrue(Visicom.addresses(feature("", "Дальня"), true).isEmpty())
    }

    @Test fun matchesZoeList() {
        val idx = AddressIndex("ZOE", "", mapOf("літературна" to mapOf("75" to "3.1")), emptyMap())
        assertEquals(listOf("3.1"), idx.queuesFor(Visicom.addresses(feature("75", "Літературна"), true).single()))
    }

    @Test fun wktClosedAndCapped() {
        val ring = (0 until 600).map { it * 0.001 to 47.8 }
        val w = Visicom.wkt(ring)
        assertTrue(w.startsWith("POLYGON(("))
        val pts = w.removePrefix("POLYGON((").removeSuffix("))").split(",")
        assertTrue(pts.size <= 250)
        assertEquals(pts.first(), pts.last())
    }

    @Test fun lonLatRoundTrip() {
        // Как в gen_city_bin.py: x = (lon - lon0) * 111320 * cos(lat0), y = (lat0 - lat) * 110540.
        val lat0 = 47.85; val lon0 = 35.15
        val kx = 111320.0 * kotlin.math.cos(Math.toRadians(lat0))
        val x = ((35.2 - lon0) * kx).toFloat(); val y = ((lat0 - 47.81) * 110540.0).toFloat()
        val b = Building(floatArrayOf(x - 5, y - 5, x + 5, y - 5, x + 5, y + 5, x - 5, y + 5), null, false, null)
        val city = CityMap("zp", "zaporizhia", "", "", "", "", "", listOf(b), emptyList(), 0f, 0f, 1f, 1f, lat0, lon0)
        val (lon, lat) = city.insideLonLat(b)
        assertEquals(35.2, lon, 1e-5); assertEquals(47.81, lat, 1e-5)
    }

    @Test fun streetOfForFixing() {
        val idx = AddressIndex("ZOE", "", mapOf("дальня" to mapOf("14" to "3.1", "14а" to "3.2")), mapOf("стара" to "дальня"), listOf("дальня" to "вул. Дальня"))
        assertEquals("дальня" to "вул. Дальня", idx.streetOf("вулиця Дальня, 15"))
        assertEquals("дальня" to "вул. Дальня", idx.streetOf("вул. Стара, 1"))
        assertEquals(null, idx.streetOf("вул. Невідома, 1"))
    }

    @Test fun searchWholeList() {
        val idx = AddressIndex("ZOE", "", mapOf("дальня" to mapOf("14" to "3.1", "14а" to "3.2", "141" to "1.1", "2" to "2.2")), emptyMap(), listOf("дальня" to "вул. Дальня"))
        assertEquals(listOf("вул. Дальня, 14", "вул. Дальня, 14а", "вул. Дальня, 141"), idx.search("дальня 14"))
        assertEquals(listOf("вул. Дальня, 14"), idx.search("вулиця Дальня, 14", limit = 1))
        assertTrue(idx.search("14").isEmpty())
        assertTrue(idx.same("вулиця Дальня, 14А", "вул. Дальня, 14а"))
    }

    @Test fun pinBody() {
        val b = org.json.JSONObject(ua.svetlo.monitor.data.Pins.body("zaporizhia", "вул. Дальня, 14", 35.2034281, 47.8131623, "id"))
        assertEquals(35.203428, b.getDouble("lon"), 1e-9); assertEquals("вул. Дальня, 14", b.getString("address"))
    }

    private fun near(name: String, d: Double) =
        """{"type":"Feature","properties":{"name":"$name","categories":"adr_address","street":"Хлібна","street_type":"вул.","dist_meters":$d}}"""

    @Test fun nearestOnlyWhenClear() {
        fun col(vararg f: String) = """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}"""
        assertEquals("вул. Хлібна, 49", Visicom.nearest(col(near("49", 2.0), near("47", 12.0))))
        assertEquals(null, Visicom.nearest(col(near("49", 5.0), near("47", 8.0))))   // почти одинаково близко
        assertEquals(null, Visicom.nearest(col(near("49", 15.0))))                    // слишком далеко
        assertEquals("вул. Хлібна, 47", Visicom.nearest(near("47", 3.0)))
        assertEquals(null, Visicom.nearest("{}"))
    }

    @Test fun searchStreetWithNumber() {
        val idx = AddressIndex("ZOE", "", mapOf("хлібна" to mapOf("47" to "6.2", "47а" to "6.2", "49" to "6.2")), emptyMap(), listOf("хлібна" to "вул. Хлібна"))
        assertEquals("вул. Хлібна, 47", idx.search("хлібна 47").first())
        assertEquals("вул. Хлібна, 47", idx.search("Вул. Хлібна, 47").first())
    }
}
