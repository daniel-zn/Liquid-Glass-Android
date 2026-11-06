/**
 * API 33+ 统一透镜着色器渲染器（Liquid Glass 2.0 核心）
 *
 * 用单个 AGSL RuntimeShader 完成 Apple Liquid Glass 的完整光学模型，
 * 取代"位移贴图 + 多个独立效果"的旧架构：
 *
 *   实时圆角矩形 SDF（随 cornerRadius/尺寸实时变化，主形状支持逐角半径，双形状 smin 液态融合）
 *     → 斜面厚度剖面（bevelWidth 可调的"玻璃厚度"）
 *     → 屏幕空间法线（SDF 数值梯度）
 *     → 折射（沿法线向外采样 → 边缘出现背景压缩带，透镜感的来源）
 *     → 色散（三通道折射率不同 → 边缘光谱边纹）
 *     → 边缘亮线（dot(N, L) 的两道对称角度瓣：迎光侧 + 背光侧内壁反射，光源方向可由重力传感器驱动）
 *     → 自适应染色 / Clear 压暗层 / 本体染色（glassTint）/ 饱和度
 *
 * 管线：backdrop 录制（带外扩边距）→ RenderEffect 模糊 → 本着色器 → 输出。
 * 形状覆盖率由 SDF 抗锯齿输出（形状外 alpha=0），无需 canvas 裁剪。
 *
 * 相比 CPU 管线：零 Bitmap 分配、零像素回读、背景是"活"的。
 * 相比旧 GPU 管线（HardwareBackdropBlur）：折射几何与真实形状一致、
 * 色散沿法线方向（旧实现是标量广播导致的 45° 对角偏移）、多出高光/融合。
 */
