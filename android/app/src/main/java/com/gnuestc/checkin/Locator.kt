package com.gnuestc.checkin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat

/**
 * 读取手机当前位置（一次性）：
 * 优先取最近 10 分钟内的系统缓存；否则请求一次实时定位，15 秒超时后退回旧缓存。
 */
object Locator {

    fun hasPermission(context: Context): Boolean =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun getBestOnce(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(null)
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val cached = providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }

        // 10 分钟内的缓存直接可用
        if (cached != null && System.currentTimeMillis() - cached.time < 10 * 60_000L) {
            onResult(cached)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                finished = true
                runCatching { lm.removeUpdates(this) }
                handler.removeCallbacksAndMessages(null)
                onResult(location)
            }
        }

        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            runCatching { lm.removeUpdates(listener) }
            onResult(cached) // 超时退回旧缓存（可能为 null）
        }

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: Exception) {
        }
        handler.postDelayed(timeout, 15_000L)
    }
}
