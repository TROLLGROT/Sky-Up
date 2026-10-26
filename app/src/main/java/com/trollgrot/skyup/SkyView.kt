package com.trollgrot.skyup
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class SkyView(ctx: Context) : View(ctx) {
    var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // device -> world(ENU, magnetic north)
    var lat = 0.0; var lon = 0.0; var decl = 0.0
    var hasLocation = false; var lowAccuracy = false
    var fovDeg = 60.0
    var onSelect: ((String?) -> Unit)? = null
    private var selected: String? = null
    private var lastInfo = 0L
    private val dp = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private class P(val x: Float, val y: Float, val alt: Double)
    private val hits = ArrayList<Pair<Body, P>>()
    private var downX = 0f; private var downY = 0f

    private fun projAzAlt(az: Double, alt: Double, f: Float): P? {
        val a = Math.toRadians(az - decl); val e = Math.toRadians(alt)
        val wx = cos(e) * sin(a); val wy = cos(e) * cos(a); val wz = sin(e)
        val r = rot
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        if (dz >= -0.05) return null
        return P((width / 2 + f * dx / -dz).toFloat(), (height / 2 - f * dy / -dz).toFloat(), alt)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF02030A.toInt())
        if (width == 0) return
        val f = ((height / 2.0) / tan(Math.toRadians(fovDeg / 2))).toFloat()
        val jd = Astro.jd(System.currentTimeMillis())
        fun proj(ra: Double, dec: Double): P? { val (alt, az) = Astro.altAz(ra, dec, lat, lon, jd); return projAzAlt(az, alt, f) }
        // horizon
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp; p.color = 0xFF2E7D32.toInt()
        var prev: P? = null
        for (az in 0..360 step 3) { val q = projAzAlt(az.toDouble(), 0.0, f); if (prev != null && q != null) c.drawLine(prev.x, prev.y, q.x, q.y, p); prev = q }
        // ecliptic
        p.strokeWidth = 1f * dp; p.color = 0x66FFC107
        prev = null
        for (l in 0..360 step 3) { val (r, d) = Astro.eclipticPoint(l.toDouble(), jd); val q = proj(r, d); if (prev != null && q != null) c.drawLine(prev.x, prev.y, q.x, q.y, p); prev = q }
        // cardinal points
        p.style = Paint.Style.FILL; p.textSize = 18f * dp; p.color = 0xFF66BB6A.toInt()
        for ((n, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) projAzAlt(az, 0.0, f)?.let { c.drawText(n, it.x - 6 * dp, it.y - 8 * dp, p) }
        // bodies
        hits.clear()
        p.textSize = 13f * dp
        for (b in Astro.all(jd)) {
            val q = proj(b.ra, b.dec) ?: continue
            if (q.x < -50 || q.x > width + 50 || q.y < -50 || q.y > height + 50) continue
            val dim = if (q.alt < 0) 0.35f else 1f
            val (col, rad) = when (b.name) {
                "Sun" -> Pair(0xFFFFB300.toInt(), 14f * dp)
                "Moon" -> Pair(0xFFE0E0E0.toInt(), 12f * dp)
                else -> if (b.type == "Planet") Pair(0xFFFFE082.toInt(), max(3f, 6f - b.mag.toFloat() * 0.8f) * dp)
                        else Pair(0xFFFFFFFF.toInt(), max(1.2f, 4.2f - b.mag.toFloat() * 0.9f) * dp)
            }
            p.style = Paint.Style.FILL; p.color = col; p.alpha = (255 * dim).toInt()
            c.drawCircle(q.x, q.y, rad, p)
            if (b.type != "Star" || b.mag < 1.6) { p.color = col; p.alpha = (200 * dim).toInt(); c.drawText(b.name, q.x + rad + 4 * dp, q.y - 2 * dp, p) }
            p.alpha = 255
            hits.add(b to q)
            if (b.name == selected) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp; p.color = 0xFF00E5FF.toInt()
                c.drawCircle(q.x, q.y, rad + 8 * dp, p)
            }
            if (b.name == selected && System.currentTimeMillis() - lastInfo > 500) { lastInfo = System.currentTimeMillis(); onSelect?.invoke(info(b, jd)) }
        }
        // crosshair + status
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * dp; p.color = 0x55FFFFFF
        c.drawCircle(width / 2f, height / 2f, 10 * dp, p)
        p.style = Paint.Style.FILL; p.textSize = 13f * dp; p.color = 0xFFFF8A65.toInt()
        var y = 40f * dp
        if (!hasLocation) { c.drawText("Location unknown - grant permission or move outdoors", 12 * dp, y, p); y += 18 * dp }
        if (lowAccuracy) c.drawText("Compass needs calibration: wave the phone in a figure 8", 12 * dp, y, p)
    }

    private fun hms(deg: Double): String { val h = deg / 15; return "%dh %02dm".format(h.toInt(), ((h - h.toInt()) * 60).toInt()) }

    private fun info(b: Body, jd: Double): String {
        val (alt, az) = Astro.altAz(b.ra, b.dec, lat, lon, jd)
        val mag = if (b.name == "Moon") "" else "Magnitude %.1f\n".format(b.mag)
        val dist = if (b.dist.isEmpty()) "" else "\n" + b.dist
        return "${b.name} - ${b.type}\n${mag}RA ${hms(b.ra)}   Dec %.2f\u00B0\nAltitude %.1f\u00B0   Azimuth %.1f\u00B0%s".format(b.dec, alt, az, dist) +
            if (alt < 0) "\n(below the horizon)" else ""
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (hypot(e.x - downX, e.y - downY) < 20 * dp) {
                val best = hits.minByOrNull { hypot(it.second.x - e.x, it.second.y - e.y) }
                if (best != null && hypot(best.second.x - e.x, best.second.y - e.y) < 60 * dp) {
                    selected = best.first.name; lastInfo = System.currentTimeMillis()
                    onSelect?.invoke(info(best.first, Astro.jd(System.currentTimeMillis())))
                } else { selected = null; onSelect?.invoke(null) }
                invalidate()
            }
        }
        return true
    }
}
