/**
 * 玻璃 Chip 组：装 [LiquidGlassChip] 的流式容器，对应 Material 的 ChipGroup
 *
 * - 排布：一行放不下自动换行，间距 [chipSpacingHorizontal] / [chipSpacingVertical]；
 *   [isSingleLine] = true 时排成一行不换行（放进 HorizontalScrollView 横向滑动）
 * - 选择：[isSingleSelection] 单选互斥；[isSelectionRequired] 至少保留一个选中（点最后一个
 *   选中的 chip 不会取消）。只对 isCheckable 的 chip 生效，变化走 [setOnCheckedStateChangeListener]
 * - 背景：组本身是透明容器，chip 默认"捕获直接父容器"在这里拍不到东西，所以背景来源由
 *   [backdropSource] 下发到每个 chip（与 [LiquidGlassListGroup] 同一套）；[enableDynamicBackground] 同理
 *
 * ```xml
 * <com.danielzn.liquidglass.LiquidGlassChipGroup
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:glassSingleSelection="true"
 *     app:backdropSourceId="@id/wallpaper">
 *     <com.danielzn.liquidglass.LiquidGlassChip ... android:text="All" android:checkable="true" />
 *     <com.danielzn.liquidglass.LiquidGlassChip ... android:text="Photos" android:checkable="true" />
 * </com.danielzn.liquidglass.LiquidGlassChipGroup>
 * ```
 * ```kotlin
 * group.setOnCheckedStateChangeListener { group, checkedIds -> ... }
 * ```
 */
package com.danielzn.liquidglass

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

