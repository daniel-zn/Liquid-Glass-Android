/**
 * 玻璃 Chip：胶囊形状的小标签，对应 Material 的 Chip
 *
 * 结构：前置图标（可选）→ 文字 → 关闭图标（可选），高 32dp。三种常见用法：
 * - 普通 chip（建议 / 操作）：setOnClickListener
 * - 筛选 chip：[isCheckable] = true，点击切换 [isChecked]；选中时玻璃染成 [checkedTint]，
 *   前置位置换成对勾
 * - 输入 chip：[isCloseIconVisible] = true，点关闭图标走 [setOnCloseIconClickListener]
 *
 * 多个 chip 放进 [LiquidGlassChipGroup]：自动换行排布、单选 / 多选，背景来源由组统一下发。
 * 文字与图标颜色跟随背景明暗（开启 enableAdaptiveTint 时自动翻转），选中时按 [checkedTint]
 * 的深浅换色；显式 [setTextColor] 之后不再自动切换。
 *
 * ```xml
 * <com.danielzn.liquidglass.LiquidGlassChip
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     android:text="Photos"
 *     android:checkable="true" />
 * ```
 * ```kotlin
 * chip.setOnCheckedChangeListener { chip, checked -> ... }
 * ```
 */
package com.danielzn.liquidglass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

