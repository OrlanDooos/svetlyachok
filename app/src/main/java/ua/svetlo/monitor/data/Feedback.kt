package ua.svetlo.monitor.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ua.svetlo.monitor.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Написати відгук»: текст уходит на наш сервер (BuildConfig.PINS_URL + /feedback), тот пересылает его автору
 * в Telegram. Кроме текста и необязательного контакта отправляем версию приложения, область выбранного адреса,
 * модель телефона и версию Android — чтобы понять, о чём речь. Тот же обезличенный id установки, что и у отметок.
 */
object Feedback {
    val enabled: Boolean get() = BuildConfig.PINS_URL.isNotBlank()
    const val MAX = 2000

    enum class Result { Ok, TooMany, Failed }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}".take(60)

    fun body(text: String, contact: String, region: String, install: String, device: String): String =
        JSONObject().put("text", text.trim().take(MAX)).put("contact", contact.trim().take(100))
            .put("app", BuildConfig.VERSION_NAME).put("region", region).put("device", device)
            .put("install", install).toString()

    suspend fun send(ctx: Context, text: String, contact: String, region: String): Result {
        if (!enabled) return Result.Failed
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(Pins.endpoint("feedback")).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("User-Agent", "Svetlyachok-Android")
                c.outputStream.use { it.write(body(text, contact, region, Pins.installId(ctx), device()).toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                when (code) { in 200..299 -> Result.Ok; 429 -> Result.TooMany; else -> Result.Failed }
            }.getOrDefault(Result.Failed)
        }
    }
}
