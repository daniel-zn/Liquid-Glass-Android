/**
 * 玻璃标签页：Material TabLayout 那一类的横向标签条，整条是一块玻璃，选中指示是一颗玻璃滴
 *
 * - [MODE_FIXED]：标签平分宽度；[MODE_SCROLLABLE]：标签按内容宽度排开，放不下时整排横向滑动，
 *   选中的标签自动滚到中间
 * - 选中指示与 [LiquidGlassTabBar] 同一种玻璃滴（折射底下的标签文字）：点击切换时带过冲和液态
 *   拉伸滑过去，宽度在标签之间插值；[setScrollPosition] 让它逐帧跟着翻页进度走
 * - 与 ViewPager2 联动用 [LiquidGlassTabLayoutMediator]，写法同 Material 的 TabLayoutMediator
 *
 * ```kotlin
 * tabLayout.tabMode = LiquidGlassTabLayout.MODE_SCROLLABLE
 * LiquidGlassTabLayoutMediator(tabLayout, viewPager) { tab, position ->
 *     tab.text = titles[position]
 * }.attach()
 * ```
 * 不用 ViewPager2 时直接 [addTab]，用 [addOnTabSelectedListener] 监听切换。
 * XML 纯文字标签可用 `app:glassTabEntries="@array/my_tabs"`，模式用 `app:glassTabMode`。
 */
package com.danielzn.liquidglass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

open class LiquidGlassTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LiquidGlassView(context, attrs, defStyleAttr) {

    /** 一个标签：文字 + 可选图标（图标在文字前面）。用 [newTab] 创建、[addTab] 加进来 */
    class Tab internal constructor(internal val owner: LiquidGlassTabLayout) {

        internal val view = LinearLayout(owner.context)
        internal val iconView = ImageView(owner.context)
        internal val labelView = TextView(owner.context)

        var text: CharSequence? = null
            set(value) {
                field = value
                labelView.text = value
                labelView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
                onContentChanged()
            }

        /** 图标，默认按文字颜色着色 */
        var icon: Drawable? = null
            set(value) {
                field = value
                iconView.setImageDrawable(value)
                iconView.visibility = if (value == null) View.GONE else View.VISIBLE
                onContentChanged()
            }

        /** 无障碍描述；不设时读文字 */
        var contentDescription: CharSequence? = null
            set(value) {
                field = value
                onContentChanged()
            }

        /** 使用方自己挂的数据 */
        var tag: Any? = null

        /** 在标签条里的下标；还没加进去时为 -1 */
        val position: Int
            get() = owner.indexOfTab(this)

        val isSelected: Boolean
            get() {
                val p = position
                return p >= 0 && p == owner.selectedTabPosition
            }

        fun select() = owner.selectTab(this)

