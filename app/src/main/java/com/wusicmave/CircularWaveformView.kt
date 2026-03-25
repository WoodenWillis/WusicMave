package com.wusicmave

import android.content.Context
import android.graphics.*
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class CircularWaveformView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var visualizer: Visualizer? = null
    private var targetBytes: ByteArray = ByteArray(1024) { 128.toByte() }

    private var waveData:     FloatArray = FloatArray(1024)
    private val smoothFactor: Float      = 0.82f

    private val path      = Path()
    private val innerPath = Path()   // faint mirror ring slightly inside

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style    = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style    = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
        alpha    = 40    // very faint echo ring
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

        val w   = width.toFloat()
        val h   = height.toFloat()
        val cx  = w / 2f
        val cy  = h / 2f
        val density = context.resources.displayMetrics.density

        val target = targetBytes
        val n      = minOf(target.size, waveData.size)

        // Smooth + compute RMS
        var rmsSum = 0f
        for (i in 0 until n) {
            val raw = ((target[i].toInt() and 0xFF) - 128) / 128f
            // Hann window to reduce edge discontinuity on the circle join
            val win = 0.5f * (1f - cos(2f * PI.toFloat() * i / n))
            val windowed = raw * win
            waveData[i] = smoothFactor * waveData[i] + (1f - smoothFactor) * windowed
            rmsSum += waveData[i] * waveData[i]
        }

        val rms       = sqrt(rmsSum / n)
        val intensity = (rms * 10f).coerceIn(0f, 1f)
        val lightness = 0.5f + intensity * 0.25f

        // Base radius: 38% of the smaller dimension, amplitude up to 22%
        val baseR  = minOf(w, h) * 0.36f
        val ampR   = minOf(w, h) * 0.22f

        // Gradient sweeping around the ring
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(
                hsl(320f, 1f, lightness),
                hsl(270f, 1f, lightness),
                hsl(210f, 1f, lightness),
                hsl(170f, 0.9f, lightness),
                hsl(320f, 1f, lightness)   // close the loop
            ),
            floatArrayOf(0f, 0.25f, 0.55f, 0.82f, 1f)
        )

        // Build the outer wave path
        path.rewind()
        for (i in 0 until n) {
            val angle = 2f * PI.toFloat() * i / n - PI.toFloat() / 2f   // start at top
            val r     = baseR + waveData[i] * ampR
            val x     = cx + r * cos(angle)
            val y     = cy + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        wavePaint.shader      = shader
        wavePaint.strokeWidth = 2f * density
        canvas.drawPath(path, wavePaint)

        // Inner echo ring — slightly smaller radius, no amplitude, just a faint circle guide
        innerPath.rewind()
        for (i in 0 until n) {
            val angle = 2f * PI.toFloat() * i / n - PI.toFloat() / 2f
            val r     = (baseR - ampR * 0.15f) + waveData[i] * ampR * 0.35f
            val x     = cx + r * cos(angle)
            val y     = cy + r * sin(angle)
            if (i == 0) innerPath.moveTo(x, y) else innerPath.lineTo(x, y)
        }
        innerPath.close()

        innerPaint.shader      = shader
        innerPaint.strokeWidth = 1f * density
        canvas.drawPath(innerPath, innerPaint)

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
        return Color.rgb(
            ((r + m) * 255).toInt(),
            ((g + m) * 255).toInt(),
            ((b + m) * 255).toInt()
        )
    }
}
