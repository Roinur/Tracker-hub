package com.roinur.trackerhub

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.roinur.trackerhub.counter.CounterItem
import com.roinur.trackerhub.counter.CounterMode
import com.roinur.trackerhub.widget.CounterWidgetProvider
import java.time.LocalDate
import kotlin.math.roundToInt

class WidgetLayoutCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        runOnMainSync { failure = runCatching { checkSizes() }.exceptionOrNull() }
        val error = failure
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", error?.stackTraceToString() ?: "PASS: compact and expanded RemoteViews layouts")
        })
    }

    private fun checkSizes() {
        val context = targetContext
        val today = LocalDate.of(2026, 11, 26)
        val counter = CounterItem("fixture", "Kaffe", CounterMode.DAILY_RESET, 1, 1, 1,
            mapOf(today.toString() to 124, today.minusDays(1).toString() to 3))
        val provider = CounterWidgetProvider.Companion
        val method = provider.javaClass.declaredMethods.single { it.name == "buildRemoteViews" }.apply { isAccessible = true }
        val density = context.resources.displayMetrics.density
        for ((width, height) in listOf(48 to 48, 65 to 70, 52 to 88, 48 to 130, 84 to 48, 138 to 55, 180 to 170, 280 to 210)) {
            val size = SizeF(width.toFloat(), height.toFloat())
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                if (Build.VERSION.SDK_INT >= 31) putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
            }
            val remote = method.invoke(provider, context, 7, counter, today, options) as RemoteViews
            val host = FrameLayout(context)
            val widget = remote.apply(context, host)
            host.addView(widget, FrameLayout.LayoutParams(-1, -1))
            val w = (width * density).roundToInt()
            val h = (height * density).roundToInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, w, h)
            for (id in listOf(R.id.widget_title, R.id.widget_value, R.id.widget_subvalue, R.id.widget_btn_plus)) {
                val view = widget.findViewById<View>(id)
                val bounds = Rect(0, 0, view.width, view.height)
                host.offsetDescendantRectToMyCoords(view, bounds)
                val label = context.resources.getResourceEntryName(id)
                check(!bounds.isEmpty && Rect(0, 0, w, h).contains(bounds)) { "$size $label out of bounds: $bounds" }
                if (view is TextView) {
                    check(view.layout != null && view.layout.height <= view.height && view.layout.getEllipsisCount(0) == 0) {
                        "$size $label text clipped (${view.width} × ${view.height})"
                    }
                }
            }
        }
    }
}