        init {
            val d = owner.resources.displayMetrics.density
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER
            iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
            iconView.visibility = View.GONE
            view.addView(iconView, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
            labelView.textSize = 14f
            labelView.maxLines = 1
            labelView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            labelView.visibility = View.GONE
            view.addView(labelView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        private fun onContentChanged() {
            // 图标和文字都在时中间留 6dp
            val gap = if (iconView.visibility == View.VISIBLE && labelView.visibility == View.VISIBLE) {
                (6 * owner.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            (iconView.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
            view.contentDescription = contentDescription ?: text
            owner.onTabContentChanged()
        }
    }

    fun interface OnTabSelectedListener {
        fun onTabSelected(tab: Tab)
    }

    fun interface OnTabReselectedListener {
        fun onTabReselected(tab: Tab)
    }

    /** [MODE_FIXED]（平分宽度）或 [MODE_SCROLLABLE]（按内容宽度排开，可横向滑动） */
    var tabMode: Int = MODE_FIXED
        set(value) {
            if (field == value) return
            field = value
            scroller.fixed = value == MODE_FIXED
            tabs.forEach { applyTabLayout(it) }
            requestLayout()
        }

    /** 选中项文字 / 图标颜色（默认 null = 跟随背景明暗用白 / 黑） */
    var selectedTintColor: Int? = null
        set(value) {
            if (field != value) {
                field = value
                updateTabStyles()
            }
        }

    val tabCount: Int
        get() = tabs.size

    /** 当前选中的下标；没有标签时为 -1 */
    val selectedTabPosition: Int
        get() = selected

    private val tabs = ArrayList<Tab>()
    private var selected = -1

    private val scroller = TabScroller(context)
    private val tabsRow = LinearLayout(context)

    /** 玻璃滴指示：真玻璃，折射标签行的内容；不接触摸，点在它上面等于点底下的标签 */
    private val droplet = Droplet(context)

    /** 指示器位置：标签下标 + 小数（2.3 = 从第 2 个往第 3 个走了 30%），几何由它和当前布局算出 */
    private var indicatorPos = 0f
    private var indicatorAnimator: ValueAnimator? = null

    private var overLightAppearance = false

    private val selectedListeners = ArrayList<OnTabSelectedListener>()
    private val reselectedListeners = ArrayList<OnTabReselectedListener>()

    init {
        // 整条的按压缩放没有意义，点击反馈交给玻璃滴
        enablePressEffect = false

        val pad = dp(4)
        setPadding(pad, pad, pad, pad)

        tabsRow.orientation = LinearLayout.HORIZONTAL
        scroller.isHorizontalScrollBarEnabled = false
        scroller.overScrollMode = View.OVER_SCROLL_NEVER
        scroller.isFillViewport = true
        scroller.fixed = true
        scroller.addView(tabsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroller.setOnScrollChangeListener { _, _, _, _, _ ->
            // 手动滑标签行时玻璃滴跟着它的标签走（滑出去的部分被外轮廓裁掉），动画中才钳在可见范围里
            placeDroplet(clamp = indicatorAnimator?.isRunning == true)
        }

        droplet.apply {
            enablePressEffect = false
            // 玻璃滴内文字保持清晰：只折射不模糊；斜面窄、折射浅，否则贴边的文字会被折射出放大的副本
            enableBackdropBlur = false
            cornerRadius = 999f
            bevelWidth = dpF(8)
            refractionHeight = dpF(4)
            dispersionStrength = 0.04f
            visibility = View.GONE
        }
        // 玻璃滴叠在标签行上方、折射行内容，必须后 add；背景保持默认的直接父容器（= 标签条自身）。
        // 位置用 translation 驱动，布局锚点用绝对的左上角，RTL 下换算也不变
        addView(droplet, LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))

        // 按玻璃形状裁子视图：玻璃滴滑到胶囊两头的弧形之外时，那部分采到的是透明，不裁会发黑
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = cornerRadiiPx().minOrNull() ?: 0f
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true

        overLightAppearance = isOverLightBackground
        attrs?.let { parseTabLayoutAttributes(context, it) }
    }

    private fun parseTabLayoutAttributes(context: Context, attrs: AttributeSet) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassTabLayout)
        try {
            tabMode = if (ta.getInt(R.styleable.LiquidGlassTabLayout_glassTabMode, 0) == 1) {
                MODE_SCROLLABLE
            } else {
                MODE_FIXED
            }
            val entriesId = ta.getResourceId(R.styleable.LiquidGlassTabLayout_glassTabEntries, 0)
            if (entriesId != 0) resources.getTextArray(entriesId).forEach { addTab(it) }
        } finally {
            ta.recycle()
        }
    }

    // ==================== 标签增删 ====================

    fun newTab(): Tab = Tab(this)

    /** 加一个标签；默认第一个加进来的自动选中（不触发回调以外的动画） */
    @JvmOverloads
    fun addTab(tab: Tab, setSelected: Boolean = tabs.isEmpty()) {
        require(tab.owner === this) { "Tab belongs to a different LiquidGlassTabLayout" }
        if (tab in tabs) return
        tabs += tab
        applyTabLayout(tab)
        tab.view.setOnClickListener { selectTab(tab) }
        tabsRow.addView(tab.view)
        updateTabStyles()
        if (setSelected) selectTab(tabs.size - 1, animate = false)
    }

    /** 纯文字标签的便捷写法，返回新建的标签 */
    fun addTab(text: CharSequence): Tab = newTab().also {
        it.text = text
        addTab(it)
    }

    fun getTabAt(index: Int): Tab? = tabs.getOrNull(index)

    fun removeTabAt(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tabs.removeAt(index)
        tabsRow.removeView(tab.view)
        when {
            tabs.isEmpty() -> {
                selected = -1
                indicatorAnimator?.cancel()
                droplet.visibility = View.GONE
            }
            index < selected -> {
                selected--
                indicatorPos = selected.toFloat()
            }
            index == selected -> {
                selected = -1
                selectTab(min(index, tabs.size - 1), animate = false)
            }
        }
        updateTabStyles()
    }

    fun removeAllTabs() {
        tabs.clear()
        tabsRow.removeAllViews()
        selected = -1
        indicatorAnimator?.cancel()
        droplet.visibility = View.GONE
    }

    internal fun indexOfTab(tab: Tab): Int = tabs.indexOf(tab)

    internal fun onTabContentChanged() {
        updateTabStyles()
        requestLayout()
    }

    fun addOnTabSelectedListener(listener: OnTabSelectedListener) {
        if (listener !in selectedListeners) selectedListeners += listener
    }

    fun removeOnTabSelectedListener(listener: OnTabSelectedListener) {
        selectedListeners -= listener
    }

    fun addOnTabReselectedListener(listener: OnTabReselectedListener) {
        if (listener !in reselectedListeners) reselectedListeners += listener
    }

    fun removeOnTabReselectedListener(listener: OnTabReselectedListener) {
        reselectedListeners -= listener
    }

    // ==================== 选择 ====================

    fun selectTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index >= 0) selectTab(index, animate = true)
    }

    /** 选中第 [index] 个；已经选中时走 reselect 回调 */
    @JvmOverloads
    fun selectTab(index: Int, animate: Boolean = true) {
        val tab = tabs.getOrNull(index) ?: return
        if (index == selected) {
            ArrayList(reselectedListeners).forEach { it.onTabReselected(tab) }
            return
        }
        val first = selected < 0
        selected = index
        updateTabStyles()
        if (animate && !first && isLaidOut) {
            animateIndicatorTo(index)
        } else {
            indicatorAnimator?.cancel()
            indicatorPos = index.toFloat()
            placeDroplet(clamp = true)
        }
        scrollToPosition(index.toFloat(), smooth = animate)
        ArrayList(selectedListeners).forEach { it.onTabSelected(tab) }
    }

    /**
     * 按翻页进度摆玻璃滴：[position] 是当前页，[positionOffset] 是往下一页走了多少（0–1）。
     * 可滑动模式下标签行同时滚动，让玻璃滴留在中间。
     *
     * @param updateSelectedTab 为 true 时选中态（文字高亮）跟着过半的那一页走，不触发回调；
     *                          [LiquidGlassTabLayoutMediator] 传 false，翻页落定时再正式选中
     */
    @JvmOverloads
    fun setScrollPosition(position: Int, positionOffset: Float, updateSelectedTab: Boolean = true) {
        if (position !in tabs.indices) return
        indicatorAnimator?.cancel()
        droplet.scaleX = 1f
        droplet.scaleY = 1f
        val offset = if (position < tabs.size - 1) positionOffset.coerceIn(0f, 1f) else 0f
        indicatorPos = position + offset
        if (updateSelectedTab) {
            val nearest = if (offset >= 0.5f) position + 1 else position
            if (nearest != selected) {
                selected = nearest
                updateTabStyles()
            }
        }
        placeDroplet(clamp = true)
        scrollToPosition(indicatorPos, smooth = false)
    }

    private fun updateTabStyles() {
        val selectedColor = selectedTintColor
            ?: if (overLightAppearance) 0xE6000000.toInt() else Color.WHITE
        val normalColor = if (overLightAppearance) 0x8C000000.toInt() else 0xB8FFFFFF.toInt()
        tabs.forEachIndexed { index, tab ->
            val color = if (index == selected) selectedColor else normalColor
            tab.labelView.setTextColor(color)
            tab.iconView.imageTintList = ColorStateList.valueOf(color)
            tab.view.isSelected = index == selected
        }
        // 玻璃滴折射的是行内容，配色变了要重采一帧
        droplet.invalidate()
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        overLightAppearance = isOverLight
        droplet.overLight = isOverLight
        updateTabStyles()
    }

    private fun applyTabLayout(tab: Tab) {
        val fixed = tabMode == MODE_FIXED
        tab.view.layoutParams = if (fixed) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val h = if (fixed) dp(8) else dp(16)
        tab.view.setPadding(h, dp(10), h, dp(10))
        tab.view.minimumWidth = if (fixed) 0 else dp(72)
        tab.labelView.ellipsize = if (fixed) android.text.TextUtils.TruncateAt.END else null
    }

    // ==================== 玻璃滴定位与动画 ====================

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        invalidateOutline()
        // 布局完成后标签尺寸才可用；动画中由动画自己摆
        if (indicatorAnimator?.isRunning != true) placeDroplet(clamp = true)
    }

