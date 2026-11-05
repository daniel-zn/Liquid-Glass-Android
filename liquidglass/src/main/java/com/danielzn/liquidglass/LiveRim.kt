package com.danielzn.liquidglass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Live glass drawn in the window itself: each glass view paints only its rim, with one shader that
 * samples the current video picture (a GPU buffer, [setFrame]) and what lies over the video behind the
 * glass ([setLayer]: a scrim, a dim, the copy of the window underneath). No extra compositor layer, no
 * capture of the view tree, no blur at run time (the video source comes softened); a new picture
 * invalidates only the glass views that use this.
 *
 * As the [LiquidGlassDrawable.backdropUnderlay], the drawable draws with [draw] instead of its lens.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LiveRim(val name: String) : BackdropUnderlay, GlassAttachable {

    private val hosts = ArrayList<View>()

    /** The video's rectangle in this window's coordinates (the picture is stretched over it). */
    var videoX = 0f
    var videoY = 0f
    var videoW = 1f
    var videoH = 1f

    private var video: BitmapShader? = null
    private var frameBitmap: Bitmap? = null
    @Volatile var frames = 0; private set
    private var version = 0

    /** Called once (main thread) at the first picture since [resetFirstFrame]. */
    var onFirstFrame: Runnable? = null
    private var firstSeen = false
    fun resetFirstFrame() { firstSeen = false }

    /**
     * Staggered: a new picture redraws one glass view per display frame (the next on the next vsync), so each
     * frame's damage is one small region (HWUI redraws a single damage rectangle: the dock and the top buttons
     * together would span the screen).
     */
    var stagger = true
    private var staggerNext = 0
    private val staggerStep = object : Runnable {
        override fun run() {
            while (staggerNext < hosts.size) {
                val h = hosts[staggerNext++]
                if (!h.isShown) continue
                h.invalidate()
                if (staggerNext < hosts.size) h.postOnAnimation(this)
                return
            }
        }
    }

    private fun invalidateStaggered() {
        val first = hosts.firstOrNull { it.isShown } ?: return
        first.removeCallbacks(staggerStep)
        staggerNext = 0
        staggerStep.run()
    }

    /** Frozen: keeps the picture it has (another window's glass is up over this one). */
    var frozen = false

    /** Glass in another window: the copy of the window underneath (kept copying while this glass is shown). */
    var windowCopy: PixelCopyBackdrop? = null

    /** A new video picture (main thread): only the glass views using this redraw. */
    fun setFrame(bmp: Bitmap) {
        if (frozen && video != null) return
        frameBitmap = bmp
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.setLocalMatrix(Matrix().apply {
            setScale(videoW / bmp.width, videoH / bmp.height)
            postTranslate(videoX, videoY)
        })
        video = s
        frames++
        GlassStats.rimFrames++
        version++
        if (stagger) invalidateStaggered() else for (i in hosts.indices) if (hosts[i].isShown) hosts[i].invalidate()
        if (!firstSeen) {
            firstSeen = true
            onFirstFrame?.run()
        }
    }

    // ---- what lies over the video behind the glass, bottom up (up to LAYERS) ----------------------

    private class Layer(val shader: Shader, val x: Float, val y: Float, val w: Float, val h: Float, val alphaOf: View?)

    private val layers = arrayOfNulls<Layer>(LAYERS)

    /**
     * Layer [i] (0 = lowest): [view]'s background, as the glass sees it (the view keeps drawing itself;
     * its live alpha and visibility count). Its position is the view's in this window, offset by [dx], [dy]
     * (a view of another window: where that window is in this one).
     */
    fun setLayer(i: Int, view: View, dx: Float = 0f, dy: Float = 0f) {
        val bg = view.background ?: run { layers[i] = null; return }
        if (view.width <= 0 || view.height <= 0) return
        val color = (bg as? android.graphics.drawable.ColorDrawable)?.color
        val bmp: Bitmap
        if (color != null) {
            bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        } else {
            val bw = (view.width / 2).coerceAtLeast(1)
            val bh = (view.height / 2).coerceAtLeast(1)
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(bw / view.width.toFloat(), bh / view.height.toFloat())
            val old = android.graphics.Rect(bg.bounds)
            bg.setBounds(0, 0, view.width, view.height)
            bg.draw(c)
            bg.bounds = old
        }
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        setBitmapLayer(i, bmp, loc[0] + dx, loc[1] + dy, view.width.toFloat(), view.height.toFloat(), view)
    }

    /** Layer [i]: a picture covering x, y, w, h (e.g. the copy of the window underneath), alpha from [alphaOf] or 1. */
    fun setBitmapLayer(i: Int, bmp: Bitmap, x: Float, y: Float, w: Float, h: Float, alphaOf: View? = null) {
        val s = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.setLocalMatrix(Matrix().apply { setScale(w / bmp.width, h / bmp.height); postTranslate(x, y) })
        layers[i] = Layer(s, x, y, w, h, alphaOf)
        version++
        for (v in hosts) v.invalidate()
    }

    fun clearLayer(i: Int) {
        layers[i] = null
        version++
    }

    // ---- drawing (called by LiquidGlassDrawable) ---------------------------------------------------

    private val loc = IntArray(2)
    private val empty = BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    /** Draws [glass]'s rim (and the clear inside's veil) in its bounds; false before the first picture. */
    internal fun draw(canvas: Canvas, host: View, b: android.graphics.Rect, p: GlassLensRenderer.LensParams, alpha: Float,
                      shader: RuntimeShader, paint: Paint): Boolean {
        val v = video ?: return false
        host.getLocationInWindow(loc)
        shader.setFloatUniform("origin", (loc[0] + b.left).toFloat(), (loc[1] + b.top).toFloat())
        shader.setFloatUniform("local", b.left.toFloat(), b.top.toFloat())
        shader.setFloatUniform("size", b.width().toFloat(), b.height().toFloat())
        shader.setFloatUniform("shape", p.lens1CX, p.lens1CY, p.lens1HW, p.lens1HH)
        shader.setFloatUniform("radii", p.radius1TL, p.radius1TR, p.radius1BR, p.radius1BL)
        shader.setFloatUniform("bevel", p.bevel)
        shader.setFloatUniform("refractPx", p.refract)
        shader.setFloatUniform("falloff", p.falloff)
        shader.setFloatUniform("refractDir", if (p.outward) 1f else -1f)
        shader.setFloatUniform("dispersion", p.dispersion)
        shader.setFloatUniform("lightDir", p.lightX, p.lightY)
        shader.setFloatUniform("specStrength", p.spec)
        shader.setFloatUniform("rimBandMax", p.rimBandMax)
        shader.setFloatUniform("satFactor", p.saturation / 100f)
        shader.setFloatUniform("dimAmount", p.dim)
        shader.setFloatUniform("clearInterior", if (p.clearInterior) 1f else 0f)
        shader.setFloatUniform("clearVeil", p.clearVeil)
        shader.setFloatUniform("convex", if (p.convex) 1f else 0f)
        shader.setFloatUniform("alpha", alpha)
        shader.setFloatUniform("videoRect", videoX, videoY, videoW, videoH)
        shader.setInputShader("video", v)
        for (i in 0 until LAYERS) {
            val l = layers[i]
            val a = l?.let { lay -> lay.alphaOf?.let { if (it.isShown) it.alpha else 0f } ?: 1f } ?: 0f
            shader.setInputShader("l$i", l?.shader ?: empty)
            shader.setFloatUniform("l${i}Rect", l?.x ?: 0f, l?.y ?: 0f, l?.w ?: 0f, l?.h ?: 0f)
            shader.setFloatUniform("l${i}A", a)
        }
        paint.shader = shader
        canvas.drawRect(b, paint)
        return true
    }

    // ---- as an underlay ------------------------------------------------------------------------

    override fun drawUnderlay(canvas: Canvas) {}
    override val underlayVersion: Int get() = version

    override fun attach(glass: View) {
        if (!hosts.contains(glass)) hosts.add(glass)
        windowCopy?.attach(glass)
    }

    override fun detach(glass: View) {
        hosts.remove(glass)
        windowCopy?.detach(glass)
    }

    override fun onGlassDrawn() {
        windowCopy?.onGlassDrawn()
    }

    companion object {
        const val LAYERS = 4

        /** The lens of GlassLensRenderer (clear tier), sampling the video picture and the layers over it. */
        internal const val AGSL = """
            uniform shader video;
            uniform shader l0;
            uniform shader l1;
            uniform shader l2;
            uniform shader l3;
            uniform float4 l0Rect; uniform float4 l1Rect; uniform float4 l2Rect; uniform float4 l3Rect;
            uniform float l0A; uniform float l1A; uniform float l2A; uniform float l3A;
            uniform float4 videoRect;
            uniform float2 origin;   // the glass's top-left corner in the window
            uniform float2 local;    // ... and in the host's canvas
            uniform float2 size;
            uniform float4 shape;
            uniform float4 radii;
            uniform float bevel; uniform float refractPx; uniform float falloff; uniform float refractDir;
            uniform float dispersion; uniform float2 lightDir; uniform float specStrength; uniform float rimBandMax;
            uniform float satFactor; uniform float dimAmount; uniform float clearInterior; uniform float clearVeil;
            uniform float convex;
            uniform float alpha;

            bool inside(float2 s, float4 r) { return s.x >= r.x && s.y >= r.y && s.x < r.x + r.z && s.y < r.y + r.w; }

            half3 behind(float2 s) {
                float2 vs = clamp(s, videoRect.xy + 0.5, videoRect.xy + videoRect.zw - 0.5);
                half3 c = video.eval(vs).rgb;
                if (l0A > 0.001 && inside(s, l0Rect)) { half4 k = l0.eval(s) * half(l0A); c = k.rgb + c * (1.0 - k.a); }
                if (l1A > 0.001 && inside(s, l1Rect)) { half4 k = l1.eval(s) * half(l1A); c = k.rgb + c * (1.0 - k.a); }
                if (l2A > 0.001 && inside(s, l2Rect)) { half4 k = l2.eval(s) * half(l2A); c = k.rgb + c * (1.0 - k.a); }
                if (l3A > 0.001 && inside(s, l3Rect)) { half4 k = l3.eval(s) * half(l3A); c = k.rgb + c * (1.0 - k.a); }
                return c;
            }

            float sdBox4(float2 p, float2 b, float4 r) {
                float rx = (p.x > 0.0) ? ((p.y > 0.0) ? r.z : r.y) : ((p.y > 0.0) ? r.w : r.x);
                float2 q = abs(p) - b + rx;
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rx;
            }
            // GlassGeometry.snellShift. Slope capped at 4 so the bend covers the lip.
            float snellShift(float t, float thick, float bev) {
                float u = 1.0 - clamp(t, 0.0, 1.0);
                float slope = min(u * u * 4.0 * (thick / max(bev, 1.0)), 4.0);
                float th1 = atan(slope);
                float th2 = asin(clamp(sin(th1) / 1.5, -1.0, 1.0));
                return thick * tan(th1 - th2);
            }
            float lensSDF(float2 p) { return sdBox4(p - shape.xy, shape.zw, radii); }

            half4 main(float2 coord) {
                float2 p = coord - local;    // the glass's own pixels (the canvas is the host's)
                if (p.x < 0.0 || p.y < 0.0 || p.x > size.x || p.y > size.y) return half4(0.0);
                float d = lensSDF(p);
                float cov = clamp(0.5 - d / 1.5, 0.0, 1.0) * alpha;
                if (cov <= 0.004) return half4(0.0);
                float bandW = clamp(bevel * 0.3, 2.0, rimBandMax);
                float lip = convex > 0.5 ? bevel * 0.82 : bevel;
                bool interior = -d > max(lip, 3.0 + bandW) + 1.0;
                if (interior && clearInterior > 0.5) {
                    float v = clearVeil * cov;
                    return half4(half3(0.5 * v), half(v));
                }
                float2 n = float2(lensSDF(p + float2(1.0, 0.0)) - lensSDF(p - float2(1.0, 0.0)),
                                  lensSDF(p + float2(0.0, 1.0)) - lensSDF(p - float2(0.0, 1.0)));
                float nl = length(n);
                n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);
                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float slope;
                float shift;
                if (interior) { slope = 0.0; shift = 0.0; }
                else if (convex > 0.5) {
                    shift = snellShift(t, refractPx, bevel);
                    slope = shift / max(refractPx, 1.0);
                } else if (falloff > 0.001) {
                    float gB = pow(5.0, -falloff);
                    slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);
                    shift = refractDir * slope * refractPx;
                } else { slope = (1.0 - t) * (1.0 - t); shift = refractDir * slope * refractPx; }
                float2 s = origin + p;
                float2 off = n * shift;
                half3 col = half3(behind(s + off * (1.0 - dispersion * slope)).r,
                                  behind(s + off).g,
                                  behind(s + off * (1.0 + dispersion * slope)).b);
                float lum = dot(float3(col), float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) col = half3(mix(float3(lum), float3(col), satFactor));
                else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    col = half3(clamp(mix(float3(lum), float3(col), 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl)), 0.0, 1.0));
                }
                col *= half(1.0 - dimAmount);
                if (convex > 0.5) col *= half(1.0 - 0.14 * smoothstep(0.12, 0.55, slope));
                float facing = dot(n, -lightDir);
                float lobeF = pow(max(facing, 0.0), 4.5);
                float lobeB = pow(max(-facing, 0.0), 4.5);
                float glowIn = clamp((-d - 1.0) / 2.0, 0.0, 1.0);
                float glow = glowIn * pow(clamp(1.0 - (-d - 3.0) / bandW, 0.0, 1.0), 1.5) * cov;
                float hair = clamp(1.0 - abs(d + 1.0) / 2.0, 0.0, 1.0) * cov;
                float spec = (hair * 0.70 * (lobeF + lobeB) + glow * 0.10 * lobeF) * specStrength;
                float3 c3 = clamp(float3(col) + float3(spec), 0.0, 1.0);
                if (clearInterior > 0.5) {
                    float a = convex > 0.5 ? smoothstep(0.03, 0.16, slope) : (1.0 - smoothstep(0.35, 1.0, t));
                    float v = clearVeil * (1.0 - a);
                    float3 rgb = clamp((c3 - float3(spec)) * a + float3(0.5 * v) + float3(spec), 0.0, 1.0);
                    return half4(half3(rgb * cov), half(clamp(a + v + spec, 0.0, 1.0) * cov));
                }
                return half4(half3(c3 * cov), half(cov));
            }
        """
    }
}
