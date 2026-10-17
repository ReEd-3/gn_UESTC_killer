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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 圆形电源开关（大号）：中心圆 + 外环梯形光芒。
 * - 关闭灰色 / 开启绿色，切换时颜色渐变（420ms），两种状态均有同色柔光晕
 * - 开启时：外环缓慢旋转（约 20s/圈）+ 中心圆轻微脉动（缓慢变大变小）
 * - 按压缩放反馈
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
            syncAnimators()
        }

    private val evaluator = ArgbEvaluator()
    private val offColor = Color.parseColor("#9AA4B2")
    private val onColor = Color.parseColor("#22C55E")
    private var animProgress = 0f

    /** 外环旋转角度（开启时持续累加） */
    private var spinDegrees = 0f

    /** 中心圆脉动进度 0..1（开启时往复变化） */
    private var pulse = 0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var animator: ValueAnimator? = null
    private var spinAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

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

    /** 根据开关状态启停「外环旋转 / 中心脉动」循环动画 */
    private fun syncAnimators() {
        if (isChecked) {
            startSpin()
            startPulse()
        } else {
            stopSpin()
            stopPulse()
        }
    }

    private fun startSpin() {
        if (!isAttachedToWindow || spinAnimator != null) return
        spinAnimator = ValueAnimator.ofFloat(spinDegrees, spinDegrees + 360f).apply {
            duration = 20000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinDegrees = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
    }

    private fun startPulse() {
        if (!isAttachedToWindow || pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulse = 0f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimators()
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

        // 外圈柔光：开启/关闭均有（灰→绿随状态渐变）
        val glowAlpha = (0x59 + 0x26 * animProgress).toInt()
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

        // 中心圆（开启时轻微脉动：±4.5% 半径，缓慢往复）
        circlePaint.style = Paint.Style.FILL
        circlePaint.color = color
        canvas.drawCircle(cx, cy, maxR * (0.52f + 0.045f * pulse), circlePaint)

        // 外环梯形光芒：外宽内窄，24 个（开启时整体缓慢旋转）
        spikePaint.style = Paint.Style.FILL
        spikePaint.color = color
        val count = 24
        val cxD = cx.toDouble()
        val cyD = cy.toDouble()
        val innerR = (maxR * 0.60f).toDouble()
        val outerR = (maxR * 0.92f).toDouble()
        val innerHalf = Math.toRadians(3.4)
        val outerHalf = Math.toRadians(5.6)
        val baseRotation = Math.toRadians(-90.0 + spinDegrees.toDouble()) +
            animProgress * Math.toRadians(10.0)
        for (i in 0 until count) {
            val angle = baseRotation + i * (2 * Math.PI / count)
            path.reset()
            path.moveTo(
                (cxD + innerR * cos(angle - innerHalf)).toFloat(),
                (cyD + innerR * sin(angle - innerHalf)).toFloat()
            )
            path.lineTo(
                (cxD + outerR * cos(angle - outerHalf)).toFloat(),
                (cyD + outerR * sin(angle - outerHalf)).toFloat()
            )
            path.lineTo(
                (cxD + outerR * cos(angle + outerHalf)).toFloat(),
                (cyD + outerR * sin(angle + outerHalf)).toFloat()
            )
            path.lineTo(
                (cxD + innerR * cos(angle + innerHalf)).toFloat(),
                (cyD + innerR * sin(angle + innerHalf)).toFloat()
            )
            path.close()
            canvas.drawPath(path, spikePaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        stopSpin()
        stopPulse()
        super.onDetachedFromWindow()
    }
}
