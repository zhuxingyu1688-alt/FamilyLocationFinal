package com.familylocation

import android.app.*
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*

class LocationService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "fl_channel"
        const val NOTIF_ID = 7001

        // 高精度优先：最多等 2 分钟；精度不够就不乱发。
        private const val LOCATION_TIMEOUT_MS = 120_000L
        private const val GOOD_ACCURACY_METERS = 30f
        private const val MAX_ACCEPT_ACCURACY_METERS = 80f
        private const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        acquireWakeLock()

        scope.launch {
            try {
                Prefs.setLastStatus(applicationContext, "📡 正在搜索 GPS 高精度位置，最多等待 2 分钟…")
                val loc = withTimeoutOrNull(LOCATION_TIMEOUT_MS + 10_000L) { getPreciseNativeLocation() }

                if (loc == null) {
                    Prefs.setLastStatus(applicationContext, "⚠️ 定位精度不足/超时：请打开 GPS、精确位置，尽量靠近窗边")
                    return@launch
                }

                val address = reverseGeocode(loc.latitude, loc.longitude)
                val result = postLocation(loc, address)
                val now = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
                Prefs.setLastSent(applicationContext, now)
                Prefs.setLastStatus(
                    applicationContext,
                    if (result.first) "✅ 发送成功（GPS 精度约 ${loc.accuracy.toInt()} 米）" else "❌ 飞书未接收：${result.second.take(40)}"
                )
            } catch (e: Exception) {
                Prefs.setLastStatus(applicationContext, "❌ ${e.message?.take(40) ?: "发送异常"}")
            } finally {
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: android.content.Intent?) = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyLocation:preciseOnce").apply {
                setReferenceCounted(false)
                acquire(LOCATION_TIMEOUT_MS + 20_000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    @Suppress("MissingPermission")
    private suspend fun getPreciseNativeLocation(): Location? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var finished = false

        fun isProviderEnabled(provider: String): Boolean = try { locationManager.isProviderEnabled(provider) } catch (_: Exception) { false }

        fun finish(location: Location?) {
            if (finished || !cont.isActive) return
            finished = true
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            cont.resume(location) {}
        }

        fun candidate(location: Location?) {
            if (location == null || finished || !cont.isActive) return
            if (!isFresh(location) || !location.hasAccuracy()) return
            best = betterOf(best, location)
            val b = best
            if (b != null && b.accuracy <= GOOD_ACCURACY_METERS) finish(b)
        }

        fun finishAtTimeout() {
            val b = best
            if (b != null && b.hasAccuracy() && b.accuracy <= MAX_ACCEPT_ACCURACY_METERS && isFresh(b)) finish(b) else finish(null)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { candidate(location) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // 先读取缓存，但只有新鲜且精度好的缓存才接受。
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach { p ->
            try { candidate(locationManager.getLastKnownLocation(p)) } catch (_: Exception) {}
        }

        val providers = mutableListOf<String>()
        if (isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
        if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)

        if (providers.isEmpty()) {
            finish(null)
            return@suspendCancellableCoroutine
        }

        providers.forEach { provider ->
            try { locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        }

        mainHandler.postDelayed({ finishAtTimeout() }, LOCATION_TIMEOUT_MS)
        cont.invokeOnCancellation {
            finished = true
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        }
    }

    private lateinit var listener: LocationListener

    private fun isFresh(location: Location): Boolean = System.currentTimeMillis() - location.time <= MAX_LOCATION_AGE_MS

    private fun betterOf(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        if (!isFresh(a) && isFresh(b)) return b
        if (isFresh(a) && !isFresh(b)) return a
        if (!a.hasAccuracy() && b.hasAccuracy()) return b
        if (a.hasAccuracy() && !b.hasAccuracy()) return a
        return if (b.accuracy < a.accuracy) b else a
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String = try {
        val geocoder = Geocoder(this, Locale.CHINA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val deferred = CompletableDeferred<String>()
            geocoder.getFromLocation(lat, lng, 1) { list -> deferred.complete(list.firstOrNull()?.getAddressLine(0) ?: "") }
            withTimeoutOrNull(4_000) { deferred.await() } ?: ""
        } else {
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0) ?: ""
        }
    } catch (_: Exception) { "" }

    private fun postLocation(loc: Location, address: String): Pair<Boolean, String> {
        val url = Prefs.getWebhookUrl(applicationContext)
        if (url.isBlank()) return false to "Webhook 为空"

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val gcj = wgs84ToGcj02(loc.latitude, loc.longitude)
        val amapUrl = "https://uri.amap.com/marker?position=${gcj.second},${gcj.first}&name=家人位置"

        val text = "📍 家人位置\n" +
            "时间：$time\n" +
            "设备：$device\n" +
            "地址：${if (address.isBlank()) "未解析到地址" else address}\n" +
            "GPS坐标：${"%.6f".format(Locale.US, loc.latitude)}, ${"%.6f".format(Locale.US, loc.longitude)}\n" +
            "GPS精度：约 ${loc.accuracy.toInt()} 米\n" +
            "高德地图：$amapUrl"

        val body = if (isFeishuWebhook(url)) {
            JSONObject().apply {
                put("msg_type", "text")
                put("content", JSONObject().apply { put("text", text) })
            }.toString()
        } else {
            JSONObject().apply {
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("accuracy", loc.accuracy)
                put("address", address)
                put("time", time)
                put("device", device)
                put("map_url", amapUrl)
                put("text", text)
            }.toString()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("User-Agent", "FamilyLocation/2.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val res = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use false to "HTTP ${response.code}: $res"
                if (!isFeishuWebhook(url)) return@use true to "ok"
                val ok = res.contains("\"code\":0") || res.contains("success", ignoreCase = true)
                ok to if (ok) "ok" else res.ifBlank { "飞书返回异常" }
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络异常")
        }
    }

    private fun isFeishuWebhook(url: String): Boolean = url.contains("open.feishu.cn/open-apis/bot", ignoreCase = true)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "家人位置", NotificationManager.IMPORTANCE_LOW).apply {
                description = "正在获取高精度位置，发送后自动停止"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotif(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("家人位置")
        .setContentText("正在获取高精度位置，发送后自动停止")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    // WGS-84 GPS 坐标转 GCJ-02，用于高德地图链接，避免在高德里出现偏移。
    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean = lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
