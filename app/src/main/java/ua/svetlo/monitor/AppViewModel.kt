package ua.svetlo.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import ua.svetlo.monitor.data.DataSource
import ua.svetlo.monitor.data.LiveRepository
import ua.svetlo.monitor.data.ScheduleStore
import ua.svetlo.monitor.notify.Notifications
import ua.svetlo.monitor.notify.Reminders
import ua.svetlo.monitor.notify.RefreshWorker
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.SettingsStore
import java.time.LocalDateTime

open class SvetloApp : Application() {
    val schedules: ScheduleStore by lazy { ScheduleStore(this) }
    val repository: OutageRepository by lazy { LiveRepository(schedules) }
    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        RefreshWorker.schedule(this)
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val svetlo = app as SvetloApp
    val repo: OutageRepository = svetlo.repository
    private val store = svetlo.settings

    /** null — настройки ещё читаются с диска (держим заставку). */
    val settings: StateFlow<Settings?> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    // Объявлено до init: init сразу вызывает refresh().
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = LocalDateTime.now(DataSource.Kyiv)
                delay(15_000)
            }
        }
        // Пока приложение открыто, обновляем графики раз в 5 минут.
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5 * 60_000)
            }
        }
    }

    private var lastRefresh = 0L

    /** Проверка новых графиков. Не чаще раза в минуту: сервер всё равно обновляется раз в 10 минут. */
    fun refresh() {
        if (_refreshing.value) return
        val t = System.currentTimeMillis()
        if (t - lastRefresh < 60_000) return
        lastRefresh = t
        viewModelScope.launch {
            _refreshing.value = true
            val s = store.flow.first()
            svetlo.schedules.refresh(s.addresses.map { it.region }.toSet())
            Reminders.reschedule(getApplication(), repo, s)
            _now.value = LocalDateTime.now(DataSource.Kyiv)
            _refreshing.value = false
        }
    }

    fun update(f: (Settings) -> Settings) {
        viewModelScope.launch {
            val before = store.flow.first()
            store.update(f)
            val after = store.flow.first()
            // Новая область — сразу скачиваем её график; изменились адреса или напоминания — перепланируем.
            if (after.addresses.map { it.region }.toSet() != before.addresses.map { it.region }.toSet()) refresh()
            else if (after.addresses != before.addresses || after.remindMinutes != before.remindMinutes || after.notifyPowerBack != before.notifyPowerBack || after.quietNight != before.quietNight)
                Reminders.reschedule(getApplication(), repo, after)
        }
    }
}
