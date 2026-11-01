/**
 * 玻璃 Toast：自带一个 window 的短消息条，压在 Activity 和它打开的弹层之上
 *
 * 不走系统 [Toast]：Toast window 里的玻璃采不到下面的界面，而且 API 30 起自定义 Toast
 * 视图已废弃、应用在后台时不再显示。这里用 Activity 的 WindowManager 加一个不可触摸、
 * 不抢焦点的应用 window，和系统 Toast 一样盖在 Dialog / BottomSheetDialog 上面；之后再
 * 打开的弹层盖过来时，把自己挪回最上层。玻璃的背景是底下各层 window（Activity、弹层
 * 及其变暗层）按层叠顺序拼出来的画面（见 [WindowStackBackdrop]）。
 * 淡入、停留、淡出后自动移除，Activity 销毁时随之移除；同一时刻只保留一条，
 * 再次 show 会顶掉上一条。
 *
 * API 沿用 Toast 的习惯：
 * ```kotlin
 * LiquidGlassToast.makeText(this, "Saved", LiquidGlassToast.LENGTH_SHORT).show()
 * LiquidGlassToast.makeText(this, R.string.done, LiquidGlassToast.LENGTH_LONG)
 *     .setIconResource(R.drawable.ic_check)
 *     .setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 24)
 *     .show()
 * ```
 * ```java
 * LiquidGlassToast.makeText(activity, "Saved", LiquidGlassToast.LENGTH_SHORT).show();
 * ```
 * 需要 Activity 的 Context：Fragment 里传 requireActivity()，View 里传 view.context，
 * Dialog / BottomSheetDialog 里传它自己的 context（会顺着找到所属的 Activity）。
 */
package com.danielzn.liquidglass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

class LiquidGlassToast private constructor(private val activity: Activity) {

    // 先于 glass 初始化：玻璃的明暗回调会碰它们
    val textView: TextView = TextView(activity)
    val imageView: ImageView = ImageView(activity)

    /** 玻璃本体，透镜参数在这上面调 */
    val glass: LiquidGlassView = ToastGlassView(activity)

    /** [LENGTH_SHORT] / [LENGTH_LONG]，与 [Toast] 的常量相同 */
    var duration: Int = LENGTH_SHORT

    /** 自定义停留时长（ms）；大于 0 时覆盖 [duration] */
    var durationMillis: Long = 0L

    private var gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    private var xOffset = 0
    private var yOffset = dp(64)

    /** 显式设置过文字颜色后不再跟随背景明暗自动切换 */
    private var autoColors = true
    private var iconTintEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { cancel() }
    private var dismissing = false

    private val windowManager: WindowManager = activity.windowManager

    /** window 的根视图：与屏幕同宽，高度包住玻璃，上下各多留一段给滑入动画 */
    private val frame = FrameLayout(activity)

