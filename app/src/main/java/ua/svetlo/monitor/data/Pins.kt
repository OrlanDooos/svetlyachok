package ua.svetlo.monitor.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.svetlo.monitor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * «Указать мой дом на карте»: житель отмечает здание для адреса из списка облэнерго.
 * Отметка сразу действует у него (Settings.houseAddr) и уходит на сервер (BuildConfig.PINS_URL).
 * Сборщик берёт дом в общую карту, когда его отметили одинаково 2+ разных устройства.
 * Отправляем только адрес, точку и обезличенный случайный id установки — ничего о человеке.
 */
object Pins {
    val enabled: Boolean get() = BuildConfig.PINS_URL.isNotBlank()

    private fun installId(ctx: Context): String {
        val p = ctx.getSharedPreferences("pins", Context.MODE_PRIVATE)
        return p.getString("id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
    }

    fun body(region: String, address: String, lon: Double, lat: Double, install: String): String =
        JSONObject().put("region", region).put("address", address)
            .put("lon", Math.round(lon * 1e6) / 1e6).put("lat", Math.round(lat * 1e6) / 1e6)
            .put("install", install).put("app", BuildConfig.VERSION_NAME).toString()

    /** true — сервер принял. Ошибки сети не мешают: локально дом уже отмечен. */
    suspend fun submit(ctx: Context, region: String, address: String, lon: Double, lat: Double): Boolean {
        if (!enabled) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(BuildConfig.PINS_URL).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("User-Agent", "Svetlyachok-Android")
                c.outputStream.use { it.write(body(region, address, lon, lat, installId(ctx)).toByteArray()) }
                val ok = c.responseCode in 200..299
                c.disconnect(); ok
            }.getOrDefault(false)
        }
    }
}
