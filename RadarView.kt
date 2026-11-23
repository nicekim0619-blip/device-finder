package com.hi.devicefinder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 레이더 화면. 가운데가 나, 바깥쪽으로 갈수록 멀어요. (각도는 진짜 방향이 아님) */
class RadarView(context: Context) : View(context) {

    data class Blip(
        val label: String,
        val distance: Double,
        val angle: Double,
        val alpha: Float,
        val tracked: Boolean,
        val phone: Boolean
    )

    var blips: List<Blip> = emptyList()
        set(value) { field = value; invalidate() }

    var sweeping = false
        set(value) { field = value; invalidate() }

    private val maxMeters = 15.0
    private var sweepAngle = 0f
    private var sweepShader: SweepGradient? = null

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 23, 12) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 26, 255, 106); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 77, 255, 143); textSize = 30f
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f }
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; isFakeBoldText = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sweepShader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Color.argb(90, 26, 255, 106), Color.TRANSPARENT),
            floatArrayOf(0f, 0.09f, 0.091f)
        )
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 8f

        c.drawCircle(cx, cy, r, bg)
        for (i in 1..3) {
            val rr = r * i / 3f
            c.drawCircle(cx, cy, rr, ring)
            c.drawText("${(maxMeters * i / 3).toInt()}m", cx + 10f, cy - rr + 34f, small)
        }
        c.drawLine(cx - r, cy, cx + r, cy, ring)
        c.drawLine(cx, cy - r, cx, cy + r, ring)

        if (sweeping) {
            sweepAngle = (sweepAngle + 3f) % 360f
            sweepPaint.shader = sweepShader
            c.save()
            c.rotate(sweepAngle - 33f, cx, cy)
            c.drawCircle(cx, cy, r, sweepPaint)
            c.restore()
        }

        for (b in blips) {
            val rr = (min(b.distance / maxMeters, 1.0) * r).toFloat()
            val x = cx + (cos(b.angle) * rr).toFloat()
            val y = cy + (sin(b.angle) * rr).toFloat()
            val a = (b.alpha * 255).toInt()
            dot.color = when {
                b.tracked -> Color.argb(a, 255, 80, 80)
                b.phone -> Color.argb(a, 255, 210, 58)
                else -> Color.argb(a, 77, 255, 143)
            }
            c.drawCircle(x, y, if (b.tracked) 22f else 14f, dot)
            label.color = Color.argb(a, 220, 255, 230)
            c.drawText(b.label.take(12), x + 22f, y + 10f, label)
        }

        c.drawCircle(cx, cy, 12f, me)
        c.drawText("나", cx - 14f, cy + 46f, me)

        if (sweeping) postInvalidateOnAnimation()
    }
}
