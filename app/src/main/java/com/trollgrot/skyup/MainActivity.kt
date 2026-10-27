package com.trollgrot.skyup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.acos
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class MainActivity : Activity(), SensorEventListener, LocationListener {
    private lateinit var sky: SkyView
    private lateinit var info: TextView
    private lateinit var detailSheet: FrameLayout
    private lateinit var starArt: View
    private lateinit var nasaArt: ImageView
    private lateinit var nasaCaption: TextView
    private var nasaRequestId = 0
    private val nasaImageCache = LinkedHashMap<String, Bitmap>()
    private val nasaImageCredits = HashMap<String, String>()
    private lateinit var sm: SensorManager
    private lateinit var root: FrameLayout
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var fovValue: TextView
    private lateinit var calibrationOverlay: FrameLayout
    private lateinit var calibrationProgress: ProgressBar
    private lateinit var calibrationStatus: TextView
    private lateinit var calibrationAction: Button
    private lateinit var controlModeButton: Button

    private val q = FloatArray(4)
    private val calibrationPreviousQ = FloatArray(4)
    private val calibrationAngles = FloatArray(3)
    private var calibrationActive = false
    private var calibrationHasPrevious = false
    private var calibrationStartedAt = 0L
    private var calibrationLastUiAt = 0L
    private var calibrationMotion = 0.0
    private var calibrationYaw = 0.0
    private var calibrationYawMin = Double.POSITIVE_INFINITY
    private var calibrationYawMax = Double.NEGATIVE_INFINITY
    private var calibrationPreviousYaw = 0.0
    private var calibrationPitchMin = Double.POSITIVE_INFINITY
    private var calibrationPitchMax = Double.NEGATIVE_INFINITY
    private var calibrationRollMin = Double.POSITIVE_INFINITY
    private var calibrationRollMax = Double.NEGATIVE_INFINITY
    private val rot = FloatArray(9)
    private val rg = FloatArray(9)
    private var night = false

    private val prefs by lazy { getSharedPreferences("sky", MODE_PRIVATE) }
    private val blue = 0xFF0D2D59.toInt()
    private val lightBlue = 0xFF58B9F5.toInt()
    private val paleBlue = 0xFFB9E3FF.toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF06152B.toInt()
        window.navigationBarColor = 0xFF06152B.toInt()

        L.ru = prefs.getString("lang", "ru") == "ru"
        if (!prefs.getBoolean("display_defaults_v04", false)) {
            prefs.edit().putBoolean("ground", false).putBoolean("day", false).putBoolean("display_defaults_v04", true).apply()
        }
        night = nightAt(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), prefs.contains("lat"))
        Catalog.load(this)
        Astro.stars(Astro.jd(System.currentTimeMillis()))
        sky = SkyView(this).apply {
            showConstellations = prefs.getBoolean("cons", true); showConNames = prefs.getBoolean("conNames", true)
            showGround = prefs.getBoolean("ground", false); showGrid = prefs.getBoolean("grid", false)
            daylightSky = prefs.getBoolean("day", false); nightMode = prefs.getBoolean("night", false)
            starLimit = prefs.getFloat("starLimit2", 6.3f).toDouble()
            drawStarNames = prefs.getBoolean("star_names", true)
            drawPlanetNames = prefs.getBoolean("planet_names", true)
            drawPlanetDetails = prefs.getBoolean("planet_details", true)
        }
        sky.setFieldOfView(prefs.getFloat("fov", 60f).toDouble().coerceIn(5.0, 60.0))
        root = FrameLayout(this)
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        sky.onSelect = { message ->
            detailSheet.visibility = if (message == null) View.GONE else View.VISIBLE
            if (message != null) { info.text = message; starArt.tag = message; starArt.invalidate() }
            loadNasaImage(message)
        }
        detailSheet = makeDetailSheet()
        detailSheet.visibility = View.GONE
        root.addView(detailSheet, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.76f).toInt(), Gravity.BOTTOM).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })

        root.addView(makeTopBar(), FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP))
        root.addView(makeTimeBar(), FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(72) })
        root.addView(makeZoomControls(), FrameLayout.LayoutParams(dp(48), dp(112), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(12)
        })
        controlModeButton = Button(this).apply {
            text = modeButtonText(false)
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedGradient(0xD80C2B50.toInt(), 0xE91A5791.toInt(), 22)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { sky.toggleControlMode() }
        }
        root.addView(controlModeButton, FrameLayout.LayoutParams(-2, dp(48), Gravity.START or Gravity.BOTTOM).apply {
            marginStart = dp(12); bottomMargin = dp(18)
        })
        sky.onControlModeChange = { manual -> controlModeButton.text = modeButtonText(manual) }
        settingsOverlay = makeSettingsOverlay()
        settingsOverlay.visibility = View.GONE
        root.addView(settingsOverlay, FrameLayout.LayoutParams(-1, -1))
        calibrationOverlay = makeCalibrationOverlay()
        calibrationOverlay.visibility = View.GONE
        root.addView(calibrationOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        if (prefs.contains("lat")) {
            setLoc(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), 0.0, false)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Android's fused rotation vector already combines gyro, accelerometer and
        // magnetic heading. Avoid layering a second quaternion filter and custom yaw
        // correction over it: that adds latency and can produce visible compass jumps.
        val orientation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        orientation?.let { sm.registerListener(this, it, SENSOR_PERIOD_US) }
        startLocation()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (_: Exception) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) startLocation()
    }

    private fun startLocation() {
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (provider == LocationManager.GPS_PROVIDER && !fineGranted) continue
                if (!lm.isProviderEnabled(provider)) continue
                try {
                    lm.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
                    lm.requestLocationUpdates(provider, 60_000L, 500f, this)
                } catch (_: SecurityException) {
                    // A coarse-only grant can use the network provider, but not the GPS provider.
                }
            }
        } catch (_: Exception) { }
    }

    private fun setLoc(latitude: Double, longitude: Double, altitude: Double, save: Boolean) {
        sky.lat = latitude
        sky.lon = longitude
        sky.hasLocation = true
        sky.decl = GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), altitude.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
        if (save) {
            prefs.edit().putFloat("lat", latitude.toFloat()).putFloat("lon", longitude.toFloat()).apply()
        }
        sky.invalidate()
    }

    override fun onLocationChanged(location: Location) {
        setLoc(location.latitude, location.longitude, location.altitude, true)
    }

    @Deprecated("Deprecated by Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getQuaternionFromVector(q, event.values)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        rot.copyInto(rg)
        trackCalibration(q)
        // Publish an immutable snapshot; SkyView reads it while rendering on the UI thread.
        sky.rot = rot.copyOf()
        // Let Android schedule one redraw at the next display vsync.
        sky.requestFrame()
    }

    private fun nightAt(la: Double, lo: Double, has: Boolean): Boolean {
        val ms = System.currentTimeMillis()
        if (!has) { val h = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY); return h >= 21 || h < 6 }
        val jd = Astro.jd(ms); val sun = Astro.all(jd, false).first { it.name == "Sun" }
        return Astro.altAz(sun.ra, sun.dec, la, lo, jd).first < -2.0
    }

    private fun tone(c: Int): Int = if (!night) c else Color.argb(Color.alpha(c), (Color.red(c) * 0.34f).toInt(), (Color.green(c) * 0.36f).toInt(), (Color.blue(c) * 0.42f).toInt())

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        sky.lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        sky.invalidate()
    }

    private fun recalibrateCompass() {
        // Android owns hardware compass calibration; this guides the user through a
        // figure-eight and confirms that the phone was actually moved.
        calibrationActive = true
        calibrationHasPrevious = false
        calibrationStartedAt = android.os.SystemClock.elapsedRealtime()
        calibrationLastUiAt = 0L
        calibrationMotion = 0.0
        calibrationYaw = 0.0
        calibrationYawMin = 0.0
        calibrationYawMax = 0.0
        calibrationPreviousYaw = 0.0
        calibrationPitchMin = Double.POSITIVE_INFINITY
        calibrationPitchMax = Double.NEGATIVE_INFINITY
        calibrationRollMin = Double.POSITIVE_INFINITY
        calibrationRollMax = Double.NEGATIVE_INFINITY
        sky.clearSelection()
        sky.lowAccuracy = false
        sky.invalidate()
        calibrationProgress.progress = 0
        calibrationStatus.text = L.t("Move the phone slowly in a figure-eight", "Медленно перемещайте телефон восьмёркой")
        calibrationAction.text = L.t("CANCEL", "ОТМЕНА")
        calibrationOverlay.visibility = View.VISIBLE
        calibrationOverlay.bringToFront()
    }

    private fun trackCalibration(current: FloatArray) {
        if (!calibrationActive) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!calibrationHasPrevious) {
            current.copyInto(calibrationPreviousQ)
            SensorManager.getOrientation(rg, calibrationAngles)
            calibrationPreviousYaw = calibrationAngles[0].toDouble()
            calibrationHasPrevious = true
        } else {
            var dot = 0.0
            for (i in 0..3) dot += current[i] * calibrationPreviousQ[i]
            val delta = 2.0 * acos(kotlin.math.abs(dot).coerceIn(0.0, 1.0))
            if (delta > Math.toRadians(0.2)) calibrationMotion += delta
            current.copyInto(calibrationPreviousQ)
            SensorManager.getOrientation(rg, calibrationAngles)
            var yawDelta = calibrationAngles[0].toDouble() - calibrationPreviousYaw
            while (yawDelta > Math.PI) yawDelta -= 2.0 * Math.PI
            while (yawDelta < -Math.PI) yawDelta += 2.0 * Math.PI
            calibrationYaw += yawDelta
            calibrationYawMin = minOf(calibrationYawMin, calibrationYaw)
            calibrationYawMax = maxOf(calibrationYawMax, calibrationYaw)
            calibrationPreviousYaw = calibrationAngles[0].toDouble()
            val pitch = calibrationAngles[1].toDouble()
            val roll = calibrationAngles[2].toDouble()
            calibrationPitchMin = minOf(calibrationPitchMin, pitch)
            calibrationPitchMax = maxOf(calibrationPitchMax, pitch)
            calibrationRollMin = minOf(calibrationRollMin, roll)
            calibrationRollMax = maxOf(calibrationRollMax, roll)
        }

        val pitchSpan = if (calibrationPitchMin.isFinite()) calibrationPitchMax - calibrationPitchMin else 0.0
        val rollSpan = if (calibrationRollMin.isFinite()) calibrationRollMax - calibrationRollMin else 0.0
        val yawSpan = if (calibrationYawMin.isFinite()) calibrationYawMax - calibrationYawMin else 0.0
        val variedAxes = (if (yawSpan > 0.55) 1 else 0) + (if (pitchSpan > 0.30) 1 else 0) + (if (rollSpan > 0.30) 1 else 0)
        val elapsed = now - calibrationStartedAt
        val complete = elapsed >= 5_000L && calibrationMotion >= 3.2 && variedAxes >= 2
        if (now - calibrationLastUiAt >= 150L || complete) {
            calibrationLastUiAt = now
            val progress = (calibrationMotion / 3.2 * 100).toInt().coerceIn(0, 95)
            runOnUiThread {
                if (!calibrationActive) return@runOnUiThread
                calibrationProgress.progress = if (complete) 100 else progress
                if (complete) {
                    calibrationActive = false
                    calibrationStatus.text = L.t("Movement check complete. Compass accuracy is controlled by Android.", "Движение распознано. Точность компаса контролирует Android.")
                    calibrationAction.text = L.t("DONE", "ГОТОВО")
                    vibrateCalibrationComplete()
                    Toast.makeText(this, L.t("Calibration movement complete", "Калибровочное движение завершено"), Toast.LENGTH_SHORT).show()
                } else {
                    calibrationStatus.text = if (elapsed < 5_000L)
                        L.t("Keep moving slowly in a figure-eight…", "Продолжайте медленно описывать восьмёрку…")
                    else L.t("Change direction and tilt a little more", "Меняйте направление и немного наклоняйте телефон")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateCalibrationComplete() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
            else vibrator.vibrate(120L)
        } catch (_: Exception) { }
    }

    private fun makeCalibrationOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xB8000714.toInt())
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = roundedGradient(0xF20A2347.toInt(), 0xF21B5B91.toInt(), 24)
            elevation = dp(18).toFloat()
            setOnClickListener { /* Keep taps inside the calibration card. */ }
        }
        val icon = TextView(this).apply {
            text = "∞"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(0xFF73CCFF.toInt())
            setShadowLayer(dp(10).toFloat(), 0f, 0f, 0xAA3C9BFF.toInt())
        }
        panel.addView(icon, LinearLayout.LayoutParams(-1, dp(88)))
        panel.addView(TextView(this).apply {
            text = L.t("COMPASS CALIBRATION", "КАЛИБРОВКА КОМПАСА")
            textSize = 17f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        calibrationStatus = TextView(this).apply {
            text = L.t("Move the phone slowly in a figure-eight", "Медленно перемещайте телефон восьмёркой")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFD8ECFF.toInt())
            setPadding(0, dp(12), 0, dp(12))
        }
        panel.addView(calibrationStatus, LinearLayout.LayoutParams(-1, -2))
        calibrationProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(lightBlue)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF254A70.toInt())
        }
        panel.addView(calibrationProgress, LinearLayout.LayoutParams(-1, dp(8)))
        panel.addView(TextView(this).apply {
            text = L.t("Keep the phone moving for a few seconds. This app guides the movement check; Android handles sensor calibration.", "Продолжайте движение несколько секунд. Приложение проверяет движение, а калибровкой датчиков управляет Android.")
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFC1D9EE.toInt())
            setPadding(0, dp(12), 0, dp(14))
        }, LinearLayout.LayoutParams(-1, -2))
        calibrationAction = Button(this).apply {
            text = L.t("CANCEL", "ОТМЕНА")
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF1478B9.toInt(), 0xFF48A7E4.toInt(), 18)
            setOnClickListener {
                if (calibrationActive) {
                    calibrationActive = false
                    calibrationOverlay.visibility = View.GONE
                } else calibrationOverlay.visibility = View.GONE
            }
        }
        panel.addView(calibrationAction, LinearLayout.LayoutParams(-1, dp(46)))
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins(dp(28), dp(20), dp(28), dp(20))
        })
        return overlay
    }

    private fun makeTimeBar(): View {
        val fmt = java.text.SimpleDateFormat("d MMM yyyy  HH:mm", if (L.ru) java.util.Locale("ru") else java.util.Locale.ENGLISH)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0); background = roundedGradient(0xE60A2347.toInt(), 0xE61C5A91.toInt(), 21) }
        fun chip(t: String, click: () -> Unit) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); setOnClickListener { click() } }
        val label = chip("") { pickDateTime() }.apply { textSize = 13f }
        bar.addView(chip("🔍") { showSearch() }, LinearLayout.LayoutParams(-2, -1))
        bar.addView(chip("⟳") { sky.timeOffset = 0L; sky.invalidate() }, LinearLayout.LayoutParams(-2, -1))
        bar.addView(chip("−1" + L.t("h", "ч")) { sky.timeOffset -= 3600000L; sky.invalidate() }, LinearLayout.LayoutParams(-2, -1))
        bar.addView(label, LinearLayout.LayoutParams(-2, -1))
        bar.addView(chip("+1" + L.t("h", "ч")) { sky.timeOffset += 3600000L; sky.invalidate() }, LinearLayout.LayoutParams(-2, -1))
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var lastNightCheck = 0L
        h.post(object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                // The sun's altitude does not need a fresh ephemeris calculation every second.
                if (now - lastNightCheck >= 60_000L) {
                    lastNightCheck = now
                    if (nightAt(sky.lat, sky.lon, sky.hasLocation) != night) { recreate(); return }
                }
                label.text = fmt.format(java.util.Date(sky.now()))
                h.postDelayed(this, 1000)
            }
        })
        return bar
    }

    private fun pickDateTime() {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = sky.now() }
        android.app.DatePickerDialog(this, { _, y, m, d ->
            c.set(y, m, d)
            android.app.TimePickerDialog(this, { _, hh, mm ->
                c.set(java.util.Calendar.HOUR_OF_DAY, hh); c.set(java.util.Calendar.MINUTE, mm)
                sky.timeOffset = c.timeInMillis - System.currentTimeMillis(); sky.invalidate()
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun showSearch() {
        val names = (listOf("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune") + Catalog.named).distinct()
        android.app.AlertDialog.Builder(this).setTitle(L.t("Find object", "Найти объект"))
            .setItems(names.map { L.name(it) }.toTypedArray()) { _, i -> sky.select(names[i]) }.show()
    }

    /** Load one NASA Library preview off the UI thread; the local sphere stays as the offline fallback. */
    private fun loadNasaImage(message: String?) {
        val requestId = ++nasaRequestId
        nasaArt.visibility = View.GONE
        nasaCaption.visibility = View.GONE
        if (message == null) return
        val displayed = message.substringBefore('\n').substringBefore('·').trim()
        val names = listOf("Sun", "Moon", "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune") + Catalog.ru.keys
        val objectName = names.firstOrNull { L.name(it) == displayed } ?: displayed
        nasaImageCache[objectName]?.let { bitmap ->
            nasaArt.setImageBitmap(bitmap)
            nasaArt.visibility = View.VISIBLE
            nasaCaption.text = "${nasaImageCredits[objectName] ?: "NASA"} · ${L.t("archive image", "снимок из архива")}: $objectName"
            nasaCaption.visibility = View.VISIBLE
            return
        }
        Thread({
            var bitmap: Bitmap? = null
            var title = objectName
            var credit = "NASA"
            try {
                val encoded = URLEncoder.encode(objectName, "UTF-8")
                val search = URL("https://images-api.nasa.gov/search?q=$encoded&media_type=image&page_size=1").openConnection() as HttpURLConnection
                search.connectTimeout = 4500; search.readTimeout = 6500
                val result = try { search.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { search.disconnect() }
                val items = result.optJSONObject("collection")?.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val item = items.optJSONObject(0)
                    val metadata = item?.optJSONArray("data")?.optJSONObject(0)
                    title = metadata?.optString("title", objectName) ?: objectName
                    credit = metadata?.optString("center", "NASA") ?: "NASA"
                    val links = item?.optJSONArray("links")
                    var imageUrl: String? = null
                    if (links != null) for (i in 0 until links.length()) {
                        val link = links.optJSONObject(i) ?: continue
                        if (link.optString("rel") == "preview") { imageUrl = link.optString("href"); break }
                    }
                    if (!imageUrl.isNullOrBlank()) {
                        val image = URL(imageUrl).openConnection() as HttpURLConnection
                        image.connectTimeout = 4500; image.readTimeout = 6500
                        bitmap = try { image.inputStream.use { BitmapFactory.decodeStream(it) } } finally { image.disconnect() }
                    }
                }
            } catch (_: Exception) { /* Keep the generated, offline model visible. */ }
            val loaded = bitmap
            val loadedCredit = credit
            if (loaded != null) runOnUiThread {
                if (requestId != nasaRequestId || isFinishing) return@runOnUiThread
                if (nasaImageCache.size >= 8) nasaImageCache.remove(nasaImageCache.keys.first())
                nasaImageCache[objectName] = loaded
                nasaImageCredits[objectName] = loadedCredit
                nasaArt.setImageBitmap(loaded)
                nasaArt.contentDescription = title
                nasaArt.visibility = View.VISIBLE
                nasaCaption.text = "$loadedCredit · $title"
                nasaCaption.visibility = View.VISIBLE
            }
        }, "nasa-image-preview").apply { isDaemon = true; start() }
    }

    private fun makeTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(16), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(tone(0xE90A2347.toInt()), tone(0xB91C5A91.toInt()), tone(0x001C5A91))
            )
        }
        val title = TextView(this).apply {
            text = "SKY UP"
            setTextColor(Color.WHITE)
            textSize = 20f
            letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = L.t("ONLY STARS ABOVE", "ВЫШЕ — ТОЛЬКО ЗВЁЗДЫ")
            setTextColor(paleBlue)
            textSize = 9f
            letterSpacing = 0.18f
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(subtitle)
        }
        bar.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = Button(this).apply {
            text = L.t("⚙  SETTINGS", "⚙  НАСТРОЙКИ")
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF17518A.toInt(), 0xFF287FC0.toInt(), 24)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            setOnClickListener { settingsOverlay.visibility = View.VISIBLE }
        }
        bar.addView(settings, LinearLayout.LayoutParams(-2, dp(42)))
        return bar
    }

    private fun makeZoomControls(): View {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedGradient(0xD80C2B50.toInt(), 0xE91A5791.toInt(), 22)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        val plus = Button(this).apply {
            text = "+"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF256DAA.toInt(), 0xFF3C9AD8.toInt(), 18)
            setOnClickListener { sky.zoomIn() }
        }
        val minus = Button(this).apply {
            text = "−"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF256DAA.toInt(), 0xFF3C9AD8.toInt(), 18)
            setOnClickListener { sky.zoomOut() }
        }
        controls.addView(plus, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(4) })
        controls.addView(minus, LinearLayout.LayoutParams(-1, 0, 1f))
        return controls
    }

    private fun modeButtonText(manual: Boolean): String = if (manual)
        L.t("◎  GYROSCOPE", "◎  ГИРОСКОП") else L.t("✋  MANUAL", "✋  ВРУЧНУЮ")

    private fun makeDetailSheet(): FrameLayout {
        val sheet = FrameLayout(this).apply {
            setBackgroundColor(0xF20A1A31.toInt())
            background = roundedGradient(0xF20D2B52.toInt(), 0xF21B426B.toInt(), 26)
            elevation = dp(20).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val handle = TextView(this).apply {
            text = "━━━━━━   ${L.t("SWIPE DOWN TO CLOSE", "СВАЙПНИТЕ ВНИЗ, ЧТОБЫ ЗАКРЫТЬ")}"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(0xFFB5D7F2.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = event.rawY; true }
                android.view.MotionEvent.ACTION_UP -> {
                    if (event.rawY - startY > dp(48)) { sky.dismissDetails(); detailSheet.visibility = View.GONE }
                    true
                }
                else -> true
            }
        }
        content.addView(handle, LinearLayout.LayoutParams(-1, dp(42)))
        starArt = object : View(this) {
            private val artPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f; val cy = height / 2f
                canvas.drawColor(0xFF091526.toInt())
                val detail = (tag as? String).orEmpty()
                val objectLabel = detail.substringBefore('\n').substringBefore('·').trim()
                val bodyKey = (listOf("Sun", "Moon", "Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune")
                    .firstOrNull { L.name(it) == objectLabel } ?: objectLabel)
                val isSolarBody = Catalog.bodyFacts.containsKey(bodyKey)
                val isMoon = bodyKey == "Moon"
                val isSun = bodyKey == "Sun"
                val isSaturn = bodyKey == "Saturn"
                val isJupiter = bodyKey == "Jupiter"
                val isMars = bodyKey == "Mars"
                val core = when {
                    isMoon -> 0xFFE1E6EC.toInt()
                    isMars -> 0xFFFF956C.toInt()
                    isJupiter -> 0xFFFFD4A0.toInt()
                    isSaturn -> 0xFFFFE6AD.toInt()
                    bodyKey == "Uranus" -> 0xFF9EEAF0.toInt()
                    bodyKey == "Neptune" -> 0xFF668FE8.toInt()
                    bodyKey == "Venus" -> 0xFFFFE6B0.toInt()
                    isSun -> 0xFFFFD451.toInt()
                    detail.contains("M1") || detail.contains("M2") -> 0xFFFF7256.toInt()
                    detail.contains("K0") || detail.contains("K1") || detail.contains("K5") -> 0xFFFFB45E.toInt()
                    detail.contains("B0") || detail.contains("B1") || detail.contains("B6") || detail.contains("B7") || detail.contains("B8") -> 0xFF8FCBFF.toInt()
                    detail.contains("A0") || detail.contains("A1") || detail.contains("A2") || detail.contains("A3") || detail.contains("A7") || detail.contains("A9") -> 0xFFE6F2FF.toInt()
                    else -> 0xFFFFE6A0.toInt()
                }
                val halo = when (core) {
                    0xFFFF7256.toInt() -> 0xFFFF4B31.toInt()
                    0xFFFFB45E.toInt() -> 0xFFFF8C32.toInt()
                    0xFF8FCBFF.toInt() -> 0xFF329EFF.toInt()
                    0xFFE6F2FF.toInt() -> 0xFF82BFFF.toInt()
                    else -> 0xFFFFA33B.toInt()
                }
                val isRedGiant = detail.contains("M1") || detail.contains("M2")
                val isGiant = detail.contains(" III") || detail.contains(" Ia") || detail.contains(" Ib")
                val radius = dp(if (isSolarBody) 62 else if (isRedGiant) 68 else if (isGiant) 61 else 54).toFloat()
                artPaint.shader = android.graphics.RadialGradient(cx, cy, radius * 1.55f,
                    intArrayOf((0x44 shl 24) or (halo and 0x00FFFFFF), (0x1C shl 24) or (halo and 0x00FFFFFF), 0x00000000), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, radius * 1.55f, artPaint)
                artPaint.shader = null
                if (isSaturn) {
                    canvas.save(); canvas.rotate(-17f, cx, cy)
                    artPaint.style = android.graphics.Paint.Style.STROKE; artPaint.strokeWidth = dp(4).toFloat(); artPaint.color = 0xB8D6B77E.toInt()
                    canvas.drawOval(cx - radius * 2.05f, cy - radius * 0.54f, cx + radius * 2.05f, cy + radius * 0.54f, artPaint)
                    artPaint.strokeWidth = dp(1.5f).toFloat(); artPaint.color = 0xDDF3DEB1.toInt()
                    canvas.drawOval(cx - radius * 1.72f, cy - radius * 0.43f, cx + radius * 1.72f, cy + radius * 0.43f, artPaint)
                    artPaint.style = android.graphics.Paint.Style.FILL; canvas.restore()
                }
                val sphere = android.graphics.Path().apply { addCircle(cx, cy, radius, android.graphics.Path.Direction.CW) }
                canvas.save()
                canvas.clipPath(sphere)
                artPaint.shader = android.graphics.RadialGradient(cx - radius * 0.32f, cy - radius * 0.38f, radius * 1.65f,
                    intArrayOf(0xFFFFFFFF.toInt(), core, halo, 0xFF160F1B.toInt()), floatArrayOf(0f, 0.38f, 0.76f, 1f), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, radius, artPaint)
                artPaint.shader = null

                if (isJupiter || isSaturn || bodyKey == "Neptune" || bodyKey == "Uranus") {
                    val bandPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(if (isJupiter) 5 else 3).toFloat()
                    }
                    for (i in -3..3) {
                        bandPaint.color = if (i % 2 == 0) (if (isJupiter) 0x66A45E43 else 0x668B9FAF) else 0x44FFF2D8
                        val y = cy + i * radius * 0.23f
                        canvas.drawLine(cx - radius, y, cx + radius, y + radius * 0.08f, bandPaint)
                    }
                    if (isJupiter) {
                        bandPaint.style = android.graphics.Paint.Style.FILL; bandPaint.color = 0x99CE7651.toInt()
                        canvas.drawOval(cx + radius * 0.12f, cy + radius * 0.14f, cx + radius * 0.62f, cy + radius * 0.42f, bandPaint)
                    }
                } else if (isMoon || isMars) {
                    val terrainPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x667B584D }
                    val terrain = if (isMoon) floatArrayOf(-0.35f, -0.08f, 0.15f, 0.35f, 0.3f, 0.09f, -0.45f, 0.42f, 0.07f, 0.18f, -0.48f, 0.06f) else floatArrayOf(-0.28f, 0.16f, 0.18f, 0.30f, -0.28f, 0.12f, 0.42f, 0.25f, 0.09f)
                    for (i in terrain.indices step 3) {
                        val x = cx + terrain[i] * radius; val y = cy + terrain[i + 1] * radius; val r = terrain[i + 2] * radius
                        canvas.drawCircle(x, y, r, terrainPaint)
                    }
                }

                // Stars have no resolved NASA surface photographs (except the Sun), so
                // use stable spectral-class granulation as an explicitly illustrative model.
                val seed = detail.substringBefore('·').hashCode().toLong()
                val random = java.util.Random(seed)
                val surfacePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                if (!isSolarBody || isSun) repeat(if (isRedGiant) 180 else 135) {
                    val angle = random.nextDouble() * Math.PI * 2
                    val distance = kotlin.math.sqrt(random.nextDouble()) * radius * 0.92
                    val x = cx + kotlin.math.cos(angle).toFloat() * distance.toFloat()
                    val y = cy + kotlin.math.sin(angle).toFloat() * distance.toFloat()
                    val size = (if (isRedGiant) 1.2 + random.nextDouble() * 6.0 else 0.7 + random.nextDouble() * 3.6) * resources.displayMetrics.density.toDouble()
                    val alpha = 18 + random.nextInt(46)
                    val shade = if (random.nextBoolean()) core else halo
                    surfacePaint.color = (alpha shl 24) or (shade and 0x00FFFFFF)
                    canvas.drawOval(x - size.toFloat(), y - size.toFloat() * 0.62f, x + size.toFloat(), y + size.toFloat() * 0.62f, surfacePaint)
                }
                artPaint.shader = android.graphics.RadialGradient(cx - radius * 0.28f, cy - radius * 0.35f, radius * 1.42f,
                    intArrayOf(0x00FFFFFF, 0x00101018, 0x9C080914.toInt()), floatArrayOf(0.42f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, radius, artPaint)
                artPaint.shader = null
                canvas.restore()
                artPaint.color = 0x66FFFFFF
                canvas.drawCircle(cx - radius * 0.33f, cy - radius * 0.4f, radius * 0.12f, artPaint)

                if (detail.contains("белый карлик") || detail.contains("white dwarf")) {
                    artPaint.shader = android.graphics.RadialGradient(cx + radius * 1.02f, cy + radius * 0.48f, dp(17).toFloat(),
                        intArrayOf(0xFFEAF6FF.toInt(), 0xFF73B9FF.toInt(), 0x0073B9FF), null, android.graphics.Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx + radius * 1.02f, cy + radius * 0.48f, dp(17).toFloat(), artPaint)
                    artPaint.shader = null
                    artPaint.color = 0xFFEAF6FF.toInt()
                    canvas.drawCircle(cx + radius * 1.02f, cy + radius * 0.48f, dp(5).toFloat(), artPaint)
                }
                artPaint.color = 0xFFB6D7F2.toInt(); artPaint.textSize = dp(11).toFloat(); artPaint.textAlign = android.graphics.Paint.Align.CENTER
                val caption = if (isSolarBody) L.t("ILLUSTRATIVE BODY MODEL · NOT TO SCALE", "МОДЕЛЬ НЕБЕСНОГО ТЕЛА · НЕ В МАСШТАБЕ")
                    else L.t("ILLUSTRATIVE STELLAR MODEL · NOT TO SCALE", "ХУДОЖЕСТВЕННАЯ МОДЕЛЬ ЗВЕЗДЫ · НЕ В МАСШТАБЕ")
                canvas.drawText(caption, cx, height - dp(12).toFloat(), artPaint)
            }
        }
        starArt.background = roundedGradient(0xFF091526.toInt(), 0xFF122744.toInt(), 20)
        val visual = FrameLayout(this).apply {
            background = roundedGradient(0xFF091526.toInt(), 0xFF122744.toInt(), 20)
            clipToOutline = true
        }
        visual.addView(starArt, FrameLayout.LayoutParams(-1, -1))
        nasaArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        visual.addView(nasaArt, FrameLayout.LayoutParams(-1, -1))
        nasaCaption = TextView(this).apply {
            textSize = 9f
            setTextColor(0xFFEAF4FF.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = android.graphics.drawable.ColorDrawable(0x99030B16.toInt())
            visibility = View.GONE
        }
        visual.addView(nasaCaption, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM))
        content.addView(visual, LinearLayout.LayoutParams(-1, dp(190)))
        content.addView(ScrollView(this).apply { addView(info) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.addView(content, FrameLayout.LayoutParams(-1, -1))
        return sheet
    }

    private fun makeSettingsOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99020B18.toInt())
            setOnClickListener { visibility = View.GONE }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedGradient(0xF20D2B52.toInt(), 0xF21B5B91.toInt(), 22)
            elevation = dp(16).toFloat()
            setOnClickListener { /* Keep taps inside the panel from dismissing it. */ }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = L.t("Settings", "Настройки")
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val close = Button(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF205A90.toInt(), 0xFF2D7BB4.toInt(), 18)
            setOnClickListener { overlay.visibility = View.GONE }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        panel.addView(header)
        val pages = listOf(LinearLayout(this), LinearLayout(this), LinearLayout(this))
        pages.forEach { it.orientation = LinearLayout.VERTICAL }
        val pageScrollViews = pages.map { page -> ScrollView(this).apply { addView(page) } }
        val content = FrameLayout(this)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun showPage(index: Int) {
            content.removeAllViews()
            content.addView(pageScrollViews[index], FrameLayout.LayoutParams(-1, -1))
        }
        listOf(L.t("GENERAL", "ОБЩЕЕ"), L.t("SKY", "НЕБО"), L.t("STARS", "ЗВЁЗДЫ")).forEachIndexed { index, title ->
            tabs.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE)
                background = roundedGradient(0xFF17518A.toInt(), 0xFF287FC0.toInt(), 16)
                setOnClickListener { showPage(index) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
        panel.addView(tabs, topMargin())
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        val general = pages[0]; val skyPage = pages[1]; val starsPage = pages[2]
        general.addView(sectionLabel(L.t("COMPASS", "КОМПАС")), topMargin())
        val hint = TextView(this).apply {
            text = L.t("Recalibrate when the compass feels inaccurate. Move the phone slowly in a figure‑8 after resetting.", "Если компас врёт — нажмите и медленно опишите телефоном восьмёрку.")
            setTextColor(0xFFD7ECFF.toInt())
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        general.addView(hint, topMargin())
        val recalibrate = Button(this).apply {
            text = L.t("⟳   RECALIBRATE COMPASS", "⟳   ПЕРЕКАЛИБРОВАТЬ КОМПАС")
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF1478B9.toInt(), 0xFF48A7E4.toInt(), 18)
            setOnClickListener { recalibrateCompass() }
        }
        general.addView(recalibrate, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        general.addView(sectionLabel(L.t("DISPLAY", "ЭКРАН")), topMargin())

        fovValue = TextView(this).apply {
            setTextColor(paleBlue)
            textSize = 13f
            text = L.t("Field of view: ", "Поле зрения: ") + "${sky.fovDeg.toInt()}°"
        }
        general.addView(fovValue, topMargin())
        val fovSeek = SeekBar(this).apply {
            max = 55
            progress = (sky.fovDeg.toInt() - 5).coerceIn(0, 55)
            progressTintList = android.content.res.ColorStateList.valueOf(lightBlue)
            thumbTintList = android.content.res.ColorStateList.valueOf(paleBlue)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    sky.setFieldOfView((progress + 5).toDouble())
                    fovValue.text = L.t("Field of view: ", "Поле зрения: ") + "${sky.fovDeg.toInt()}°"
                    prefs.edit().putFloat("fov", sky.fovDeg.toFloat()).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { }
            })
        }
        general.addView(fovSeek, topMargin())
        general.addView(sectionLabel(L.t("LANGUAGE", "ЯЗЫК")), topMargin())
        val langRow = LinearLayout(this)
        for ((code, label) in listOf("ru" to "Русский", "en" to "English")) langRow.addView(Button(this).apply {
            text = label; isAllCaps = false; setTextColor(Color.WHITE)
            background = roundedGradient(if ((code == "ru") == L.ru) 0xFF1478B9.toInt() else 0xFF153D69.toInt(), if ((code == "ru") == L.ru) 0xFF48A7E4.toInt() else 0xFF205B8C.toInt(), 18)
            setOnClickListener { prefs.edit().putString("lang", code).apply(); recreate() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        general.addView(langRow, topMargin())

        skyPage.addView(sectionLabel(L.t("SKY APPEARANCE", "ВИД НЕБА")), topMargin())
        addSettingSwitch(starsPage, L.t("Star names", "Названия звёзд"), sky.drawStarNames) { value ->
            sky.drawStarNames = value
            prefs.edit().putBoolean("star_names", value).apply()
            sky.invalidate()
        }
        addSettingSwitch(skyPage, L.t("Planet names", "Названия планет"), sky.drawPlanetNames) { value ->
            sky.drawPlanetNames = value
            prefs.edit().putBoolean("planet_names", value).apply()
            sky.invalidate()
        }
        addSettingSwitch(skyPage, L.t("Detailed planet surfaces", "Детальные поверхности планет"), sky.drawPlanetDetails) { value ->
            sky.drawPlanetDetails = value
            prefs.edit().putBoolean("planet_details", value).apply()
            sky.invalidate()
        }

        fun sw(page: LinearLayout, key: String, en: String, ru: String, def: Boolean, set: (Boolean) -> Unit) = addSettingSwitch(page, L.t(en, ru), prefs.getBoolean(key, def)) { v -> set(v); prefs.edit().putBoolean(key, v).apply(); sky.invalidate() }
        sw(skyPage, "cons", "Constellation lines", "Линии созвездий", true) { sky.showConstellations = it }
        sw(skyPage, "conNames", "Constellation names", "Названия созвездий", true) { sky.showConNames = it }
        sw(skyPage, "ground", "Horizon and ground", "Горизонт и земля", false) { sky.showGround = it }
        sw(skyPage, "grid", "Altitude/azimuth grid", "Сетка высота/азимут", false) { sky.showGrid = it }
        sw(skyPage, "day", "Realistic daylight sky", "Реалистичное дневное небо", false) { sky.daylightSky = it }
        sw(skyPage, "night", "Night (red) mode", "Ночной (красный) режим", false) { sky.nightMode = it }
        starsPage.addView(sectionLabel(L.t("STAR CATALOG", "КАТАЛОГ ЗВЁЗД")), topMargin())
        starsPage.addView(TextView(this).apply {
            text = L.t("The catalog is based on Hipparcos positions and brightness. Physical reference data is shown for selected well-studied stars; unavailable values are left blank.", "Каталог использует координаты и яркость Hipparcos. Справочные физические данные приведены для ряда хорошо изученных звёзд; отсутствующие значения не подменяются догадками.")
            setTextColor(0xFFD7ECFF.toInt()); textSize = 12f; setLineSpacing(dp(2).toFloat(), 1f)
        }, topMargin())
        starsPage.addView(sectionLabel(L.t("VISIBILITY", "ОТОБРАЖЕНИЕ")), topMargin())
        val starTxt = TextView(this).apply { setTextColor(paleBlue); textSize = 13f; text = L.t("Faintest star shown: ", "Самая слабая звезда: ") + "%.1f".format(sky.starLimit) }
        starsPage.addView(starTxt, topMargin())
        starsPage.addView(SeekBar(this).apply {
            max = 45; progress = ((sky.starLimit - 2) * 10).toInt().coerceIn(0, 45)
            progressTintList = android.content.res.ColorStateList.valueOf(lightBlue); thumbTintList = android.content.res.ColorStateList.valueOf(paleBlue)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) { sky.starLimit = 2.0 + v / 10.0; starTxt.text = L.t("Faintest star shown: ", "Самая слабая звезда: ") + "%.1f".format(sky.starLimit); prefs.edit().putFloat("starLimit2", sky.starLimit.toFloat()).apply(); sky.invalidate() }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }, topMargin())

        val reset = Button(this).apply {
            text = L.t("RESET ZOOM", "СБРОС МАСШТАБА")
            isAllCaps = false
            textSize = 12f
            setTextColor(paleBlue)
            background = roundedGradient(0xFF153D69.toInt(), 0xFF205B8C.toInt(), 18)
            setOnClickListener {
                sky.setFieldOfView(60.0)
                fovSeek.progress = 55
            }
        }
        general.addView(reset, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        showPage(0)
        overlay.addView(panel, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER).apply {
            setMargins(dp(22), dp(20), dp(22), dp(20))
        })
        return overlay
    }

    private fun sectionLabel(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 10f
        letterSpacing = 0.16f
        setTextColor(0xFF91D6FF.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun addSettingSwitch(parent: LinearLayout, label: String, checked: Boolean, changed: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        row.addView(text, LinearLayout.LayoutParams(0, dp(42), 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            thumbTintList = android.content.res.ColorStateList.valueOf(paleBlue)
            trackTintList = android.content.res.ColorStateList.valueOf(lightBlue)
            setOnCheckedChangeListener { _, value -> changed(value) }
        }
        row.addView(toggle)
        parent.addView(row, topMargin())
    }

    private fun topMargin() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }

    private fun roundedGradient(a0: Int, b0: Int, r0: Int): GradientDrawable = roundedGradient2(tone(a0), tone(b0), r0)

    private fun roundedGradient2(start: Int, end: Int, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(start, end)
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 41
        private const val SENSOR_PERIOD_US = 16_667
    }
}