package com.danielzn.liquidglass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassLensRenderer {

    companion object {
        private const val TAG = "GlassLensRenderer"

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /**
         * 透镜 AGSL 着色器
         *
         * 坐标约定：
         * - coord 为录制内容（含 margin 外扩）的像素坐标
         * - p = coord - margin 为视图局部坐标，形状参数均在此空间
         */
        private const val LENS_AGSL = """
            uniform shader content;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float4 shape1;    // 真实主形状（触摸凸起的尺度用它）
            uniform float4 radii1;
            uniform float4 shape1L;   // 主形状：平边方向延伸到视图外，那条边就没有斜面（视图矩形外硬切）
            uniform float  rimSoft;   // 边缘柔化：折射带内沿法线方向的抹匀宽度（px，0 = 关）
            uniform float4 shape2;
            uniform float  radius2;
            uniform float  blendK;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  falloff;      // 折射剖面：> 0 为逆幂（引力透镜）衰减指数，0 = 平方斜面
            uniform float  refractDir;   // -1 向内采样（默认，与 iOS 一致：内侧压缩镜像）/ +1 向外（可选的凸透镜模式）
            uniform float2 sampleLo;     // 采样安全区（录制内容坐标）：区外没有内容，读到的是透明黑
            uniform float2 sampleHi;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  rimBandMax;   // 贴边高光带宽度上限（px，小控件按短边收）
            uniform float4 tintColor;
            uniform float  adaptiveTint;
            uniform float4 glassTint;
            uniform float  dimAmount;
            uniform float  satFactor;
            uniform float  press;
            uniform float2 touchPos;
            uniform float  touchAmp;
            uniform float  clearInterior; // 1: away from the rim the glass is transparent (what's behind shows itself)
            uniform float  clearVeil;     // opacity of a neutral mid-grey veil over the clear inside (0 = none)
            uniform float  convex;        // 1: squircle lip, Snell's law, sample outward (GlassGeometry.snellShift)

            float sdRoundedBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            // 逐角半径版：r = (左上, 右上, 右下, 左下)，按 p 所在象限选半径
            float sdRoundedBox4(float2 p, float2 b, float4 r) {
                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
                float2 q = abs(p) - b + rx;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
            }

            float sminPoly(float a, float b, float k) {
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            // 斜面 / 法线 / 高光用透镜形状（平边已延伸出去，不产生边缘）
            // GlassGeometry.snellShift. Cubic bezel (the bend reaches well in), slope capped at 1.15.
            float snellShift(float t, float thick, float bev) {
                float u = 1.0 - clamp(t, 0.0, 1.0);
                float u2 = u * u;
                float slope = min(u2 * u * 2.0 * (thick / max(bev, 1.0)), 1.15);
                float th1 = atan(slope);
                float th2 = asin(clamp(sin(th1) / 1.5, -1.0, 1.0));
                return thick * tan(th1 - th2);
            }

            float lensSDF(float2 p) {
                float d = sdRoundedBox4(p - shape1L.xy, shape1L.zw, radii1);
                if (shape2.z > 0.5) {
                    float d2 = sdRoundedBox(p - shape2.xy, shape2.zw, radius2);
                    if (blendK > 0.5) {
                        d = sminPoly(d, d2, blendK);
                    } else {
                        d = min(d, d2);
                    }
                }
                return d;
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                // 视图矩形外硬切：平边方向形状延伸到了视图外，靠这一刀收口，
                // 平边上不做羽化，两块玻璃贴边拼接时不会叠出一条发丝缝
                if (p.x < 0.0 || p.y < 0.0 || p.x > viewSize.x || p.y > viewSize.y) {
                    return half4(0.0);
                }
                float d = lensSDF(p);

                // 覆盖率：1.5px 抗锯齿羽化，形状外完全透明
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                // Interior fast path: away from the rim the bevel, the rim light and the glow are all
                // zero, so one sample is the whole result (most of a panel's pixels; no normal, no pow).
                float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
                // Past the lip the bend is gone, so the sample is the pixel itself.
                float lip = convex > 0.5 ? bevel * 0.55 : bevel;
                bool interior = (-d > max(lip, 3.0 + bandW) + 1.0) && touchAmp <= 0.001;
                // Clear interior: nothing to draw there, the real content behind shows through (a video on
                // its own plane keeps its own brightness, colour processing and frame rate).
                if (interior && clearInterior > 0.5) {
                    // Only a faint neutral veil (premultiplied mid-grey): lifts dark and tames bright content a
                    // little, as Apple's clear glass does, without colour, blur or refraction.
                    float v = clearVeil * cov;
                    return half4(half3(0.5 * v), half(v));
                }

                float2 n = float2(0.0, -1.0);
                if (!interior) {
                // SDF 数值梯度 → 屏幕空间外法线
                n = float2(
                    lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
                    lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0))
                );
                float nLen = length(n);
                if (nLen > 0.0001) {
                    n = n / nLen;
                } else {
                    n = float2(0.0, -1.0);
                }
                }

                // 厚度剖面：t=1 平坦内部，t=0 边缘；slope 为斜面陡峭度（折射位移的比例）
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;
                float refr = refractPx * (1.0 + 0.6 * press);
                // shift: px along the outward normal. Convex lip is always outward; the older
                // profile follows refractDir (negative = inward).
                float slope;
                float shift;
                if (interior) {
                    slope = 0.0;
                    shift = 0.0;
                } else if (convex > 0.5) {
                    shift = snellShift(t, refr, bevel);
                    slope = shift / max(refr, 1.0);
                } else if (falloff > 0.001) {
                    // 引力透镜式逆幂衰减：位移 ∝ (1 + x/k)^-p，核半径 k = 斜面宽度的 1/4，
                    // 减去带末端的值再归一化，贴边 = 1、带末端平滑落到 0。越贴边越剧烈：
                    // p = 2 时离边 k 处只剩 1/4，绝大部分弯折压在最外几个像素，
                    // 内侧只留一段缓慢回落的轻微放大尾巴
                    float gB = pow(5.0, -falloff);
                    slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
                    shift = refractDir * slope * refr;
                } else {
                    // 平方斜面：弯折沿整条斜面带均匀铺开
                    slope = edge * edge;
                    shift = refractDir * slope * refr;
                }

                // RuntimeShader 子输入只保证"输出裁剪区"内可采样（Android 未暴露 Skia 的
                // childSampleRadius），向外采样必须让输出区覆盖整个外扩录制区——见 draw()
                // 里的外层合成节点；采样再由 sampleLo/Hi 钳在有内容的范围内
                float2 offset = n * shift;

                // 触摸局部液态凸起（手指下方的局部放大：采样向触点收缩）
                if (touchAmp > 0.001) {
                    float2 tp = p - touchPos;
                    float tr = length(tp);
                    float sigma = max(max(shape1.z, shape1.w), 1.0);
                    float bump = touchAmp * exp(-(tr * tr) / (sigma * sigma * 0.30));
                    if (tr > 1.0) {
                        offset -= (tp / tr) * (bump * refr * 0.5);
                    }
                }

                // 色散：三通道折射量不同（蓝光弯折最多），边缘出现光谱边纹
                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

                // 安全钳制到有内容的采样区，杜绝透明黑
                float2 lo = sampleLo;
                float2 hi = sampleHi;
                cR = clamp(cR, lo, hi);
                cG = clamp(cG, lo, hi);
                cB = clamp(cB, lo, hi);
                float3 col;
                if (interior) {
                    col = content.eval(clamp(coord, lo, hi)).rgb;
                } else if (rimSoft > 0.01 && slope > 0.001) {
                    // 边缘柔化：折射带内沿法线方向抹匀（宽度随斜面深度增长），
                    // 压缩带从一条硬线变成一段渐变，采样点仍钳在内容区内
                    float2 sm = n * (rimSoft * slope);
                    float2 cR1 = clamp(cR - sm, lo, hi);
                    float2 cR2 = clamp(cR + sm, lo, hi);
                    float2 cG1 = clamp(cG - sm, lo, hi);
                    float2 cG2 = clamp(cG + sm, lo, hi);
                    float2 cB1 = clamp(cB - sm, lo, hi);
                    float2 cB2 = clamp(cB + sm, lo, hi);
                    col = float3(
                        (content.eval(cR).r + content.eval(cR1).r + content.eval(cR2).r) / 3.0,
                        (content.eval(cG).g + content.eval(cG1).g + content.eval(cG2).g) / 3.0,
                        (content.eval(cB).b + content.eval(cB1).b + content.eval(cB2).b) / 3.0
                    );
                } else {
                    col = float3(
                        content.eval(cR).r,
                        content.eval(cG).g,
                        content.eval(cB).b
                    );
                }

                // 饱和度（合并进同一 pass）；提饱和端走 vibrancy 曲线：低饱和
                // 像素多提、高饱和像素少提、极亮像素保护，避免线性提饱和把浓色
                // 推过曝（曲线与 GlassRuntimeEffects 的 API 36 滤镜一致）
                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(float3(lum), col, satFactor);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                // 自适应染色（Regular）/ 压暗层（Clear）
                if (adaptiveTint > 0.5) {
                    // 逐像素自适应：按局部（模糊后）亮度在提亮/压暗之间平滑过渡，
                    // 玻璃跨明暗背景时不再整体翻转；曲线与全局版本
                    // （LiquidGlassView.onLuminanceSample）一致
                    float lumT = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float e = smoothstep(0.35, 0.75, lumT);
                    col = mix(col, float3(1.0 - e), 0.14 + 0.08 * e);
                } else {
                    col = mix(col, tintColor.rgb, tintColor.a);
                }
                col = col * (1.0 - dimAmount);
                // The lip is a volume: a little darker where the glass is thickest, not across the inside.
                if (convex > 0.5) {
                    col *= 1.0 - 0.14 * smoothstep(0.12, 0.55, slope);
                }

                // 使用方指定的玻璃本体色：按"有色介质"建模——吸收（保留背景明暗
                // 层次与折射细节）+ 少量散射（暗背景下也看得出色相）。
                // 位置在光照之前：染色属于透射，镜面高光属于表面反射，不该被染色
                if (glassTint.a > 0.002) {
                    float lumTint = dot(col, float3(0.2126, 0.7152, 0.0722));
                    float3 absorbed = col * mix(float3(1.0), glassTint.rgb, 0.85);
                    float3 scattered = glassTint.rgb * (0.38 * (1.0 - lumTint));
                    col = mix(col, clamp(absorbed + scattered, float3(0.0), float3(1.0)), glassTint.a);
                }

                // Light from above: the lobes die toward the corners, so the glint stays on the
                // top and bottom edges. The top is the lit edge; the bottom is a quieter reflection.
                float specV = 0.0;
                if (!interior) {
                float facing = dot(n, lightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);

                // Hairline (about 1 px), plus a short glow inward from the lit edge. Wider than
                // that, and stacked edges read as stripes. Brighter where the picture is bright.
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 2.0) / bandW, 0.0, 1.0), 1.5) * cov;
                float hair = clamp(1.0 - abs(d + 1.0) / 1.15, 0.0, 1.0) * cov;
                float reflect = 0.65 + 0.55 * lum;
                float spec = (hair * (0.42 * lobeF + 0.16 * lobeB) + glow * 0.08 * lobeF)
                             * reflect * specStrength * (1.0 - 0.35 * press);
                col += float3(spec);
                specV = spec;
                }

                col = clamp(col, float3(0.0), float3(1.0));
                if (clearInterior > 0.5) {
                    // Only the rim is drawn: the refracted band fades out towards the inside, the rim light
                    // stays at full strength (premultiplied: specular adds light where the glass is clear).
                    // The bend stays opaque. Fade only across the last couple of pixels of offset:
                    // blending a larger shift with the sharp picture under the hole draws a second copy.
                    float a = convex > 0.5 ? smoothstep(0.4, 1.8, shift) : (1.0 - smoothstep(0.35, 1.0, t));
                    // Where the rim fades out, the veil takes over (the same faint grey as the inside).
                    float v = clearVeil * (1.0 - a);
                    float3 rgb = clamp((col - float3(specV)) * a + float3(0.5 * v) + float3(specV), float3(0.0), float3(1.0));
                    return half4(half3(rgb * cov), half(clamp(a + v + specV, 0.0, 1.0) * cov));
                }
                return half4(half3(col * cov), half(cov));
            }
        """
    }

    /**
     * 透镜渲染参数（值语义；与上一帧不等时才重建 RenderEffect）
     *
     * 形状坐标均为视图局部像素；shape2HW <= 0 表示副形状禁用。
     */
    data class LensParams(
        val blurRadius: Float,
        val shape1CX: Float, val shape1CY: Float,
        val shape1HW: Float, val shape1HH: Float,
        val radius1TL: Float, val radius1TR: Float, val radius1BR: Float, val radius1BL: Float,
        // 透镜形状（平边方向延伸后的主形状；无平边时与 shape1 相同）
        val lens1CX: Float, val lens1CY: Float, val lens1HW: Float, val lens1HH: Float,
        val rimSoft: Float,     // 边缘柔化宽度（px）
        val shape2CX: Float, val shape2CY: Float,
        val shape2HW: Float, val shape2HH: Float, val radius2: Float,
        val blendK: Float,
        val bevel: Float,
        val refract: Float,
        val falloff: Float,     // 折射剖面：> 0 逆幂衰减指数，0 = 平方斜面
        val outward: Boolean,   // 折射向外采样（true）/ 向内（旧行为）
        val rimBandMax: Float,  // 迎光侧辉光带宽度上限（px）
        val dispersion: Float,
        val lightX: Float, val lightY: Float,
        val spec: Float,
        val tint: Int,          // straight-alpha ARGB（adaptiveTint 时被忽略）
        val adaptiveTint: Boolean, // 逐像素自适应染色（Regular + enableAdaptiveTint）
        val glassTint: Int,     // 使用方指定的玻璃本体色（straight-alpha ARGB，a=0 关闭）
        val dim: Float,
        val saturation: Float,  // 100 = 原始
        val press: Float,
        val touchX: Float, val touchY: Float, val touchAmp: Float,
        /** < 1: the backdrop is recorded and blurred at this scale, then upscaled under the full-res lens. */
        val backdropScale: Float = 1f,
        /** Draw the rim band only; the interior stays transparent. */
        val clearInterior: Boolean = false,
        /** With [clearInterior]: opacity of a light lift over the transparent inside. */
        val clearVeil: Float = 0f,
        /** Convex squircle lip (Snell, sampled outward). [refract] is the lip thickness. */
        val convex: Boolean = false,
        /** With [clearInterior] (live overlay): 0 clear inside, up to 1 frosted (GlassLook.frost). */
        val frost: Float = 0f
    )

    private val renderNode = RenderNode("LiquidGlassLens")

    /** The downscaled, blurred backdrop (only with LensParams.backdropScale < 1). */
    private val blurNode = RenderNode("LiquidGlassLensBlur")
    private var blurNodeRadius = Float.NaN

    /**
     * 向外折射用的外层合成节点。RenderEffect 的输出区是节点范围与画布裁剪区的交集，
     * 带效果的节点直接画到视图画布上会被裁到视图矩形，margin 里录下的内容对着色器
     * 不可见（RuntimeShader 子输入只保证输出区内可采样）。把它画进一个自带合成层、
     * 范围等于整个录制区的外层节点：层内裁剪区就是录制区，着色器采得到 margin；
     * 外层节点再画到视图画布上时才被裁到视图矩形
     */
    private val layerNode = RenderNode("LiquidGlassLensLayer").apply {
        setUseCompositingLayer(true, null)
    }

    // 采样安全区（录制内容坐标），随玻璃相对背景来源的位置变化
    private var sampleLoX = 0f
    private var sampleLoY = 0f
    private var sampleHiX = 0f
    private var sampleHiY = 0f

    private var shader: RuntimeShader? = null
    private var shaderBroken = false

    private var lastParams: LensParams? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastMargin = -1

    private val location = IntArray(2)
    private val parentLocation = IntArray(2)

    private val backdropCapture = BackdropCapture()

    /** AGSL 编译失败（个别设备驱动问题）时为 false，调用方应回退旧管线 */
    val isAvailable: Boolean
        get() = !shaderBroken

    /**
     * 绘制透镜玻璃到目标画布
     *
     * @param canvas 目标画布（必须硬件加速）
     * @param glassView 玻璃视图（定位 + 录制时跳过自身）
     * @param params 本帧渲染参数
     * @param margin 背景录制外扩边距（px，覆盖折射/模糊采样越界）
     * @return 是否成功绘制；false 时调用方应回退
     */
    fun draw(
        canvas: Canvas,
        glassView: View,
        params: LensParams,
        margin: Int
    ): Boolean {
        val host = glassView as? GlassSurface ?: return false
        return draw(canvas, glassView, host, 0, 0, glassView.width, glassView.height, params, margin)
    }

    /**
     * Draws lens glass for [surface] over the rectangle ([x], [y], [width], [height]) of [hostView]
     * (view-local coordinates; the canvas origin is the view's top-left). [hostView] is hidden from
     * its own backdrop recording.
     */
    fun draw(
        canvas: Canvas,
        hostView: View,
        surface: GlassSurface,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        params: LensParams,
        margin: Int
    ): Boolean {
        if (shaderBroken) return false
        if (!canvas.isHardwareAccelerated) return false
        val parent = surface.glassBackdropSource ?: return false
        if (width <= 0 || height <= 0) return false
        val glassView = hostView

        val sh = shader ?: try {
            RuntimeShader(LENS_AGSL).also { shader = it }
        } catch (e: Exception) {
            // AGSL 编译失败：标记不可用，让调用方永久回退旧管线
            Log.e(TAG, "Lens AGSL compile failed, falling back", e)
            shaderBroken = true
            return false
        }

        // 计算相对背景视图的偏移（屏幕坐标差：兼容滚动容器，且背景视图在
        // 另一个 window（Dialog/PopupWindow）时也成立）
        glassView.getLocationOnScreen(location)
        parent.getLocationOnScreen(parentLocation)
        val offsetX = (location[0] + x - parentLocation[0]).toFloat()
        val offsetY = (location[1] + y - parentLocation[1]).toFloat()

        val recW = width + 2 * margin
        val recH = height + 2 * margin
        val boundsChanged = updateSampleBounds(
            params.outward, margin, width, height, recW, recH,
            offsetX, offsetY, parent.width, parent.height
        )

        if (boundsChanged || params != lastParams ||
            width != lastWidth || height != lastHeight || margin != lastMargin
        ) {
            try {
                renderNode.setRenderEffect(buildEffect(sh, params, margin, width, height))
            } catch (e: Exception) {
                Log.e(TAG, "Lens effect build failed, falling back", e)
                shaderBroken = true
                return false
            }
            lastParams = params
            lastWidth = width
            lastHeight = height
            lastMargin = margin
        }

        // Nothing the recording depends on changed (position, size, look, the underlay's picture, which
        // of the source's own children are visible, a forced refresh): draw the node as it is. Its
        // children's changes still show (they are referenced, not copied), and HWUI keeps its layer: an
        // animation elsewhere in the window (a wiggling tile over the dock) re-renders no glass.
        val sig = sourceSignature(parent)
        val underlayV = surface.glassUnderlay?.underlayVersion ?: 0
        val forceV = surface.glassBackdropVersion
        val unchanged = recorded && params === lastRecordedParams && offsetX == lastOffX && offsetY == lastOffY &&
            width == lastRecW && height == lastRecH && margin == lastRecMargin && sig == lastSig &&
            underlayV == lastUnderlayV && forceV == lastForceV && parent === lastSource && x == lastX && y == lastY
        if (unchanged) {
            canvas.drawRenderNode(if (params.outward) layerNode else renderNode)
            return true
        }
        recorded = true
        curSig = sig; curForce = forceV
        lastRecordedParams = params; lastOffX = offsetX; lastOffY = offsetY; lastRecW = width; lastRecH = height
        lastRecMargin = margin; lastSig = sig; lastUnderlayV = underlayV; lastForceV = forceV; lastSource = parent
        lastX = x; lastY = y

        // Outward samples (the convex lip) have to see past the view, and a RuntimeShader only
        // reads inside its output. Drawing the lens into layerNode makes that output the whole
        // recording, margin included. Quarter-scale recording stays on either way: the lip reads
        // the upscaled picture.
        val scale = params.backdropScale
        val scaled = scale < 0.99f && scale > 0f
        if (params.outward) {
            renderNode.setPosition(0, 0, recW, recH)
            if (scaled) {
                recordScaledBackdrop(parent, glassView, surface, offsetX, offsetY, margin, recW, recH, scale, params.blurRadius)
            } else {
                recordBackdrop(parent, glassView, surface, offsetX, offsetY, margin, recW, recH)
            }
            layerNode.setPosition(x - margin, y - margin, x + width + margin, y + height + margin)
            val layerCanvas = layerNode.beginRecording(recW, recH)
            try {
                layerCanvas.drawRenderNode(renderNode)
            } finally {
                layerNode.endRecording()
            }
            canvas.drawRenderNode(layerNode)
        } else {
            renderNode.setPosition(x - margin, y - margin, x + width + margin, y + height + margin)
            if (scaled) {
                recordScaledBackdrop(parent, glassView, surface, offsetX, offsetY, margin, recW, recH, scale, params.blurRadius)
            } else {
                recordBackdrop(parent, glassView, surface, offsetX, offsetY, margin, recW, recH)
            }
            canvas.drawRenderNode(renderNode)
        }
        return true
    }

    private var recorded = false
    private var curSig = 0
    private var curForce = 0

    /**
     * Draws the backdrop in source coordinates into [c] (already translated to the recording's origin, and
     * scaled by [scale] when recording scaled): the panel's shared one if [glassView] is a control on a glass
     * panel, else its own capture.
     */
    private fun drawBackdrop(c: android.graphics.Canvas, parent: View, glassView: View, host: GlassSurface, scale: Float) {
        val anchor = if (Build.VERSION.SDK_INT >= 33) SharedBackdrop.anchorFor(glassView, parent) else null
        if (anchor != null) {
            val s = if (scale > 0f && scale < 0.99f) scale else 0.5f
            val node = SharedBackdrop.get(parent, anchor, host.glassUnderlay, curForce, curSig, s)
            val save = c.save()
            c.scale(1f / s, 1f / s)
            c.drawRenderNode(node)
            c.restoreToCount(save)
            return
        }
        GlassStats.lensRecords++
        backdropCapture.draw(c, parent, glassView, host.glassUnderlay)
    }
    private var lastRecordedParams: LensParams? = null
    private var lastOffX = Float.NaN
    private var lastOffY = Float.NaN
    private var lastRecW = -1
    private var lastRecH = -1
    private var lastRecMargin = -1
    private var lastSig = 0
    private var lastUnderlayV = 0
    private var lastForceV = 0
    private var lastSource: View? = null
    private var lastX = 0
    private var lastY = 0

    /** Which direct children of [source] would be drawn (their own changes need no re-record). */
    private fun sourceSignature(source: View): Int {
        var h = source.width * 31 + source.height
        if (source is android.view.ViewGroup) {
            for (i in 0 until source.childCount) {
                val c = source.getChildAt(i)
                h = h * 31 + System.identityHashCode(c)
                h = h * 31 + c.visibility
            }
        }
        return h
    }

    /** 把背景来源画进 renderNode（节点局部坐标，原点在外扩后的录制区左上角） */
    private fun recordBackdrop(
        parent: View,
        glassView: View,
        host: GlassSurface,
        offsetX: Float,
        offsetY: Float,
        margin: Int,
        recW: Int,
        recH: Int
    ) {
        val recordingCanvas = renderNode.beginRecording(recW, recH)
        try {
            recordingCanvas.translate(margin - offsetX, margin - offsetY)
            host.glassCapturing = true
            // 硬件画布上父视图绘制子视图不走 View.draw，isCapturingBackdrop 拦不住
            // 自己；跳过自身、以及跨层级祖先的重入/成环处理都在 BackdropCapture 里
            try {
                drawBackdrop(recordingCanvas, parent, glassView, host, 1f)
            } finally {
                host.glassCapturing = false
            }
        } finally {
            renderNode.endRecording()
        }
    }

    /**
     * Records the backdrop at [scale] into [blurNode] (blurred there, on scale² of the pixels) and
     * draws it scaled back up into [renderNode], whose effect is then the lens alone.
     */
    private fun recordScaledBackdrop(
        parent: View,
        glassView: View,
        host: GlassSurface,
        offsetX: Float,
        offsetY: Float,
        margin: Int,
        recW: Int,
        recH: Int,
        scale: Float,
        blurRadius: Float
    ) {
        val bw = ceil(recW * scale).toInt().coerceAtLeast(1)
        val bh = ceil(recH * scale).toInt().coerceAtLeast(1)
        blurNode.setPosition(0, 0, bw, bh)
        val r = blurRadius * scale
        if (r != blurNodeRadius) {
            blurNode.setRenderEffect(
                if (r > 0.01f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
            )
            blurNodeRadius = r
        }
        val bc = blurNode.beginRecording(bw, bh)
        try {
            bc.scale(scale, scale)
            bc.translate(margin - offsetX, margin - offsetY)
            host.glassCapturing = true
            try {
                drawBackdrop(bc, parent, glassView, host, scale)
            } finally {
                host.glassCapturing = false
            }
        } finally {
            blurNode.endRecording()
        }
        val rc = renderNode.beginRecording(recW, recH)
        try {
            rc.scale(1f / scale, 1f / scale)
            rc.drawRenderNode(blurNode)
        } finally {
            renderNode.endRecording()
        }
    }

    /**
     * 采样安全区。向内采样时是视图矩形（旧行为）；向外采样时是整个录制区与背景来源
     * 矩形的交集——来源之外没有内容，读到的是透明黑。滚动时来源边界逐帧移动，量化到
     * 4px 免得每帧重建 effect；玻璃离来源边缘超过 margin 时安全区恒为整个录制区
     *
     * @return 安全区是否变化（变化则需重建 effect）
     */
    private fun updateSampleBounds(
        outward: Boolean,
        margin: Int,
        width: Int,
        height: Int,
        recW: Int,
        recH: Int,
        offsetX: Float,
        offsetY: Float,
        parentW: Int,
        parentH: Int
    ): Boolean {
        val loX: Float
        val loY: Float
        val hiX: Float
        val hiY: Float
        if (outward) {
            val pl = margin - offsetX
            val pt = margin - offsetY
            loX = max(1f, floor(max(1f, pl + 1f) / 4f) * 4f)
            loY = max(1f, floor(max(1f, pt + 1f) / 4f) * 4f)
            hiX = max(loX, min(recW - 1f, ceil(min(recW - 1f, pl + parentW - 1f) / 4f) * 4f))
            hiY = max(loY, min(recH - 1f, ceil(min(recH - 1f, pt + parentH - 1f) / 4f) * 4f))
        } else {
            loX = margin + 1f
            loY = margin + 1f
            hiX = margin + width - 1f
            hiY = margin + height - 1f
        }
        val changed = loX != sampleLoX || loY != sampleLoY || hiX != sampleHiX || hiY != sampleHiY
        sampleLoX = loX
        sampleLoY = loY
        sampleHiX = hiX
        sampleHiY = hiY
        return changed
    }

    /**
     * 构建效果链：模糊（内层）→ 透镜着色器（外层）
     */
    private fun buildEffect(
        sh: RuntimeShader,
        p: LensParams,
        margin: Int,
        width: Int,
        height: Int
    ): RenderEffect {
        sh.setFloatUniform("margin", margin.toFloat())
        sh.setFloatUniform("viewSize", width.toFloat(), height.toFloat())
        sh.setFloatUniform("shape1", p.shape1CX, p.shape1CY, p.shape1HW, p.shape1HH)
        sh.setFloatUniform("radii1", p.radius1TL, p.radius1TR, p.radius1BR, p.radius1BL)
        sh.setFloatUniform("shape1L", p.lens1CX, p.lens1CY, p.lens1HW, p.lens1HH)
        sh.setFloatUniform("rimSoft", p.rimSoft)
        sh.setFloatUniform("shape2", p.shape2CX, p.shape2CY, p.shape2HW, p.shape2HH)
        sh.setFloatUniform("radius2", p.radius2)
        sh.setFloatUniform("blendK", p.blendK)
        sh.setFloatUniform("bevel", p.bevel)
        sh.setFloatUniform("refractPx", p.refract)
        sh.setFloatUniform("falloff", p.falloff)
        sh.setFloatUniform("refractDir", if (p.outward) 1f else -1f)
        sh.setFloatUniform("sampleLo", sampleLoX, sampleLoY)
        sh.setFloatUniform("sampleHi", sampleHiX, sampleHiY)
        sh.setFloatUniform("dispersion", p.dispersion)
        sh.setFloatUniform("lightDir", p.lightX, p.lightY)
        sh.setFloatUniform("specStrength", p.spec)
        sh.setFloatUniform("rimBandMax", p.rimBandMax)
        sh.setFloatUniform(
            "tintColor",
            Color.red(p.tint) / 255f,
            Color.green(p.tint) / 255f,
            Color.blue(p.tint) / 255f,
            Color.alpha(p.tint) / 255f
        )
        sh.setFloatUniform("adaptiveTint", if (p.adaptiveTint) 1f else 0f)
        sh.setFloatUniform(
            "glassTint",
            Color.red(p.glassTint) / 255f,
            Color.green(p.glassTint) / 255f,
            Color.blue(p.glassTint) / 255f,
            Color.alpha(p.glassTint) / 255f
        )
        sh.setFloatUniform("dimAmount", p.dim)
        sh.setFloatUniform("satFactor", p.saturation / 100f)
        sh.setFloatUniform("press", p.press)
        sh.setFloatUniform("touchPos", p.touchX, p.touchY)
        sh.setFloatUniform("touchAmp", p.touchAmp)
        sh.setFloatUniform("clearInterior", if (p.clearInterior) 1f else 0f)
        sh.setFloatUniform("clearVeil", p.clearVeil)
        sh.setFloatUniform("convex", if (p.convex) 1f else 0f)

        val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
        val scaled = p.backdropScale < 0.99f && p.backdropScale > 0f
        return if (p.blurRadius > 0.01f && !scaled) {
            RenderEffect.createChainEffect(
                lens,
                RenderEffect.createBlurEffect(p.blurRadius, p.blurRadius, Shader.TileMode.CLAMP)
            )
        } else {
            lens
        }
    }

    fun release() {
        recorded = false
        lastSource = null
        renderNode.discardDisplayList()
        blurNode.discardDisplayList()
        blurNodeRadius = Float.NaN
        layerNode.discardDisplayList()
        shader = null
        lastParams = null
    }
}
