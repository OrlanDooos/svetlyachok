package ua.svetlo.monitor

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.svetlo.monitor.data.CityMap
import ua.svetlo.monitor.data.VisicomTiles

class VisicomTilesTest {
    @Test fun tileNumbersMatchWebMercator() {
        // Запорожье, майдан Героїв: тайл 16/39164/22825 (проверено на тайлах Visicom).
        assertEquals(39164, VisicomTiles.tileX(35.1396, 16))
        assertEquals(22825, VisicomTiles.tileY(47.8388, 16))
        val x = 39164; val y = 22825
        assertEquals(x, VisicomTiles.tileX(VisicomTiles.lon(x, 16) + 1e-9, 16))
        assertEquals(y, VisicomTiles.tileY(VisicomTiles.lat(y, 16) - 1e-9, 16))
    }

    @Test fun zoomKeepsTilePixelNearDp() {
        // 1 м = 2.75 px при плотности 2.75 → 1 м на dp → уровень 17 (≈0.8 м на пиксель тайла на 47.8°).
        assertEquals(17, VisicomTiles.zoomFor(2.75f, 2.75f, 47.8))
        assertEquals(VisicomTiles.MinZ, VisicomTiles.zoomFor(0.0001f, 2.75f, 47.8))
        assertEquals(VisicomTiles.MaxZ, VisicomTiles.zoomFor(1000f, 1f, 47.8))
    }

    @Test fun lonLatRoundTrip() {
        val c = CityMap("zp", "r", "", "", "", "", "", emptyList(), emptyList(), 0f, 0f, 0f, 0f, 47.85, 35.15)
        val (x, y) = c.fromLonLat(35.2, 47.8)
        val (lon, lat) = c.toLonLat(x, y)
        assertEquals(35.2, lon, 1e-6); assertEquals(47.8, lat, 1e-6)
    }
}
