/**
 * 把 [LiquidGlassTabLayout] 和 ViewPager2 绑在一起，用法与 Material 的 TabLayoutMediator 相同：
 * 标签按页生成（在 [TabConfigurationStrategy] 里设文字 / 图标），拖动翻页时玻璃滴跟着进度走，
 * 点标签翻页；[autoRefresh] 开着时 adapter 数据变了会重建标签。
 *
 * 需要应用自己依赖 androidx.viewpager2（本库对它是 compileOnly，不会带进来）。
 *
 * ```kotlin
 * LiquidGlassTabLayoutMediator(tabLayout, viewPager) { tab, position ->
 *     tab.text = titles[position]
 * }.attach()
 * ```
 * ```java
 * new LiquidGlassTabLayoutMediator(tabLayout, viewPager,
 *         (tab, position) -> tab.setText(titles[position])).attach();
 * ```
 */
package com.danielzn.liquidglass

import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class LiquidGlassTabLayoutMediator @JvmOverloads constructor(
    private val tabLayout: LiquidGlassTabLayout,
    private val viewPager: ViewPager2,
    private val autoRefresh: Boolean = true,
    private val smoothScroll: Boolean = true,
    private val strategy: TabConfigurationStrategy
) {

    /** 为第 position 页配置标签（文字、图标、tag） */
    fun interface TabConfigurationStrategy {
        fun onConfigureTab(tab: LiquidGlassTabLayout.Tab, position: Int)
    }

    private var adapter: RecyclerView.Adapter<*>? = null

    var isAttached = false
        private set

    /** 翻页引起的选中不再回头去翻页 */
    private var selectingFromPager = false

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        private var previousState = ViewPager2.SCROLL_STATE_IDLE
        private var scrollState = ViewPager2.SCROLL_STATE_IDLE

        override fun onPageScrollStateChanged(state: Int) {
            previousState = scrollState
            scrollState = state
        }

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            // 手指拖动、以及松手后的惯性阶段跟着进度走；点标签引起的平滑翻页不跟（玻璃滴自己在走动画）
            val follow = scrollState != ViewPager2.SCROLL_STATE_SETTLING ||
                previousState == ViewPager2.SCROLL_STATE_DRAGGING
            if (follow) tabLayout.setScrollPosition(position, positionOffset, updateSelectedTab = false)
        }

        override fun onPageSelected(position: Int) {
            if (position == tabLayout.selectedTabPosition || position >= tabLayout.tabCount) return
            // 拖动翻页时玻璃滴已经跟到位，不再补一段动画；代码直接跳页时才动画过去
            val animate = scrollState == ViewPager2.SCROLL_STATE_IDLE ||
                (scrollState == ViewPager2.SCROLL_STATE_SETTLING && previousState == ViewPager2.SCROLL_STATE_IDLE)
            selectingFromPager = true
            try {
                tabLayout.selectTab(position, animate)
            } finally {
                selectingFromPager = false
            }
        }
    }

    private val tabListener = LiquidGlassTabLayout.OnTabSelectedListener { tab ->
        if (!selectingFromPager) viewPager.setCurrentItem(tab.position, smoothScroll)
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = populateTabs()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = populateTabs()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = populateTabs()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = populateTabs()
    }

    /** 绑定：ViewPager2 必须已经设好 adapter */
    fun attach() {
        check(!isAttached) { "LiquidGlassTabLayoutMediator is already attached" }
        val pagerAdapter = checkNotNull(viewPager.adapter) {
            "LiquidGlassTabLayoutMediator attached before ViewPager2 has an adapter"
        }
        adapter = pagerAdapter
        isAttached = true
        viewPager.registerOnPageChangeCallback(pageCallback)
        tabLayout.addOnTabSelectedListener(tabListener)
        if (autoRefresh) pagerAdapter.registerAdapterDataObserver(dataObserver)
        populateTabs()
        tabLayout.setScrollPosition(viewPager.currentItem, 0f, updateSelectedTab = true)
    }

    /** 解绑：标签保留，之后不再联动 */
    fun detach() {
        if (!isAttached) return
        if (autoRefresh) adapter?.unregisterAdapterDataObserver(dataObserver)
        tabLayout.removeOnTabSelectedListener(tabListener)
        viewPager.unregisterOnPageChangeCallback(pageCallback)
        adapter = null
        isAttached = false
    }

    private fun populateTabs() {
        tabLayout.removeAllTabs()
        val count = adapter?.itemCount ?: 0
        for (i in 0 until count) {
            val tab = tabLayout.newTab()
            strategy.onConfigureTab(tab, i)
            tabLayout.addTab(tab, setSelected = false)
        }
        if (count > 0) {
            selectingFromPager = true
            try {
                tabLayout.selectTab(viewPager.currentItem.coerceIn(0, count - 1), animate = false)
            } finally {
                selectingFromPager = false
            }
        }
    }
}
