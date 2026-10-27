package com.trollgrot.skyup
import kotlin.math.*

class Body(val name: String, val type: String, val ra: Double, val dec: Double, val mag: Double, val dist: String = "", val bv: Double = 0.6) {
    val vx = kotlin.math.cos(Math.toRadians(dec)) * kotlin.math.cos(Math.toRadians(ra))
    val vy = kotlin.math.cos(Math.toRadians(dec)) * kotlin.math.sin(Math.toRadians(ra))
    val vz = kotlin.math.sin(Math.toRadians(dec))
}

/** Fully offline astronomy: JPL "Approximate Positions of the Planets" (Keplerian elements, 1800-2050). */
object Astro {
    private val D = PI / 180
    fun jd(ms: Long) = ms / 86400000.0 + 2440587.5
    fun norm(x: Double): Double { val r = x % 360; return if (r < 0) r + 360 else r }

    // a, da, e, de, I, dI, L, dL, long.peri, d, long.node, d  (per Julian century)
    private val EL = arrayOf(
        doubleArrayOf(0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749, 252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081),
        doubleArrayOf(0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890, 181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418),
        doubleArrayOf(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668, 100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0),
        doubleArrayOf(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131, -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343),
        doubleArrayOf(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714, 34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106),
        doubleArrayOf(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609, 49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794),
        doubleArrayOf(19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939, 313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589),
        doubleArrayOf(30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372, -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664))
    private val NAMES = arrayOf("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
    private val MAGS = doubleArrayOf(0.0, -4.0, 0.0, 0.5, -2.0, 0.5, 5.7, 7.8)

    /** Position of one solar-system body, without calculating every other planet. */
    fun bodyAt(jd: Double, name: String): Body? {
        val d = jd - 2451545.0
        val T = d / 36525.0
        val earth = helio(2, T)
        if (name == "Sun") {
            val (ra, dec) = eclToEq(-earth[0], -earth[1], -earth[2], 23.43928)
            val (pr, pd) = precess(ra, dec, jd)
            return Body(name, "Star (our Sun)", pr, pd, -26.7)
        }
        if (name == "Moon") {
            val L = 218.316 + 13.176396 * d
            val M = (134.963 + 13.064993 * d) * D
            val F = (93.272 + 13.229350 * d) * D
            val lon = (L + 6.289 * sin(M)) * D
            val lat = 5.128 * sin(F) * D
            val (ra, dec) = eclToEq(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat), 23.4393 - 0.0000004 * d)
            val (pr, pd) = precess(ra, dec, jd)
            val distance = 385001 - 20905 * cos(M)
            return Body(name, "Natural satellite", pr, pd, -10.0, "Distance %,d km".format(distance.toInt()))
        }
        val index = NAMES.indexOf(name)
        if (index >= 0 && index != 2) {
            val planet = helio(index, T)
            val x = planet[0] - earth[0]; val y = planet[1] - earth[1]; val z = planet[2] - earth[2]
            val (ra, dec) = eclToEq(x, y, z, 23.43928)
            val (pr, pd) = precess(ra, dec, jd)
            return Body(name, "Planet", pr, pd, MAGS[index])
        }
        return starByName(jd, name)
    }

    /** Heliocentric ecliptic J2000 position (AU). */
    fun helio(i: Int, T: Double): DoubleArray {
        val e = EL[i]
        val a = e[0] + e[1] * T; val ec = e[2] + e[3] * T; val inc = (e[4] + e[5] * T) * D
        val L = e[6] + e[7] * T; val wp = e[8] + e[9] * T; val node = (e[10] + e[11] * T) * D
        val w = wp * D - node
        val M = norm(L - wp) * D
        var E = M
        repeat(10) { E = M + ec * sin(E) }
        val xp = a * (cos(E) - ec); val yp = a * sqrt(1 - ec * ec) * sin(E)
        val cw = cos(w); val sw = sin(w); val cn = cos(node); val sn = sin(node); val ci = cos(inc); val si = sin(inc)
        return doubleArrayOf(
            (cw * cn - sw * sn * ci) * xp + (-sw * cn - cw * sn * ci) * yp,
            (cw * sn + sw * cn * ci) * xp + (-sw * sn + cw * cn * ci) * yp,
            (sw * si) * xp + (cw * si) * yp)
    }

