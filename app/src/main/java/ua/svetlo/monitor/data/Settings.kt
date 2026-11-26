package ua.svetlo.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.svetlo.monitor.ui.theme.ThemeMode

data class Settings(
    val onboarded: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val largeText: Boolean = false,
    val scenario: Scenario = Scenario.Normal,
    val remindMinutes: Int = 15,
    val notifyChanges: Boolean = true,
    val notifyPowerBack: Boolean = true,
    val notifyEmergency: Boolean = true,
    val quietNight: Boolean = false,
    val addresses: List<Address> = DefaultAddresses,
    val selectedId: String = DefaultAddresses.first().id,
) {
    val selected: Address get() = addresses.firstOrNull { it.id == selectedId } ?: addresses.first()
}

val DefaultAddresses = listOf(
    Address("home", "Дом", MapAssets.HOME_ADDRESS, "Киев", "kyiv-city", "3.1"),
    Address("mom", "Мама", "вулиця Городоцька, 12", "Львов", "lviv", "2.2"),
    Address("dacha", "Дача", "вулиця Київська, 5", "Бровары", "kyiv", "4.1"),
)

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val large = booleanPreferencesKey("large")
        val scenario = stringPreferencesKey("scenario")
        val remind = intPreferencesKey("remind")
        val nChanges = booleanPreferencesKey("n_changes")
        val nBack = booleanPreferencesKey("n_back")
        val nEmergency = booleanPreferencesKey("n_emergency")
        val quiet = booleanPreferencesKey("quiet")
        val addresses = stringPreferencesKey("addresses")
        val selected = stringPreferencesKey("selected")
    }

    val flow: Flow<Settings> = ctx.store.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        val addrs = this[K.addresses]?.let(::decode)?.takeIf { it.isNotEmpty() } ?: d.addresses
        return Settings(
            onboarded = this[K.onboarded] ?: d.onboarded,
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            largeText = this[K.large] ?: d.largeText,
            scenario = this[K.scenario]?.let { runCatching { Scenario.valueOf(it) }.getOrNull() } ?: d.scenario,
            remindMinutes = this[K.remind] ?: d.remindMinutes,
            notifyChanges = this[K.nChanges] ?: d.notifyChanges,
            notifyPowerBack = this[K.nBack] ?: d.notifyPowerBack,
            notifyEmergency = this[K.nEmergency] ?: d.notifyEmergency,
            quietNight = this[K.quiet] ?: d.quietNight,
            addresses = addrs,
            selectedId = this[K.selected]?.takeIf { id -> addrs.any { it.id == id } } ?: addrs.first().id,
        )
    }

    suspend fun update(f: (Settings) -> Settings) {
        ctx.store.edit { p ->
            val s = f(p.toSettings())
            p[K.onboarded] = s.onboarded
            p[K.theme] = s.theme.name
            p[K.large] = s.largeText
            p[K.scenario] = s.scenario.name
            p[K.remind] = s.remindMinutes
            p[K.nChanges] = s.notifyChanges
            p[K.nBack] = s.notifyPowerBack
            p[K.nEmergency] = s.notifyEmergency
            p[K.quiet] = s.quietNight
            p[K.addresses] = encode(s.addresses)
            p[K.selected] = s.selectedId
        }
    }

    private fun encode(list: List<Address>) = list.joinToString("\u001e") {
        listOf(it.id, it.label, it.street, it.city, it.region, it.queue).joinToString("\u001f")
    }

    private fun decode(s: String) = s.split("\u001e").mapNotNull { row ->
        val f = row.split("\u001f")
        if (f.size == 6) Address(f[0], f[1], f[2], f[3], f[4], f[5]) else null
    }
}
