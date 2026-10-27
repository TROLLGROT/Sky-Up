package com.trollgrot.skyup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.acos
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener, LocationListener {
    private lateinit var sky: SkyView
    private lateinit var info: TextView
    private lateinit var sm: SensorManager
    private lateinit var root: FrameLayout
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var fovValue: TextView

    private val q = FloatArray(4)
    private val n = FloatArray(4)
    private var hasQuaternion = false
    private val rot = FloatArray(9)

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
        Catalog.load(this)
        sky = SkyView(this).apply {
            showConstellations = prefs.getBoolean("cons", true); showConNames = prefs.getBoolean("conNames", true)
            showGround = prefs.getBoolean("ground", true); showGrid = prefs.getBoolean("grid", false)
            daylightSky = prefs.getBoolean("day", true); nightMode = prefs.getBoolean("night", false)
            starLimit = prefs.getFloat("starLimit", 5.5f).toDouble()
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
            background = roundedGradient(0xE6102D52.toInt(), 0xE91B477A.toInt(), 16)
            visibility = View.GONE
        }
        sky.onSelect = { message ->
            info.visibility = if (message == null) View.GONE else View.VISIBLE
            if (message != null) info.text = message
        }
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(14), 0, dp(14), dp(14))
        })

        root.addView(makeTopBar(), FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP))
        root.addView(makeTimeBar(), FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(72) })
        root.addView(makeZoomControls(), FrameLayout.LayoutParams(dp(48), dp(112), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(12)
        })
        settingsOverlay = makeSettingsOverlay()
        settingsOverlay.visibility = View.GONE
        root.addView(settingsOverlay, FrameLayout.LayoutParams(-1, -1))
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
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
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
        SensorManager.getQuaternionFromVector(n, event.values)
        if (!hasQuaternion) {
            n.copyInto(q)
            hasQuaternion = true
        } else {
            var dot = n[0] * q[0] + n[1] * q[1] + n[2] * q[2] + n[3] * q[3]
            if (dot < 0f) {
                for (i in 0..3) n[i] = -n[i]
                dot = -dot
            }
            val angle = 2f * acos(minOf(1f, dot))
            val alpha = minOf(1f, 0.08f + angle * 4f)
            for (i in 0..3) q[i] += alpha * (n[i] - q[i])
            val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            if (length > 0f) for (i in 0..3) q[i] /= length
        }
        SensorManager.getRotationMatrixFromVector(rot, floatArrayOf(q[1], q[2], q[3], q[0]))
        sky.rot = rot.copyOf()
        sky.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        sky.lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        sky.invalidate()
    }

    private fun recalibrateCompass() {
        // Drop the smoothed orientation so the next rotation-vector sample is used as a fresh baseline.
        hasQuaternion = false
        sky.clearSelection()
        sky.lowAccuracy = false
        sky.invalidate()
        Toast.makeText(this, L.t("Compass reset. Slowly move the phone in a figure‑8.", "Компас сброшен. Медленно опишите телефоном восьмёрку."), Toast.LENGTH_LONG).show()
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
        h.post(object : Runnable { override fun run() { label.text = fmt.format(java.util.Date(sky.now())); h.postDelayed(this, 1000) } })
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

    private fun makeTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(16), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xE90A2347.toInt(), 0xB91C5A91.toInt(), 0x001C5A91)
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
            text = L.t("LIVE SKY MAP", "КАРТА НЕБА")
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
            text = "Settings"
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
        panel.addView(sectionLabel(L.t("DISPLAY", "ЭКРАН")), topMargin())

        fovValue = TextView(this).apply {
            setTextColor(paleBlue)
            textSize = 13f
            text = L.t("Field of view: ", "Поле зрения: ") + "${sky.fovDeg.toInt()}°"
        }
        panel.addView(fovValue, topMargin())
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
        panel.addView(fovSeek, topMargin())

        panel.addView(sectionLabel(L.t("SKY APPEARANCE", "ВИД НЕБА")), topMargin())
        addSettingSwitch(panel, L.t("Star names", "Названия звёзд"), sky.drawStarNames) { value ->
            sky.drawStarNames = value
            prefs.edit().putBoolean("star_names", value).apply()
            sky.invalidate()
        }
        addSettingSwitch(panel, L.t("Planet names", "Названия планет"), sky.drawPlanetNames) { value ->
            sky.drawPlanetNames = value
            prefs.edit().putBoolean("planet_names", value).apply()
            sky.invalidate()
        }
        addSettingSwitch(panel, L.t("Detailed planet surfaces", "Детальные поверхности планет"), sky.drawPlanetDetails) { value ->
            sky.drawPlanetDetails = value
            prefs.edit().putBoolean("planet_details", value).apply()
            sky.invalidate()
        }

        fun sw(key: String, en: String, ru: String, def: Boolean, set: (Boolean) -> Unit) = addSettingSwitch(panel, L.t(en, ru), prefs.getBoolean(key, def)) { v -> set(v); prefs.edit().putBoolean(key, v).apply(); sky.invalidate() }
        sw("cons", "Constellation lines", "Линии созвездий", true) { sky.showConstellations = it }
        sw("conNames", "Constellation names", "Названия созвездий", true) { sky.showConNames = it }
        sw("ground", "Horizon and ground", "Горизонт и земля", true) { sky.showGround = it }
        sw("grid", "Altitude/azimuth grid", "Сетка высота/азимут", false) { sky.showGrid = it }
        sw("day", "Realistic daylight sky", "Реалистичное дневное небо", true) { sky.daylightSky = it }
        sw("night", "Night (red) mode", "Ночной (красный) режим", false) { sky.nightMode = it }
        val starTxt = TextView(this).apply { setTextColor(paleBlue); textSize = 13f; text = L.t("Faintest star shown: ", "Самая слабая звезда: ") + "%.1f".format(sky.starLimit) }
        panel.addView(starTxt, topMargin())
        panel.addView(SeekBar(this).apply {
            max = 35; progress = ((sky.starLimit - 2) * 10).toInt().coerceIn(0, 35)
            progressTintList = android.content.res.ColorStateList.valueOf(lightBlue); thumbTintList = android.content.res.ColorStateList.valueOf(paleBlue)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) { sky.starLimit = 2.0 + v / 10.0; starTxt.text = L.t("Faintest star shown: ", "Самая слабая звезда: ") + "%.1f".format(sky.starLimit); prefs.edit().putFloat("starLimit", sky.starLimit.toFloat()).apply(); sky.invalidate() }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { }
            })
        }, topMargin())
        panel.addView(sectionLabel(L.t("LANGUAGE", "ЯЗЫК")), topMargin())
        val langRow = LinearLayout(this)
        for ((code, label) in listOf("ru" to "Русский", "en" to "English")) langRow.addView(Button(this).apply {
            text = label; isAllCaps = false; setTextColor(Color.WHITE)
            background = roundedGradient(if ((code == "ru") == L.ru) 0xFF1478B9.toInt() else 0xFF153D69.toInt(), if ((code == "ru") == L.ru) 0xFF48A7E4.toInt() else 0xFF205B8C.toInt(), 18)
            setOnClickListener { prefs.edit().putString("lang", code).apply(); recreate() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        panel.addView(langRow, topMargin())
        panel.addView(sectionLabel(L.t("COMPASS", "КОМПАС")), topMargin())
        val hint = TextView(this).apply {
            text = L.t("Recalibrate when the compass feels inaccurate. Move the phone slowly in a figure‑8 after resetting.", "Если компас врёт — нажмите и медленно опишите телефоном восьмёрку.")
            setTextColor(0xFFD7ECFF.toInt())
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(hint, topMargin())
        val recalibrate = Button(this).apply {
            text = L.t("⟳   RECALIBRATE COMPASS", "⟳   ПЕРЕКАЛИБРОВАТЬ КОМПАС")
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundedGradient(0xFF1478B9.toInt(), 0xFF48A7E4.toInt(), 18)
            setOnClickListener { recalibrateCompass() }
        }
        panel.addView(recalibrate, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })

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
        panel.addView(reset, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        overlay.addView(ScrollView(this).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
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

    private fun roundedGradient(start: Int, end: Int, radiusDp: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(start, end)
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 41
    }
}
