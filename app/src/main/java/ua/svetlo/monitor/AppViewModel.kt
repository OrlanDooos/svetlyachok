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
import ua.svetlo.monitor.data.MockRepository
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.SettingsStore
import java.time.LocalDateTime

class SvetloApp : Application() {
    val repository: OutageRepository by lazy { MockRepository() }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val svetlo = app as SvetloApp
    val repo: OutageRepository = svetlo.repository
    private val store = svetlo.settings

    /** null — настройки ещё читаются с диска (держим заставку). */
    val settings: StateFlow<Settings?> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = LocalDateTime.now()
                delay(15_000)
            }
        }
    }

    fun update(f: (Settings) -> Settings) {
        viewModelScope.launch { store.update(f) }
    }
}
