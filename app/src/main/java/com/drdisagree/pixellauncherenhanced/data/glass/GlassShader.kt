package com.drdisagree.pixellauncherenhanced.data.glass

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object GlassShader {

    const val REFRACTION_RATIO = 0.18f
    private const val DISPERSION = 0.06f
    private const val FROSTED_SAMPLE_RADIUS = 4
    private const val FROSTED_MIN_WIDTH = 16

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlineRect = RectF()
    private val outlinePath = Path()
    private val outlineRadii = FloatArray(8)

    const val LIQUID = """
        uniform shader image;
        uniform float2 origin;
        uniform float2 size;
        uniform float4 radii;
        uniform float refraction;
        uniform float edge;
        uniform float dispersion;
        uniform float rim;
        uniform float saturation;
        uniform shader mask;
        uniform float useMask;
        uniform float2 maskOrigin;
        uniform float maskScale;
        uniform float maskRange;
        uniform float4 dim;

        float roundedBox(float2 p, float2 halfSize, float r) {
            float2 q = abs(p) - halfSize + r;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        float cornerRadius(float2 p) {
            return p.y > 0.0 ? (p.x > 0.0 ? radii.z : radii.w) : (p.x > 0.0 ? radii.y : radii.x);
        }

        float boxDistance(float2 p, float2 halfSize) {
            return roundedBox(p, halfSize, cornerRadius(p));
        }

        float maskDistance(float2 coord) {
            float m = mask.eval((coord - maskOrigin) * maskScale).a;
            return (0.5 - m) * 2.0 * maskRange;
        }

        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 p = coord - origin - halfSize;
            float2 e = float2(1.0, 0.0);
            float d;
            float2 normal;
            float width = edge;
            float strength = 1.0;

            if (useMask > 0.5) {
                d = maskDistance(coord);
                float2 m = float2(2.0, 0.0);
                normal = float2(
                    maskDistance(coord + m.xy) - maskDistance(coord - m.xy),
                    maskDistance(coord + m.yx) - maskDistance(coord - m.yx)
                );
                width = min(edge, maskRange * 0.9);
                strength = smoothstep(0.05, 0.4, length(normal));
            } else {
                d = boxDistance(p, halfSize);
                normal = float2(
                    boxDistance(p + e.xy, halfSize) - boxDistance(p - e.xy, halfSize),
                    boxDistance(p + e.yx, halfSize) - boxDistance(p - e.yx, halfSize)
                );
            }
            normal = normal / max(length(normal), 0.0001);

            float band = clamp(1.0 + d / max(width, 1.0), 0.0, 1.0);
            float bend = band * band * band * strength;
            float2 offset = -normal * refraction * bend;

            half4 color;
            color.r = image.eval(coord + offset * (1.0 + dispersion)).r;
            color.g = image.eval(coord + offset).g;
            color.b = image.eval(coord + offset * (1.0 - dispersion)).b;
            color.a = 1.0;
            half luma = dot(color.rgb, half3(0.2126, 0.7152, 0.0722));
            color.rgb = clamp(mix(half3(luma), color.rgb, half(saturation)), 0.0, 1.0);
            color.rgb = mix(color.rgb, half3(dim.rgb), half(dim.a));

            float rimBand = smoothstep(-2.5, 0.0, d);
            float light = clamp(dot(normal, normalize(float2(-0.5, -0.85))), 0.0, 1.0);
            float shade = clamp(dot(normal, normalize(float2(0.5, 0.85))), 0.0, 1.0);
            color.rgb += half3(rim * rimBand * (0.18 + 0.5 * light));
            color.rgb += half3(rim * bend * 0.12 * light);
            color.rgb -= half3(rim * bend * 0.06 * shade);
            color.rgb += half3(0.03);
            return color;
        }
    """

    fun baseRefraction(width: Float, height: Float): Float = minOf(width, height) * REFRACTION_RATIO

    fun edgeWidth(width: Float, height: Float, style: GlassStyle): Float =
        max(1f, baseRefraction(width, height) * style.depth)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyStyle(shader: RuntimeShader, width: Float, height: Float, style: GlassStyle) {
        val base = baseRefraction(width, height)
        shader.setFloatUniform("refraction", base * style.refraction)
        shader.setFloatUniform("edge", max(1f, base * style.depth))
        shader.setFloatUniform("dispersion", DISPERSION * style.dispersion)
        shader.setFloatUniform("rim", style.rim)
        shader.setFloatUniform("saturation", style.saturation)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun setRadii(shader: RuntimeShader, radii: FloatArray) {
        shader.setFloatUniform("radii", radii[0], radii[2], radii[4], radii[6])
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun setRadii(shader: RuntimeShader, corner: Float, bottomCorner: Float = corner) {
        shader.setFloatUniform("radii", corner, corner, bottomCorner, bottomCorner)
    }

    fun cornerRadii(corner: Float, bottomCorner: Float, out: FloatArray): FloatArray {
        out.fill(corner, 0, 4)
        out.fill(bottomCorner, 4, 8)
        return out
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun setDim(shader: RuntimeShader, color: Int) {
        shader.setFloatUniform(
            "dim",
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            Color.alpha(color) / 255f
        )
    }

    fun backdropFilter(overlay: Int, saturation: Float = 1f): ColorFilter? {
        val alpha = Color.alpha(overlay) / 255f
        if (saturation == 1f) {
            return if (alpha == 0f) null else BlendModeColorFilter(overlay, BlendMode.SRC_OVER)
        }

        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        if (alpha > 0f) {
            val keep = 1f - alpha
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        keep, 0f, 0f, 0f, Color.red(overlay) * alpha,
                        0f, keep, 0f, 0f, Color.green(overlay) * alpha,
                        0f, 0f, keep, 0f, Color.blue(overlay) * alpha,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        return ColorMatrixColorFilter(matrix)
    }

    fun drawOutline(
        canvas: Canvas,
        rect: RectF,
        corner: Float,
        bottomCorner: Float,
        style: GlassStyle?,
        alpha: Float,
        density: Float
    ) {
        drawOutline(canvas, rect, cornerRadii(corner, bottomCorner, outlineRadii), style, alpha, density)
    }

    fun drawOutline(canvas: Canvas, rect: RectF, radii: FloatArray, style: GlassStyle?, alpha: Float, density: Float) {
        if (rect.isEmpty) return
        val width = prepareOutline(style, alpha, density)
        if (width <= 0f) return

        val inset = width / 2f
        outlineRect.set(rect)
        outlineRect.inset(inset, inset)
        for (i in 0 until 8) outlineRadii[i] = max(0f, radii[i] - inset)
        outlinePath.reset()
        outlinePath.addRoundRect(outlineRect, outlineRadii, Path.Direction.CW)
        canvas.drawPath(outlinePath, outlinePaint)
    }

    fun drawOutline(canvas: Canvas, path: Path, style: GlassStyle?, alpha: Float, unit: Float) {
        val width = prepareOutline(style, alpha, unit)
        if (width <= 0f || path.isEmpty) return

        outlinePaint.strokeWidth = width * 2f
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawPath(path, outlinePaint)
        canvas.restoreToCount(save)
    }

    private fun prepareOutline(style: GlassStyle?, alpha: Float, unit: Float): Float {
        style ?: return 0f
        val width = style.outlineWidth * unit
        val outlineAlpha = (style.outlineOpacity.coerceIn(0f, 1f) * alpha.coerceIn(0f, 1f) * 255).roundToInt()
        if (width <= 0f || outlineAlpha <= 0) return 0f

        outlinePaint.strokeWidth = width
        outlinePaint.color = style.outlineColor
        outlinePaint.alpha = outlineAlpha
        return width
    }

    fun frostedSample(radius: Float, coverWidth: Float, baseWidth: Int): Pair<Int, Int> {
        if (radius <= 0f || coverWidth <= 0f) return baseWidth to 0
        for (sampleRadius in FROSTED_SAMPLE_RADIUS downTo 1) {
            val width = (coverWidth * boxSigma(sampleRadius) / radius).roundToInt()
            if (width <= baseWidth) return max(width, min(FROSTED_MIN_WIDTH, baseWidth)) to sampleRadius
        }
        return baseWidth to 1
    }

    fun sampleSigma(width: Int, radius: Int): Float = boxSigma(radius) / width

    private fun boxSigma(radius: Int): Float = sqrt(radius * (radius + 1f))

    fun sampleBlur(base: Bitmap, width: Int, radius: Int): Bitmap {
        var source = base
        while (source.width / 2 >= width) {
            source = Bitmap.createScaledBitmap(source, source.width / 2, max(1, source.height / 2), true)
        }
        val sample = if (width == source.width) {
            if (source === base) base.copy(Bitmap.Config.ARGB_8888, true) else source
        } else {
            val height = max(1, (base.height.toLong() * width / base.width).toInt())
            Bitmap.createScaledBitmap(source, width, height, true)
        }.let { if (it.isMutable && it !== base) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        return if (radius > 0) boxBlur(sample, radius) else sample
    }

    fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val buffer = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        repeat(3) {
            blurPass(pixels, buffer, width, height, radius, horizontal = true)
            blurPass(buffer, pixels, width, height, radius, horizontal = false)
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun blurPass(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1

        for (line in 0 until lines) {
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = input[index(line, i.coerceIn(0, length - 1), width, horizontal)]
                r += pixel shr 16 and 0xFF
                g += pixel shr 8 and 0xFF
                b += pixel and 0xFF
            }
            for (i in 0 until length) {
                output[index(line, i, width, horizontal)] =
                    (0xFF shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outPixel = input[index(line, (i - radius).coerceIn(0, length - 1), width, horizontal)]
                val inPixel = input[index(line, (i + radius + 1).coerceIn(0, length - 1), width, horizontal)]
                r += (inPixel shr 16 and 0xFF) - (outPixel shr 16 and 0xFF)
                g += (inPixel shr 8 and 0xFF) - (outPixel shr 8 and 0xFF)
                b += (inPixel and 0xFF) - (outPixel and 0xFF)
            }
        }
    }

    private fun index(line: Int, position: Int, width: Int, horizontal: Boolean): Int {
        return if (horizontal) line * width + position else position * width + line
    }
}
