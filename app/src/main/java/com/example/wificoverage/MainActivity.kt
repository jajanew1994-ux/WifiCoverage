package com.example.wificoverage

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var wifi: WifiManager
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnSave: Button

    private val rssiWindow = ArrayDeque<Int>()
    private var lastPoint: Location? = null
    private var running = false
    private var centered = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startTracking()
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleReading(loc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = Configuration.getInstance()
        cfg.load(applicationContext, getSharedPreferences("osm", Context.MODE_PRIVATE))
        cfg.userAgentValue = "WifiCoverageApp/1.0 (github.com/jajanew1994-ux/WifiCoverage)"
        cfg.tileFileSystemCacheMaxBytes = 1_000_000_000L
        cfg.tileFileSystemCacheTrimBytes = 800_000_000L

        setContentView(R.layout.activity_main)

        fused = LocationServices.getFusedLocationProviderClient(this)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnSave = findViewById(R.id.btnSave)

        val carto = XYTileSource(
            "CartoVoyager", 0, 20, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                "https://b.basemaps.cartocdn.com/rastertiles/voyager/"
            ),
            "© OpenStreetMap contributors © CARTO"
        )

        mapView = findViewById(R.id.map)
        mapView.setTileSource(carto)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)

        btnToggle.setOnClickListener {
            if (running) stopTracking() else requestPermsAndStart()
        }
        btnSave.setOnClickListener { saveAreaOffline() }
    }

    private fun saveAreaOffline() {
        Toast.makeText(this, "Saving visible map area...", Toast.LENGTH_SHORT).show()
        CacheManager(mapView).downloadAreaAsync(
            this, mapView.boundingBox, 16, 19,
            object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete() {
                    Toast.makeText(this@MainActivity, "Area saved for offline use", Toast.LENGTH_LONG).show()
                }
                override fun onTaskFailed(errors: Int) {
                    Toast.makeText(this@MainActivity, "Save failed. Check your internet", Toast.LENGTH_LONG).show()
                }
                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {}
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(total: Int) {}
            }
        )
    }

    private fun requestPermsAndStart() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateDistanceMeters(0.5f)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        running = true
        btnToggle.text = "Stop"
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(locationCallback)
        running = false
        btnToggle.text = "Start"
    }

    @Suppress("DEPRECATION")
    private fun handleReading(loc: Location) {
        val here = GeoPoint(loc.latitude, loc.longitude)
        if (!centered) {
            mapView.controller.animateTo(here)
            centered = true
        }

        val info = wifi.connectionInfo
        if (info == null || info.networkId == -1) {
            tvStatus.text = "Not connected to WiFi (map still tracking)"
            return
        }

        rssiWindow.addLast(info.rssi)
        if (rssiWindow.size > 5) rssiWindow.removeFirst()
        val avg = rssiWindow.average().toInt()

        val band = if (info.frequency > 4900) "5 GHz" else "2.4 GHz"
        tvStatus.text = "$avg dBm  |  ${info.linkSpeed} Mbps  |  $band"

        val prev = lastPoint
        if (prev == null || loc.distanceTo(prev) >= 1.5f) {
            lastPoint = loc

            val dot = Polygon(mapView).apply {
                points = Polygon.pointsAsCircle(here, 2.0)
                fillPaint.color = rssiToColor(avg)
                outlinePaint.strokeWidth = 0f
            }
            mapView.overlays.add(dot)
            mapView.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) fused.removeLocationUpdates(locationCallback)
    }
}
