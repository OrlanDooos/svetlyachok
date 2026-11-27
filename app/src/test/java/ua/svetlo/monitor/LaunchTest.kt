package ua.svetlo.monitor

import androidx.test.core.app.ActivityScenario
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.os.Looper

/** Настоящее приложение (с WorkManager, уведомлениями и ViewModel) должно запускаться без падений. */
class TestApp : SvetloApp() {
    override fun onCreate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this, Configuration.Builder().build())
        super.onCreate()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class)
class LaunchTest {
    /** Старт приложения без экрана: Application.onCreate, ViewModel, первое обновление и напоминания. */
    @Test fun launches() {
        var crash: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crash = e }
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<SvetloApp>()
        val vm = AppViewModel(app)
        val looper = shadowOf(Looper.getMainLooper())
        repeat(10) { looper.idle(); Thread.sleep(300) }
        vm.update { it }
        repeat(5) { looper.idle(); Thread.sleep(200) }
        crash?.let { throw AssertionError("Приложение упало при запуске", it) }
    }

    /** Адреса из списков облэнерго: точное совпадение, старые названия улиц, выбор дома из списка. */
    @Test fun addressIndex() {
        val idx = ua.svetlo.monitor.data.AddressIndex.parse(javaClass.getResource("/fixtures/addr/zaporizhia.json")!!.readText())
        org.junit.Assert.assertEquals(listOf("6.1"), idx.queuesFor("вулиця Абрагама Коопа, 108"))
        org.junit.Assert.assertEquals(listOf("6.1"), idx.queuesFor("вулиця Істоміна, 108"))
        org.junit.Assert.assertEquals(listOf("1.1"), idx.queuesFor("Соборний проспект, 165"))
        org.junit.Assert.assertNull(idx.queuesFor("Соборний проспект, 151"))
        org.junit.Assert.assertNull(idx.queuesFor("вулиця Абрагама Коопа, 108в"))
        val (key, name) = idx.streetNames.first { it.second.contains("Абрагама Коопа") }
        val house = idx.houses(key).first()
        org.junit.Assert.assertNotNull(idx.queuesFor("$name, $house"))
    }
}
