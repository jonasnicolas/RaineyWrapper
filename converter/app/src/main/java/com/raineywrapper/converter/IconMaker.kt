package com.raineywrapper.converter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive launcher icon layers, matching builder/icons.py: a blurred copy of the art
 * as background and the art fitted into the visible area as foreground; or, without art,
 * the title on a colour derived from it.
 */
object IconMaker {
    private const val LAYER_DP = 108
    private const val MASTER = 432 // xxxhdpi
    private val DENSITIES = linkedMapOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)

    /** Zip entry name -> PNG bytes, for the template's res/mipmap-<density>-v4/ entries. */
    fun entries(title: String, image: File?): Map<String, ByteArray> {
        val (bg, fg) = layers(title, image)
        val out = linkedMapOf<String, ByteArray>()
        for ((density, scale) in DENSITIES) {
            val size = (LAYER_DP * scale).toInt()
            out["res/mipmap-$density-v4/ic_launcher_background.png"] = png(Bitmap.createScaledBitmap(bg, size, size, true))
            out["res/mipmap-$density-v4/ic_launcher_foreground.png"] = png(Bitmap.createScaledBitmap(fg, size, size, true))
        }
        return out
    }

    /** Both layers composited, roughly as a launcher shows them. */
    fun preview(title: String, image: File?, size: Int): Bitmap {
        val (bg, fg) = layers(title, image)
        val out = Bitmap.createBitmap(MASTER, MASTER, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Launchers show roughly the middle 72dp of the 108dp layers, masked to a rounded square.
        val inset = MASTER * 18f / LAYER_DP
        val clip = RectF(inset, inset, MASTER - inset, MASTER - inset)
        val layer = Bitmap.createBitmap(MASTER, MASTER, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply {
            drawBitmap(bg, 0f, 0f, null)
            drawBitmap(fg, 0f, 0f, null)
        }
        paint.shader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(clip, MASTER * 0.12f, MASTER * 0.12f, paint)
        val cropped = Bitmap.createBitmap(out, inset.toInt(), inset.toInt(), (MASTER - 2 * inset).toInt(), (MASTER - 2 * inset).toInt())
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    private fun layers(title: String, image: File?): Pair<Bitmap, Bitmap> {
        val art = image?.let { decode(it) }
        return if (art != null) artLayers(art) else titleLayers(title)
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MASTER) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun artLayers(art: Bitmap): Pair<Bitmap, Bitmap> {
        // Background: cover-crop, then blur by shrinking to a few pixels and scaling back up.
        val cover = centerCrop(art, MASTER)
        val tiny = Bitmap.createScaledBitmap(cover, 12, 12, true)
        val bg = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 48, 48, true), MASTER, MASTER, true)

        // Foreground: the art fitted into the 72dp visible area, rounded corners.
        val box = MASTER * 72f / LAYER_DP
        val scale = min(box / art.width, box / art.height)
        val w = art.width * scale
        val h = art.height * scale
        val left = (MASTER - w) / 2
        val top = (MASTER - h) / 2
        val fg = Bitmap.createBitmap(MASTER, MASTER, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(left, top) })
            }
        }
        Canvas(fg).drawRoundRect(RectF(left, top, left + w, top + h), MASTER / 24f, MASTER / 24f, paint)
        return bg to fg
    }

    private fun titleLayers(title: String): Pair<Bitmap, Bitmap> {
        // Stable colour per title so the same game always gets the same icon.
        val hash = MessageDigest.getInstance("SHA-1").digest(title.toByteArray())
        val hue = (((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)) / 65535f * 360f
        val bg = Bitmap.createBitmap(MASTER, MASTER, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.55f)))
        }

        val fg = Bitmap.createBitmap(MASTER, MASTER, Bitmap.Config.ARGB_8888)
        val safe = (MASTER * 62 / LAYER_DP)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        var layout: StaticLayout
        var size = MASTER / 6f
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, safe)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            val widest = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            // Shrink until every word fits on a line, so words are never split mid-word.
            val longestWord = title.split(' ').maxOf { paint.measureText(it) }
            if ((layout.height <= safe && widest <= safe && longestWord <= safe && layout.lineCount <= 4) || size < 14f) break
            size -= 2f
        }
        Canvas(fg).apply {
            translate((MASTER - safe) / 2f, (MASTER - layout.height) / 2f)
            layout.draw(this)
        }
        return bg to fg
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, max(size, 1), max(size, 1), true)
    }

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