    private fun eclToEq(x: Double, y: Double, z: Double, epsDeg: Double): Pair<Double, Double> {
        val ce = cos(epsDeg * D); val se = sin(epsDeg * D)
        val ye = y * ce - z * se; val ze = y * se + z * ce
        return Pair(norm(atan2(ye, x) / D), asin(ze / sqrt(x * x + ye * ye + ze * ze)) / D)
    }

    /** First-order precession J2000 -> date. */
    fun precess(ra: Double, dec: Double, jd: Double): Pair<Double, Double> {
        val yr = (jd - 2451545.0) / 365.25
        val r = ra * D; val d = dec * D
        return Pair(norm(ra + (0.012812 + 0.005568 * sin(r) * tan(d)) * yr), dec + 0.005568 * cos(r) * yr)
    }

    fun eclipticPoint(lon: Double, jd: Double): Pair<Double, Double> {
        val (r, d) = eclToEq(cos(lon * D), sin(lon * D), 0.0, 23.43928)
        return precess(r, d, jd)
    }

    private const val STARS = "Sirius,6.7525,-16.716,-1.46;Canopus,6.3992,-52.696,-0.74;Arcturus,14.2610,19.182,-0.05;Rigil Kentaurus,14.6600,-60.834,-0.01;Vega,18.6156,38.784,0.03;Capella,5.2782,45.998,0.08;Rigel,5.2423,-8.202,0.13;Procyon,7.6550,5.225,0.34;Achernar,1.6286,-57.237,0.46;Betelgeuse,5.9195,7.407,0.50;Hadar,14.0637,-60.373,0.61;Altair,19.8464,8.868,0.76;Acrux,12.4433,-63.099,0.76;Aldebaran,4.5987,16.509,0.86;Antares,16.4901,-26.432,0.96;Spica,13.4199,-11.161,0.97;Pollux,7.7553,28.026,1.14;Fomalhaut,22.9608,-29.622,1.16;Deneb,20.6905,45.280,1.25;Mimosa,12.7953,-59.689,1.25;Regulus,10.1395,11.967,1.35;Adhara,6.9771,-28.972,1.50;Castor,7.5767,31.888,1.58;Gacrux,12.5194,-57.113,1.64;Shaula,17.5601,-37.104,1.62;Bellatrix,5.4189,6.350,1.64;Elnath,5.4382,28.608,1.65;Miaplacidus,9.2200,-69.717,1.68;Alnilam,5.6036,-1.202,1.69;Alnitak,5.6793,-1.943,1.77;Alioth,12.9005,55.960,1.76;Dubhe,11.0621,61.751,1.79;Mirfak,3.4054,49.861,1.79;Wezen,7.1399,-26.393,1.84;Kaus Australis,18.4029,-34.385,1.85;Alkaid,13.7923,49.313,1.86;Sargas,17.6220,-42.998,1.87;Polaris,2.5303,89.264,1.98;Mintaka,5.5334,-0.299,2.23;Merak,11.0307,56.382,2.37;Mizar,13.3988,54.925,2.04;Hamal,2.1196,23.463,2.00;Alphard,9.4598,-8.659,1.98;Saiph,5.7959,-9.670,2.06;Denebola,11.8177,14.572,2.14;Schedar,0.6751,56.537,2.24"
    private val starList: List<Body> by lazy {
        STARS.split(";").map { val p = it.split(","); Body(p[0], "Star", p[1].toDouble() * 15, p[2].toDouble(), p[3].toDouble()) }
    }

    @Volatile private var starCache: List<Body>? = null
    @Volatile private var starCacheJd = 0.0
    @Volatile private var starMap: Map<String, Body>? = null
    @Volatile private var starCacheLoading = false
    private val starCacheLock = Any()