open class LiquidGlassChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    fun interface OnCheckedChangeListener {
        fun onCheckedChanged(chip: LiquidGlassChip, isChecked: Boolean)
    }

    /** 前置图标位（chipIcon，选中时换成对勾） */
    val chipIconView: ImageView = ImageView(context)

    /** 文字标签（字体 / 字距等细节直接拿它设置） */
    val textView: TextView = TextView(context)

    /** 尾部关闭图标位 */
    val closeIconView: ImageView = ImageView(context)

    private val row = LinearLayout(context)

    /** 显式设置过文字颜色后不再跟随背景明暗自动切换 */
    private var autoColors = true
    private var contentColor = Color.WHITE

    private var checked = false
    private var checkedFraction = 0f
    private var checkedAnimator: ValueAnimator? = null
    private var checkedListener: OnCheckedChangeListener? = null

    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.liquid_glass_ic_check)

    var text: CharSequence
        get() = textView.text
        set(value) {
            textView.text = value
            updateCloseDescription()
        }

    /** 前置图标；null = 没有。默认按文字颜色着色，彩色图标配 [isChipIconTintEnabled] = false */
    var chipIcon: Drawable? = null
        set(value) {
            field = value
            updateLeadingIcon()
        }

    /** 前置图标是否按文字颜色着色 */
    var isChipIconTintEnabled = true
        set(value) {
            field = value
            applyContentColor()
        }

    /** 显示尾部关闭图标（输入 chip） */
    var isCloseIconVisible = false
        set(value) {
            field = value
            closeIconView.visibility = if (value) View.VISIBLE else View.GONE
            updatePadding()
        }

    /** 关闭图标，默认是一个 ×，按文字颜色着色 */
    var closeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.liquid_glass_ic_close)
        set(value) {
            field = value
            closeIconView.setImageDrawable(value)
            applyContentColor()
        }

    /** 可选中（筛选 chip）：点击切换 [isChecked]。关掉时顺带取消选中 */
    var isCheckable = false
        set(value) {
            field = value
            if (!value && checked) isChecked = false
        }

    /**
     * 选中状态；[isCheckable] 为 false 时设 true 无效。
     * 在 [LiquidGlassChipGroup] 里时由组负责单选互斥与"至少选一个"
     */
    var isChecked: Boolean
        get() = checked
        set(value) {
            if (value && !isCheckable) return
            if (value == checked) return
            checked = value
            animateChecked(value)
            updateLeadingIcon()
            applyAutoColors()
            checkedListener?.onCheckedChanged(this, value)
            (parent as? LiquidGlassChipGroup)?.onChipCheckedChanged(this, value)
        }

    /** 选中时前置位置显示对勾（有 chipIcon 时替换它），默认开 */
    var isCheckedIconVisible = true
        set(value) {
            field = value
            updateLeadingIcon()
        }

    /**
     * 选中时的玻璃本体颜色（straight-alpha ARGB，alpha 即强度），默认 40% 的 iOS 系统蓝。
     * 选中期间替代 [glassTint] 渲染，取消选中后回到 [glassTint]
     */
    var checkedTint: Int = DEFAULT_CHECKED_TINT
        set(value) {
            field = value
            invalidate()
            applyAutoColors()
        }

    override val effectiveGlassTint: Int
        get() = when {
            checkedFraction <= 0f -> glassTint
            checkedFraction >= 1f -> checkedTint
            else -> blendArgb(glassTint, checkedTint, checkedFraction)
        }

    init {
        // 形状沿用 LiquidGlassView 默认的胶囊（不在这里写死，XML 的 app:cornerRadius 才生效）；
        // 32dp 的最小高度放在内容行上，chip 本身仍可用 android:minHeight 加高
        isClickable = true
        isFocusable = true

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(32)

        chipIconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        chipIconView.visibility = View.GONE
        row.addView(chipIconView, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })

        textView.textSize = 14f
        textView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textView.maxLines = 1
        textView.includeFontPadding = false
        row.addView(textView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 关闭图标的点击区比图标大：宽 26dp、高与 chip 同高，图标居中
        closeIconView.scaleType = ImageView.ScaleType.CENTER
        closeIconView.setImageDrawable(closeIcon)
        closeIconView.visibility = View.GONE
        row.addView(closeIconView, LinearLayout.LayoutParams(dp(26), dp(32)).apply { marginStart = dp(2) })

        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        updatePadding()
        applyAutoColors()

        attrs?.let { parseChipAttributes(context, it) }
    }

    private fun parseChipAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassChip)
        try {
            ta.getText(R.styleable.LiquidGlassChip_android_text)?.let { text = it }
            val sizePx = ta.getDimensionPixelSize(R.styleable.LiquidGlassChip_android_textSize, 0)
            if (sizePx > 0) textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx.toFloat())
            val iconRes = ta.getResourceId(R.styleable.LiquidGlassChip_glassChipIcon, 0)
            if (iconRes != 0) chipIcon = ContextCompat.getDrawable(context, iconRes)
            isCloseIconVisible = ta.getBoolean(R.styleable.LiquidGlassChip_glassCloseIconVisible, isCloseIconVisible)
            isCheckedIconVisible = ta.getBoolean(R.styleable.LiquidGlassChip_glassCheckedIconVisible, isCheckedIconVisible)
            checkedTint = ta.getColor(R.styleable.LiquidGlassChip_glassCheckedTint, checkedTint)
            isCheckable = ta.getBoolean(R.styleable.LiquidGlassChip_android_checkable, isCheckable)
            if (ta.getBoolean(R.styleable.LiquidGlassChip_android_checked, false)) setCheckedImmediately()
            if (ta.hasValue(R.styleable.LiquidGlassChip_android_textColor)) {
                setTextColor(ta.getColor(R.styleable.LiquidGlassChip_android_textColor, Color.WHITE))
            }
        } finally {
            ta.recycle()
        }
    }

    fun setChipIconResource(resId: Int) {
        chipIcon = if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    fun setCloseIconResource(resId: Int) {
        closeIcon = if (resId == 0) null else ContextCompat.getDrawable(context, resId)
    }

    fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        checkedListener = listener
    }

    /** 点关闭图标的回调（回调参数是 chip 本身）；null = 关闭图标只是装饰，点它等于点 chip */
    fun setOnCloseIconClickListener(listener: OnClickListener?) {
        if (listener == null) {
            closeIconView.setOnClickListener(null)
            closeIconView.isClickable = false
        } else {
            closeIconView.setOnClickListener { listener.onClick(this) }
        }
    }

    /** 文字字号（sp） */
    fun setTextSize(sp: Float) {
        textView.textSize = sp
    }

    /** 显式指定文字 / 图标颜色（同时关闭明暗自动切换与文字阴影） */
    fun setTextColor(color: Int) {
        autoColors = false
        textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        contentColor = color
        textView.setTextColor(color)
        applyContentColor()
    }

    fun toggle() {
        if (isCheckable) isChecked = !checked
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 禁用时吞掉触摸但不做按压反馈、不派发点击（LiquidGlassView 自己不看 isEnabled）
        if (!isEnabled) return isClickable
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (isCheckable) {
            val target = !checked
            // 组要求至少选中一个时，最后一个选中的点了不取消
            val group = parent as? LiquidGlassChipGroup
            if (target || group == null || group.canUncheck(this)) isChecked = target
        }
        return super.performClick()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // M3 的禁用态：内容 38% 不透明度
        row.alpha = if (enabled) 1f else 0.38f
        closeIconView.isEnabled = enabled
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = if (isCheckable) CompoundButton::class.java.name else Button::class.java.name
        info.isCheckable = isCheckable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            info.checked = if (checked) {
                AccessibilityNodeInfo.CHECKED_STATE_TRUE
            } else {
                AccessibilityNodeInfo.CHECKED_STATE_FALSE
            }
        } else {
            @Suppress("DEPRECATION")
            info.isChecked = checked
        }
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        applyAutoColors()
    }

    /** XML 里 android:checked 的初值：不播动画、不回调 */
    private fun setCheckedImmediately() {
        if (!isCheckable) return
        checked = true
        checkedFraction = 1f
        updateLeadingIcon()
        applyAutoColors()
        invalidate()
    }

    private fun animateChecked(target: Boolean) {
        checkedAnimator?.cancel()
        val end = if (target) 1f else 0f
        if (!isAttachedToWindow) {
            checkedFraction = end
            invalidate()
            return
        }
        checkedAnimator = ValueAnimator.ofFloat(checkedFraction, end).apply {
            duration = CHECK_ANIM_MS
            addUpdateListener {
                checkedFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateLeadingIcon() {
        val icon = if (checked && isCheckedIconVisible) checkIcon else chipIcon
        chipIconView.setImageDrawable(icon)
        chipIconView.visibility = if (icon == null) View.GONE else View.VISIBLE
        updatePadding()
        applyContentColor()
    }

    /** 两端留白：挨着图标的一侧收窄，胶囊两头的弧度才不会显得空 */
    private fun updatePadding() {
        val start = if (chipIconView.visibility == View.VISIBLE) dp(8) else dp(14)
        val end = if (closeIconView.visibility == View.VISIBLE) dp(2) else dp(14)
        row.setPaddingRelative(start, 0, end, 0)
    }

    /** 暗背景白字加投影、亮背景深色字；选中时按 [checkedTint] 的深浅决定 */
    private fun applyAutoColors() {
        if (!autoColors) return
        val lightBehind = if (checked) {
            luminance(checkedTint) > 0.6f
        } else {
            isOverLightBackground
        }
        if (lightBehind) {
            contentColor = 0xDE000000.toInt()
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            contentColor = Color.WHITE
            textView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        textView.setTextColor(contentColor)
        applyContentColor()
    }

    private fun applyContentColor() {
        // 对勾总跟文字同色；自带的 chipIcon 看 isChipIconTintEnabled
        val showingCheck = checked && isCheckedIconVisible
        if (showingCheck || isChipIconTintEnabled) {
            chipIconView.setColorFilter(contentColor)
        } else {
            chipIconView.clearColorFilter()
        }
        closeIconView.setColorFilter(contentColor)
    }

    private fun updateCloseDescription() {
        closeIconView.contentDescription =
            context.getString(R.string.liquid_glass_chip_close_description, textView.text)
    }

    private fun luminance(color: Int): Float =
        (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f

    /** 两个 straight-alpha 颜色按预乘插值（一端透明时不会先变暗再变亮） */
    private fun blendArgb(from: Int, to: Int, t: Float): Int {
        val a0 = Color.alpha(from) / 255f
        val a1 = Color.alpha(to) / 255f
        val a = a0 + (a1 - a0) * t
        if (a <= 0f) return Color.TRANSPARENT
        fun ch(c0: Int, c1: Int) = ((c0 * a0 + (c1 * a1 - c0 * a0) * t) / a).roundToInt().coerceIn(0, 255)
        return Color.argb(
            (a * 255f).roundToInt(),
            ch(Color.red(from), Color.red(to)),
            ch(Color.green(from), Color.green(to)),
            ch(Color.blue(from), Color.blue(to))
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** [checkedTint] 的默认值：40% 的 iOS 系统蓝 */
        const val DEFAULT_CHECKED_TINT = 0x660A84FF
        private const val CHECK_ANIM_MS = 160L
    }
}
