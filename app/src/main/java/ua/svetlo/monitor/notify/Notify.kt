package ua.svetlo.monitor.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ua.svetlo.monitor.MainActivity
import ua.svetlo.monitor.R
import ua.svetlo.monitor.SvetloApp
import ua.svetlo.monitor.data.DataSource
import ua.svetlo.monitor.data.I18n
import ua.svetlo.monitor.data.OutageRepository
import ua.svetlo.monitor.data.RegionMode
import ua.svetlo.monitor.data.Settings
import ua.svetlo.monitor.data.Slot
import ua.svetlo.monitor.data.hm
import ua.svetlo.monitor.data.tr
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val REMIND = "remind"
    const val CHANGES = "changes"
    const val EMERGENCY = "emergency"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REMIND, "Нагадування про відключення", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANGES, "Зміни графіка", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(EMERGENCY, "Аварійні відключення", NotificationManager.IMPORTANCE_HIGH))
    }

    fun allowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(ctx: Context, id: Int, channel: String, title: String, text: String, silent: Boolean = false) {
        if (!allowed(ctx)) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .setSilent(silent)
            .setPriority(if (channel == CHANGES) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}

private fun quiet(s: Settings, t: LocalDateTime) = s.quietNight && (t.hour >= 23 || t.hour < 7)

/** Напоминания по графику: за N минут до отключения и когда свет должны вернуть. */
object Reminders {
    private const val PREFS = "reminders"

    fun reschedule(ctx: Context, repo: OutageRepository, s: Settings) {
        I18n.lang = s.lang
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Снимаем старые будильники.
        prefs.getStringSet("ids", emptySet())!!.forEach { id ->
            am.cancel(PendingIntent.getBroadcast(ctx, id.toInt(), Intent(ctx, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return@forEach)
        }
        val ids = mutableSetOf<String>()
        if (!s.onboarded) { prefs.edit().putStringSet("ids", ids).apply(); return }
        val now = LocalDateTime.now(DataSource.Kyiv)
        var code = 1000
        s.addresses.forEach { a ->
            if ((repo.summary(a.region)?.mode ?: RegionMode.Unknown) != RegionMode.Schedule) return@forEach
            val windows = offWindows(repo, a.region, a.queue, now).take(3)
            windows.forEach { (start, end) ->
                val who = "${a.labelName} · ${tr("черга", "очередь")} ${a.queue}"
                if (s.remindMinutes > 0) {
                    val at = start.minusMinutes(s.remindMinutes.toLong())
                    if (at.isAfter(now)) {
                        set(ctx, am, code++, at, tr("Через ${s.remindMinutes} хв відключать світло", "Через ${s.remindMinutes} мин отключат свет"),
                            "$who. ${tr("Відключення", "Отключение")} ${start.hm()}–${end.hm()}", quiet(s, at)); ids += (code - 1).toString()
                    }
                }
                if (s.notifyPowerBack && end.isAfter(now)) {
                    set(ctx, am, code++, end, tr("Світло мають увімкнути", "Свет должны включить"),
                        "$who. ${tr("За графіком відключення закінчилось о", "По графику отключение закончилось в")} ${end.hm()}", quiet(s, end)); ids += (code - 1).toString()
                }
            }
        }
        prefs.edit().putStringSet("ids", ids).apply()
    }

    /** Окна отключений по графику на сегодня и завтра, начиная с текущего. */
    fun offWindows(repo: OutageRepository, region: String, queue: String, now: LocalDateTime): List<Pair<LocalDateTime, LocalDateTime>> {
        val d0 = now.toLocalDate()
        val days = listOf(d0, d0.plusDays(1)).map { repo.schedule(region, queue, it) }.filter { it.known && !it.forecast }
        val out = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        days.forEach { d ->
            d.windows(Slot.Off).forEach { r ->
                val a = d.date.atStartOfDay().plusMinutes(r.first * 30L)
                val b = d.date.atStartOfDay().plusMinutes((r.last + 1) * 30L)
                // Склеиваем окно через полночь.
                if (out.isNotEmpty() && out.last().second == a) out[out.lastIndex] = out.last().first to b else out += a to b
            }
        }
        return out.filter { it.second.isAfter(now) }
    }

    private fun set(ctx: Context, am: AlarmManager, code: Int, at: LocalDateTime, title: String, text: String, silent: Boolean) {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("title", title).putExtra("text", text).putExtra("silent", silent).putExtra("id", code)
        val pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val t = at.atZone(DataSource.Kyiv).toInstant().toEpochMilli()
        // Окно в 2 минуты: точные будильники требуют особого разрешения, а точность до минуты здесь не нужна.
        am.setWindow(AlarmManager.RTC_WAKEUP, t - 60_000, 120_000, pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifications.show(ctx, intent.getIntExtra("id", 1), Notifications.REMIND, intent.getStringExtra("title") ?: return, intent.getStringExtra("text") ?: "", intent.getBooleanExtra("silent", false))
        Widget.updateAll(ctx)
    }
}

/** После перезагрузки телефона будильники пропадают — ставим заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        RefreshWorker.schedule(ctx)
        RefreshWorker.runOnce(ctx)
    }
}

/** Фоновая проверка графиков примерно раз в 15 минут. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SvetloApp
        val s = app.settings.flow.first()
        I18n.lang = s.lang
        if (!s.onboarded) return Result.success()
        val repo = app.repository
        val now = LocalDateTime.now(DataSource.Kyiv)
        val dates = listOf(now.toLocalDate(), now.toLocalDate().plusDays(1))
        fun snapshot() = s.addresses.associate { a -> a.id to dates.map { d -> repo.schedule(a.region, a.queue, d).let { if (it.known && !it.forecast) it.slots else null } } }
        val beforeSlots = snapshot()
        val beforeMode = s.addresses.associate { it.region to (repo.summary(it.region)?.mode ?: RegionMode.Unknown) }
        val changed = app.schedules.refresh(s.addresses.map { it.region }.toSet())
        if (app.schedules.lastError != null) return Result.retry()
        val afterSlots = snapshot()
        s.addresses.forEachIndexed { i, a ->
            val mode = repo.summary(a.region)?.mode ?: RegionMode.Unknown
            val was = beforeMode[a.region]
            if (s.notifyEmergency && was != null && was != mode && (mode == RegionMode.Emergency || mode == RegionMode.Blackout)) {
                Notifications.show(applicationContext, 200 + i, Notifications.EMERGENCY,
                    if (mode == RegionMode.Blackout) tr("Блекаут", "Блэкаут") else tr("Аварійні відключення", "Аварийные отключения"),
                    "${a.labelName}: " + (repo.region(a.region)?.attention?.take(220) ?: tr("графіки тимчасово не діють", "графики временно не действуют")))
            } else if (s.notifyChanges && a.region in changed && beforeSlots[a.id] != afterSlots[a.id] && afterSlots[a.id]?.any { it != null } == true) {
                val idx = dates.indices.firstOrNull { beforeSlots[a.id]?.getOrNull(it) != afterSlots[a.id]?.getOrNull(it) } ?: 0
                val day = if (idx == 0) tr("сьогодні", "сегодня") else tr("завтра", "завтра")
                val w = Reminders.offWindows(repo, a.region, a.queue, now).filter { it.first.toLocalDate() == dates[idx] || idx == 0 }.take(4)
                Notifications.show(applicationContext, 100 + i, Notifications.CHANGES, tr("Графік на $day змінився", "График на $day изменился"),
                    "${a.labelName} · ${tr("черга", "очередь")} ${a.queue}: " + (if (w.isEmpty()) tr("відключень немає", "отключений нет") else w.joinToString(", ") { "${it.first.hm()}–${it.second.hm()}" }),
                    silent = quiet(s, now))
            }
        }
        Reminders.reschedule(applicationContext, repo, s)
        Widget.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun runOnce(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
