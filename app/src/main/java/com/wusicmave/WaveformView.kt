package com.wusicmave

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class WaveformView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var visualizer: Visualizer? = null
    private var targetBytes: ByteArray = ByteArray(1024) { 128.toByte() }

    private var waveData: FloatArray = FloatArray(1024)
    private val smoothingFactor = 0.8f

    private val path = Path()
    private val mirrorPath = Path()

    // Just the sharp line, no more background or glow
    private val sharpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val mirrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 51 // ~0.2 opacity
    }

    fun startVisualizing(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            visualizer?.release()
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, r: Int) {
                        waveform?.let { targetBytes = it.clone() }
                    }
                    override fun onFftDataCapture(v: Visualizer?, f: ByteArray?, r: Int) {}
                }, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
            postInvalidateOnAnimation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopVisualizing() {
        visualizer?.release()
        visualizer = null
        targetBytes = ByteArray(1024) { 128.toByte() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val density = context.resources.displayMetrics.density

        // Background drawing removed! It is now fully transparent.

        val target = targetBytes
        val n = minOf(target.size, waveData.size)
        var rmsSum = 0f

        for (i in 0 until n) {
            val rawFloat = (((target[i].toInt() and 0xFF) - 128) / 128f) * 2.5f
            val w = 0.42f - 0.5f * cos(2f * PI.toFloat() * i / (n - 1)) +
                    0.08f * cos(4f * PI.toFloat() * i / (n - 1))
            val windowed = rawFloat * w

            waveData[i] = smoothingFactor * waveData[i] + (1f - smoothingFactor) * windowed
            rmsSum += waveData[i] * waveData[i]
        }

        val rms = sqrt(rmsSum / n)
        val intensity = (rms * 8f).coerceIn(0f, 1f)
        val lightness = 0.5f + intensity * 0.25f

        val shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(
                hsl(320f, 1f, lightness),
                hsl(270f, 1f, lightness),
                hsl(210f, 1f, lightness),
                hsl(170f, 0.9f, lightness)
            ),
            floatArrayOf(0f, 0.33f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )

        val sliceW = w / waveData.size
        path.rewind()
        for (i in waveData.indices) {
            val x = i * sliceW
            val y = h / 2f + waveData[i] * h * 0.42f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        sharpPaint.shader = shader
        sharpPaint.strokeWidth = 1.5f * density
        canvas.drawPath(path, sharpPaint)

        mirrorPath.rewind()
        mirrorPaint.shader = shader
        mirrorPaint.strokeWidth = 1f * density
        for (i in waveData.indices) {
            val x = i * sliceW
            val y = h / 2f - waveData[i] * h * 0.42f
            if (i == 0) mirrorPath.moveTo(x, y) else mirrorPath.lineTo(x, y)
        }
        canvas.drawPath(mirrorPath, mirrorPaint)

        if (visualizer?.enabled == true) {
            postInvalidateOnAnimation()
        }
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        return Color.rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }
}