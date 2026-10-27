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
    @Volatile var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var frameRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
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
    var showConstellations = true; var showConNames = true; var showGrid = false; var showGround = false
    var daylightSky = false; var nightMode = false; var starLimit = 5.5
    var timeOffset = 0L
    fun now() = System.currentTimeMillis() + timeOffset
    private var traceName: String? = null
    private var traceJd = 0.0
    private var traceLat = Double.NaN
    private var traceLon = Double.NaN
    private var traceDecl = Double.NaN
    private val trace = ArrayList<DoubleArray>()
    private val traceLabels = ArrayList<String?>()
    private val tracePath = Path()
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val quad = Path()
    private val quadCoords = FloatArray(8)
    private val azimuthSin = DoubleArray(360)
    private val azimuthCos = DoubleArray(360)
    private var trigDeclination = Double.NaN
    private val skylineSamples = DoubleArray(361) { i ->
        val a = Math.toRadians(i.toDouble())
        0.9 + 0.5 * sin(3 * a + 0.3) + 0.3 * sin(7 * a + 1.7) + 0.18 * sin(17 * a + 4.0)
    }
    private val dwellRect = RectF()
    private val arrowPath = Path()
    private var backgroundShader: Shader? = null
    private var backgroundShaderWidth = -1
    private var backgroundShaderHeight = -1
    private var cachedBodies: List<Body> = emptyList()
    private var cachedBodiesJd = Double.NaN
    private var eclipticCacheJd = Double.NaN
    private var eclipticCache: List<DoubleArray> = emptyList()
    fun select(name: String, openDetails: Boolean = true) {
        selected = name; val jd = Astro.jd(now())
        if (openDetails) Astro.all(jd).firstOrNull { it.name == name }?.let { onSelect?.invoke(info(it, jd)) }
        invalidate()
    }
    var onSelect: ((String?) -> Unit)? = null

    private var selected: String? = null
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private data class Hit(var body: Body, var x: Float, var y: Float)
    private val hits = ArrayList<Hit>()
    private var hitCount = 0
    private val frameIntervalMs = 16L
    private var lastFrameRequestAt = 0L
    private var frameRequestQueued = false
    private val queuedFrameRequest = Runnable {
        frameRequestQueued = false
        lastFrameRequestAt = android.os.SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }
    fun requestFrame() {
        val now = android.os.SystemClock.uptimeMillis()
        val delay = frameIntervalMs - (now - lastFrameRequestAt)
        if (delay <= 0L) {
            lastFrameRequestAt = now
            postInvalidateOnAnimation()
        } else if (!frameRequestQueued) {
            frameRequestQueued = true
            postDelayed(queuedFrameRequest, delay)
        }
    }
    private fun addHit(body: Body, x: Float, y: Float) {
        if (hitCount == hits.size) hits.add(Hit(body, x, y))
        else hits[hitCount].apply { this.body = body; this.x = x; this.y = y }
        hitCount++
    }
    private var downX = 0f
    private var downY = 0f
    private var gestureScaling = false
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            gestureScaling = true
            fovDeg = (fovDeg / detector.scaleFactor.toDouble()).coerceIn(MIN_FOV, MAX_FOV)
            requestFrame()
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
        val r = frameRot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        // stereographic projection: far less edge stretching, so the sky no longer feels like the inside of a sphere
        if (1 - dz < 0.4) return null
        val k = 2.0 * focalLength / (1 - dz)
        return Projected((width / 2.0 + k * dx).toFloat(), (height / 2.0 - k * dy).toFloat(), altitude)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF020817.toInt())
        if (width == 0 || height == 0) return
        // Read the atomically published sensor matrix once so every layer in a frame
        // uses the same orientation rather than mixing values from two sensor events.
        frameRot = rot
        ensureAzimuthTrigCache()

        p.style = Paint.Style.FILL
        if (backgroundShader == null || backgroundShaderWidth != width || backgroundShaderHeight != height) {
            backgroundShaderWidth = width
            backgroundShaderHeight = height
            backgroundShader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF06152E.toInt(), 0xFF020817.toInt(), 0xFF0B1530.toInt()), null, Shader.TileMode.CLAMP)
        }
        p.shader = backgroundShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null

        val focal = ((height / 2.0) / (2 * tan(Math.toRadians(fovDeg / 4.0)))).toFloat()
        val julianDay = Astro.jd(now())
        prepMatrix(julianDay)
        fun project(ra: Double, dec: Double): Projected? {
            val (altitude, azimuth) = Astro.altAz(ra, dec, lat, lon, julianDay)
            return projectAzAlt(azimuth, altitude, focal)
        }

        // Ephemeris changes slowly compared with the sensor-driven frame rate. Reuse the
        // last positions for up to one second rather than recalculating every rendered frame.
        val bodies = if (cachedBodies.isEmpty() || abs(julianDay - cachedBodiesJd) >= 1.0 / 86400.0) {
            Astro.all(julianDay, false).also { cachedBodies = it; cachedBodiesJd = julianDay }
        } else cachedBodies
        val sunAlt = bodies.first { it.name == "Sun" }.let { Astro.altAz(it.ra, it.dec, lat, lon, julianDay).first }
        val day = if (daylightSky) ((sunAlt + 12) / 12).coerceIn(0.0, 1.0).toFloat() else 0f
        val twi = if (daylightSky) (1 - abs(sunAlt + 5) / 11).coerceIn(0.0, 1.0).toFloat() else 0f
        val starFade = 1f - day * 0.97f
        if (day > 0f) canvas.drawColor(withAlpha(0xFF3A78B8.toInt(), (235 * day).toInt()))
        val haze = lerpC(lerpC(0xFF1B3A6B.toInt(), 0xFF9CC8F0.toInt(), day), 0xFFFF8A3C.toInt(), twi * 0.8f)
        if (day > 0f) for (i in 0 until 8) fillBand(canvas, focal, i * 3.5 + 3.5, i * 3.5, withAlpha(haze, (130 * (1 - i / 8f) * (1 - i / 8f)).toInt()))
        if (showGrid) drawGrid(canvas, focal)
        if (showConstellations) drawConstellations(canvas, julianDay, day, focal, ::project)
        drawHorizonAndEcliptic(canvas, focal, julianDay, ::project)
        drawCardinalPoints(canvas, focal)

        hitCount = 0
        val zoom = (ln(MAX_FOV / fovDeg) / ln(12.0)).coerceIn(0.0, 1.0).toFloat()
        val catalogStars = Astro.stars(julianDay)
        if (catalogStars.isEmpty()) drawBackgroundStars(canvas, ::project)
        else drawStars(canvas, catalogStars, focal, zoom, starFade)
        for (body in bodies) {
            if (body.type == "Star" && body.mag > starLimit) continue
            val point = project(body.ra, body.dec) ?: continue
            if (point.x < -100 || point.x > width + 100 || point.y < -100 || point.y > height + 100) continue
            if (showGround && point.alt < -2.0 && body.name != selected) continue
            if (body.type == "Star" && body.mag > starLimit) continue
            val dim = (0.35f + 0.65f * ((point.alt + 1) / 2).coerceIn(0.0, 1.0).toFloat()) * (if (body.type == "Star") starFade else if (body.name == "Sun") 1f else 1f - day * 0.55f)
            val radius = when (body.name) {
                "Sun" -> 13f * dp + 2f * dp * zoom
                "Moon" -> 10f * dp + 5f * dp * zoom
                else -> if (body.type == "Planet") planetRadius(body.name, zoom) else starRadius(body.mag)
            }
            if (body.name == "Sun") drawSun(canvas, point, radius, dim)
            else if (body.name == "Moon") drawMoon(canvas, point, radius, dim)
            else if (body.type == "Planet") { if (radius < 7f * dp) drawGlowPlanet(canvas, body, point, dim) else drawPlanet(canvas, body.name, point, radius, dim, zoom) }
            else drawStar(canvas, point, radius, dim, body.mag, body.bv)

            if (body.type == "Planet" && drawPlanetNames || body.name == "Sun" || body.name == "Moon" || body.type == "Star" && drawStarNames && !body.name.startsWith("HIP") && body.mag < 1.7 + 2.5 * zoom) {
                textPaint.style = Paint.Style.FILL
                textPaint.textSize = if (body.type == "Star") 10.5f * dp else 12f * dp
                textPaint.color = if (body.type == "Star") 0xFFD3E5FF.toInt() else 0xFFF3F8FF.toInt()
                textPaint.setShadowLayer(4f * dp, 0f, 0f, 0xBB6DBBFF.toInt())
                canvas.drawText(L.name(body.name), point.x + radius + 4f * dp, point.y - 2f * dp, textPaint)
                textPaint.clearShadowLayer()
            }
            addHit(body, point.x, point.y)

            if (body.name == selected) {
                p.shader = null
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f * dp
                p.color = 0xFF9BE7FF.toInt()
                canvas.drawCircle(point.x, point.y, radius + 8f * dp, p)
                p.style = Paint.Style.FILL
            }
        }

        if (showGround) drawGround(canvas, focal, day)
        drawTrace(canvas, focal, julianDay, bodies)
        updateDwell(canvas)
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
        if (showGround) {
            for (azimuth in 0..360 step 3) {
                val current = projectAzAlt(azimuth.toDouble(), 0.0, focal)
                if (previous != null && current != null) canvas.drawLine(previous.x, previous.y, current.x, current.y, p)
                previous = current
            }
        }

        p.strokeWidth = 1f * dp
        p.color = 0x48F6D58A
        previous = null
        if (eclipticCache.isEmpty() || abs(jd - eclipticCacheJd) >= 1.0) {
            eclipticCacheJd = jd
            eclipticCache = (0..360 step 3).map { longitude ->
                val (ra, dec) = Astro.eclipticPoint(longitude.toDouble(), jd)
                val r = Math.toRadians(ra); val d = Math.toRadians(dec)
                doubleArrayOf(cos(d) * cos(r), cos(d) * sin(r), sin(d))
            }
        }
        val m = M; val cx = width / 2f; val cy = height / 2f; val k2 = 2.0 * focal
        var prevX = 0f; var prevY = 0f; var prevOk = false
        for (v in eclipticCache) {
            val den = 1.0 - (m[6] * v[0] + m[7] * v[1] + m[8] * v[2])
            if (den < 0.4) { prevOk = false; continue }
            val k = k2 / den
            val x = (cx + k * (m[0] * v[0] + m[1] * v[1] + m[2] * v[2])).toFloat()
            val y = (cy - k * (m[3] * v[0] + m[4] * v[1] + m[5] * v[2])).toFloat()
            if (prevOk) canvas.drawLine(prevX, prevY, x, y, p)
            prevX = x; prevY = y; prevOk = true
        }
        p.style = Paint.Style.FILL
    }

    private fun drawCardinalPoints(canvas: Canvas, focal: Float) {
        textPaint.shader = null
        textPaint.setShadowLayer(5f * dp, 0f, 0f, 0xAA65B8FF.toInt())
        textPaint.textSize = 14f * dp
        textPaint.color = 0xFF8EC8F5.toInt()
        projectAzAlt(0.0, 0.0, focal)?.let { canvas.drawText(L.t("N", "С"), it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        projectAzAlt(90.0, 0.0, focal)?.let { canvas.drawText(L.t("E", "В"), it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        projectAzAlt(180.0, 0.0, focal)?.let { canvas.drawText(L.t("S", "Ю"), it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        projectAzAlt(270.0, 0.0, focal)?.let { canvas.drawText(L.t("W", "З"), it.x - 5f * dp, it.y - 8f * dp, textPaint) }
        textPaint.clearShadowLayer()
    }

    private fun starRadius(magnitude: Double): Float = max(1.0f, 3.8f - magnitude.toFloat() * 0.72f) * dp

    private val sprites = HashMap<Int, android.graphics.Bitmap>()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = android.graphics.Rect(0, 0, 128, 128)
    private val dstR = RectF()
    private fun sprite(color: Int): android.graphics.Bitmap = sprites.getOrPut(color) {
        val bmp = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); val pp = Paint(Paint.ANTI_ALIAS_FLAG); val h = 64f; val core = h / 7f
        pp.shader = RadialGradient(h, h, h, intArrayOf(withAlpha(color, 170), withAlpha(color, 70), withAlpha(color, 22), withAlpha(color, 0)), floatArrayOf(0f, 0.1f, 0.38f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(h, h, h, pp)
        pp.shader = RadialGradient(h, h, core * 1.7f, intArrayOf(0xFFFFFFFF.toInt(), withAlpha(color, 220), withAlpha(color, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(h, h, core * 1.7f, pp)
        bmp
    }

    private fun glowPoint(c: Canvas, x: Float, y: Float, core: Float, color: Int, dim: Float, spikes: Boolean) {
        val big = core * 7f
        dstR.set(x - big, y - big, x + big, y + big); spritePaint.alpha = (255 * dim).toInt().coerceIn(0, 255)
        c.drawBitmap(sprite(color), srcR, dstR, spritePaint)
        if (spikes) {
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * dp; p.color = withAlpha(color, (110 * dim).toInt())
            c.drawLine(x - core * 5f, y, x + core * 5f, y, p); c.drawLine(x, y - core * 5f, x, y + core * 5f, p); p.style = Paint.Style.FILL
        }
    }

    private fun bvColor(bv: Double): Int = when { bv < 0.0 -> 0xFF9DBBFF; bv < 0.3 -> 0xFFC9DAFF; bv < 0.6 -> 0xFFF2F4FF; bv < 1.0 -> 0xFFFFE9B8; bv < 1.4 -> 0xFFFFC27A; else -> 0xFFFF9A62 }.toInt()

    private fun drawStar(canvas: Canvas, point: Projected, radius: Float, dim: Float, magnitude: Double, bv: Double = 0.6) =
        glowPoint(canvas, point.x, point.y, radius * 0.75f, bvColor(bv), dim, magnitude < 0.6)

    private fun drawGlowPlanet(c: Canvas, b: Body, pt: Projected, dim: Float) {
        val col = when (b.name) { "Mercury" -> 0xFFE4E0D4; "Venus" -> 0xFFFFF0C8; "Mars" -> 0xFFFFA070; "Jupiter" -> 0xFFFFE2B8; "Saturn" -> 0xFFFFE9A8; "Uranus" -> 0xFFBFF4F8; else -> 0xFF9CBFFF }.toInt()
        glowPoint(c, pt.x, pt.y, min(5.6f, max(2.4f, 3.3f - b.mag.toFloat() * 0.35f)) * dp, col, dim, b.mag < -1.5)
    }

    // ---- fast star pipeline: one 3x3 matrix per frame (equatorial -> device), no trig per star
    private val M = DoubleArray(9)
    private val Ueq = DoubleArray(3)
    private val basisE = DoubleArray(3)
    private val basisN = DoubleArray(3)
    private val basisUp = DoubleArray(3)
    private val basisW0 = DoubleArray(3)
    private val basisW1 = DoubleArray(3)
    private fun prepMatrix(jd: Double) {
        val th = Math.toRadians(Astro.norm(280.46061837 + 360.98564736629 * (jd - 2451545.0) + lon)); val ph = Math.toRadians(lat); val ps = Math.toRadians(decl)
        val st = sin(th); val ct = cos(th); val sp = sin(ph); val cp = cos(ph); val sps = sin(ps); val cps = cos(ps)
        val e = basisE; val n = basisN; val up = basisUp; val w0 = basisW0; val w1 = basisW1
        e[0] = -st; e[1] = ct; e[2] = 0.0
        n[0] = -ct * sp; n[1] = -st * sp; n[2] = cp
        up[0] = ct * cp; up[1] = st * cp; up[2] = sp
        Ueq[0] = up[0]; Ueq[1] = up[1]; Ueq[2] = up[2]
        for (i in 0..2) { w0[i] = cps * e[i] - sps * n[i]; w1[i] = sps * e[i] + cps * n[i] }
        val r = frameRot
        for (i in 0..2) for (k in 0..2) M[i * 3 + k] = r[i] * w0[k] + r[3 + i] * w1[k] + r[6 + i] * up[k]
    }

    private var bufA = FloatArray(0); private var bufB = FloatArray(0); private var bufC = FloatArray(0)
    private val ptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    private fun drawStars(c: Canvas, st: List<Body>, f: Float, zoom: Float, fade: Float) {
        if (st.isEmpty()) return
        if (bufA.size < st.size * 2) { bufA = FloatArray(st.size * 2); bufB = FloatArray(st.size * 2); bufC = FloatArray(st.size * 2) }
        var na = 0; var nb = 0; var nc = 0
        val m = M; val u = Ueq; val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val k2 = 2.0 * f
        val sel = selected
        for (b in st) {
            if (b.mag > starLimit) break
            val den = 1 - (m[6] * b.vx + m[7] * b.vy + m[8] * b.vz)
            if (den < 0.4) continue
            val k = k2 / den
            val x = (cx + k * (m[0] * b.vx + m[1] * b.vy + m[2] * b.vz)).toFloat(); val y = (cy - k * (m[3] * b.vx + m[4] * b.vy + m[5] * b.vz)).toFloat()
            val isSel = sel != null && b.name == sel
            if (!isSel && (x < -30f || x > w + 30f || y < -30f || y > h + 30f)) continue
            val uu = u[0] * b.vx + u[1] * b.vy + u[2] * b.vz
            if (showGround && uu < -0.035 && !isSel) continue
            var dim = fade
            if (uu < 0.035) dim *= (0.35 + 0.65 * ((Math.toDegrees(asin(uu.coerceIn(-1.0, 1.0))) + 1) / 2).coerceIn(0.0, 1.0)).toFloat()
            if (dim < 0.02f) continue
            val rad = starRadius(b.mag)
            if (b.mag > 3.3) {
                if (b.mag < 4.6) { bufA[na++] = x; bufA[na++] = y } else if (b.mag < 5.6) { bufB[nb++] = x; bufB[nb++] = y } else { bufC[nc++] = x; bufC[nc++] = y }
            } else glowPoint(c, x, y, rad * 0.75f, bvColor(b.bv), dim, b.mag < 0.6)
            if (b.mag < 1.7 + 2.5 * zoom && drawStarNames && !b.name.startsWith("HIP")) {
                textPaint.style = Paint.Style.FILL; textPaint.textSize = 10.5f * dp; textPaint.color = 0xFFD3E5FF.toInt()
                textPaint.setShadowLayer(4f * dp, 0f, 0f, 0xBB6DBBFF.toInt())
                c.drawText(L.name(b.name), x + rad + 4f * dp, y - 2f * dp, textPaint); textPaint.clearShadowLayer()
            }
            if (b.mag <= 5.0 || isSel) addHit(b, x, y)
            if (isSel) {
                p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f * dp; p.color = 0xFF9BE7FF.toInt()
                c.drawCircle(x, y, rad + 8f * dp, p); p.style = Paint.Style.FILL
            }
        }
        ptPaint.color = withAlpha(0xFFDDE8FF.toInt(), (205 * fade).toInt()); ptPaint.strokeWidth = 2.6f * dp; c.drawPoints(bufA, 0, na, ptPaint)
        ptPaint.color = withAlpha(0xFFDDE8FF.toInt(), (150 * fade).toInt()); ptPaint.strokeWidth = 2.1f * dp; c.drawPoints(bufB, 0, nb, ptPaint)
        ptPaint.color = withAlpha(0xFFDDE8FF.toInt(), (105 * fade).toInt()); ptPaint.strokeWidth = 1.7f * dp; c.drawPoints(bufC, 0, nc, ptPaint)
    }

    private var dwellName: String? = null
    private var dwellStart = 0L
    /** Hold the crosshair on an object for one second to select it. */
    private fun updateDwell(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val lim = 30.0 * dp
        var best: Body? = null; var bs = Double.POSITIVE_INFINITY
        for (i in 0 until hitCount) {
            val hit = hits[i]; val d = hypot((hit.x - cx).toDouble(), (hit.y - cy).toDouble())
            if (d > lim) continue
            val sc = d - (if (hit.body.mag < 2.5) 12.0 * dp else 0.0)
            if (sc < bs) { bs = sc; best = hit.body }
        }
        val nm = best?.name; val t = System.currentTimeMillis()
        if (nm != dwellName) { dwellName = nm; dwellStart = t }
        if (nm != null && nm != selected) {
            val pr = ((t - dwellStart) / 1000f).coerceIn(0f, 1f)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * dp; p.color = 0xFF9BE7FF.toInt()
            dwellRect.set(cx - 18 * dp, cy - 18 * dp, cx + 18 * dp, cy + 18 * dp)
            c.drawArc(dwellRect, -90f, 360f * pr, false, p); p.style = Paint.Style.FILL
            if (pr >= 1f) select(nm, openDetails = false) else requestFrame()
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
            if (prev < 0 && a >= 0 && rise == null) rise = timeFmt.format(java.util.Date(((j - 2440587.5) * 86400000).toLong()))
            if (prev >= 0 && a < 0 && set == null) set = timeFmt.format(java.util.Date(((j - 2440587.5) * 86400000).toLong()))
            prev = a
            if (rise != null && set != null) break
        }
        return L.t("Rise ", "Восход ") + (rise ?: "—") + "   " + L.t("Set ", "Закат ") + (set ?: "—")
    }

    private fun info(body: Body, jd: Double): String {
        val (alt, az) = Astro.altAz(body.ra, body.dec, lat, lon, jd)
        val mag = if (body.name == "Moon") "" else L.t("Magnitude ", "Звёздная величина ") + "%.1f\n".format(body.mag)
        val dist = if (body.dist.isEmpty()) "" else "\n" + (if (L.ru) body.dist.replace("Distance from Earth", "Расстояние от Земли").replace("Distance", "Расстояние").replace(" AU", " а.е.").replace(" km", " км") else body.dist)
        val star = if (body.type == "Star") {
            val facts = Catalog.starFacts[body.name]
            val colorClass = when {
                body.bv < -0.2 -> "O/B"
                body.bv < 0.0 -> "B"
                body.bv < 0.3 -> "A"
                body.bv < 0.58 -> "F"
                body.bv < 0.81 -> "G"
                body.bv < 1.4 -> "K"
                else -> "M"
            }
            if (facts != null) "\n\n${L.t("ABOUT THIS STAR", "О ЗВЕЗДЕ")}\n${L.t("Class", "Класс")}: ${facts.kind}\n${L.t("Radius", "Радиус")}: ${facts.radius}\n${L.t("Mass", "Масса")}: ${facts.mass}\n${L.t("Distance from Earth", "Расстояние от Земли")}: ${facts.distanceLy}\n\n${facts.note}\n${L.t("Values are rounded reference estimates.", "Значения округлены и приведены для справки.")}"
            else "\n\n${L.t("ABOUT THIS STAR", "О ЗВЕЗДЕ")}\n${L.t("Approximate colour class", "Примерный спектральный класс по цвету")}: $colorClass\n${L.t("Color index B−V", "Индекс цвета B−V")}: %.2f\n".format(body.bv) +
                L.t("The catalog has no reliable radius, mass or distance for this star yet.", "В каталоге пока нет надёжных данных о радиусе, массе и расстоянии этой звезды.")
        } else ""
        return "${L.name(body.name)} · ${L.type(body.type)}\n$mag" + L.t("RA ", "Пр. восх. ") + formatHours(body.ra) + "   " + L.t("Dec ", "Скл. ") + "%.2f°\n".format(body.dec) +
            L.t("Altitude ", "Высота ") + "%.1f°   ".format(alt) + L.t("Azimuth ", "Азимут ") + "%.1f°".format(az) + dist + star + "\n\n" + riseSet(body, jd) +
            (if (alt < 0) "\n" + L.t("Below the horizon", "Под горизонтом") else "")
    }

    private fun lerpC(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    private fun ensureAzimuthTrigCache() {
        if (trigDeclination == decl) return
        for (i in 0 until 360) {
            val a = Math.toRadians(i - decl)
            azimuthSin[i] = sin(a)
            azimuthCos[i] = cos(a)
        }
        trigDeclination = decl
    }

    // Writes projected coordinates into a shared buffer so the hundreds of horizon-band
    // quads per frame do not allocate temporary Projected objects.
    private fun projectAzAltInto(azimuth: Double, altitude: Double, focalLength: Float, out: FloatArray, offset: Int): Boolean {
        val index = ((azimuth.toInt() % 360) + 360) % 360
        val ce = cos(Math.toRadians(altitude)); val se = sin(Math.toRadians(altitude))
        val wx = ce * azimuthSin[index]; val wy = ce * azimuthCos[index]; val wz = se
        val r = frameRot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        val denom = 1.0 - dz
        if (denom < 0.4) return false
        val k = 2.0 * focalLength / denom
        out[offset] = (width / 2.0 + k * dx).toFloat()
        out[offset + 1] = (height / 2.0 - k * dy).toFloat()
        return true
    }

    private fun quadAppend(path: Path, f: Float, az0: Double, t0: Double, az1: Double, t1: Double, bot: Double) {
        val v = quadCoords
        if (!projectAzAltInto(az0, t0, f, v, 0) || !projectAzAltInto(az1, t1, f, v, 2) ||
            !projectAzAltInto(az1, bot, f, v, 4) || !projectAzAltInto(az0, bot, f, v, 6)) return
        val minX = minOf(v[0], v[2], v[4], v[6]); val maxX = maxOf(v[0], v[2], v[4], v[6])
        val minY = minOf(v[1], v[3], v[5], v[7]); val maxY = maxOf(v[1], v[3], v[5], v[7])
        if (maxX < 0 || minX > width || maxY < 0 || minY > height) return
        path.moveTo(v[0], v[1]); path.lineTo(v[2], v[3]); path.lineTo(v[4], v[5]); path.lineTo(v[6], v[7]); path.close()
    }

    private fun fillBand(c: Canvas, f: Float, altTop: Double, altBot: Double, color: Int) {
        p.shader = null; p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f
        quad.rewind()
        for (az in 0 until 360 step 10) quadAppend(quad, f, az.toDouble(), altTop, az + 10.0, altTop, altBot)
        p.color = color
        c.drawPath(quad, p)
        p.style = Paint.Style.FILL
    }

    private fun drawGround(c: Canvas, f: Float, day: Float) {
        val top = lerpC(0xFF0A1220.toInt(), 0xFF2A453D.toInt(), day); val deep = lerpC(0xFF02050A.toInt(), 0xFF101C1A.toInt(), day)
        val mid1 = lerpC(top, deep, 0.35f); val mid2 = lerpC(top, deep, 0.7f)
        p.shader = null; p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f
        quad.rewind()
        for (az in 0 until 360 step 4) quadAppend(quad, f, az.toDouble(), skylineSamples[az], az + 4.0, skylineSamples[az + 4], -5.0)
        p.color = top
        c.drawPath(quad, p)

        quad.rewind()
        for (az in 0 until 360 step 10) { val a = az.toDouble(); quadAppend(quad, f, a, -5.0, a + 10, -5.0, -20.0) }
        p.color = mid1
        c.drawPath(quad, p)

        quad.rewind()
        for (az in 0 until 360 step 10) { val a = az.toDouble(); quadAppend(quad, f, a, -20.0, a + 10, -20.0, -45.0) }
        p.color = mid2
        c.drawPath(quad, p)

        quad.rewind()
        for (az in 0 until 360 step 10) { val a = az.toDouble(); quadAppend(quad, f, a, -45.0, a + 10, -45.0, -90.0) }
        p.color = deep
        c.drawPath(quad, p)
        p.style = Paint.Style.FILL
    }

    private fun drawGrid(c: Canvas, f: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * dp; p.color = 0x3358A6E0
        for (alt in 30..60 step 30) { var pr: Projected? = null
            for (az in 0..360 step 4) { val q = projectAzAlt(az.toDouble(), alt.toDouble(), f); if (pr != null && q != null) c.drawLine(pr.x, pr.y, q.x, q.y, p); pr = q } }
        for (az in 0 until 360 step 30) { var pr: Projected? = null
            for (alt in 0..90 step 4) { val q = projectAzAlt(az.toDouble(), alt.toDouble(), f); if (pr != null && q != null) c.drawLine(pr.x, pr.y, q.x, q.y, p); pr = q } }
        p.style = Paint.Style.FILL
    }

    private var lineVJd = -1e9
    private var lineV: List<DoubleArray> = emptyList()

    private fun drawConstellations(c: Canvas, jd: Double, day: Float, f: Float, project: (Double, Double) -> Projected?) {
        if (abs(jd - lineVJd) > 2.0 || lineV.size != Catalog.lines.size) {
            lineVJd = jd
            lineV = Catalog.lines.map { ln ->
                val o = DoubleArray(ln.size / 2 * 3); var i = 0; var k = 0
                while (i + 1 < ln.size) {
                    val (r, d) = Astro.precess(ln[i], ln[i + 1], jd); val rr = Math.toRadians(r); val dd = Math.toRadians(d)
                    o[k++] = cos(dd) * cos(rr); o[k++] = cos(dd) * sin(rr); o[k++] = sin(dd); i += 2
                }
                o
            }
        }
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1f * dp; p.color = withAlpha(0xFF7FB6E8.toInt(), (120 * (1 - day * 0.9f)).toInt())
        val m = M; val u = Ueq; val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val k2 = 2.0 * f
        for (ln in lineV) {
            var px = 0f; var py = 0f; var pUp = 0.0; var pOk = false; var i = 0
            while (i + 2 < ln.size) {
                val vx = ln[i]; val vy = ln[i + 1]; val vz = ln[i + 2]; i += 3
                val den = 1 - (m[6] * vx + m[7] * vy + m[8] * vz)
                if (den < 0.4) { pOk = false; continue }
                val k = k2 / den
                val x = (cx + k * (m[0] * vx + m[1] * vy + m[2] * vz)).toFloat(); val y = (cy - k * (m[3] * vx + m[4] * vy + m[5] * vz)).toFloat()
                val up = u[0] * vx + u[1] * vy + u[2] * vz
                if (pOk && !(showGround && (pUp < 0 || up < 0)) && !(px < 0 && x < 0 || px > w && x > w || py < 0 && y < 0 || py > h && y > h)) c.drawLine(px, py, x, y, p)
                px = x; py = y; pUp = up; pOk = true
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
        if (traceName != name || abs(jd - traceJd) > 0.03 || traceLat != lat || traceLon != lon || traceDecl != decl) {
            traceName = name; traceJd = jd; traceLat = lat; traceLon = lon; traceDecl = decl
            trace.clear(); traceLabels.clear()
            val star = Astro.starByName(jd, name)
            // Stars repeat their apparent sky path after one sidereal day; solar-system
            // objects use 24 hours. Five-minute samples keep the apparent sky path smooth.
            val steps = 288
            val spanDays = if (star != null) 0.9972695663 else 1.0
            for (k in 0..steps) {
                val j = jd + spanDays * k / steps
                val b = star ?: Astro.bodyAt(j, name) ?: return
                val (alt, az) = Astro.altAz(b.ra, b.dec, lat, lon, j)
                val azRad = Math.toRadians(az - decl); val altRad = Math.toRadians(alt)
                trace.add(doubleArrayOf(cos(altRad) * sin(azRad), cos(altRad) * cos(azRad), sin(altRad)))
                traceLabels.add(if (k > 0 && k < steps && k % 72 == 0) timeFmt.format(java.util.Date(((j - 2440587.5) * 86400000).toLong())) else null)
            }
        }
        tracePath.rewind()
        var prevOk = false
        val r = frameRot; val cx = width / 2f; val cy = height / 2f; val k2 = 2.0 * f
        for (k in trace.indices) {
            val t = trace[k]
            val dx = r[0] * t[0] + r[3] * t[1] + r[6] * t[2]
            val dy = r[1] * t[0] + r[4] * t[1] + r[7] * t[2]
            val dz = r[2] * t[0] + r[5] * t[1] + r[8] * t[2]
            val den = 1.0 - dz
            if (den < 0.4) { prevOk = false; continue }
            val scale = k2 / den
            val x = (cx + scale * dx).toFloat(); val y = (cy - scale * dy).toFloat()
            if (prevOk) tracePath.lineTo(x, y) else tracePath.moveTo(x, y)
            // Hourly markers; label only every six hours. Leave the final sample
            // unlabeled and undotted so it cannot duplicate the starting timestamp.
            if (k < trace.lastIndex && k % 12 == 0) {
                val a = 230
                p.style = Paint.Style.FILL; p.color = withAlpha(0xFF9BE7FF.toInt(), a)
                c.drawCircle(x, y, 3.2f * dp, p)
                textPaint.textSize = 10f * dp; textPaint.color = withAlpha(0xFFD8F3FF.toInt(), a)
                traceLabels[k]?.let { c.drawText(it, x + 6 * dp, y - 6 * dp, textPaint) }
            }
            prevOk = true
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * dp; p.color = withAlpha(0xFF9BE7FF.toInt(), 155)
        c.drawPath(tracePath, p); p.style = Paint.Style.FILL
    }

    /** Edge arrow pointing to the selected object when it is outside the field of view. */
    private fun drawArrow(c: Canvas, jd: Double, bodies: List<Body>) {
        val b = bodies.firstOrNull { it.name == selected } ?: Astro.starByName(jd, selected ?: "") ?: return
        val (alt, az) = Astro.altAz(b.ra, b.dec, lat, lon, jd)
        val a = Math.toRadians(az - decl); val e = Math.toRadians(alt)
        val wx = cos(e) * sin(a); val wy = cos(e) * cos(a); val wz = sin(e); val r = frameRot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz; val dy = r[1] * wx + r[4] * wy + r[7] * wz; val dz = r[2] * wx + r[5] * wy + r[8] * wz
        val sep = Math.toDegrees(acos((-dz).coerceIn(-1.0, 1.0)))
        if (sep < fovDeg * 0.45) return
        val ang = atan2(-dy, dx).toFloat(); val rad = min(width, height) / 2f - 70f * dp
        val cx = width / 2f + cos(ang) * rad; val cy = height / 2f + sin(ang) * rad
        c.save(); c.rotate(Math.toDegrees(ang.toDouble()).toFloat(), cx, cy)
        arrowPath.rewind(); arrowPath.moveTo(cx + 16 * dp, cy); arrowPath.lineTo(cx - 10 * dp, cy - 12 * dp); arrowPath.lineTo(cx - 10 * dp, cy + 12 * dp); arrowPath.close()
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0xFF58B9F5.toInt(); c.drawPath(arrowPath, p); c.restore()
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
                if (!gestureScaling && !scaleDetector.isInProgress && hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) < 20.0 * dp) {
                    var closest: Hit? = null; var closestDistance = Double.POSITIVE_INFINITY
                    for (i in 0 until hitCount) {
                        val candidate = hits[i]
                        val distance = hypot((candidate.x - event.x).toDouble(), (candidate.y - event.y).toDouble())
                        if (distance < closestDistance) { closest = candidate; closestDistance = distance }
                    }
                    if (closest != null && closestDistance < 60.0 * dp) {
                        selected = closest.body.name
                        onSelect?.invoke(info(closest.body, Astro.jd(now())))
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
