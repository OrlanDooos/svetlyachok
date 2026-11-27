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
}
