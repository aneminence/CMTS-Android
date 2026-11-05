package com.cmts.mobile

import android.app.*
import android.content.Intent
import android.hardware.*
import android.location.*
import android.os.*
import androidx.compose.runtime.*
import com.cmts.mobile.performance.PerformanceController
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject

data class MountZero(
    val enabled: Boolean = false,
    val accel: List<Double> = emptyList(),
    val gyro: List<Double> = emptyList(),
    val attitude: List<Double> = emptyList(),
    val zeroedAtElapsedNanos: Long? = null,
)

object Live {
    val emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
        Build.MODEL.contains("sdk_gphone") || Build.HARDWARE in listOf("goldfish", "ranchu")
    /** True while a normal recording session is writing samples.csv. */
    var running by mutableStateOf(false)
    var status by mutableStateOf("未采集")
    var samples by mutableStateOf(mapOf<String, Sample>())
    var rates by mutableStateOf(mapOf<String, Double?>())
    var track by mutableStateOf(listOf<TrackPoint>())
    var startedNanos by mutableStateOf(0L)
    var mountZero by mutableStateOf(MountZero())
    /** Service process is alive for recording and/or performance. */
    var serviceAlive by mutableStateOf(false)
}

/**
 * Foreground capture. Supports concurrent holds:
 * - recording: writes session CSV + metadata
 * - performance: feeds raw GNSS speed into PerformanceController (may run without recording)
 * Last release stops the service.
 */