    /** Returns cached positions immediately and prepares a new epoch off the UI thread. */
    fun stars(jd: Double): List<Body> {
        val cached = starCache
        if (cached == null || abs(jd - starCacheJd) >= 2.0) prepareStars(jd)
        val current = starCache ?: return emptyList()
        return if (abs(jd - starCacheJd) < 2.0) current else emptyList()
    }

    private fun prepareStars(jd: Double) {
        var startWorker = false
        synchronized(starCacheLock) {
            if (!starCacheLoading) {
                starCacheLoading = true
                startWorker = true
            }
        }
        if (!startWorker) return
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val src = if (Catalog.stars.isNotEmpty()) Catalog.stars else starList
                val fresh = src.map { s ->
                    val (r, de) = precess(s.ra, s.dec, jd)
                    Body(s.name, s.type, r, de, s.mag, "", s.bv)
                }
                val byName = HashMap<String, Body>(fresh.size)
                for (body in fresh) byName.putIfAbsent(body.name, body)
                synchronized(starCacheLock) {
                    starCache = fresh
                    starCacheJd = jd
                    starMap = byName
                    starCacheLoading = false
                }
            } catch (_: Exception) {
                synchronized(starCacheLock) { starCacheLoading = false }
            }
        }, "sky-star-positions").apply { isDaemon = true; start() }
    }

    fun starByName(jd: Double, name: String): Body? {
        val l = stars(jd)
        if (l.isEmpty()) return null
        return starMap?.get(name)
    }

    fun all(jd: Double, withStars: Boolean = true): List<Body> {
        val T = (jd - 2451545.0) / 36525.0
        val d = jd - 2451545.0
        val out = ArrayList<Body>()
        val earth = helio(2, T)
        // Sun
        run {
            val (r, de) = eclToEq(-earth[0], -earth[1], -earth[2], 23.43928)
            val (pr, pd) = precess(r, de, jd)
            out.add(Body("Sun", "Star (our Sun)", pr, pd, -26.7, "Distance %.4f AU".format(sqrt(earth[0] * earth[0] + earth[1] * earth[1] + earth[2] * earth[2]))))
        }
        // Moon (low-precision series, ~0.3 deg)
        run {
            val L = 218.316 + 13.176396 * d; val M = (134.963 + 13.064993 * d) * D; val F = (93.272 + 13.229350 * d) * D
            val lon = (L + 6.289 * sin(M)) * D; val lat = 5.128 * sin(F) * D
            val (r, de) = eclToEq(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat), 23.4393 - 0.0000004 * d)
            val dist = 385001 - 20905 * cos(M)
            out.add(Body("Moon", "Natural satellite", r, de, -10.0, "Distance %,d km".format(dist.toInt())))
        }
        for (i in intArrayOf(0, 1, 3, 4, 5, 6, 7)) {
            val h = helio(i, T)
            val x = h[0] - earth[0]; val y = h[1] - earth[1]; val z = h[2] - earth[2]
            val (r, de) = eclToEq(x, y, z, 23.43928)
            val (pr, pd) = precess(r, de, jd)
            out.add(Body(NAMES[i], "Planet", pr, pd, MAGS[i], "Distance from Earth %.3f AU".format(sqrt(x * x + y * y + z * z))))
        }
        if (withStars) out.addAll(stars(jd))
        return out
    }

    /** returns (altitude, azimuth from true north toward east), degrees. */
    fun altAz(ra: Double, dec: Double, lat: Double, lon: Double, jd: Double): Pair<Double, Double> {
        val gmst = norm(280.46061837 + 360.98564736629 * (jd - 2451545.0))
        val H = (gmst + lon - ra) * D
        val de = dec * D; val la = lat * D
        val alt = asin(sin(de) * sin(la) + cos(de) * cos(la) * cos(H))
        val az = atan2(-cos(de) * sin(H), sin(de) * cos(la) - cos(de) * sin(la) * cos(H))
        return Pair(alt / D, norm(az / D))
    }
}
