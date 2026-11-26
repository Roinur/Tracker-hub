package com.roinur.trackerhub.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object WidgetBackgroundStorage {
    private const val DIR_NAME = "widget_backgrounds"
    private const val MAX_SAVED_DIMENSION = 640

    fun fileFor(context: Context, fileName: String?): File? {
        if (fileName.isNullOrBlank()) return null
        val safeName = File(fileName).name
        if (safeName.isBlank()) return null
        val file = File(File(context.applicationContext.filesDir, DIR_NAME), safeName)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun importFromUri(context: Context, uri: Uri): String? {
        val appContext = context.applicationContext
        val decoded = decodeFromUri(appContext, uri) ?: return null
        val scaled = scaleDownIfNeeded(decoded, MAX_SAVED_DIMENSION)
        if (scaled !== decoded) decoded.recycle()

        val dir = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }
        val fileName = "bg_${UUID.randomUUID()}.jpg"
        val outFile = File(dir, fileName)
        return try {
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 86, out)
            }
            fileName
        } catch (_: Exception) {
            outFile.delete()
            null
        } finally {
            scaled.recycle()
        }
    }

    private fun decodeFromUri(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fromDecoder = runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val maxSide = max(w, h)
                    if (maxSide > MAX_SAVED_DIMENSION) {
                        val ratio = MAX_SAVED_DIMENSION.toFloat() / maxSide.toFloat()
                        decoder.setTargetSize(
                            max(1, (w * ratio).roundToInt()),
                            max(1, (h * ratio).roundToInt())
                        )
                    }
                }
            }.getOrNull()
            if (fromDecoder != null) return fromDecoder
        }

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sampleSize * 2) >= MAX_SAVED_DIMENSION) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun deleteFile(context: Context, fileName: String?) {
        fileFor(context, fileName)?.delete()
    }

    fun decodeThumbnail(context: Context, fileName: String?): Bitmap? {
        val file = fileFor(context, fileName) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * Renders a center-cropped, rounded-corner bitmap with a subtle dark scrim so widget text
     * remains legible over any user-selected photo while staying compact for RemoteViews IPC.
     */
    fun renderWidgetBitmap(
        context: Context,
        fileName: String?,
        targetWidthPx: Int,
        targetHeightPx: Int,
        cornerRadiusPx: Float
    ): Bitmap? {
        val file = fileFor(context, fileName) ?: return null
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        try {
            val w = targetWidthPx.coerceIn(120, 480)
            val h = targetHeightPx.coerceIn(120, 480)
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, maskPaint)

            val scale = max(w.toFloat() / source.width.toFloat(), h.toFloat() / source.height.toFloat())
            val dx = (w - source.width * scale) * 0.5f
            val dy = (h - source.height * scale) * 0.5f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }

            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(source, matrix, bitmapPaint)

            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(122, 14, 18, 16)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
            canvas.drawRect(rect, scrimPaint)

            return output
        } catch (_: Exception) {
            return null
        } finally {
            source.recycle()
        }
    }

    private fun scaleDownIfNeeded(source: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = max(source.width, source.height)
        if (maxSide <= maxDimension) return source
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = max(1, (source.width * ratio).roundToInt())
        val targetH = max(1, (source.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }
}
