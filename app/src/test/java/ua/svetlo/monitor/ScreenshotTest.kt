package ua.svetlo.monitor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.svetlo.monitor.data.Lang
import ua.svetlo.monitor.data.Address
import ua.svetlo.monitor.data.LiveRepository
import ua.svetlo.monitor.data.ScheduleStore
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.ui.Routes
import ua.svetlo.monitor.ui.SvetloContent
import ua.svetlo.monitor.ui.theme.ThemeMode
import java.time.LocalDateTime

/**
 * Скриншоты всех экранов на разных размерах. Запуск: gradle testDebugUnitTest.
 * Картинки сохраняются в app/build/outputs/roborazzi.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class ScreenshotTest(private val device: String, private val qualifiers: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices() = listOf(
            arrayOf("phone", "w390dp-h844dp-port-xhdpi"),
            arrayOf("small", "w340dp-h700dp-port-xhdpi"),
            arrayOf("fold", "w673dp-h841dp-port-xhdpi"),
            arrayOf("tablet", "w1280dp-h800dp-land-mdpi"),
            arrayOf("landscape", "w844dp-h390dp-land-xhdpi"),
        )
    }

    @get:Rule val compose = createComposeRule()

    private val now = LocalDateTime.of(2026, 11, 26, 14, 36)

    private val addrs = listOf(
        Address("home", "Дім", "вулиця Лєскова, 9", "Київ", "kyiv-city", "3.1"),
        Address("parents", "Батьки", "Соборний проспект, 151", "Запоріжжя", "zaporizhia", "4.2"),
        Address("work", "Робота", "", "Дніпро", "dnipropetrovsk", "2.1"),
    )
    private val on = Settings(onboarded = true, addresses = addrs, selectedId = "home")

    private val repo by lazy {
        val dir = kotlin.io.path.createTempDirectory("svetlo").toFile()
        val store = ScheduleStore(dir)
        listOf("regions", "kyiv-city", "kyiv", "dnipropetrovsk", "odessa", "lviv", "zaporizhia").forEach { n ->
            store.put("$n.json", javaClass.getResource("/fixtures/$n.json")!!.readText())
        }
        store.put("addr/zaporizhia.json", javaClass.getResource("/fixtures/addr/zaporizhia.json")!!.readText())
        LiveRepository(store)
    }

    private fun shot(name: String, route: String, s: Settings = on, fontScale: Float? = null, waitNetMs: Long = 0) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        if (fontScale != null) RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { SvetloContent(repo, s, now, {}, route) }
        compose.mainClock.advanceTimeBy(1500)
        // Тайлы Visicom грузятся по сети в фоне: ждём реальное время (только если в сборке есть ключ).
        var waited = 0L
        while (waited < waitNetMs) { Thread.sleep(500); compose.mainClock.advanceTimeBy(500); waited += 500 }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    @Test fun home() = shot("home", Routes.Home)
    @Test fun homeEmergencyDark() = shot("home_emergency_dark", Routes.Home, on.copy(selectedId = "work", theme = ThemeMode.Dark))
    @Test fun homeZpDark() = shot("home_zp_dark", Routes.Home, on.copy(selectedId = "parents", theme = ThemeMode.Dark))
    @Test fun homeLargeText() = shot("home_large", Routes.Home, on.copy(largeText = true))
    @Test fun homeSystemFont() = shot("home_font130", Routes.Home, fontScale = 1.3f)
    @Test fun map() = shot("map", Routes.Map)
    @Test fun city() = shot("city", Routes.city())
    @Test fun schedule() = shot("schedule", Routes.Schedule)
    @Test fun detail() = shot("detail", "detail")
    @Test fun detailZpDark() = shot("detail_zp_dark", "detail", on.copy(selectedId = "parents", theme = ThemeMode.Dark))
    @Test fun detailEmergency() = shot("detail_emergency", "detail", on.copy(selectedId = "work"))
    @Test fun area() = shot("area", Routes.Area)
    @Test fun windows() = shot("windows", Routes.Windows)
    @Test fun more() = shot("more", Routes.More)
    @Test fun moreDark() = shot("more_dark", Routes.More, on.copy(theme = ThemeMode.Dark))
    @Test fun onboarding() = shot("onboarding", Routes.Onboarding, Settings())
    @Test fun addresses() = shot("addresses", Routes.Addresses)
    @Test fun sources() = shot("sources", Routes.Sources)
    @Test fun notif() = shot("notif", Routes.Notif)
    @Test fun cityZaporizhzhia() = shot("city_zp", Routes.city(id = "zaporizhzhia"), on.copy(selectedId = "parents"))
    @Test fun cityZaporizhzhiaVisicom() = shot("city_zp_visicom", Routes.city(id = "zaporizhzhia"), on.copy(selectedId = "parents"),
        waitNetMs = if (ua.svetlo.monitor.data.VisicomTiles.enabled) 8000 else 0)
    @Test fun cityZaporizhzhiaDark() = shot("city_zp_dark", Routes.city(id = "zaporizhzhia"), on.copy(selectedId = "parents", theme = ThemeMode.Dark))
    @Test fun homeRussian() = shot("home_ru", Routes.Home, on.copy(lang = Lang.Ru))
}
