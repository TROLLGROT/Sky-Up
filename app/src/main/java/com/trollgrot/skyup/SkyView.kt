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
    var showConstellations = true; var showConNames = true; var showGrid = false; var showGround = true
    var daylightSky = true; var nightMode = false; var starLimit = 5.5
    var timeOffset = 0L
    fun now() = System.currentTimeMillis() + timeOffset
    private var traceName: String? = null
    private var traceJd = 0.0
    private val trace = ArrayList<DoubleArray>()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val quad = Path()
    fun select(name: String) {
        selected = name; lastInfo = System.currentTimeMillis(); val jd = Astro.jd(now())
        Astro.all(jd).firstOrNull { it.name == name }?.let { onSelect?.invoke(info(it, jd)) }
        invalidate()
    }
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
        val julianDay = Astro.jd(now())
        fun project(ra: Double, dec: Double): Projected? {
            val (altitude, azimuth) = Astro.altAz(ra, dec, lat, lon, julianDay)
            return projectAzAlt(azimuth, altitude, focal)
        }

        val bodies = Astro.all(julianDay)
        val sunAlt = bodies.first { it.name == "Sun" }.let { Astro.altAz(it.ra, it.dec, lat, lon, julianDay).first }
        val day = if (daylightSky) ((sunAlt + 12) / 12).coerceIn(0.0, 1.0).toFloat() else 0f
        val twi = if (daylightSky) (1 - abs(sunAlt + 5) / 11).coerceIn(0.0, 1.0).toFloat() else 0f
        val starFade = 1f - day * 0.97f
        if (day > 0f) canvas.drawColor(withAlpha(0xFF3A78B8.toInt(), (235 * day).toInt()))
        val haze = lerpC(lerpC(0xFF1B3A6B.toInt(), 0xFF9CC8F0.toInt(), day), 0xFFFF8A3C.toInt(), twi * 0.8f)
        for (i in 0 until 6) fillBand(canvas, focal, i * 4.0 + 4, i * 4.0, withAlpha(haze, (150 * (1 - i / 6f)).toInt()))
        if (Catalog.stars.isEmpty()) drawBackgroundStars(canvas, ::project)
        if (showGrid) drawGrid(canvas, focal)
        if (showConstellations) drawConstellations(canvas, julianDay, day, ::project)
        if (showGround) drawGround(canvas, focal, day)
        drawHorizonAndEcliptic(canvas, focal, julianDay, ::project)
        drawCardinalPoints(canvas, focal)

        hits.clear()
        val zoom = (ln(MAX_FOV / fovDeg) / ln(12.0)).coerceIn(0.0, 1.0).toFloat()
        for (body in bodies) {
            val point = project(body.ra, body.dec) ?: continue
            if (point.x < -100 || point.x > width + 100 || point.y < -100 || point.y > height + 100) continue
            if (showGround && point.alt < -0.3 && body.name != selected) continue
            if (body.type == "Star" && body.mag > starLimit) continue
            val dim = (if (point.alt < 0) 0.35f else 1f) * (if (body.type == "Star") starFade else if (body.name == "Sun") 1f else 1f - day * 0.55f)
            val radius = when (body.name) {
                "Sun" -> 13f * dp + 2f * dp * zoom
                "Moon" -> 10f * dp + 5f * dp * zoom
                else -> if (body.type == "Planet") planetRadius(body.name, zoom) else starRadius(body.mag)
            }
            if (body.name == "Sun") drawSun(canvas, point, radius, dim)
            else if (body.name == "Moon") drawMoon(canvas, point, radius, dim)
            else if (body.type == "Planet") drawPlanet(canvas, body.name, point, radius, dim, zoom)
            else drawStar(canvas, point, radius, dim, body.mag, body.bv)

            if (body.type == "Planet" && drawPlanetNames || body.name == "Sun" || body.name == "Moon" || body.type == "Star" && drawStarNames && !body.name.startsWith("HIP") && body.mag < 1.7 + 2.5 * zoom) {
                textPaint.style = Paint.Style.FILL
                textPaint.textSize = if (body.type == "Star") 10.5f * dp else 12f * dp
                textPaint.color = if (body.type == "Star") 0xFFD3E5FF.toInt() else 0xFFF3F8FF.toInt()
                textPaint.setShadowLayer(4f * dp, 0f, 0f, 0xBB6DBBFF.toInt())
                canvas.drawText(L.name(body.name), point.x + radius + 4f * dp, point.y - 2f * dp, textPaint)
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

        drawTrace(canvas, focal, julianDay, bodies)
        drawArrow(canvas, julianDay, bodies)
        drawCenterReticle(canvas)
        drawStatus(canvas)
        if (nightMode) canvas.drawColor(0xFFFF3020.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
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
        for ((label, azimuth) in listOf(L.t("N", "С") to 0.0, L.t("E", "В") to 90.0, L.t("S", "Ю") to 180.0, L.t("W", "З") to 270.0)) {
            projectAzAlt(azimuth, 0.0, focal)?.let { canvas.drawText(label, it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        }
        textPaint.clearShadowLayer()
    }

    private fun starRadius(magnitude: Double): Float = max(1.0f, 3.8f - magnitude.toFloat() * 0.72f) * dp

    private fun drawStar(canvas: Canvas, point: Projected, radius: Float, dim: Float, magnitude: Double, bv: Double = 0.6) {
        val color = when { bv < 0.0 -> 0xFF9DBBFF; bv < 0.3 -> 0xFFC9DAFF; bv < 0.6 -> 0xFFF2F4FF; bv < 1.0 -> 0xFFFFE9B8; bv < 1.4 -> 0xFFFFC27A; else -> 0xFFFF9A62 }.toInt()
        p.style = Paint.Style.FILL
        if (magnitude > 3.3) {
            p.color = withAlpha(color, ((70 + (5.6 - magnitude) * 55) * dim).toInt())
            canvas.drawCircle(point.x, point.y, radius * 0.95f, p)
            return
        }
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
        var y = 126f * dp
        if (!hasLocation) {
            canvas.drawText(L.t("Location unavailable · allow location for accurate sky positions", "Нет геолокации · разрешите доступ для точного неба"), 14f * dp, y, textPaint)
            y += 17f * dp
        }
        if (lowAccuracy) canvas.drawText(L.t("Compass accuracy is low · move the phone in a figure‑8", "Низкая точность компаса · опишите телефоном восьмёрку"), 14f * dp, y, textPaint)
        textPaint.clearShadowLayer()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun formatHours(degrees: Double): String {
        val hours = degrees / 15.0
        return L.t("%dh %02dm", "%dч %02dм").format(hours.toInt(), ((hours - hours.toInt()) * 60).toInt())
    }

    private fun riseSet(b: Body, jd: Double): String {
        var rise: String? = null; var set: String? = null
        var prev = Astro.altAz(b.ra, b.dec, lat, lon, jd).first
        for (k in 1..144) {
            val j = jd + k / 144.0; val a = Astro.altAz(b.ra, b.dec, lat, lon, j).first
            val t = timeFmt.format(java.util.Date(((j - 2440587.5) * 86400000).toLong()))
            if (prev < 0 && a >= 0 && rise == null) rise = t
            if (prev >= 0 && a < 0 && set == null) set = t
            prev = a
        }
        return L.t("Rise ", "Восход ") + (rise ?: "—") + "   " + L.t("Set ", "Закат ") + (set ?: "—")
    }

    private fun info(body: Body, jd: Double): String {
        val (alt, az) = Astro.altAz(body.ra, body.dec, lat, lon, jd)
        val mag = if (body.name == "Moon") "" else L.t("Magnitude ", "Звёздная величина ") + "%.1f\n".format(body.mag)
        val dist = if (body.dist.isEmpty()) "" else "\n" + (if (L.ru) body.dist.replace("Distance from Earth", "Расстояние от Земли").replace("Distance", "Расстояние").replace(" AU", " а.е.").replace(" km", " км") else body.dist)
        return "${L.name(body.name)} · ${L.type(body.type)}\n$mag" + L.t("RA ", "Пр. восх. ") + formatHours(body.ra) + "   " + L.t("Dec ", "Скл. ") + "%.2f°\n".format(body.dec) +
            L.t("Altitude ", "Высота ") + "%.1f°   ".format(alt) + L.t("Azimuth ", "Азимут ") + "%.1f°".format(az) + dist + "\n" + riseSet(body, jd) +
            (if (alt < 0) "\n" + L.t("Below the horizon", "Под горизонтом") else "")
    }

    private fun lerpC(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun fillBand(c: Canvas, f: Float, altTop: Double, altBot: Double, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f; p.color = color
        for (az in 0 until 360 step 6) {
            val a = projectAzAlt(az.toDouble(), altTop, f); val b = projectAzAlt(az + 6.0, altTop, f)
            val d = projectAzAlt(az + 6.0, altBot, f); val e = projectAzAlt(az.toDouble(), altBot, f)
            if (a == null || b == null || d == null || e == null) continue
            if (maxOf(a.x, b.x, d.x, e.x) < 0 || minOf(a.x, b.x, d.x, e.x) > width || maxOf(a.y, b.y, d.y, e.y) < 0 || minOf(a.y, b.y, d.y, e.y) > height) continue
            quad.rewind(); quad.moveTo(a.x, a.y); quad.lineTo(b.x, b.y); quad.lineTo(d.x, d.y); quad.lineTo(e.x, e.y); quad.close()
            c.drawPath(quad, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun drawGround(c: Canvas, f: Float, day: Float) {
        val top = lerpC(0xFF0C1624.toInt(), 0xFF2A453D.toInt(), day); val deep = lerpC(0xFF03060B.toInt(), 0xFF101C1A.toInt(), day)
        for (k in 0 until 9) fillBand(c, f, -k * 10.0, -k * 10.0 - 10, lerpC(top, deep, min(1f, (k + 0.5f) / 3f)))
    }

    private fun drawGrid(c: Canvas, f: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * dp; p.color = 0x3358A6E0
        for (alt in listOf(30.0, 60.0)) { var pr: Projected? = null
            for (az in 0..360 step 4) { val q = projectAzAlt(az.toDouble(), alt, f); if (pr != null && q != null) c.drawLine(pr.x, pr.y, q.x, q.y, p); pr = q } }
        for (az in 0 until 360 step 30) { var pr: Projected? = null
            for (alt in 0..90 step 4) { val q = projectAzAlt(az.toDouble(), alt.toDouble(), f); if (pr != null && q != null) c.drawLine(pr.x, pr.y, q.x, q.y, p); pr = q } }
        p.style = Paint.Style.FILL
    }

    private fun drawConstellations(c: Canvas, jd: Double, day: Float, project: (Double, Double) -> Projected?) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1f * dp; p.color = withAlpha(0xFF7FB6E8.toInt(), (120 * (1 - day * 0.9f)).toInt())
        for (ln in Catalog.lines) {
            var pr: Projected? = null; var i = 0
            while (i + 1 < ln.size) {
                val (r, d) = Astro.precess(ln[i], ln[i + 1], jd); val q = project(r, d)
                if (pr != null && q != null && !(showGround && (pr.alt < 0 || q.alt < 0))) c.drawLine(pr.x, pr.y, q.x, q.y, p)
                pr = q; i += 2
            }
        }
        p.style = Paint.Style.FILL
        if (showConNames && day < 0.6f) {
            textPaint.textSize = 10f * dp; textPaint.color = withAlpha(0xFF9CC8F0.toInt(), 150); textPaint.textAlign = Paint.Align.CENTER
            for (k in Catalog.cons) { val (r, d) = Astro.precess(k.ra, k.dec, jd); val q = project(r, d) ?: continue
                if (showGround && q.alt < 0) continue
                c.drawText(L.t(k.en, k.ru).uppercase(), q.x, q.y, textPaint) }
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawTrace(c: Canvas, f: Float, jd: Double, bodies: List<Body>) {
        val name = selected ?: return
        if (traceName != name || abs(jd - traceJd) > 0.03) {
            traceName = name; traceJd = jd; trace.clear()
            val star = bodies.firstOrNull { it.name == name }?.takeIf { it.type == "Star" }
            for (k in 0..288) {
                val j = jd + k * 5.0 / 1440
                val b = star ?: Astro.all(j, false).firstOrNull { it.name == name } ?: return
                val (alt, az) = Astro.altAz(b.ra, b.dec, lat, lon, j); trace.add(doubleArrayOf(alt, az, j))
            }
        }
        var pr: Projected? = null
        for ((k, t) in trace.withIndex()) {
            val q = projectAzAlt(t[1], t[0], f)
            if (q != null) {
                val a = if (t[0] < 0) 70 else 230
                if (pr != null) { p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * dp; p.color = withAlpha(0xFF9BE7FF.toInt(), a / 3); c.drawLine(pr.x, pr.y, q.x, q.y, p) }
                p.style = Paint.Style.FILL; p.color = withAlpha(0xFF9BE7FF.toInt(), a)
                val hour = k % 12 == 0
                c.drawCircle(q.x, q.y, (if (hour) 3.2f else 1.5f) * dp, p)
                if (hour) { textPaint.textSize = 10f * dp; textPaint.color = withAlpha(0xFFD8F3FF.toInt(), a); c.drawText(timeFmt.format(java.util.Date(((t[2] - 2440587.5) * 86400000).toLong())), q.x + 6 * dp, q.y - 6 * dp, textPaint) }
            }
            pr = q
        }
    }

    /** Edge arrow pointing to the selected object when it is outside the field of view. */
    private fun drawArrow(c: Canvas, jd: Double, bodies: List<Body>) {
        val b = bodies.firstOrNull { it.name == selected } ?: return
        val (alt, az) = Astro.altAz(b.ra, b.dec, lat, lon, jd)
        val a = Math.toRadians(az - decl); val e = Math.toRadians(alt)
        val wx = cos(e) * sin(a); val wy = cos(e) * cos(a); val wz = sin(e); val r = rot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz; val dy = r[1] * wx + r[4] * wy + r[7] * wz; val dz = r[2] * wx + r[5] * wy + r[8] * wz
        val sep = Math.toDegrees(acos((-dz).coerceIn(-1.0, 1.0)))
        if (sep < fovDeg * 0.45) return
        val ang = atan2(-dy, dx).toFloat(); val rad = min(width, height) / 2f - 70f * dp
        val cx = width / 2f + cos(ang) * rad; val cy = height / 2f + sin(ang) * rad
        c.save(); c.rotate(Math.toDegrees(ang.toDouble()).toFloat(), cx, cy)
        val tri = Path(); tri.moveTo(cx + 16 * dp, cy); tri.lineTo(cx - 10 * dp, cy - 12 * dp); tri.lineTo(cx - 10 * dp, cy + 12 * dp); tri.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0xFF58B9F5.toInt(); c.drawPath(tri, p); c.restore()
        textPaint.textSize = 12f * dp; textPaint.color = 0xFFEAF6FF.toInt(); textPaint.textAlign = Paint.Align.CENTER
        c.drawText("${L.name(b.name)} · ${sep.toInt()}°", cx - cos(ang) * 30 * dp, cy - sin(ang) * 30 * dp, textPaint); textPaint.textAlign = Paint.Align.LEFT
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
                        onSelect?.invoke(info(closest.first, Astro.jd(now())))
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