    private val windowParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION
        // 不是全屏 window：全屏的应用 window 会被拿去决定状态栏 / 导航栏的深浅样式
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        format = PixelFormat.TRANSLUCENT
        // ALT_FOCUSABLE_IM 与 NOT_FOCUSABLE 同时设：仍不抢焦点，但层级排在输入法之下、收得到输入法的
        // insets，键盘弹出时落在键盘上方（只设 NOT_FOCUSABLE 会压在键盘上，玻璃又采不到键盘）。
        // NO_LIMITS：偏移小于动画余量时，window 可以伸出可用区域，玻璃仍落在要求的位置
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // window 动画是 SurfaceFlinger 上的变换，玻璃会按动画前的位置采样；进出场在 view 里做
        windowAnimations = 0
        title = "LiquidGlassToast"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 给系统栏和输入法让位：贴底的 Toast 在键盘弹出时落在键盘上方
            fitInsetsTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
        }
    }

    /** 玻璃的背景：底下各层 window 按层叠顺序拼出的画面 */
    private val backdrop = WindowStackBackdrop(activity)

    /** 挪到最上层时 window 会先拆下再加回，这期间的 detach 不算消失 */
    private var raising = false
    private var raisePending = false
    private var raiseCount = 0
    private val raiseRunnable = Runnable { raise() }

    /** 每帧检查一次有没有后打开的弹层盖到上面 */
    private val coverCheck = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (frame.parent == null) return
            if (!raisePending && !dismissing && raiseCount < MAX_RAISES &&
                AppWindows.isCovered(frame)
            ) {
                // 不在帧回调里增删 window
                raisePending = true
                handler.post(raiseRunnable)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    init {
        // 默认斜面是给大面板定的，一条 44dp 高的消息条按高度收一档，折射取斜面的一半
        glass.apply {
            cornerRadius = 999f
            bevelWidth = dpF(14f)
            refractionHeight = dpF(7f)
            edgeSoftness = dpF(3f)
            blurAmount = 0.25f
            enableAdaptiveTint = true
            enableDynamicBackground = true
            enablePressEffect = false
            isClickable = false
            isFocusable = false
            minimumHeight = dp(44)
            backdropSource = backdrop
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.visibility = View.GONE
        row.addView(imageView, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        textView.textSize = 14f
        textView.maxLines = 2
        textView.ellipsize = TextUtils.TruncateAt.END
        textView.includeFontPadding = false
        textView.maxWidth = dp(320)
        row.addView(textView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        glass.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        frame.addView(glass)
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // window 没了（正常收起，或 Activity 先一步销毁）：定时器和各种监听都不该再留着
                if (!raising) releaseWindow()
            }
        })
        applyAutoColors(glass.isOverLightBackground)
    }

    fun setText(text: CharSequence): LiquidGlassToast {
        textView.text = text
        return this
    }

    fun setText(@StringRes resId: Int): LiquidGlassToast = setText(activity.getText(resId))

    /** 文字左侧的小图标，默认跟文字同色；彩色图标（比如应用图标）配 [setIconTintEnabled] false */
    fun setIcon(icon: Drawable?): LiquidGlassToast {
        imageView.setImageDrawable(icon)
        imageView.visibility = if (icon == null) View.GONE else View.VISIBLE
        applyIconTint()
        return this
    }

    fun setIconResource(@DrawableRes resId: Int): LiquidGlassToast =
        setIcon(if (resId == 0) null else ContextCompat.getDrawable(activity, resId))

    fun setIconTintEnabled(enabled: Boolean): LiquidGlassToast {
        iconTintEnabled = enabled
        applyIconTint()
        return this
    }

    /**
     * 与 [Toast.setGravity] 同义：偏移量为 px，从 gravity 指定的那条边算起
     * （系统栏和输入法占住的部分不算在内）
     */
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int): LiquidGlassToast {
        this.gravity = gravity
        this.xOffset = xOffset
        this.yOffset = yOffset
        return this
    }

    fun setDuration(duration: Int): LiquidGlassToast {
        this.duration = duration
        return this
    }

    /** 显式指定文字/图标颜色（同时关闭明暗自动切换与文字阴影） */
    fun setTextColor(color: Int): LiquidGlassToast {
        autoColors = false
        textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        textView.setTextColor(color)
        applyIconTint()
        return this
    }

    fun show(): LiquidGlassToast {
        // 已销毁的 Activity 加不了 window
        if (activity.isDestroyed) return this
        current?.takeIf { it !== this }?.cancel(animate = false)
        current = this
        handler.removeCallbacks(hideRunnable)
        dismissing = false
        raiseCount = 0

        applyLayout()
        syncSecureFlag()
        if (frame.parent == null) {
            if (!addWindow()) {
                if (current === this) current = null
                return this
            }
            animateIn()
        } else {
            windowManager.updateViewLayout(frame, windowParams)
            glass.animate().cancel()
            glass.alpha = 1f
            glass.scaleX = 1f
            glass.scaleY = 1f
            glass.translationY = 0f
        }
        glass.announceForAccessibility(textView.text)
        handler.postDelayed(hideRunnable, effectiveDurationMillis())
        return this
    }

    /** 淡出后移除；没在显示时无事发生 */
    fun cancel() = cancel(animate = true)

    private fun cancel(animate: Boolean) {
        handler.removeCallbacks(hideRunnable)
        if (current === this) current = null
        if (frame.parent == null) return
        if (!animate || !glass.isAttachedToWindow) {
            removeWindow()
            return
        }
        if (dismissing) return
        dismissing = true
        glass.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .setDuration(EXIT_MS)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .setUpdateListener { glass.invalidate() }
            .withEndAction { removeWindow() }
            .start()
    }

    private fun effectiveDurationMillis(): Long = when {
        durationMillis > 0L -> durationMillis
        duration == LENGTH_LONG -> LONG_MS
        else -> SHORT_MS
    }

    /**
     * 按 gravity / 偏移摆放：竖直方向交给 window（WMS 给系统栏和输入法让位后，偏移从让出来的
     * 那条边算起），水平方向在与屏幕同宽的 window 里摆玻璃
     */
    private fun applyLayout() {
        val decor = activity.window.decorView
        frame.layoutDirection = decor.layoutDirection
        val edge = dp(16)
        val pad = dp(SLIDE_DP)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.leftMargin = edge
        lp.rightMargin = edge
        lp.topMargin = pad
        lp.bottomMargin = pad
        glass.translationX = 0f
        when (Gravity.getAbsoluteGravity(gravity, decor.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> {
                lp.gravity = Gravity.LEFT
                lp.leftMargin = edge + xOffset
            }
            Gravity.RIGHT -> {
                lp.gravity = Gravity.RIGHT
                lp.rightMargin = edge + xOffset
            }
            else -> {
                lp.gravity = Gravity.CENTER_HORIZONTAL
                glass.translationX = xOffset.toFloat()
            }
        }
        glass.layoutParams = lp

        // 玻璃上下各有 pad 的余量：贴边的两种 gravity 把它从偏移里扣掉，玻璃才落在要求的位置
        when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> {
                windowParams.gravity = Gravity.TOP
                windowParams.y = yOffset - pad
            }
            Gravity.BOTTOM -> {
                windowParams.gravity = Gravity.BOTTOM
                windowParams.y = yOffset - pad
            }
            else -> {
                windowParams.gravity = Gravity.CENTER_VERTICAL
                windowParams.y = yOffset
            }
        }
    }

    /** Activity 禁止截屏时 Toast 也禁：玻璃里折射的就是 Activity 的内容 */
    private fun syncSecureFlag() {
        val secure = WindowManager.LayoutParams.FLAG_SECURE
        windowParams.flags = if (activity.window.attributes.flags and secure != 0) {
            windowParams.flags or secure
        } else {
            windowParams.flags and secure.inv()
        }
    }

    private fun addWindow(): Boolean {
        try {
            windowManager.addView(frame, windowParams)
        } catch (e: RuntimeException) {
            // BadTokenException 等：Activity 已经不在了
            Log.w(TAG, "Unable to add the toast window", e)
            return false
        }
        // 背景来源不挂载，尺寸手动铺满 Activity 所在的屏幕范围（向外折射的采样边界要用）
        val decor = activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        backdrop.layout(0, 0, location[0] + decor.width, location[1] + decor.height)
        backdrop.ownWindow = frame
        registerLifecycle()
        Choreographer.getInstance().postFrameCallback(coverCheck)
        return true
    }

    /**
     * 后打开的弹层盖到了上面（同一 Activity 里新加的 window 总在最上层）：
     * 拆下 window 重新加一次，回到最上层
     */
    private fun raise() {
        raisePending = false
        if (frame.parent == null || dismissing) return
        raiseCount++
        raising = true
        try {
            windowManager.removeViewImmediate(frame)
            syncSecureFlag()
            windowManager.addView(frame, windowParams)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to re-add the toast window", e)
        } finally {
            raising = false
        }
        // 加不回去（Activity 正在销毁）：当作已经收起
        if (frame.parent == null) releaseWindow()
    }

    private fun removeWindow() {
        if (frame.parent != null) {
            try {
                windowManager.removeViewImmediate(frame)
            } catch (e: RuntimeException) {
                // Activity 销毁时系统已经把它移走了
            }
        }
        releaseWindow()
    }

    /** window 拆掉之后的收尾（可重复调用） */
    private fun releaseWindow() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(raiseRunnable)
        raisePending = false
        Choreographer.getInstance().removeFrameCallback(coverCheck)
        unregisterLifecycle()
        backdrop.ownWindow = null
        glass.animate().cancel()
        dismissing = false
        if (current === this) current = null
    }

    /** Activity 销毁前先把 window 拿掉，否则系统会报 window 泄漏 */
    private fun registerLifecycle() {
        if (lifecycleCallbacks != null) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) cancel(animate = false)
            }

            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
        }
        activity.application.registerActivityLifecycleCallbacks(callbacks)
        lifecycleCallbacks = callbacks
    }

    private fun unregisterLifecycle() {
        val callbacks = lifecycleCallbacks ?: return
        activity.application.unregisterActivityLifecycleCallbacks(callbacks)
        lifecycleCallbacks = null
    }

    private fun animateIn() {
        // 从所在的那条边滑进来一点：底部向上、顶部向下、居中向上
        val slide = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> -dpF(SLIDE_DP.toFloat())
            else -> dpF(SLIDE_DP.toFloat())
        }
        glass.alpha = 0f
        glass.scaleX = ENTER_SCALE
        glass.scaleY = ENTER_SCALE
        glass.translationY = slide
        glass.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            // 位移中每帧重录：玻璃按屏幕坐标采背景，不重绘的话折射会拖着走
            .setUpdateListener { glass.invalidate() }
            .withEndAction(null)
            .start()
    }

    private fun applyIconTint() {
        if (!iconTintEnabled) {
            imageView.clearColorFilter()
            return
        }
        imageView.setColorFilter(textView.currentTextColor)
    }

    /** 暗背景：白字加投影；亮背景：深色字去投影（与其他小部件同一套规则） */
    private fun applyAutoColors(isOverLight: Boolean) {
        if (isOverLight) {
            textView.setTextColor(0xDE000000.toInt())
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else {
            textView.setTextColor(Color.WHITE)
            textView.setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        applyIconTint()
    }

    /** 不拦截触摸、前景明暗跟随背景 */
    private inner class ToastGlassView(context: Context) : LiquidGlassView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onAppearanceChanged(isOverLight: Boolean) {
            if (autoColors) applyAutoColors(isOverLight)
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * activity.resources.displayMetrics.density

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG

        private const val TAG = "LiquidGlassToast"
        private const val SHORT_MS = 2000L
        private const val LONG_MS = 3500L
        private const val ENTER_MS = 260L
        private const val EXIT_MS = 180L
        private const val ENTER_SCALE = 0.94f
        private const val EXIT_SCALE = 0.94f

        /** 滑入距离（dp），也是 window 在玻璃上下多留的余量 */
        private const val SLIDE_DP = 24

        /** 一条 Toast 最多挪几次到最上层：防止和别的同样抢最上层的 window 来回顶 */
        private const val MAX_RAISES = 8

        private var current: LiquidGlassToast? = null

        @JvmStatic
        @JvmOverloads
        fun makeText(context: Context, text: CharSequence, duration: Int = LENGTH_SHORT): LiquidGlassToast =
            LiquidGlassToast(findActivity(context)).apply {
                setText(text)
                this.duration = duration
            }

        @JvmStatic
        @JvmOverloads
        fun makeText(context: Context, @StringRes resId: Int, duration: Int = LENGTH_SHORT): LiquidGlassToast =
            makeText(context, context.getText(resId), duration)

        /** 立刻开始收掉正在显示的那条 */
        @JvmStatic
        fun cancelCurrent() {
            current?.cancel()
        }

        private fun findActivity(context: Context): Activity {
            var c: Context? = context
            while (c != null) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            throw IllegalArgumentException(
                "LiquidGlassToast needs an Activity context: the toast window is attached to the " +
                    "activity so the glass can refract the activity and its dialogs beneath it"
            )
        }
    }
}
