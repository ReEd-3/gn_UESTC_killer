package com.gnuestc.checkin.engine

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
 * [v7 巡检链] 引擎侧打卡接口（主应用 CheckinApi 的移植，参数口径保持一致）：
 *  - GET  /student-checkin-record/today —— 以 recordStatusCode==CHECKED_IN 为唯一可信的成功标准
 *  - POST /student-checkin-record/checkin —— Cookie(SESSION) 鉴权、无签名
 * 注意：服务端在"有效时段外"提交也会返回 code=0 但不生成记录，因此提交后必须复查 /today。
 */
class EngineCheckin(cookie: String) {

    // 容忍只有值、没有 "SESSION=" 前缀的情况
    private val cookieHeader = if (cookie.contains("=")) cookie else "SESSION=$cookie"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val base = "https://jzsz.uestc.edu.cn/daily-checkin/api"

    fun getToday(): JSONObject = get("/student-checkin-record/today")

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

    /** 提交后复查今日状态（最多 3 次、间隔 2 秒）：唯一可信的成功判定 */
    fun verifyCheckedIn(): Boolean {
        repeat(3) { attempt ->
            val ok = runCatching {
                getToday().optJSONObject("data")?.optString("recordStatusCode") == "CHECKED_IN"
            }.getOrDefault(false)
            if (ok) return true
            if (attempt < 2) Thread.sleep(2000)
        }
        return false
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
        const val REVERSE_GEOCODE = "四川省成都市郫都区银杏大道"

        /** 手机 UA，与主应用一致（避免脚本类客户端默认 UA） */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