    /** [pos] 处指示器在标签行坐标里的左边和宽度；两端外推（过冲时会超出首尾标签一点） */
    private fun indicatorGeometry(pos: Float): Pair<Float, Float> {
        val last = tabs.size - 1
        val i = floor(pos).toInt().coerceIn(0, last)
        val f = pos - i
        val a = tabs[i].view
        val next = if (i < last) tabs[i + 1].view else null
        return if (next != null) {
            // 两个标签之间：左边和宽度都线性插值（pos < 0 时在第一个标签左边外推）
            (a.left + (next.left - a.left) * f) to (a.width + (next.width - a.width) * f)
        } else {
            // 最后一个标签再往外：按它自己的宽度外推
            (a.left + a.width * f) to a.width.toFloat()
        }
    }

    /** 把指示器位置换算成本视图坐标，摆好玻璃滴 */
    private fun placeDroplet(clamp: Boolean) {
        val ref = tabs.firstOrNull()?.view
        if (selected < 0 || ref == null || ref.width <= 0) {
            droplet.visibility = View.GONE
            return
        }
        droplet.visibility = View.VISIBLE
        val (x, w) = indicatorGeometry(indicatorPos)
        resizeDroplet(w.roundToInt().coerceAtLeast(1), ref.height)
        var tx = scroller.left + tabsRow.left + x - scroller.scrollX - paddingLeft
        if (clamp) tx = clampDropletX(tx, droplet.scaleX)
        droplet.translationX = tx
        droplet.translationY = (scroller.top + tabsRow.top + ref.top - paddingTop).toFloat()
        // 折射内容取决于滴与标签行的相对位置，移动后必须重采
        droplet.invalidate()
    }

