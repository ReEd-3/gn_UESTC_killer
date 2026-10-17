package com.gnuestc.checkin

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 圆形电源开关（大号）：中心圆 + 环形三角光芒。
 * 关闭为灰色、开启为绿色；切换时颜色渐变（420ms）+ 轻微旋转，按压缩放反馈。
 */
class PowerToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    private val evaluator = ArgbEvaluator()
    private val offColor = Color.parseColor("#9AA4B2")
    private val onColor = Color.parseColor("#22C55E")
    private var animProgress = 0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(animProgress, target).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate()
            .scaleX(if (pressed) 0.92f else 1f)
            .scaleY(if (pressed) 0.92f else 1f)
            .setDuration(140)
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val color = evaluator.evaluate(animProgress, offColor, onColor) as Int

        // 外圈柔光（仅开启时出现，随渐变动画淡入）
        val glowAlpha = (0x66 * animProgress).toInt()
        if (glowAlpha > 0) {
            glowPaint.shader = RadialGradient(
                cx, cy, maxR,
                intArrayOf(
                    Color.argb(glowAlpha, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, maxR, glowPaint)
        }

        // 中心圆
        circlePaint.style = Paint.Style.FILL
        circlePaint.color = color
        canvas.drawCircle(cx, cy, maxR * 0.52f, circlePaint)

        // 环形三角光芒
        spikePaint.style = Paint.Style.FILL
        spikePaint.color = color
        val count = 18
        val cxD = cx.toDouble()
        val cyD = cy.toDouble()
        val innerR = (maxR * 0.60f).toDouble()
        val outerR = (maxR * 0.92f).toDouble()
        val halfAngle = Math.toRadians(6.5)
        val baseRotation = Math.toRadians(-90.0) + animProgress * Math.toRadians(10.0)
        for (i in 0 until count) {
            val angle = baseRotation + i * (2 * Math.PI / count)
            path.reset()
            path.moveTo(
                (cxD + innerR * cos(angle)).toFloat(),
                (cyD + innerR * sin(angle)).toFloat()
            )
            path.lineTo(
                (cxD + outerR * cos(angle - halfAngle)).toFloat(),
                (cyD + outerR * sin(angle - halfAngle)).toFloat()
            )
            path.lineTo(
                (cxD + outerR * cos(angle + halfAngle)).toFloat(),
                (cyD + outerR * sin(angle + halfAngle)).toFloat()
            )
            path.close()
            canvas.drawPath(path, spikePaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
