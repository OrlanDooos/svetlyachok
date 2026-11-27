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
    val lang: Lang = Lang.Uk,
    val remindMinutes: Int = 15,
    val notifyChanges: Boolean = true,
    val notifyPowerBack: Boolean = true,
    val notifyEmergency: Boolean = true,
    val quietNight: Boolean = false,
    val addresses: List<Address> = DefaultAddresses,
    val selectedId: String = DefaultAddresses.first().id,
    /** Адреса домов, которые житель указал сам (дом без адреса на карте): ключ дома → «вул. X, 12». */
    val houseAddr: Map<String, String> = emptyMap(),
) {
    val selected: Address get() = addresses.firstOrNull { it.id == selectedId } ?: addresses.first()
}

/** До онбординга — одна пустая адреса. Её заменяет выбор на втором шаге онбординга. */
val DefaultAddresses = listOf(Address("home", "Дім", "", "Київ", "kyiv-city", "1.1"))

/** Версия схемы настроек. 3 — первая рабочая версия: тестовые адреса из 0.1/0.2 сбрасываем. */
private const val SCHEMA = 3

/** Адреса из сборки 0.1 сохранялись по-русски — переводим их в украинский вид. */
private val LegacyUk = mapOf("Дом" to "Дім", "Киев" to "Київ", "Львов" to "Львів", "Бровары" to "Бровари")

private val Context.store by preferencesDataStore("settings")

class SettingsStore(private val ctx: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val large = booleanPreferencesKey("large")
        val lang = stringPreferencesKey("lang")
        val remind = intPreferencesKey("remind")
        val nChanges = booleanPreferencesKey("n_changes")
        val nBack = booleanPreferencesKey("n_back")
        val nEmergency = booleanPreferencesKey("n_emergency")
        val quiet = booleanPreferencesKey("quiet")
        val addresses = stringPreferencesKey("addresses")
        val selected = stringPreferencesKey("selected")
        val houseAddr = stringPreferencesKey("house_addr")
        val schema = intPreferencesKey("schema")
    }

    val flow: Flow<Settings> = ctx.store.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        if ((this[K.schema] ?: 0) < SCHEMA) {
            // Тестовые сборки: оставляем только тему, язык и шрифт, адреса выбираем заново.
            return d.copy(
                theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
                largeText = this[K.large] ?: d.largeText,
                lang = this[K.lang]?.let { runCatching { Lang.valueOf(it) }.getOrNull() } ?: d.lang,
            )
        }
        val addrs = this[K.addresses]?.let(::decode)?.takeIf { it.isNotEmpty() } ?: d.addresses
        return Settings(
            onboarded = this[K.onboarded] ?: d.onboarded,
            theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            largeText = this[K.large] ?: d.largeText,
            lang = this[K.lang]?.let { runCatching { Lang.valueOf(it) }.getOrNull() } ?: d.lang,
            remindMinutes = this[K.remind] ?: d.remindMinutes,
            notifyChanges = this[K.nChanges] ?: d.notifyChanges,
            notifyPowerBack = this[K.nBack] ?: d.notifyPowerBack,
            notifyEmergency = this[K.nEmergency] ?: d.notifyEmergency,
            quietNight = this[K.quiet] ?: d.quietNight,
            addresses = addrs,
            selectedId = this[K.selected]?.takeIf { id -> addrs.any { it.id == id } } ?: addrs.first().id,
            houseAddr = this[K.houseAddr]?.split("\u001e")?.mapNotNull { r -> r.split("\u001f").takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap(),
        )
    }

    suspend fun update(f: (Settings) -> Settings) {
        ctx.store.edit { p ->
            val s = f(p.toSettings())
            p[K.onboarded] = s.onboarded
            p[K.theme] = s.theme.name
            p[K.large] = s.largeText
            p[K.lang] = s.lang.name
            p[K.remind] = s.remindMinutes
            p[K.nChanges] = s.notifyChanges
            p[K.nBack] = s.notifyPowerBack
            p[K.nEmergency] = s.notifyEmergency
            p[K.quiet] = s.quietNight
            p[K.addresses] = encode(s.addresses)
            p[K.selected] = s.selectedId
            p[K.houseAddr] = s.houseAddr.entries.joinToString("\u001e") { "${it.key}\u001f${it.value}" }
            p[K.schema] = SCHEMA
        }
    }

    private fun encode(list: List<Address>) = list.joinToString("\u001e") {
        listOf(it.id, it.label, it.street, it.city, it.region, it.queue).joinToString("\u001f")
    }

    private fun decode(s: String) = s.split("\u001e").mapNotNull { row ->
        val f = row.split("\u001f")
        if (f.size == 6) Address(f[0], LegacyUk[f[1]] ?: f[1], f[2], LegacyUk[f[3]] ?: f[3], f[4], f[5]) else null
    }
}
