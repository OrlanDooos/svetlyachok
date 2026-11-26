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
import ua.svetlo.monitor.data.MockRepository
import ua.svetlo.monitor.data.Scenario
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
@Config(sdk = [34])
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

    private fun shot(name: String, route: String, s: Settings = Settings(onboarded = true), fontScale: Float? = null) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        if (fontScale != null) RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { SvetloContent(MockRepository(), s, now, {}, route) }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${device}_$name.png")
    }

    @Test fun home() = shot("home", Routes.Home)
    @Test fun homeEmergencyDark() = shot("home_emergency_dark", Routes.Home, Settings(onboarded = true, scenario = Scenario.Emergency, theme = ThemeMode.Dark))
    @Test fun homeLargeText() = shot("home_large", Routes.Home, Settings(onboarded = true, largeText = true))
    @Test fun homeSystemFont() = shot("home_font130", Routes.Home, fontScale = 1.3f)
    @Test fun map() = shot("map", Routes.Map)
    @Test fun city() = shot("city", Routes.city())
    @Test fun cityQueue() = shot("city_queue", Routes.city("3.1"))
    @Test fun schedule() = shot("schedule", Routes.Schedule)
    @Test fun area() = shot("area", Routes.Area)
    @Test fun more() = shot("more", Routes.More)
    @Test fun onboarding() = shot("onboarding", Routes.Onboarding, Settings())
    @Test fun grid() = shot("grid", Routes.Grid)
    @Test fun addresses() = shot("addresses", Routes.Addresses)
    @Test fun premium() = shot("premium", Routes.Premium)
    @Test fun cityZaporizhzhia() = shot("city_zp", Routes.city(id = "zaporizhzhia"))
    @Test fun homeRussian() = shot("home_ru", Routes.Home, Settings(onboarded = true, lang = Lang.Ru))
    @Test fun moreRussian() = shot("more_ru", Routes.More, Settings(onboarded = true, lang = Lang.Ru))
}
