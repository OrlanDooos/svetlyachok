package ua.svetlo.monitor.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ua.svetlo.monitor.MainActivity
import ua.svetlo.monitor.R
import ua.svetlo.monitor.SvetloApp
import ua.svetlo.monitor.data.DataSource
import ua.svetlo.monitor.data.I18n
import ua.svetlo.monitor.data.OutageType
import ua.svetlo.monitor.data.between
import ua.svetlo.monitor.data.formatDuration
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.data.tr
import java.time.LocalDateTime

/** Виджет на главном экране: статус выбранного адреса и ближайшая смена. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)

    companion object {
        fun updateAll(ctx: Context) {
            val app = ctx.applicationContext as? SvetloApp ?: return
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                run {
                    val s = app.settings.flow.first()
                    I18n.lang = s.lang
                    val a = s.selected
                    val now = LocalDateTime.now(DataSource.Kyiv)
                    val st = app.repository.now(a.region, a.queue, now)
                    val (bg, title) = when {
                        st.type == OutageType.Blackout -> R.drawable.widget_bg_black to tr("Блекаут", "Блэкаут")
                        st.type == OutageType.Emergency && st.lightOn == true -> R.drawable.widget_bg_red to tr("За графіком світло є", "По графику свет есть")
                        st.type == OutageType.Emergency && st.lightOn == false -> R.drawable.widget_bg_red to tr("За графіком світла немає", "По графику света нет")
                        st.type == OutageType.Emergency -> R.drawable.widget_bg_red to tr("Аварійні відключення", "Аварийные отключения")
                        st.regionOnly && st.type == OutageType.Stabilization -> R.drawable.widget_bg_yellow to ua.svetlo.monitor.ui.screens.regionOnlyTitle(st)
                        st.regionOnly && st.type == OutageType.None -> R.drawable.widget_bg_green to ua.svetlo.monitor.ui.screens.regionOnlyTitle(st)
                        st.lightOn == null -> R.drawable.widget_bg_grey to tr("Немає графіка", "Нет графика")
                        st.lightOn -> R.drawable.widget_bg_green to tr("Світло є", "Свет есть")
                        else -> R.drawable.widget_bg_yellow to tr("Світла немає", "Света нет")
                    }
                    val sub = st.nextChange?.let {
                        (if (st.lightOn == true) tr("Вимкнуть о ", "Выключат в ") else tr("Увімкнуть о ", "Включат в ")) + it.hm() + " · " + formatDuration(between(now, it))
                    }?.let { if (st.schedulesSuspended) "$it · " + tr("графік може не діяти", "график может не действовать") else it }
                        ?: if (st.schedulesSuspended) tr("Графіки не діють", "Графики не действуют") else ""
                    val v = RemoteViews(ctx.packageName, R.layout.widget)
                    v.setInt(R.id.widget_root, "setBackgroundResource", bg)
                    v.setTextViewText(R.id.widget_title, title)
                    v.setTextViewText(R.id.widget_sub, sub)
                    v.setTextViewText(R.id.widget_addr, "${a.labelName} · ${tr("черга", "очередь")} ${a.queue}")
                    val dark = bg == R.drawable.widget_bg_yellow || bg == R.drawable.widget_bg_grey
                    val fg = if (dark) 0xFF16181D.toInt() else 0xFFFFFFFF.toInt()
                    listOf(R.id.widget_title, R.id.widget_sub, R.id.widget_addr).forEach { v.setTextColor(it, fg) }
                    v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    mgr.updateAppWidget(ids, v)
                }
            }
        }
    }
}
