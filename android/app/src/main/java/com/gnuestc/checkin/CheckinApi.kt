package com.gnuestc.checkin

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 打卡接口封装。
 * 请求格式来自 2026-10-16 对打卡页的实测抓包：
 *  - POST /daily-checkin/api/student-checkin-record/checkin
 *  - 鉴权仅靠 Cookie（SESSION），无签名、无 token
 *  - 服务端会记录 clientTime 时钟偏差、userAgent、IP 等遥测字段
 */
class CheckinApi(cookie: String) {

    // 容忍用户只粘贴值（无 "SESSION=" 前缀）的情况
    private val cookieHeader = if (cookie.contains("=")) cookie else "SESSION=$cookie"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val base = "https://jzsz.uestc.edu.cn/daily-checkin/api"

    fun getToday(): JSONObject = get("/student-checkin-record/today")

    /** 本账号可用打卡地点（服务端按学生/组团下发：latitude/longitude/radiusMeter/groupName） */
    fun getAvailableLocations(): JSONObject = get("/checkin-location/available-locations")

    /** 生效的打卡时间表（businessDate + studentCheckinStart/EndOffsetMinute，当天 00:00 起分钟偏移） */
    fun getEffectiveSchedule(): JSONObject = get("/checkin-schedule/effective")

    fun checkin(latitude: Double, longitude: Double, reverseGeocode: String): JSONObject {
        val body = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("reverseGeocode", reverseGeocode)
            put("clientTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("userAgent", USER_AGENT)
            put("coordinateSystemCode", "gcj02")
            put("accuracy", 20)
            put("locationDurationMs", (300..1200).random())
            put("networkTypeCode", "4g")
            put("locationEnabled", false)
            put("locationAuthorized", "")
            put("locationAccuracy", "")
            put("errorMessage", "")
        }
        return post("/student-checkin-record/checkin", body)
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("platform", "h5")
            .header("Cookie", cookieHeader)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(base + path)
            .header("platform", "h5")
            .header("Cookie", cookieHeader)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return JSONObject(text.ifBlank { "{}" })
        }
    }

    companion object {
        /** 打卡点兜底值（GCJ02）；登录后会从服务端提取本账号真实打卡点覆盖 */
        const val LATITUDE = 30.753818
        const val LONGITUDE = 103.934258
        const val FENCE_RADIUS_M = 150.0
        const val REVERSE_GEOCODE = "四川省成都市郫都区银杏大道"

        /** 手机 UA，避免使用 OkHttp/脚本类默认 UA */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
