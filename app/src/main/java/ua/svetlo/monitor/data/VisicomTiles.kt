package ua.svetlo.monitor.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ua.svetlo.monitor.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Подложка карты города — растровые тайлы Visicom Tiles (веб-Меркатор, 256×256, XYZ через origin=nw).
 * https://api.visicom.ua/uk/products/tiles/tiles-references
 *
 * Условия Visicom (оферта п. 5.4–5.5, 5.7): подпись «Картографічні дані © АТ «Візіком»» не прячем, тайлы не меняем
 * (поэтому в тёмной теме подложки нет — там наша карта из OSM), храним только как временный кеш: в памяти
 * и на диске в cacheDir (система может его очистить), не дольше [MaxAgeMs]. Заранее карту не скачиваем.
 * Нет сети или тайла — под ним видна наша карта из OSM, она рисуется первой.
 */
object VisicomTiles {
    const val Attribution = "Картографічні дані © АТ «Візіком»"
    const val Site = "https://api.visicom.ua"
    const val MinZ = 6
    const val MaxZ = 19
    private const val MaxAgeMs = 7L * 24 * 3600 * 1000

    private val key: String get() = BuildConfig.VISICOM_KEY
    val enabled: Boolean get() = key.isNotBlank()

    fun wanted(ctx: Context): Boolean = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).getBoolean("visicom", true)
    fun setWanted(ctx: Context, on: Boolean) = ctx.getSharedPreferences("map", Context.MODE_PRIVATE).edit().putBoolean("visicom", on).apply()

    /** Растёт, когда загрузился новый тайл: карта перерисовывается. */
    val loaded = MutableStateFlow(0)

    private val mem = object : LruCache<Long, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.width * value.height * 4
    }
    private val pending = HashSet<Long>()
    private val failed = HashMap<Long, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(4)

    fun id(z: Int, x: Int, y: Int): Long = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    /** Тайл из памяти, если уже загружен. */
    fun cached(z: Int, x: Int, y: Int): ImageBitmap? = mem.get(id(z, x, y))

    /** Тайл из памяти; если его нет — ставим в загрузку и возвращаем null. */
    fun get(ctx: Context, z: Int, x: Int, y: Int): ImageBitmap? {
        val id = id(z, x, y)
        mem.get(id)?.let { return it }
        val now = System.currentTimeMillis()
        synchronized(pending) {
            if (id in pending || (failed[id] ?: 0L) > now - 60_000) return null
            pending += id
        }
        val dir = File(ctx.cacheDir, "visicom-tiles")
        scope.launch {
            permits.withPermit {
                val bmp = runCatching { load(dir, z, x, y) }.getOrNull()
                synchronized(pending) { pending -= id; if (bmp == null) failed[id] = System.currentTimeMillis() else failed -= id }
                if (bmp != null) { mem.put(id, bmp); loaded.value++ }
            }
        }
        return null
    }

    private fun load(dir: File, z: Int, x: Int, y: Int): ImageBitmap? {
        val f = File(dir, "$z/$x/$y.png")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < MaxAgeMs) {
            BitmapFactory.decodeFile(f.path)?.let { return it.asImageBitmap() }
        }
        val url = "https://tms${(x + y) % 3 + 1}.visicom.ua/2.0.0/planet3/base/$z/$x/$y.png?origin=nw&lang=uk&key=$key"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Svetlyachok-Android")
        val bytes = try {
            if (c.responseCode != 200 || c.contentType?.startsWith("image/") != true) return null
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        runCatching { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        return bmp.asImageBitmap()
    }

    /** Удаляем с диска тайлы старше недели (вызываем при открытии карты). */
    fun trim(ctx: Context) = scope.launch {
        val old = System.currentTimeMillis() - MaxAgeMs
        File(ctx.cacheDir, "visicom-tiles").walkBottomUp().forEach { if (it.isFile && it.lastModified() < old) it.delete() }
    }

    // Веб-Меркатор: номер тайла ↔ долгота/широта (левый верхний угол тайла).
    fun lon(x: Int, z: Int): Double = x / (1 shl z).toDouble() * 360.0 - 180.0
    fun lat(y: Int, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))
    fun tileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }

    /** Уровень тайлов: чтобы пиксель тайла был примерно dp экрана (подписи читаемы). [pxPerMeter] — масштаб карты. */
    fun zoomFor(pxPerMeter: Float, density: Float, lat: Double): Int {
        val v = 156543.034 * kotlin.math.cos(Math.toRadians(lat)) * pxPerMeter / density
        return (ln(v) / ln(2.0) + 0.5).toInt().coerceIn(MinZ, MaxZ)
    }
}
