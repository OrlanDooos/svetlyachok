package ua.svetlo.monitor.data

/** Язык интерфейса. Украинский по умолчанию, русский — по выбору в настройках. */
enum class Lang { Uk, Ru }

object I18n {
    @Volatile var lang: Lang = Lang.Uk
}

/** Возвращает строку на текущем языке интерфейса. */
fun tr(uk: String, ru: String): String = if (I18n.lang == Lang.Uk) uk else ru
