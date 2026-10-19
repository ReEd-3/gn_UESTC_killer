package com.gnuestc.checkin

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gnuestc.checkin.databinding.ActivityMapPickBinding
import java.util.Locale

/**
 * 地图选点：内置 Leaflet + 高德瓦片（本地页面，无需任何地图 key）。
 * 高德瓦片为 GCJ02 对齐，地图上点到的经纬度即 GCJ02，与提交打卡的坐标系一致，可直接使用。
 */
class MapPickActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickBinding
    private var pickedLat = CheckinApi.LATITUDE
    private var pickedLng = CheckinApi.LONGITUDE

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // [修复 2026-10-19] 主题已改由 App(Application) 统一应用；保留备查
        // ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 边到边显示：给根布局留出状态栏间距
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        pickedLat = intent.getDoubleExtra(EXTRA_START_LAT, Prefs.getLocationLat(this))
        pickedLng = intent.getDoubleExtra(EXTRA_START_LNG, Prefs.getLocationLng(this))
        updatePickedText()

        with(binding.webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true
        }
        // 仅加载本地 assets 页面，暴露 Android.onPick 接收地图点选结果
        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPick(lat: Double, lng: Double) {
                runOnUiThread {
                    pickedLat = lat
                    pickedLng = lng
                    updatePickedText()
                }
            }
        }, "Android")

        binding.webView.loadUrl(
            "file:///android_asset/map.html?lat=%f&lng=%f&flat=%f&flng=%f&fence=%f"
                .format(
                    Locale.US, pickedLat, pickedLng,
                    Prefs.getDefaultLat(this), Prefs.getDefaultLng(this), Prefs.getDefaultRadius(this)
                )
        )

        binding.btnMapCancel.setOnClickListener { finish() }
        binding.btnMapConfirm.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_LAT, pickedLat)
                    .putExtra(EXTRA_LNG, pickedLng)
            )
            finish()
        }
    }

    private fun updatePickedText() {
        binding.tvMapPicked.text =
            String.format(Locale.US, "已选：%.6f, %.6f", pickedLat, pickedLng)
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_START_LAT = "start_lat"
        const val EXTRA_START_LNG = "start_lng"
    }
}