class CaptureService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private val thread = HandlerThread("cmts-recording")
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private var writer: BufferedWriter? = null
    private var folder: File? = null
    private var sequence = 0L
    private val rates = mutableMapOf<String, Rate>()
    private val latestSamples = mutableMapOf<String, Sample>()
    private val latestRates = mutableMapOf<String, Double?>()
    private var lastUiPostNanos = 0L
    private var listenersUp = false
    private var recordHold = false
    private var perfHold = false
    private var wake: PowerManager.WakeLock? = null
    private val satelliteCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            accept(
                Sample(
                    "phone.satellites",
                    SystemClock.elapsedRealtimeNanos(),
                    null,
                    listOf(
                        status.satelliteCount.toDouble(),
                        (0 until status.satelliteCount).count { status.usedInFix(it) }.toDouble(),
                    ),
                    "visible_used",
                )
            )
        }
        override fun onStopped() {
            main.post { Live.samples = Live.samples - "phone.satellites" }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        Live.serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_ACQUIRE
        val client = intent?.getStringExtra(EXTRA_CLIENT) ?: CLIENT_RECORD
        when (action) {
            ACTION_RELEASE -> handler.post { releaseClient(client) }
            else -> handler.post { acquireClient(client) }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("capture", "CMTS 采集", NotificationManager.IMPORTANCE_LOW))
        val title = when {
            recordHold && perfHold -> "CMTS 记录 + 性能测试"
            perfHold -> "CMTS 性能测试采集"
            else -> "CMTS 正在记录真实传感器"
        }
        startForeground(
            1,
            Notification.Builder(this, "capture")
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentText(if (Live.emulator) "模拟器数据 · 非硬件验收" else "设备回调 · 待硬件验证")
                .build(),
        )
    }

    private fun acquireClient(client: String) {
        when (client) {
            CLIENT_PERF -> {
                if (perfHold) return
                perfHold = true
            }
            else -> {
                if (recordHold) return
                recordHold = true
            }
        }
        try {
            ensureForeground()
            ensureListeners()
            if (client == CLIENT_RECORD) startRecordingSession()
            main.post {
                Live.status = statusText()
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun releaseClient(client: String) {
        when (client) {
            CLIENT_PERF -> perfHold = false
            else -> {
                if (recordHold) stopRecordingSession()
                recordHold = false
            }
        }
        main.post { Live.status = statusText() }
        if (!recordHold && !perfHold) {
            tearDownListeners()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            ensureForeground()
        }
    }

    private fun statusText(): String = buildString {
        when {
            recordHold && perfHold -> append("记录中 + 性能测试")
            recordHold -> append("记录中")
            perfHold -> append("性能测试采集中")
            else -> append("未采集")
        }
    }

    private fun ensureListeners() {
        if (listenersUp) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("精确定位权限未授予或已撤销")
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CMTS:capture")
            .apply { acquire() }
        val missing = mutableListOf<String>()
        for (type in listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_PRESSURE,
        )) {
            val sensor = sensors.getDefaultSensor(type, true) ?: sensors.getDefaultSensor(type)
            if (sensor == null ||
                !sensors.registerListener(this, sensor, if (type == Sensor.TYPE_PRESSURE) 200_000 else 10_000, handler)
            ) {
                missing.add("sensor:$type")
            }
        }
        check(locations.registerGnssStatusCallback(satelliteCallback, handler)) { "无法注册卫星状态回调" }
        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, thread.looper)
        listenersUp = true
        main.post {
            Live.status = statusText() + "；缺失=${missing.joinToString().ifEmpty { "无" }}；等待 GNSS"
        }
    }

    private fun tearDownListeners() {
        if (!listenersUp) return
        runCatching { locations.unregisterGnssStatusCallback(satelliteCallback) }
        runCatching { sensors.unregisterListener(this) }
        runCatching { locations.removeUpdates(this) }
        listenersUp = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun startRecordingSession() {
        if (Live.running) return
        Live.samples = emptyMap()
        Live.rates = emptyMap()
        latestSamples.clear()
        latestRates.clear()
        lastUiPostNanos = 0L
        Live.track = emptyList()
        Live.startedNanos = SystemClock.elapsedRealtimeNanos()
        sequence = 0L
        rates.clear()
        folder = File(filesDir, "sessions/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        PerformanceController.bindCaptureSession(folder!!.name)
        File(folder, "metadata.json").writeText(
            JSONObject()
                .put("schema", 1)
                .put("sessionId", folder!!.name)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("startedUtcMs", System.currentTimeMillis())
                .put("requestedImuPeriodUs", 10000)
                .put("pressureUnit", "mbar (hPa)")
                .put("requestedPressurePeriodUs", 200000)
                .put("attitude", "phone.attitude: pitch X, roll Y, azimuth -Z, heading accuracy; degrees; Android getOrientation natural device axes; magnetic north; derived TYPE_ROTATION_VECTOR")
                .put("rotationVector", "phone.rotation_vector: x*sin(theta/2), y*sin(theta/2), z*sin(theta/2), cos(theta/2), heading accuracy radians (negative unavailable); raw Android values")
                .put("vehicleFrame", "X=device Y (top/forward), Y=-device X (left), Z=device Z (screen outward/up); CSV/phone.* raw with gravity; Dashboard accel shows gravity-removed linear via rotation vector; mountZero is gyro/attitude display bias only")
                .put("vehicleAttitude", "vehicle.attitude: roll X, pitch Y, yaw Z degrees; right-handed; roll positive left side up, pitch positive nose down, yaw positive left from magnetic north; Euler singularity at pitch +/-90 deg")
                .put("gpsValues", "v1 lat, v2 lon, v3 alt, v4 speed m/s, v5 bearing, v6 horiz accuracy m, v7 vertical accuracy m, v8 speed accuracy m/s (API26+ when hasSpeedAccuracy)")
                .put("status", "incomplete")
                .put("environment", if (Live.emulator) "emulator_simulated" else "device_unverified")
                .put("imuFrame", "Android device XYZ; acceleration includes gravity")
                .toString(2),
        )
        writer = File(folder, "samples.csv").bufferedWriter().apply {
            write("sequence,source,sample_elapsed_ns,callback_elapsed_ns,utc_ms,quality,v1,v2,v3,v4,v5,v6,v7,v8\n")
            flush()
        }
        main.post { Live.running = true }
    }

    private fun stopRecordingSession() {
        val zeroSnapshot = Live.mountZero
        try {
            writer?.close()
            writer = null
            folder?.let { dir ->
                File(dir, "closed.marker").writeText("${System.currentTimeMillis()}")
                val metaFile = File(dir, "metadata.json")
                if (metaFile.exists()) {
                    val meta = JSONObject(metaFile.readText())
                    meta.put("mountZeroEnabled", zeroSnapshot.enabled)
                    if (zeroSnapshot.enabled) {
                        meta.put("mountZeroAccel", org.json.JSONArray(zeroSnapshot.accel))
                        meta.put("mountZeroGyro", org.json.JSONArray(zeroSnapshot.gyro))
                        meta.put("mountZeroAttitude", org.json.JSONArray(zeroSnapshot.attitude))
                        meta.put("mountZeroAtElapsedNanos", zeroSnapshot.zeroedAtElapsedNanos)
                        meta.put(
                            "mountZeroNote",
                            "mountZero is gyro/attitude display bias only; Dashboard accel uses gravity removal from rotation vector/attitude. samples.csv phone.* and vehicle.* remain raw (accel includes gravity).",
                        )
                    }
                    meta.put("status", "closed")
                    metaFile.writeText(meta.toString(2))
                }
            }
        } finally {
            folder = null
            main.post { Live.running = false }
            if (!perfHold) PerformanceController.bindCaptureSession(null)
        }
    }

    private fun fail(e: Exception) {
        main.post {
            Live.status = "采集失败：${e.javaClass.simpleName}: ${e.message}"
        }
        recordHold = false
        perfHold = false
        tearDownListeners()
        stopSelf()
    }

    private fun accept(sample: Sample) {
        try {
            if (filesDir.usableSpace < 10 * 1024 * 1024) error("剩余空间不足 10 MiB")
            val labeled = if (Live.emulator) sample.copy(quality = "SIMULATED_${sample.quality}") else sample
            if (recordHold) {
                writer?.apply {
                    write(labeled.csv(++sequence, SystemClock.elapsedRealtimeNanos()))
                    newLine()
                    if (sequence % 50L == 0L) flush()
                }
            }
            val rate = rates.getOrPut(sample.source) { Rate() }.update(sample.nanos)
            latestSamples[sample.source] = labeled
            if (sample.source == "phone.gps") {
                val lat = sample.values.getOrNull(0)
                val lon = sample.values.getOrNull(1)
                if (lat != null && lon != null) {
                    val point = TrackPoint(lat, lon, sample.nanos)
                    main.post { Live.track = (Live.track + point).takeLast(10000) }
                }
            }
            latestRates[sample.source] = rate
            // Raw samples at sensor rate; Compose capped ~10 Hz.
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastUiPostNanos >= 100_000_000L) {
                lastUiPostNanos = now
                val sampleSnapshot = latestSamples.toMap()
                val rateSnapshot = latestRates.toMap()
                main.post {
                    Live.samples = sampleSnapshot
                    Live.rates = rateSnapshot
                }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PRESSURE) {
            val pressure = e.values.firstOrNull()?.toDouble()
            if (pressure != null && pressure.isFinite() && pressure > 0) {
                accept(Sample("phone.pressure", e.timestamp, null, listOf(pressure), "mbar_accuracy_${e.accuracy}"))
            }
            return
        }
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            accept(Sample("phone.rotation_vector", e.timestamp, null, e.values.take(5).map { it.toDouble() }, "accuracy_${e.accuracy}"))
            val matrix = FloatArray(9)
            val angles = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(matrix, e.values)
            SensorManager.getOrientation(matrix, angles)
            accept(Sample("vehicle.attitude", e.timestamp, null, vehicleAttitude(matrix), "derived_rotation_vector_magnetic_north_accuracy_${e.accuracy}"))
            accept(
                Sample(
                    "phone.attitude",
                    e.timestamp,
                    null,
                    attitudeDegrees(angles[0].toDouble(), angles[1].toDouble(), angles[2].toDouble()) +
                        listOf(e.values.getOrNull(4)?.takeIf { it >= 0f }?.let { Math.toDegrees(it.toDouble()) }),
                    "derived_rotation_vector_magnetic_north_accuracy_${e.accuracy}",
                ),
            )
        } else {
            val source = when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> "phone.accel"
                Sensor.TYPE_GYROSCOPE -> "phone.gyro"
                else -> return
            }
            accept(Sample(source, e.timestamp, null, e.values.take(3).map { it.toDouble() }, "accuracy_${e.accuracy}"))
            accept(
                Sample(
                    source.replace("phone.", "vehicle."),
                    e.timestamp,
                    null,
                    vehicleAxes(e.values.take(3).map { it.toDouble() }),
                    "transformed_device_axes_accuracy_${e.accuracy}",
                ),
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        val received = SystemClock.elapsedRealtimeNanos()
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) {
            l.speedAccuracyMetersPerSecond.toDouble()
        } else null
        val hasSpeed = l.hasSpeed()
        val speed = if (hasSpeed) l.speed.toDouble() else null
        // Feed performance engine BEFORE UI throttle — raw GNSS path.
        if (perfHold || PerformanceController.holdCapture) {
            PerformanceController.onRawGnss(
                sampleTimeNanos = l.elapsedRealtimeNanos,
                receivedTimeNanos = received,
                utcMillis = l.time,
                speedMps = speed,
                speedAccuracyMps = speedAccuracy,
                hasSpeed = hasSpeed,
                mock = l.isFromMockProvider,
                rawReference = "provider=${l.provider}",
            )
        }
        accept(
            Sample(
                "phone.gps",
                l.elapsedRealtimeNanos,
                l.time,
                listOf(
                    l.latitude,
                    l.longitude,
                    if (l.hasAltitude()) l.altitude else null,
                    speed,
                    if (l.hasBearing()) l.bearing.toDouble() else null,
                    if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                    if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
                    speedAccuracy,
                ),
                if (l.isFromMockProvider) "MOCK" else "provider_reported",
            ),
        )
    }

    override fun onProviderDisabled(provider: String) {
        main.post { Live.status = statusText() + "；GNSS 已关闭" }
    }

    override fun onProviderEnabled(provider: String) {
        main.post { Live.status = statusText() + "；GNSS 已开启" }
    }

    override fun onDestroy() {
        handler.post {
            if (recordHold) stopRecordingSession()
            tearDownListeners()
            thread.quitSafely()
        }
        Live.running = false
        Live.serviceAlive = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTION = "cmts.capture.action"
        const val EXTRA_CLIENT = "cmts.capture.client"
        const val ACTION_ACQUIRE = "acquire"
        const val ACTION_RELEASE = "release"
        const val CLIENT_RECORD = "record"
        const val CLIENT_PERF = "perf"

        fun acquireIntent(context: android.content.Context, client: String): Intent =
            Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_ACQUIRE)
                .putExtra(EXTRA_CLIENT, client)

        fun releaseIntent(context: android.content.Context, client: String): Intent =
            Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_RELEASE)
                .putExtra(EXTRA_CLIENT, client)
    }
}
