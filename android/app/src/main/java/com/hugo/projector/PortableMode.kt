package com.hugo.projector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** Owns the app's LocalOnlyHotspot reservation. No shell or tethering privileges are used. */
object PortableMode {
    private lateinit var app: Context
    private lateinit var wifi: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var generation = 0
    private var notifyChange: () -> Unit = {}
    @Volatile var state = "off"
        private set
    @Volatile var error = ""
        private set
    @Volatile var ssid = ""
        private set
    @Volatile var passphrase = ""
        private set
    @Volatile var hotspotAddress: String? = null
        private set
    private var previousAddresses = emptySet<String>()

    fun initialize(context: Context, onChange: () -> Unit) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        }
        notifyChange = onChange
    }

    fun preferred(): Boolean = app.getSharedPreferences("hugo_portable", Context.MODE_PRIVATE).getBoolean("preferred", false)

    fun permissionMissing(): Array<String> {
        val names = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            names.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            names.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return names.toTypedArray()
    }

    fun start() {
        if (state == "starting" || state == "active") return
        app.getSharedPreferences("hugo_portable", Context.MODE_PRIVATE).edit().putBoolean("preferred", true).apply()
        if (permissionMissing().isNotEmpty()) {
            state = "permission_required"; error = "Allow Nearby devices and Location on the projector"; notifyChange(); return
        }
        previousAddresses = addresses().values.toSet()
        val current = ++generation
        state = "starting"; error = ""; hotspotAddress = null; ssid = ""; passphrase = ""; notifyChange()
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    if (current != generation || !preferred()) { value.close(); return }
                    reservation = value
                    val config = value.softApConfiguration
                    ssid = config.ssid ?: ""
                    passphrase = config.passphrase ?: ""
                    state = "active"; error = ""; updateAddress(current, 0); notifyChange()
                    Log.i("HUGO_PORTABLE", "LocalOnlyHotspot started")
                }

                override fun onStopped() {
                    if (current != generation) return
                    reservation = null; ssid = ""; passphrase = ""; hotspotAddress = null
                    state = "stopped"; error = "Hotspot stopped; start Portable Mode again"; notifyChange()
                    Log.w("HUGO_PORTABLE", "LocalOnlyHotspot stopped")
                }

                override fun onFailed(reason: Int) {
                    if (current != generation) return
                    reservation = null; ssid = ""; passphrase = ""; hotspotAddress = null
                    state = "failed"; error = "LocalOnlyHotspot failed ($reason)"; notifyChange()
                    Log.w("HUGO_PORTABLE", "LocalOnlyHotspot failed reason=$reason")
                }
            }, handler)
        } catch (e: Exception) {
            state = "failed"; error = "LocalOnlyHotspot unavailable: ${e.javaClass.simpleName}"; notifyChange()
            Log.w("HUGO_PORTABLE", "LocalOnlyHotspot unavailable", e)
        }
    }

    fun stop() {
        ++generation
        val old = reservation
        reservation = null; hotspotAddress = null; ssid = ""; passphrase = ""; error = ""; state = "off"
        app.getSharedPreferences("hugo_portable", Context.MODE_PRIVATE).edit().putBoolean("preferred", false).apply()
        try { old?.close() } catch (e: Exception) { Log.w("HUGO_PORTABLE", "Closing hotspot", e) }
        notifyChange()
    }

    fun stopRuntime() {
        ++generation
        val old = reservation
        reservation = null; hotspotAddress = null; ssid = ""; passphrase = ""; error = ""; state = "off"
        try { old?.close() } catch (e: Exception) { Log.w("HUGO_PORTABLE", "Closing hotspot", e) }
        notifyChange()
    }

    fun restoreIfPreferred() {
        if (preferred() && state == "off") start()
    }

    private fun updateAddress(current: Int, attempt: Int) {
        if (current != generation || state != "active") return
        val candidates = addresses()
        hotspotAddress = candidates.entries.firstOrNull { (name, ip) ->
            ip !in previousAddresses && (name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan"))
        }?.value ?: candidates.entries.firstOrNull { (name, _) -> name.startsWith("ap") || name.startsWith("swlan") }?.value
        notifyChange()
        if (hotspotAddress == null && attempt < 20) handler.postDelayed({ updateAddress(current, attempt + 1) }, 500)
    }

    fun addresses(): Map<String, String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.flatMap { network ->
            network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { network.name to it.hostAddress }
        }?.toMap() ?: emptyMap()
    } catch (_: Exception) { emptyMap() }
}
