package com.roinur.trackerhub.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object MidnightRolloverScheduler {
    private const val REQUEST_CODE_MIDNIGHT = 900_001
    internal const val SAFETY_MARGIN_MS = 1_000L

    /**
     * Computes the epoch millisecond timestamp of the next local calendar midnight (+1s safety margin)
     * in [zoneId], handling DST spring-forward and fall-back transitions accurately.
     */
    fun computeNextMidnightEpochMillis(now: Instant, zoneId: ZoneId): Long {
        val localDate = now.atZone(zoneId).toLocalDate()
        val nextMidnight = localDate.plusDays(1).atStartOfDay(zoneId).toInstant()
        return nextMidnight.toEpochMilli() + SAFETY_MARGIN_MS
    }

    fun scheduleIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val widgetIds = manager.getAppWidgetIds(ComponentName(appContext, CounterWidgetProvider::class.java))
        if (widgetIds.isEmpty()) {
            cancel(appContext)
            return
        }
        scheduleNextMidnight(appContext)
    }

    fun scheduleNextMidnight(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtMillis = computeNextMidnightEpochMillis(Instant.now(), ZoneId.systemDefault())
        val pendingIntent = buildMidnightPendingIntent(appContext)

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildMidnightPendingIntent(appContext))
    }

    private fun buildMidnightPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CounterWidgetProvider::class.java).apply {
            action = CounterWidgetProvider.ACTION_MIDNIGHT_ROLLOVER
            data = Uri.parse("trackerhub://counter-widget/midnight-rollover")
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MIDNIGHT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
