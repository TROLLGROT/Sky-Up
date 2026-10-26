package com.trollgrot.skyup

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*
import java.util.Random

class SkyView(ctx: Context) : View(ctx) {
    var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    var lat = 0.0
    var lon = 0.0
    var decl = 0.0
    var hasLocation = false
    var lowAccuracy = false
    var fovDeg = 60.0
        private set
    var drawStarNames = true
    var drawPlanetNames = true
    var drawPlanetDetails = true
    var onSelect: ((String?) -> Unit)? = null

    private var selected: String? = null
    private var lastInfo = 0L
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val hits = ArrayList<Pair<Body, Projected>>()
    private var downX = 0f
    private var downY = 0f
    private var gestureScaling = false
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gestureScaling = true
            fovDeg = (fovDeg / detector.scaleFactor.toDouble()).coerceIn(MIN_FOV, MAX_FOV)
            invalidate()
            return true
        }
    })

    private data class Projected(val x: Float, val y: Float, val alt: Double)
    private data class BackgroundStar(val ra: Double, val dec: Double, val strength: Float)
    private val backgroundStars: List<BackgroundStar> = run {
        val random = Random(1831L)
        List(220) {
            BackgroundStar(random.nextDouble() * 360.0, asin(random.nextDouble() * 2.0 - 1.0) * 180.0 / PI, 0.25f + random.nextFloat() * 0.55f)
        }
    }

    fun setFieldOfView(value: Double) {
        fovDeg = value.coerceIn(MIN_FOV, MAX_FOV)
        invalidate()
    }

    fun zoomIn() = setFieldOfView(fovDeg / 1.35)
    fun zoomOut() = setFieldOfView(fovDeg * 1.35)

    fun clearSelection() {
        selected = null
        onSelect?.invoke(null)
    }

    private fun projectAzAlt(azimuth: Double, altitude: Double, focalLength: Float): Projected? {
        val a = Math.toRadians(azimuth - decl)
        val e = Math.toRadians(altitude)
        val wx = cos(e) * sin(a)
        val wy = cos(e) * cos(a)
        val wz = sin(e)
        val r = rot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        if (dz >= -0.05) return null
        return Projected((width / 2.0 + focalLength * dx / -dz).toFloat(), (height / 2.0 - focalLength * dy / -dz).toFloat(), altitude)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF020817.toInt())
        if (width == 0 || height == 0) return

        p.style = Paint.Style.FILL
        p.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(0xFF06152E.toInt(), 0xFF020817.toInt(), 0xFF0B1530.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        val focal = ((height / 2.0) / tan(Math.toRadians(fovDeg / 2.0))).toFloat()
        val julianDay = Astro.jd(System.currentTimeMillis())
        fun project(ra: Double, dec: Double): Projected? {
            val (altitude, azimuth) = Astro.altAz(ra, dec, lat, lon, julianDay)
            return projectAzAlt(azimuth, altitude, focal)
        }

        drawBackgroundStars(canvas, ::project)
        drawHorizonAndEcliptic(canvas, focal, julianDay, ::project)
        drawCardinalPoints(canvas, focal)

        hits.clear()
        val zoom = (ln(MAX_FOV / fovDeg) / ln(12.0)).coerceIn(0.0, 1.0).toFloat()
        for (body in Astro.all(julianDay)) {
            val point = project(body.ra, body.dec) ?: continue
            if (point.x < -100 || point.x > width + 100 || point.y < -100 || point.y > height + 100) continue
            val dim = if (point.alt < 0) 0.35f else 1f
            val radius = when (body.name) {
                "Sun" -> 13f * dp + 2f * dp * zoom
                "Moon" -> 10f * dp + 5f * dp * zoom
                else -> if (body.type == "Planet") planetRadius(body.name, zoom) else starRadius(body.mag)
            }
            if (body.name == "Sun") drawSun(canvas, point, radius, dim)
            else if (body.name == "Moon") drawMoon(canvas, point, radius, dim)
            else if (body.type == "Planet") drawPlanet(canvas, body.name, point, radius, dim, zoom)
            else drawStar(canvas, point, radius, dim, body.mag)

            if (body.type == "Planet" && drawPlanetNames || body.name == "Sun" || body.name == "Moon" || body.type == "Star" && drawStarNames && body.mag < 1.7) {
                textPaint.style = Paint.Style.FILL
                textPaint.textSize = if (body.type == "Star") 10.5f * dp else 12f * dp
                textPaint.color = if (body.type == "Star") 0xFFD3E5FF.toInt() else 0xFFF3F8FF.toInt()
                textPaint.setShadowLayer(4f * dp, 0f, 0f, 0xBB6DBBFF.toInt())
                canvas.drawText(body.name, point.x + radius + 4f * dp, point.y - 2f * dp, textPaint)
                textPaint.clearShadowLayer()
            }
            hits.add(body to point)

            if (body.name == selected) {
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f * dp
                p.color = 0xFF9BE7FF.toInt()
                canvas.drawCircle(point.x, point.y, radius + 8f * dp, p)
                p.style = Paint.Style.FILL
            }
            if (body.name == selected && System.currentTimeMillis() - lastInfo > 450) {
                lastInfo = System.currentTimeMillis()
                onSelect?.invoke(info(body, julianDay))
            }
        }

        drawCenterReticle(canvas)
        drawStatus(canvas)
    }

    private fun drawBackgroundStars(canvas: Canvas, project: (Double, Double) -> Projected?) {
        for (star in backgroundStars) {
            val q = project(star.ra, star.dec) ?: continue
            if (q.x < 0 || q.x > width || q.y < 0 || q.y > height) continue
            val r = (0.55f + star.strength * 0.9f) * dp
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Color.argb((70 + star.strength * 90).toInt(), 160, 194, 255)
            canvas.drawCircle(q.x, q.y, r * 2.1f, p)
            p.color = Color.argb((100 + star.strength * 130).toInt(), 226, 239, 255)
            canvas.drawCircle(q.x, q.y, r * 0.72f, p)
        }
    }

    private fun drawHorizonAndEcliptic(canvas: Canvas, focal: Float, jd: Double, project: (Double, Double) -> Projected?) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.3f * dp
        p.color = 0x775AA9D8.toInt()
        var previous: Projected? = null
        for (azimuth in 0..360 step 3) {
            val current = projectAzAlt(azimuth.toDouble(), 0.0, focal)
            if (previous != null && current != null) canvas.drawLine(previous.x, previous.y, current.x, current.y, p)
            previous = current
        }

        p.strokeWidth = 1f * dp
        p.color = 0x48F6D58A
        previous = null
        for (longitude in 0..360 step 3) {
            val (ra, dec) = Astro.eclipticPoint(longitude.toDouble(), jd)
            val current = project(ra, dec)
            if (previous != null && current != null) canvas.drawLine(previous.x, previous.y, current.x, current.y, p)
            previous = current
        }
        p.style = Paint.Style.FILL
    }

    private fun drawCardinalPoints(canvas: Canvas, focal: Float) {
        textPaint.shader = null
        textPaint.setShadowLayer(5f * dp, 0f, 0f, 0xAA65B8FF.toInt())
        textPaint.textSize = 14f * dp
        textPaint.color = 0xFF8EC8F5.toInt()
        for ((label, azimuth) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            projectAzAlt(azimuth, 0.0, focal)?.let { canvas.drawText(label, it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        }
        textPaint.clearShadowLayer()
    }

    private fun starRadius(magnitude: Double): Float = max(1.0f, 3.8f - magnitude.toFloat() * 0.72f) * dp

    private fun drawStar(canvas: Canvas, point: Projected, radius: Float, dim: Float, magnitude: Double) {
        val color = if (magnitude < 0.3) 0xFFB9D9FF.toInt() else 0xFFEAF3FF.toInt()
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(point.x, point.y, radius * 3.8f,
            intArrayOf(withAlpha(color, (150 * dim).toInt()), withAlpha(color, (42 * dim).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.34f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(point.x, point.y, radius * 3.8f, p)
        p.shader = null
        p.color = withAlpha(color, (235 * dim).toInt())
        canvas.drawCircle(point.x, point.y, radius, p)
        if (magnitude < 0.4) {
            p.strokeWidth = 0.7f * dp
            p.color = withAlpha(0xFFD6E8FF.toInt(), (100 * dim).toInt())
            p.style = Paint.Style.STROKE
            canvas.drawLine(point.x - radius * 2.8f, point.y, point.x + radius * 2.8f, point.y, p)
            canvas.drawLine(point.x, point.y - radius * 2.8f, point.x, point.y + radius * 2.8f, p)
            p.style = Paint.Style.FILL
        }
    }

    private fun planetRadius(name: String, zoom: Float): Float {
        val base = when (name) {
            "Jupiter" -> 5.6f
            "Saturn" -> 5.2f
            "Venus" -> 4.7f
            "Mars" -> 4.1f
            "Mercury" -> 3.6f
            "Uranus", "Neptune" -> 4.4f
            else -> 4f
        }
        return (base + zoom * when (name) {
            "Jupiter", "Saturn" -> 9.2f
            "Venus", "Mars" -> 7.5f
            else -> 6.6f
        }) * dp
    }

    private fun drawSun(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(point.x, point.y, radius * 2.8f,
            intArrayOf(withAlpha(0xFFFFE66D.toInt(), (220 * dim).toInt()), withAlpha(0xFFFF9B20.toInt(), (90 * dim).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(point.x, point.y, radius * 2.8f, p)
        p.shader = RadialGradient(point.x - radius * 0.28f, point.y - radius * 0.34f, radius * 1.8f,
            intArrayOf(0xFFFFF6B4.toInt(), 0xFFFFD54F.toInt(), 0xFFE87515.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(point.x, point.y, radius, p)
        p.shader = null
        p.color = withAlpha(0xFFFFF6C7.toInt(), (100 * dim).toInt())
        canvas.drawCircle(point.x - radius * 0.23f, point.y - radius * 0.28f, radius * 0.48f, p)
    }

    private fun drawMoon(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(point.x - radius * 0.3f, point.y - radius * 0.3f, radius * 1.7f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFBFC8D6.toInt(), 0xFF596679.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(point.x, point.y, radius, p)
        p.shader = null
        val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0xFF647184.toInt(), (75 * dim).toInt()) }
        canvas.drawCircle(point.x - radius * 0.28f, point.y + radius * 0.14f, radius * 0.18f, craterPaint)
        canvas.drawCircle(point.x + radius * 0.24f, point.y - radius * 0.30f, radius * 0.12f, craterPaint)
        canvas.drawCircle(point.x + radius * 0.32f, point.y + radius * 0.34f, radius * 0.10f, craterPaint)
        p.color = Color.WHITE
        p.alpha = (255 * dim).toInt()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.7f * dp
        canvas.drawCircle(point.x, point.y, radius, p)
        p.style = Paint.Style.FILL
        p.alpha = 255
    }

    private fun drawPlanet(canvas: Canvas, name: String, point: Projected, radius: Float, dim: Float, zoom: Float) {
        val palette = when (name) {
            "Mercury" -> intArrayOf(0xFFD8D5CB.toInt(), 0xFF969B9E.toInt(), 0xFF4F5964.toInt())
            "Venus" -> intArrayOf(0xFFFFE1A0.toInt(), 0xFFD6A96B.toInt(), 0xFF80533E.toInt())
            "Mars" -> intArrayOf(0xFFFFB17E.toInt(), 0xFFC75E43.toInt(), 0xFF71372F.toInt())
            "Jupiter" -> intArrayOf(0xFFFFD6A0.toInt(), 0xFFD19A70.toInt(), 0xFF775044.toInt())
            "Saturn" -> intArrayOf(0xFFFFE6B4.toInt(), 0xFFC7A66E.toInt(), 0xFF80664D.toInt())
            "Uranus" -> intArrayOf(0xFFD6FFFF.toInt(), 0xFF70C7D2.toInt(), 0xFF347D9C.toInt())
            else -> intArrayOf(0xFF9CBFFF.toInt(), 0xFF315FBD.toInt(), 0xFF142A69.toInt())
        }

        if (name == "Saturn" || name == "Uranus") drawRings(canvas, point, radius, name == "Saturn", dim)

        val disc = Path().apply { addCircle(point.x, point.y, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(disc)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(point.x - radius * 0.38f, point.y - radius * 0.38f, radius * 1.7f,
            intArrayOf(palette[0], palette[1], palette[2]), floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(point.x, point.y, radius, p)
        p.shader = null

        if (drawPlanetDetails && zoom > 0.18f) {
            when (name) {
                "Jupiter" -> drawJupiterBands(canvas, point, radius, palette, dim)
                "Saturn" -> drawSaturnBands(canvas, point, radius, dim)
                "Mars" -> drawMarsMarks(canvas, point, radius, dim)
                "Venus" -> drawVenusClouds(canvas, point, radius, dim)
                "Uranus", "Neptune" -> drawIceGiantBands(canvas, point, radius, palette, dim)
                "Mercury" -> drawMercuryCrater(canvas, point, radius, dim)
            }
        }
        canvas.restoreToCount(save)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.7f * dp
        p.color = withAlpha(0xFFE8F3FF.toInt(), (110 * dim).toInt())
        canvas.drawCircle(point.x, point.y, radius, p)
        p.style = Paint.Style.FILL
        p.alpha = 255
    }

    private fun drawRings(canvas: Canvas, point: Projected, radius: Float, saturn: Boolean, dim: Float) {
        val save = canvas.save()
        canvas.rotate(-17f, point.x, point.y)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (saturn) 2.1f * dp else 1.1f * dp
        p.color = withAlpha(if (saturn) 0xFFE6C99A.toInt() else 0xFF8BDDE6.toInt(), (185 * dim).toInt())
        val ring = RectF(point.x - radius * 1.75f, point.y - radius * 0.48f, point.x + radius * 1.75f, point.y + radius * 0.48f)
        canvas.drawOval(ring, p)
        if (saturn) {
            p.strokeWidth = 1.1f * dp
            p.color = withAlpha(0xFFFAE8C5.toInt(), (150 * dim).toInt())
            canvas.drawOval(RectF(point.x - radius * 1.42f, point.y - radius * 0.38f, point.x + radius * 1.42f, point.y + radius * 0.38f), p)
        }
        p.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun drawJupiterBands(canvas: Canvas, point: Projected, radius: Float, colors: IntArray, dim: Float) {
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f * dp, radius * 0.16f) }
        for (i in -2..2) {
            bandPaint.color = withAlpha(if (i % 2 == 0) 0xFF8E5947.toInt() else 0xFFFFE0B4.toInt(), (125 * dim).toInt())
            val y = point.y + i * radius * 0.31f
            canvas.drawOval(RectF(point.x - radius * 1.1f, y - radius * 0.10f, point.x + radius * 1.1f, y + radius * 0.10f), bandPaint)
        }
        bandPaint.style = Paint.Style.FILL
        bandPaint.color = withAlpha(0xFFC65F46.toInt(), (210 * dim).toInt())
        canvas.drawOval(RectF(point.x + radius * 0.16f, point.y + radius * 0.17f, point.x + radius * 0.55f, point.y + radius * 0.39f), bandPaint)
    }

    private fun drawSaturnBands(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(0.8f * dp, radius * 0.10f) }
        bandPaint.color = withAlpha(0xFF95754E.toInt(), (115 * dim).toInt())
        canvas.drawLine(point.x - radius, point.y - radius * 0.18f, point.x + radius, point.y - radius * 0.18f, bandPaint)
        bandPaint.color = withAlpha(0xFFFFE9C5.toInt(), (130 * dim).toInt())
        canvas.drawLine(point.x - radius, point.y + radius * 0.24f, point.x + radius, point.y + radius * 0.24f, bandPaint)
    }

    private fun drawMarsMarks(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        p.color = withAlpha(0xFF773B31.toInt(), (155 * dim).toInt())
        canvas.drawOval(RectF(point.x - radius * 0.45f, point.y - radius * 0.15f, point.x + radius * 0.10f, point.y + radius * 0.24f), p)
        p.color = withAlpha(0xFFFFD0A0.toInt(), (115 * dim).toInt())
        canvas.drawCircle(point.x + radius * 0.34f, point.y - radius * 0.29f, radius * 0.18f, p)
    }

    private fun drawVenusClouds(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.7f * dp, radius * 0.08f)
        p.color = withAlpha(0xFFFFF0C6.toInt(), (110 * dim).toInt())
        canvas.drawArc(RectF(point.x - radius * 0.9f, point.y - radius * 0.65f, point.x + radius * 0.9f, point.y + radius * 0.25f), 190f, 145f, false, p)
        canvas.drawArc(RectF(point.x - radius * 0.7f, point.y - radius * 0.05f, point.x + radius * 0.7f, point.y + radius * 0.80f), 205f, 125f, false, p)
        p.style = Paint.Style.FILL
    }

    private fun drawIceGiantBands(canvas: Canvas, point: Projected, radius: Float, colors: IntArray, dim: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.6f * dp, radius * 0.07f)
        p.color = withAlpha(colors[0], (95 * dim).toInt())
        canvas.drawLine(point.x - radius, point.y - radius * 0.25f, point.x + radius, point.y - radius * 0.10f, p)
        canvas.drawLine(point.x - radius, point.y + radius * 0.25f, point.x + radius, point.y + radius * 0.12f, p)
        p.style = Paint.Style.FILL
    }

    private fun drawMercuryCrater(canvas: Canvas, point: Projected, radius: Float, dim: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.65f * dp, radius * 0.06f)
        p.color = withAlpha(0xFFE2DED1.toInt(), (115 * dim).toInt())
        canvas.drawCircle(point.x - radius * 0.24f, point.y - radius * 0.12f, radius * 0.25f, p)
        canvas.drawCircle(point.x + radius * 0.33f, point.y + radius * 0.26f, radius * 0.16f, p)
        p.style = Paint.Style.FILL
    }

    private fun drawCenterReticle(canvas: Canvas) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f * dp
        p.color = 0x4489CFFF
        canvas.drawCircle(width / 2f, height / 2f, 10f * dp, p)
        canvas.drawLine(width / 2f - 15f * dp, height / 2f, width / 2f - 7f * dp, height / 2f, p)
        canvas.drawLine(width / 2f + 7f * dp, height / 2f, width / 2f + 15f * dp, height / 2f, p)
        canvas.drawLine(width / 2f, height / 2f - 15f * dp, width / 2f, height / 2f - 7f * dp, p)
        canvas.drawLine(width / 2f, height / 2f + 7f * dp, width / 2f, height / 2f + 15f * dp, p)
        p.style = Paint.Style.FILL
    }

    private fun drawStatus(canvas: Canvas) {
        textPaint.shader = null
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 11f * dp
        textPaint.color = 0xFFBBD7F1.toInt()
        textPaint.setShadowLayer(4f * dp, 0f, 1f * dp, Color.BLACK)
        var y = 88f * dp
        if (!hasLocation) {
            canvas.drawText("Location unavailable · allow location for accurate sky positions", 14f * dp, y, textPaint)
            y += 17f * dp
        }
        if (lowAccuracy) canvas.drawText("Compass accuracy is low · move the phone in a figure‑8", 14f * dp, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun formatHours(degrees: Double): String {
        val hours = degrees / 15.0
        return "%dh %02dm".format(hours.toInt(), ((hours - hours.toInt()) * 60).toInt())
    }

    private fun info(body: Body, jd: Double): String {
        val (altitude, azimuth) = Astro.altAz(body.ra, body.dec, lat, lon, jd)
        val magnitude = if (body.name == "Moon") "" else "Magnitude %.1f\n".format(body.mag)
        val distance = if (body.dist.isEmpty()) "" else "\n${body.dist}"
        return "${body.name} · ${body.type}\n${magnitude}RA ${formatHours(body.ra)}   Dec %.2f°\nAltitude %.1f°   Azimuth %.1f°%s".format(
            body.dec, altitude, azimuth, distance
        ) + if (altitude < 0) "\nBelow the horizon" else ""
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                gestureScaling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> gestureScaling = true
            MotionEvent.ACTION_UP -> {
                if (!gestureScaling && !scaleDetector.isInProgress && hypot(event.x - downX, event.y - downY) < 20f * dp) {
                    val closest = hits.minByOrNull { hypot(it.second.x - event.x, it.second.y - event.y) }
                    if (closest != null && hypot(closest.second.x - event.x, closest.second.y - event.y) < 60f * dp) {
                        selected = closest.first.name
                        lastInfo = System.currentTimeMillis()
                        onSelect?.invoke(info(closest.first, Astro.jd(System.currentTimeMillis())))
                    } else {
                        selected = null
                        onSelect?.invoke(null)
                    }
                    invalidate()
                }
                gestureScaling = false
            }
            MotionEvent.ACTION_CANCEL -> gestureScaling = false
        }
        return true
    }

    companion object {
        private const val MIN_FOV = 5.0
        private const val MAX_FOV = 60.0
    }
}