    /** 尺寸当场量好摆好，不等下一次 layout：跟翻页进度时宽度逐帧在变 */
    private fun resizeDroplet(w: Int, h: Int) {
        val lp = droplet.layoutParams as LayoutParams
        if (lp.width == w && lp.height == h && droplet.width == w && droplet.height == h) return
        lp.width = w
        lp.height = h
        droplet.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        droplet.layout(paddingLeft, paddingTop, paddingLeft + w, paddingTop + h)
    }

    /**
     * 按当前横向拉伸量把玻璃滴的**可视**边缘钳在标签行的可见范围里
     * （过冲 / 拉伸推出胶囊两头时像液体抵住壁）
     */
    private fun clampDropletX(x: Float, scaleX: Float): Float {
        val w = droplet.width.toFloat()
        if (w <= 0f || scroller.width <= 0) return x
        val bulge = (scaleX - 1f) * w / 2f
        val minX = (scroller.left - paddingLeft) + bulge
        val maxX = (scroller.right - paddingLeft) - w - bulge
        return if (minX <= maxX) x.coerceIn(minX, maxX) else x
    }

    /** 玻璃滴滑到指定标签：过冲弹性 + 跨得越远液态拉伸越明显（与 LiquidGlassTabBar 同一手感） */
    private fun animateIndicatorTo(index: Int) {
        indicatorAnimator?.cancel()
        val start = indicatorPos
        val dist = index - start
        if (abs(dist) < 0.001f) {
            indicatorPos = index.toFloat()
            placeDroplet(clamp = true)
            return
        }
        val stretch = 0.22f * min(1f, abs(dist) / 3f)
        val overshoot = OvershootInterpolator(1.1f)
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val s = sin(PI.toFloat() * min(t * 1.15f, 1f))
                droplet.scaleX = 1f + stretch * s
                droplet.scaleY = 1f - stretch * 0.55f * s
                indicatorPos = start + dist * overshoot.getInterpolation(t)
                placeDroplet(clamp = true)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // cancel() 也会走到这里：中途改道时不能落位，否则滴会瞬移到新目标
                    if (canceled) return
                    droplet.scaleX = 1f
                    droplet.scaleY = 1f
                    indicatorPos = index.toFloat()
                    placeDroplet(clamp = true)
                }
            })
            start()
        }
    }

    /** 可滑动模式：把 [pos] 处的指示器滚到可见范围中间 */
    private fun scrollToPosition(pos: Float, smooth: Boolean) {
        if (tabMode != MODE_SCROLLABLE || tabs.isEmpty() || scroller.width <= 0) return
        val (x, w) = indicatorGeometry(pos)
        val viewport = scroller.width - scroller.paddingLeft - scroller.paddingRight
        val max = (tabsRow.width - viewport).coerceAtLeast(0)
        val target = (tabsRow.left + x + w / 2f - viewport / 2f).roundToInt().coerceIn(0, max)
        if (smooth) scroller.smoothScrollTo(target, 0) else scroller.scrollTo(target, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    /** 平分模式下把标签行量成正好一屏宽（HorizontalScrollView 默认按内容宽度量子视图） */
    private class TabScroller(context: Context) : HorizontalScrollView(context) {
        var fixed = false

        override fun measureChildWithMargins(
            child: View,
            parentWidthMeasureSpec: Int,
            widthUsed: Int,
            parentHeightMeasureSpec: Int,
            heightUsed: Int
        ) {
            if (!fixed) {
                super.measureChildWithMargins(
                    child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed
                )
                return
            }
            val lp = child.layoutParams as MarginLayoutParams
            val w = (MeasureSpec.getSize(parentWidthMeasureSpec) - paddingLeft - paddingRight -
                lp.leftMargin - lp.rightMargin - widthUsed).coerceAtLeast(0)
            val heightSpec = getChildMeasureSpec(
                parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
                lp.height
            )
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec)
        }
    }

    /** 玻璃滴不接触摸：落在它上面的点击 / 滑动交给底下的标签行 */
    private class Droplet(context: Context) : LiquidGlassView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    companion object {
        /** 标签平分宽度 */
        const val MODE_FIXED = 0

        /** 标签按内容宽度排开，放不下时横向滑动 */
        const val MODE_SCROLLABLE = 1
    }
}
