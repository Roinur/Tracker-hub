package com.roinur.trackerhub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.roinur.trackerhub.MainActivity
import com.roinur.trackerhub.R
import com.roinur.trackerhub.counter.CounterItem
import com.roinur.trackerhub.counter.CounterMode
import com.roinur.trackerhub.counter.CounterRepository
import com.roinur.trackerhub.settings.AppLanguage
import com.roinur.trackerhub.settings.TrackerHubSettingsRepository
import com.roinur.trackerhub.ui.CounterAccentDarkPalette
import com.roinur.trackerhub.ui.CounterAccentLightPalette
import java.time.LocalDate
import java.util.concurrent.Executors

class CounterWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INCREMENT -> {
                runWidgetWork { handleStep(context.applicationContext, intent, delta = +1) }
                return
            }
            ACTION_DECREMENT -> {
                runWidgetWork { handleStep(context.applicationContext, intent, delta = -1) }
                return
            }
            ACTION_MIDNIGHT_ROLLOVER -> {
                handleDateOrSystemChange(context)
                return
            }
            ACTION_PIN_CONFIRMED -> {
                val appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val counterId = intent.getStringExtra(EXTRA_COUNTER_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && !counterId.isNullOrBlank()) {
                    val repo = CounterRepository.getInstance(context)
                    repo.bindWidget(appWidgetId, counterId)
                    updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
                    MidnightRolloverScheduler.scheduleIfNeeded(context)
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    private fun runWidgetWork(work: () -> Unit) {
        val pending = goAsync()
        widgetWorker.execute {
            try {
                work()
            } catch (error: Exception) {
                android.util.Log.e("TrackerHubWidget", "Could not update widget", error)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = CounterRepository.getInstance(context)
        repo.syncDateRollover()
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
        MidnightRolloverScheduler.scheduleIfNeeded(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val repo = CounterRepository.getInstance(context)
        repo.unbindWidgets(appWidgetIds)
        MidnightRolloverScheduler.scheduleIfNeeded(context)
    }

    override fun onEnabled(context: Context) {
        CounterRepository.getInstance(context).syncDateRollover()
        MidnightRolloverScheduler.scheduleNextMidnight(context)
    }

    override fun onDisabled(context: Context) {
        MidnightRolloverScheduler.cancel(context)
    }

    private fun handleStep(context: Context, intent: Intent, delta: Int) {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val repo = CounterRepository.getInstance(context)
        val rolledOver = repo.syncDateRollover()
        val counterId = (if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            repo.getBoundCounterId(appWidgetId)
        } else {
            null
        })

        if (!counterId.isNullOrBlank()) {
            repo.adjustCounter(counterId, delta)
        }

        if (rolledOver) {
            updateAllWidgets(context)
        } else if (!counterId.isNullOrBlank()) {
            val manager = AppWidgetManager.getInstance(context)
            val targets = buildSet {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) add(appWidgetId)
                addAll(repo.getWidgetIdsForCounter(counterId))
            }
            for (targetId in targets) {
                updateWidget(context, manager, targetId)
            }
        } else if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            updateWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
        }
        MidnightRolloverScheduler.scheduleIfNeeded(context)
    }

    companion object {
        private val widgetWorker = Executors.newSingleThreadExecutor()
        const val ACTION_INCREMENT = "com.roinur.trackerhub.widget.ACTION_INCREMENT"
        const val ACTION_DECREMENT = "com.roinur.trackerhub.widget.ACTION_DECREMENT"
        const val ACTION_MIDNIGHT_ROLLOVER = "com.roinur.trackerhub.widget.ACTION_MIDNIGHT_ROLLOVER"
        const val ACTION_PIN_CONFIRMED = "com.roinur.trackerhub.widget.ACTION_PIN_CONFIRMED"
        const val EXTRA_COUNTER_ID = "com.roinur.trackerhub.widget.EXTRA_COUNTER_ID"

        private const val SLOT_INCREMENT = 1
        private const val SLOT_DECREMENT = 2
        private const val SLOT_OPEN_APP = 3
        private const val SLOT_CONFIGURE = 4

        internal fun requestCodeFor(appWidgetId: Int, slot: Int): Int {
            return appWidgetId * 16 + slot
        }

        fun handleDateOrSystemChange(context: Context) {
            CounterRepository.getInstance(context).syncDateRollover()
            updateAllWidgets(context)
            MidnightRolloverScheduler.scheduleIfNeeded(context)
        }

        fun updateAllWidgets(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val widgetIds = manager.getAppWidgetIds(ComponentName(appContext, CounterWidgetProvider::class.java))
            if (widgetIds.isEmpty()) {
                MidnightRolloverScheduler.cancel(appContext)
                return
            }
            for (widgetId in widgetIds) {
                updateWidget(appContext, manager, widgetId)
            }
            MidnightRolloverScheduler.scheduleNextMidnight(appContext)
        }

        fun updateWidgetsForCounter(context: Context, counterId: String) {
            val appContext = context.applicationContext
            val repo = CounterRepository.getInstance(appContext)
            val manager = AppWidgetManager.getInstance(appContext)
            val widgetIds = repo.getWidgetIdsForCounter(counterId)
            if (widgetIds.isEmpty()) return
            for (widgetId in widgetIds) {
                updateWidget(appContext, manager, widgetId)
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            optionsOverride: Bundle? = null
        ) {
            val appContext = context.applicationContext
            val repo = CounterRepository.getInstance(appContext)
            val today = repo.currentDate()
            val counter = repo.currentSnapshot().counterForWidget(appWidgetId)
            val options = optionsOverride ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val views = buildRemoteViews(appContext, appWidgetId, counter, today, options)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun isPinWidgetSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context.applicationContext)
            return manager.isRequestPinAppWidgetSupported
        }

        fun requestPinForCounter(context: Context, counterId: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            if (!manager.isRequestPinAppWidgetSupported) return false

            val provider = ComponentName(appContext, CounterWidgetProvider::class.java)
            val callbackIntent = Intent(appContext, CounterWidgetProvider::class.java).apply {
                action = ACTION_PIN_CONFIRMED
                data = Uri.parse("trackerhub://counter-widget/pin/$counterId")
                putExtra(EXTRA_COUNTER_ID, counterId)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val successCallback = PendingIntent.getBroadcast(
                appContext,
                ("pin_$counterId".hashCode() and 0x0FFFFFFF),
                callbackIntent,
                flags
            )
            return manager.requestPinAppWidget(provider, null, successCallback)
        }

        private fun buildRemoteViews(
            context: Context,
            appWidgetId: Int,
            counter: CounterItem?,
            today: LocalDate,
            options: Bundle?
        ): RemoteViews {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val maxWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width) ?: width
            val maxHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height) ?: height
            fun render(layout: Int) = renderWidget(context, appWidgetId, counter, today, layout)
            fun layoutFor(w: Float, h: Float): Int = when {
                w >= 110 && h >= 104 -> R.layout.widget_counter
                w >= 116 && w >= h * 1.55f -> R.layout.widget_counter_horizontal
                w >= 68 && w >= h * 1.55f -> R.layout.widget_counter_horizontal_tight
                h >= 72 && h >= w * 1.55f -> R.layout.widget_counter_vertical
                else -> R.layout.widget_counter_square
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val sizes = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                val offered = sizes.orEmpty().filter { it.width > 0f && it.height > 0f }.distinct().take(16)
                if (offered.isNotEmpty()) {
                    return RemoteViews(offered.associateWith { render(layoutFor(it.width, it.height)) })
                }
                return RemoteViews(linkedMapOf(
                    SizeF(44f, 44f) to render(R.layout.widget_counter_square),
                    SizeF(84f, 44f) to render(R.layout.widget_counter_horizontal_tight),
                    SizeF(116f, 44f) to render(R.layout.widget_counter_horizontal),
                    SizeF(44f, 84f) to render(R.layout.widget_counter_vertical),
                    SizeF(110f, 104f) to render(R.layout.widget_counter)
                ))
            }
            val portrait = render(layoutFor(width.toFloat(), maxHeight.toFloat()))
            val landscape = render(layoutFor(maxWidth.toFloat(), height.toFloat()))
            return RemoteViews(landscape, portrait)
        }

        private fun renderWidget(
            context: Context,
            appWidgetId: Int,
            counter: CounterItem?,
            today: LocalDate,
            layout: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val palette = if (dark) CounterAccentDarkPalette else CounterAccentLightPalette
            val base = context.getColor(R.color.widget_surface)
            val accent = counter?.let { palette[Math.floorMod(it.colorIndex, palette.size)].toArgb() }
                ?: palette.first().toArgb()
            val surface = ColorUtils.blendARGB(base, accent, if (dark) .15f else .12f)
            val onAccent = if (ColorUtils.calculateContrast(Color.BLACK, accent) >= 4.5) Color.BLACK else Color.WHITE
            val customBgBitmap = counter?.widgetBackgroundFile?.let { fileName ->
                val (targetW, targetH) = when (layout) {
                    R.layout.widget_counter_horizontal, R.layout.widget_counter_horizontal_tight -> 440 to 200
                    R.layout.widget_counter_vertical -> 200 to 420
                    R.layout.widget_counter_square -> 220 to 220
                    else -> 420 to 420
                }
                val radiusPx = when (layout) {
                    R.layout.widget_counter_horizontal, R.layout.widget_counter_horizontal_tight -> 44f
                    R.layout.widget_counter_square -> 40f
                    else -> 52f
                }
                WidgetBackgroundStorage.renderWidgetBitmap(context, fileName, targetW, targetH, radiusPx)
            }
            if (customBgBitmap != null) {
                views.setImageViewBitmap(R.id.widget_surface_tint, customBgBitmap)
                views.setInt(R.id.widget_surface_tint, "setColorFilter", 0)
                views.setTextColor(R.id.widget_title, Color.parseColor("#F4F7F5"))
                views.setTextColor(R.id.widget_value, Color.parseColor("#FFFFFF"))
                views.setTextColor(R.id.widget_subvalue, Color.parseColor("#D8E0DB"))
            } else {
                views.setInt(R.id.widget_surface_tint, "setColorFilter", surface)
            }
            views.setInt(R.id.widget_plus_tint, "setColorFilter", accent)
            views.setInt(R.id.widget_plus_icon, "setColorFilter", onAccent)
            if (counter == null) {
                views.setViewVisibility(R.id.widget_configured_container, View.GONE)
                views.setViewVisibility(R.id.widget_unconfigured_container, View.VISIBLE)
                views.setOnClickPendingIntent(
                    R.id.widget_unconfigured_container,
                    buildConfigurePendingIntent(context, appWidgetId)
                )
                return views
            }

            views.setViewVisibility(R.id.widget_configured_container, View.VISIBLE)
            views.setViewVisibility(R.id.widget_unconfigured_container, View.GONE)

            val primaryValue = counter.primaryDisplayValue(today)
            val todayValue = counter.todayCount(today)

            views.setTextViewText(R.id.widget_title, counter.name)
            views.setTextViewText(R.id.widget_value, primaryValue.toString())

            val isSwedish = AppLanguage.isSwedish(
                TrackerHubSettingsRepository.getInstance(context).current().language
            )
            val subText = when (counter.mode) {
                CounterMode.DAILY_RESET -> {
                    val yesterday = counter.countForDate(today.minusDays(1))
                    if (isSwedish) "Igår $yesterday" else "Yesterday $yesterday"
                }
                CounterMode.CUMULATIVE -> {
                    val prefix = if (isSwedish) "Idag" else "Today"
                    if (todayValue > 0) "$prefix +$todayValue" else if (todayValue < 0) "$prefix $todayValue" else null
                }
            }

            if (subText != null && (counter.mode == CounterMode.DAILY_RESET || layout != R.layout.widget_counter_square)) {
                views.setTextViewText(R.id.widget_subvalue, subText)
                views.setViewVisibility(R.id.widget_subvalue, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_subvalue, View.GONE)
            }

            views.setOnClickPendingIntent(
                R.id.widget_btn_plus,
                buildStepPendingIntent(context, appWidgetId, counter.id, ACTION_INCREMENT, SLOT_INCREMENT)
            )

            val openAppIntent = buildOpenCounterPendingIntent(context, appWidgetId, counter.id)
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)

            return views
        }

        private fun buildStepPendingIntent(
            context: Context,
            appWidgetId: Int,
            counterId: String,
            actionName: String,
            slot: Int
        ): PendingIntent {
            val intent = Intent(context, CounterWidgetProvider::class.java).apply {
                action = actionName
                data = Uri.parse("trackerhub://counter-widget/$appWidgetId/$slot")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_COUNTER_ID, counterId)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCodeFor(appWidgetId, slot),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildOpenCounterPendingIntent(
            context: Context,
            appWidgetId: Int,
            counterId: String
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_COUNTER
                data = Uri.parse("trackerhub://counter-open/$appWidgetId/$counterId")
                putExtra(MainActivity.EXTRA_OPEN_COUNTER_ID, counterId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(
                context,
                requestCodeFor(appWidgetId, SLOT_OPEN_APP),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun buildConfigurePendingIntent(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, CounterWidgetConfigureActivity::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
                data = Uri.parse("trackerhub://counter-configure/$appWidgetId")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return PendingIntent.getActivity(
                context,
                requestCodeFor(appWidgetId, SLOT_CONFIGURE),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
