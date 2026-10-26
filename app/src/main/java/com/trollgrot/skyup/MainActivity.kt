package com.trollgrot.skyup
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.*
import android.hardware.GeomagneticField
import android.location.*
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.acos
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener, LocationListener {
    private lateinit var sky: SkyView
    private lateinit var info: TextView
    private lateinit var sm: SensorManager
    private val q = FloatArray(4); private val n = FloatArray(4); private var has = false
    private val rot = FloatArray(9)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sky = SkyView(this)
        info = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xCC000000.toInt()); setPadding(32, 24, 32, 24); textSize = 15f; visibility = View.GONE }
        sky.onSelect = { t -> info.visibility = if (t == null) View.GONE else View.VISIBLE; if (t != null) info.text = t }
        val root = FrameLayout(this)
        root.addView(sky)
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val pr = getSharedPreferences("sky", MODE_PRIVATE)
        if (pr.contains("lat")) setLoc(pr.getFloat("lat", 0f).toDouble(), pr.getFloat("lon", 0f).toDouble(), 0.0, false)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    override fun onResume() {
        super.onResume()
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) // fused gyro + accelerometer + magnetometer
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        startLocation()
    }

    override fun onPause() {
        super.onPause(); sm.unregisterListener(this)
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { startLocation() }

    private fun startLocation() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (pv in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(pv)) continue
                lm.getLastKnownLocation(pv)?.let { onLocationChanged(it) }
                lm.requestLocationUpdates(pv, 60000L, 500f, this)
            }
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    private fun setLoc(la: Double, lo: Double, alt: Double, save: Boolean) {
        sky.lat = la; sky.lon = lo; sky.hasLocation = true
        sky.decl = GeomagneticField(la.toFloat(), lo.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination.toDouble()
        if (save) getSharedPreferences("sky", MODE_PRIVATE).edit().putFloat("lat", la.toFloat()).putFloat("lon", lo.toFloat()).apply()
    }

    override fun onLocationChanged(l: Location) { setLoc(l.latitude, l.longitude, l.altitude, true) }
    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getQuaternionFromVector(n, e.values) // w,x,y,z
        if (!has) { n.copyInto(q); has = true } else {
            var d = n[0] * q[0] + n[1] * q[1] + n[2] * q[2] + n[3] * q[3]
            if (d < 0) { for (i in 0..3) n[i] = -n[i]; d = -d }
            // adaptive filter: heavy smoothing when still, almost none when moving fast
            val ang = 2f * acos(minOf(1f, d))
            val a = minOf(1f, 0.08f + ang * 4f)
            for (i in 0..3) q[i] += a * (n[i] - q[i])
            val len = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            for (i in 0..3) q[i] /= len
        }
        SensorManager.getRotationMatrixFromVector(rot, floatArrayOf(q[1], q[2], q[3], q[0]))
        sky.rot = rot.copyOf()
        sky.invalidate()
    }

    override fun onAccuracyChanged(s: Sensor, acc: Int) { sky.lowAccuracy = acc <= SensorManager.SENSOR_STATUS_ACCURACY_LOW }
}