open class LiquidGlassChipGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    fun interface OnCheckedStateChangeListener {
        fun onCheckedChanged(group: LiquidGlassChipGroup, checkedIds: List<Int>)
    }

    /** 同一行里 chip 之间的间距（px），默认 8dp */
    var chipSpacingHorizontal: Int = dp(8)
        set(value) {
            field = value.coerceAtLeast(0)
            requestLayout()
        }

    /** 行与行之间的间距（px），默认 8dp */
    var chipSpacingVertical: Int = dp(8)
        set(value) {
            field = value.coerceAtLeast(0)
            requestLayout()
        }

    /** 排成一行不换行（外面套 HorizontalScrollView 横向滑动） */
    var isSingleLine = false
        set(value) {
            field = value
            requestLayout()
        }

    /** 单选：选中一个时其余自动取消。打开时如果已经选了多个，只保留第一个 */
    var isSingleSelection = false
        set(value) {
            field = value
            if (value) {
                val first = checkedChips().firstOrNull()
                if (first != null) onChipCheckedChanged(first, true)
            }
        }

    /** 至少保留一个选中：点最后一个选中的 chip 不会取消（代码里 [clearCheck] 不受限制） */
    var isSelectionRequired = false

    /** 下发给每个 chip 的背景来源；组是透明容器，不指定的话 chip 拍到的是空的 */
    var backdropSource: View? = null
        set(value) {
            field = value
            pendingBackdropSourceId = 0
            forEachChip { it.backdropSource = value }
        }

    /** 下发给每个 chip 的动态背景开关 */
    var enableDynamicBackground: Boolean = false
        set(value) {
            field = value
            forEachChip { it.enableDynamicBackground = value }
        }

    /** XML 里 app:backdropSourceId 指定的 id，挂载后从根视图解析 */
    private var pendingBackdropSourceId = 0

    private var checkedListener: OnCheckedStateChangeListener? = null

    /** 单选互斥时连带取消别的 chip，这期间不逐个回调，最后统一回调一次 */
    private var updatingChecks = false

    /** 选中的 chip id，按 chip 在组里的顺序 */
    val checkedChipIds: List<Int>
        get() = checkedChips().map { it.id }

    /** 单选模式下选中的那个 chip 的 id；没选中或不是单选模式时为 [View.NO_ID] */
    val checkedChipId: Int
        get() = if (isSingleSelection) checkedChips().firstOrNull()?.id ?: View.NO_ID else View.NO_ID

    init {
        attrs?.let { parseGroupAttributes(context, it) }
    }

    private fun parseGroupAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassChipGroup)
        try {
            chipSpacingHorizontal = ta.getDimensionPixelSize(
                R.styleable.LiquidGlassChipGroup_glassChipSpacingHorizontal, chipSpacingHorizontal
            )
            chipSpacingVertical = ta.getDimensionPixelSize(
                R.styleable.LiquidGlassChipGroup_glassChipSpacingVertical, chipSpacingVertical
            )
            isSingleLine = ta.getBoolean(R.styleable.LiquidGlassChipGroup_glassSingleLine, isSingleLine)
            isSingleSelection = ta.getBoolean(R.styleable.LiquidGlassChipGroup_glassSingleSelection, isSingleSelection)
            isSelectionRequired = ta.getBoolean(R.styleable.LiquidGlassChipGroup_glassSelectionRequired, isSelectionRequired)
            pendingBackdropSourceId = ta.getResourceId(R.styleable.LiquidGlassChipGroup_backdropSourceId, 0)
        } finally {
            ta.recycle()
        }
    }

    /** 横纵间距一起设（px） */
    fun setChipSpacing(px: Int) {
        chipSpacingHorizontal = px
        chipSpacingVertical = px
    }

    fun setOnCheckedStateChangeListener(listener: OnCheckedStateChangeListener?) {
        checkedListener = listener
    }

    /** 选中指定 id 的 chip（要求它 isCheckable） */
    fun check(id: Int) {
        (findViewById<View>(id) as? LiquidGlassChip)?.takeIf { it.parent === this }?.isChecked = true
    }

    /** 全部取消选中 */
    fun clearCheck() {
        val before = checkedChips()
        if (before.isEmpty()) return
        updatingChecks = true
        try {
            before.forEach { it.isChecked = false }
        } finally {
            updatingChecks = false
        }
        notifyCheckedChanged()
    }

    /** 选中要求至少保留一个时，[chip] 能不能被点掉 */
    internal fun canUncheck(chip: LiquidGlassChip): Boolean =
        !isSelectionRequired || !chip.isChecked || checkedChips().size > 1

    internal fun onChipCheckedChanged(chip: LiquidGlassChip, checked: Boolean) {
        if (updatingChecks) return
        if (checked && isSingleSelection) {
            updatingChecks = true
            try {
                forEachChip { if (it !== chip && it.isChecked) it.isChecked = false }
            } finally {
                updatingChecks = false
            }
        }
        notifyCheckedChanged()
    }

    private fun notifyCheckedChanged() {
        checkedListener?.onCheckedChanged(this, checkedChipIds)
    }

    private fun checkedChips(): List<LiquidGlassChip> {
        val result = ArrayList<LiquidGlassChip>()
        forEachChip { if (it.isChecked) result += it }
        return result
    }

    private inline fun forEachChip(block: (LiquidGlassChip) -> Unit) {
        for (i in 0 until childCount) {
            (getChildAt(i) as? LiquidGlassChip)?.let(block)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 与 LiquidGlassView 同一套：整棵树 inflate 完才能按 id 找到
        if (backdropSource == null && pendingBackdropSourceId != 0) {
            val id = pendingBackdropSourceId
            rootView?.findViewById<View>(id)?.let { backdropSource = it }
                ?: Log.w(TAG, "backdropSourceId 未在视图树中找到")
            pendingBackdropSourceId = 0
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child !is LiquidGlassChip) return
        // 选中状态按 id 汇报，没 id 的补一个
        if (child.id == View.NO_ID) child.id = View.generateViewId()
        backdropSource?.let { child.backdropSource = it }
        if (enableDynamicBackground) child.enableDynamicBackground = true
        if (child.isChecked) onChipCheckedChanged(child, true)
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        if (child is LiquidGlassChip && child.isChecked) notifyCheckedChanged()
    }

    // ==================== 流式排布 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val limit = if (isSingleLine || widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            widthSize - paddingEnd
        }
        var x = paddingStart
        var y = paddingTop
        var lineHeight = 0
        var maxRight = paddingStart
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.marginStart + lp.marginEnd
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingStart && x + w > limit) {
                x = paddingStart
                y += lineHeight + chipSpacingVertical
                lineHeight = 0
            }
            x += w
            maxRight = max(maxRight, x)
            lineHeight = max(lineHeight, h)
            x += chipSpacingHorizontal
        }
        setMeasuredDimension(
            resolveSize(maxRight + paddingEnd, widthMeasureSpec),
            resolveSize(y + lineHeight + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val limit = if (isSingleLine) Int.MAX_VALUE else width - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val w = child.measuredWidth + lp.marginStart + lp.marginEnd
            val h = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > paddingStart && x + w > limit) {
                x = paddingStart
                y += lineHeight + chipSpacingVertical
                lineHeight = 0
            }
            // 按"起始边"排，RTL 时整体镜像
            val start = x + lp.marginStart
            val left = if (rtl) width - start - child.measuredWidth else start
            val top = y + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
            x += w + chipSpacingHorizontal
            lineHeight = max(lineHeight, h)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "LiquidGlassChipGroup"
    }
}
