package com.cmts.mobile

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.*
import android.os.*
import androidx.compose.runtime.*
import com.cmts.mobile.performance.PerformanceController
import com.cmts.mobile.remote.RemoteBridge
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject

internal data class MountZero(
    val basis: MountBasis? = null,
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
    var activeSession by mutableStateOf<File?>(null)
    var running by mutableStateOf(false)
    var status by mutableStateOf("未采集")
    var samples by mutableStateOf(mapOf<String, Sample>())
    /**
     * Sensor-rate snapshot for remote WS (not Compose-throttled).
     * Updated on the capture thread; readers should treat as immutable map swap.
     */
    @Volatile var remoteSamples: Map<String, Sample> = emptyMap()
    var rates by mutableStateOf(mapOf<String, Double?>())
    var track by mutableStateOf(listOf<TrackPoint>())
    var startedNanos by mutableStateOf(0L)
    internal var mountZero by mutableStateOf(MountZero())
    /** Service process is alive for recording and/or performance. */
    var serviceAlive by mutableStateOf(false)
    /** Positioning source used for live map / recording / dashboard. Default is phone GNSS. */
    var gnssSource by mutableStateOf(GnssSource.PHONE)
    /** Short status for the source switch; empty when using phone GNSS with nothing to say. */
    var gnssStatus by mutableStateOf("")
}

/**
 * Foreground capture. Supports concurrent holds:
 * - recording: writes session CSV + metadata
 * - performance: feeds raw GNSS speed into PerformanceController (may run without recording)
 * - usb GNSS: keeps the CH344Q / UM982 reader alive in the background
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
    private var remoteHold = false
    /** True after PerformanceController.holdCapture observed while CLIENT_PERF held. */
    private var perfSawHold = false
    private var wake: PowerManager.WakeLock? = null
    private val perfHoldWatch = object : Runnable {
        override fun run() {
            if (!perfHold) return
            if (PerformanceController.holdCapture) {
                perfSawHold = true
            } else if (perfSawHold) {
                // Hold ended (finish/cancel) while page may already be disposed — release here.
                releaseClient(CLIENT_PERF)
                return
            }
            handler.postDelayed(this, 250L)
        }
    }
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

    private var previewVisible = false
    private var gpsUp = false
    private var usbHold = false
    private var usbStarted = false
    private var chosenNmeaPort: Int? = null
    private val nmeaPorts = mutableSetOf<Int>()
    private val nmeaBuffers = mutableMapOf<Int, NmeaLineBuffer>()
    private val nmeaAssemblers = mutableMapOf<Int, NmeaFixAssembler>()
    private val nmeaWait = Runnable {
        if (!usbHold || Live.gnssSource != GnssSource.EXTERNAL) return@Runnable
        if (chosenNmeaPort == null) {
            main.post { Live.gnssStatus = "已连接紫板，但未发现 NMEA 数据口" }
        }
    }
    private val usbListener = object : UsbByteListener {
        override fun onBytes(port: Int, data: ByteArray) {
            handler.post { handleUsbBytes(port, data) }
        }
        override fun onCounts(counts: LongArray) = Unit
        override fun onStatus(message: String) {
            handler.post { handleUsbStatus(message) }
        }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == UsbSerialHub.deviceName()) {
                        handler.post {
                            usbStarted = false
                            main.post {
                                if (Live.gnssSource == GnssSource.EXTERNAL) {
                                    Live.gnssStatus = "外接模块已拔出，未改回手机 GNSS。请重新连接或改选「手机 GNSS」。"
                                }
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (Live.gnssSource == GnssSource.EXTERNAL && usbHold) {
                        handler.post {
                            usbStarted = false
                            startUsbGnss()
                        }
                    }
                }
            }
        }
    }

    inner class PreviewBinder : Binder() {
        fun refresh() { handler.post {
            previewVisible = true
            try { ensureListeners() } catch (e: Exception) {
                main.post { Live.status = "预览不可用：${e.message}" }
            }
        } }
    }

    override fun onBind(intent: Intent?): IBinder = PreviewBinder()

    override fun onUnbind(intent: Intent?): Boolean {
        handler.post {
            previewVisible = false
            if (!recordHold && !perfHold && !remoteHold && !usbHold) tearDownListeners()
        }
        return false
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        locations = getSystemService(LOCATION_SERVICE) as LocationManager
        Live.serviceAlive = true
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)
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
            recordHold && (perfHold || remoteHold || usbHold) -> "CMTS 记录 + 远程/性能/外接定位"
            recordHold -> "CMTS 正在记录真实传感器"
            usbHold && (perfHold || remoteHold) -> "CMTS 外接定位 + 远程/性能"
            usbHold -> "CMTS 外接 UM982 定位"
            perfHold && remoteHold -> "CMTS 性能 + 远程流"
            perfHold -> "CMTS 性能测试采集"
            remoteHold -> "CMTS 远程实时流"
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
                perfSawHold = PerformanceController.holdCapture
                handler.removeCallbacks(perfHoldWatch)
                handler.post(perfHoldWatch)
            }
            CLIENT_REMOTE -> {
                if (remoteHold) return
                remoteHold = true
                RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
            }
            CLIENT_USB_GNSS -> {
                usbHold = true
            }
            else -> {
                if (recordHold) return
                recordHold = true
                RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
            }
        }
        try {
            ensureForeground()
            ensureListeners()
            if (client == CLIENT_RECORD) startRecordingSession()
            // Remote / preview / perf / usb GNSS alone → READY when listeners up and not recording
            if (!recordHold && (remoteHold || perfHold || previewVisible || usbHold)) {
                RecordingStateMachine.tryTransition(RecordingState.READY)
            }
            main.post {
                Live.status = statusText()
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun releaseClient(client: String) {
        when (client) {
            CLIENT_PERF -> {
                handler.removeCallbacks(perfHoldWatch)
                perfHold = false
                perfSawHold = false
            }
            CLIENT_REMOTE -> {
                remoteHold = false
            }
            CLIENT_USB_GNSS -> {
                usbHold = false
                stopUsbGnss()
                if (Live.gnssSource == GnssSource.PHONE) {
                    main.post { Live.gnssStatus = "" }
                    if (listenersUp) startPhoneGnss()
                }
            }
            else -> {
                if (recordHold) stopRecordingSession()
                recordHold = false
            }
        }
        main.post { Live.status = statusText() }
        if (!recordHold && !perfHold && !remoteHold && !usbHold) {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            if (!previewVisible) {
                tearDownListeners()
                RecordingStateMachine.tryTransition(RecordingState.IDLE)
            } else {
                RecordingStateMachine.tryTransition(RecordingState.READY)
            }
            if (!previewVisible) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                // Keep process for preview bind; drop FGS if nothing held
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } else {
            if (!recordHold) RecordingStateMachine.tryTransition(RecordingState.READY)
            ensureForeground()
        }
    }

    private fun statusText(): String = buildString {
        when {
            recordHold && (perfHold || remoteHold || usbHold) -> append("记录中 + 远程/性能/外接定位")
            recordHold -> append("记录中")
            usbHold && (perfHold || remoteHold) -> append("外接定位 + 远程/性能")
            usbHold -> append("外接 UM982 定位")
            perfHold && remoteHold -> append("性能测试 + 远程流")
            perfHold -> append("性能测试采集中")
            remoteHold -> append("远程实时流")
            previewVisible -> append("实时预览 · 未记录")
            else -> append("未采集")
        }
        append(" · ").append(RecordingStateMachine.snapshot().name)
    }

    private fun ensureListeners() {
        val permitted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permitted && (recordHold || perfHold || remoteHold)) throw SecurityException("需要精确定位权限")
        if ((recordHold || perfHold || remoteHold || usbHold) && wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CMTS:capture").apply { acquire() }
        }
        val missing = mutableListOf<String>()
        if (!listenersUp) for (type in listOf(
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
        listenersUp = true
        applyLocationSource()
        val wait = if (Live.gnssSource == GnssSource.EXTERNAL) "等待外接 UM982" else "等待 GNSS"
        main.post {
            Live.status = statusText() + "；缺失=${missing.joinToString().ifEmpty { "无" }}；$wait"
        }
    }

    private fun applyLocationSource() {
        if (Live.gnssSource == GnssSource.EXTERNAL) {
            stopPhoneGnss()
            if (usbHold) startUsbGnss()
        } else {
            stopUsbGnss()
            startPhoneGnss()
        }
    }

    private fun startPhoneGnss() {
        val permitted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (permitted && !gpsUp) {
            check(locations.registerGnssStatusCallback(satelliteCallback, handler)) { "无法注册卫星状态回调" }
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, thread.looper)
            gpsUp = true
        }
    }

    private fun stopPhoneGnss() {
        if (!gpsUp) return
        runCatching { locations.unregisterGnssStatusCallback(satelliteCallback) }
        runCatching { locations.removeUpdates(this) }
        gpsUp = false
        latestSamples.remove("phone.gps")
        latestSamples.remove("phone.satellites")
        main.post { Live.samples = Live.samples - "phone.gps" - "phone.satellites" }
    }

    private fun startUsbGnss() {
        if (usbStarted) return
        val usb = getSystemService(UsbManager::class.java)
        val device = GnssSourceSettings.findCh344q(usb)
        if (device == null) {
            main.post { Live.gnssStatus = "未检测到紫板 CH344Q，请检查 OTG 连接" }
            return
        }
        if (!usb.hasPermission(device)) {
            main.post { Live.gnssStatus = "没有 USB 权限，请在主界面重新选择外接 UM982" }
            return
        }
        nmeaPorts.clear()
        nmeaBuffers.clear()
        nmeaAssemblers.clear()
        chosenNmeaPort = null
        UsbSerialHub.addListener(usbListener)
        val ok = UsbSerialHub.acquire(
            application,
            UsbHubOwner.GNSS,
            device,
            baud = 115200,
            assertDtr = false,
            saveDir = null,
        )
        if (!ok) {
            UsbSerialHub.removeListener(usbListener)
            return
        }
        usbStarted = true
        main.post { Live.gnssStatus = GnssSourceSettings.connectedWaitingNmea() }
        handler.removeCallbacks(nmeaWait)
        handler.postDelayed(nmeaWait, 8_000L)
    }

    private fun stopUsbGnss() {
        handler.removeCallbacks(nmeaWait)
        val flushPort = chosenNmeaPort
        nmeaAssemblers.values.forEach { assembler ->
            assembler.flush().forEach { update ->
                if (update is NmeaUpdate.Fix && flushPort != null) onExternalFix(update.fix, flushPort)
            }
        }
        UsbSerialHub.removeListener(usbListener)
        UsbSerialHub.release(UsbHubOwner.GNSS)
        usbStarted = false
        chosenNmeaPort = null
        nmeaPorts.clear()
        nmeaBuffers.clear()
        nmeaAssemblers.clear()
    }

    private fun handleUsbBytes(port: Int, data: ByteArray) {
        val lines = nmeaBuffers.getOrPut(port) { NmeaLineBuffer() }.append(data)
        for (line in lines) {
            if (NmeaParser.looksLikeNmea(line)) {
                nmeaPorts.add(port)
                val chosen = NmeaPortSelector.choose(nmeaPorts)
                if (chosen != null) chosenNmeaPort = chosen
            }
            val usePort = chosenNmeaPort ?: continue
            if (port != usePort) continue
            val assembler = nmeaAssemblers.getOrPut(port) { NmeaFixAssembler() }
            for (update in assembler.onLine(line)) {
                when (update) {
                    is NmeaUpdate.Fix -> onExternalFix(update.fix, port)
                    is NmeaUpdate.NoFix -> main.post { Live.gnssStatus = GnssSourceSettings.noFixStatus() }
                    NmeaUpdate.Ignored -> Unit
                }
            }
        }
    }

    private fun handleUsbStatus(message: String) {
        if (!message.startsWith("接收已结束")) return
        usbStarted = false
        if (Live.gnssSource == GnssSource.EXTERNAL) {
            val unplugged = message.contains("USB 已断开") || message.contains("拔出")
            main.post {
                Live.gnssStatus = if (unplugged) {
                    "外接模块已拔出，未改回手机 GNSS。请重新连接或改选「手机 GNSS」。"
                } else {
                    message
                }
            }
        }
    }

    private fun onExternalFix(fix: NmeaFix, port: Int) {
        val received = SystemClock.elapsedRealtimeNanos()
        if (perfHold || PerformanceController.holdCapture) {
            PerformanceController.onRawGnss(
                sampleTimeNanos = received,
                receivedTimeNanos = received,
                utcMillis = fix.utcMillis,
                speedMps = fix.speedMps,
                speedAccuracyMps = null,
                hasSpeed = fix.speedMps != null,
                mock = false,
                rawReference = "um982:${UsbSerialPorts.portLabel(port)}",
            )
        }
        accept(
            Sample(
                "phone.gps",
                received,
                fix.utcMillis,
                listOf(fix.latitude, fix.longitude, fix.altitudeM, fix.speedMps, fix.headingDeg, null, null, null),
                fix.quality,
            )
        )
        val used = fix.satsUsed?.toDouble()
        val visible = (fix.satsVisible ?: fix.satsUsed)?.toDouble()
        if (used != null || visible != null) {
            accept(Sample("phone.satellites", received, null, listOf(visible, used), "um982_nmea"))
        }
        main.post { Live.gnssStatus = GnssSourceSettings.locatedStatus(port) }
    }

    private fun tearDownListeners() {
        LaunchCalibration.cancel()
        if (!listenersUp) return
        stopUsbGnss()
        runCatching { locations.unregisterGnssStatusCallback(satelliteCallback) }
        runCatching { sensors.unregisterListener(this) }
        runCatching { locations.removeUpdates(this) }
        listenersUp = false
        gpsUp = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun startRecordingSession() {
        if (Live.running) {
            RecordingStateMachine.tryTransition(RecordingState.RECORDING)
            return
        }
        RecordingStateMachine.tryTransition(RecordingState.INITIALIZING)
        Live.samples = emptyMap()
        Live.rates = emptyMap()
        latestSamples.clear()
        Live.remoteSamples = emptyMap()
        latestRates.clear()
        lastUiPostNanos = 0L
        Live.track = emptyList()
        Live.startedNanos = SystemClock.elapsedRealtimeNanos()
        sequence = 0L
        rates.clear()
        folder = File(filesDir, "sessions/${java.util.UUID.randomUUID()}").also {
            Live.activeSession = it
            check(it.mkdirs()) { "无法创建记录目录" }
        }
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
                .put("vehicleFrame", "X=device Y (top/forward), Y=-device X (left), Z=device Z (screen outward/up); CSV/phone.* raw with gravity; Dashboard accel shows gravity-removed linear via rotation vector; mountZero rotates live display into calibrated vehicle axes")
                .put("vehicleAttitude", "vehicle.attitude: roll X, pitch Y, yaw Z degrees; right-handed; roll positive left side up, pitch positive nose down, yaw positive left from magnetic north; Euler singularity at pitch +/-90 deg")
                .put("gpsValues", "v1 lat, v2 lon, v3 alt, v4 speed m/s, v5 bearing, v6 horiz accuracy m, v7 vertical accuracy m, v8 speed accuracy m/s (API26+ when hasSpeedAccuracy)")
                .put("status", "incomplete")
                .put("gnssSource", if (Live.gnssSource == GnssSource.EXTERNAL) "um982_usb" else "phone.gps")
                .put("environment", if (Live.emulator) "emulator_simulated" else "device_unverified")
                .put("imuFrame", "Android device XYZ; acceleration includes gravity")
                .toString(2),
        )
        writer = File(folder, "samples.csv").bufferedWriter().apply {
            write("sequence,source,sample_elapsed_ns,callback_elapsed_ns,utc_ms,quality,v1,v2,v3,v4,v5,v6,v7,v8\n")
            flush()
        }
        RecordingStateMachine.markSession(folder!!.name, System.currentTimeMillis())
        RecordingStateMachine.tryTransition(RecordingState.RECORDING)
        main.post { Live.running = true }
    }

    private fun stopRecordingSession() {
        LaunchCalibration.cancel()
        RecordingStateMachine.tryTransition(RecordingState.STOPPING)
        RecordingStateMachine.tryTransition(RecordingState.SAVING)
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
                        zeroSnapshot.basis?.let { b ->
                            fun vector(v: V3) = org.json.JSONArray(listOf(v.x,v.y,v.z))
                            meta.put("finalMountBasis", org.json.JSONArray(listOf(vector(b.forward),vector(b.left),vector(b.up))))
                        }
                        meta.put("mountZeroAccel", org.json.JSONArray(zeroSnapshot.accel))
                        meta.put("mountZeroGyro", org.json.JSONArray(zeroSnapshot.gyro))
                        meta.put("mountZeroAttitude", org.json.JSONArray(zeroSnapshot.attitude))
                        meta.put("mountZeroAtElapsedNanos", zeroSnapshot.zeroedAtElapsedNanos)
                        meta.put(
                            "mountZeroNote",
                            "mountZero rotates live display into calibrated vehicle axes; Dashboard accel uses gravity removal from rotation vector/attitude. samples.csv phone.* and vehicle.* remain raw (accel includes gravity).",
                        )
                    }
                    meta.put("gnssSource", if (Live.gnssSource == GnssSource.EXTERNAL) "um982_usb" else "phone.gps")
                    meta.put("status", "closed")
                    metaFile.writeText(meta.toString(2))
                }
            }
        } finally {
            Live.activeSession = null
            folder = null
            RecordingStateMachine.markSession(null, null)
            val next = if (perfHold || remoteHold || previewVisible || usbHold) RecordingState.READY else RecordingState.IDLE
            RecordingStateMachine.tryTransition(next)
            main.post { Live.running = false }
            if (!perfHold) PerformanceController.bindCaptureSession(null)
        }
    }

    private fun fail(e: Exception) {
        RecordingStateMachine.force(RecordingState.ERROR, "${e.javaClass.simpleName}: ${e.message}")
        main.post {
            Live.status = "采集失败：${e.javaClass.simpleName}: ${e.message}"
        }
        recordHold = false
        perfHold = false
        remoteHold = false
        usbHold = false
        tearDownListeners()
        stopSelf()
    }

    private fun accept(sample: Sample) {
        try {
            if (recordHold && filesDir.usableSpace < 10 * 1024 * 1024) error("剩余空间不足 10 MiB")
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
            if (remoteHold) {
                Live.remoteSamples = latestSamples.toMap()
                // Drive WS off IMU callbacks; GNSS only refreshes fields / map.
                if (sample.source == "vehicle.accel" ||
                    sample.source == "phone.accel" ||
                    sample.source == "vehicle.gyro" ||
                    sample.source == "phone.gyro"
                ) {
                    RemoteBridge.onImuSample()
                }
            }
            if (recordHold || previewVisible) LaunchCalibration.observe(sample,latestSamples)
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
        if (Live.gnssSource == GnssSource.EXTERNAL) return
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
        runCatching { unregisterReceiver(usbReceiver) }
        handler.post {
            if (recordHold) stopRecordingSession()
            usbHold = false
            tearDownListeners()
            thread.quitSafely()
        }
        Live.running = false
        Live.serviceAlive = false
        RecordingStateMachine.force(RecordingState.IDLE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACTION = "cmts.capture.action"
        const val EXTRA_CLIENT = "cmts.capture.client"
        const val ACTION_ACQUIRE = "acquire"
        const val ACTION_RELEASE = "release"
        const val CLIENT_RECORD = "record"
        const val CLIENT_PERF = "perf"
        /** Remote WS subscriber hold: sensors on, no session CSV. */
        const val CLIENT_REMOTE = "remote"
        /** External UM982 USB reader hold: keeps FGS + wake lock without a recording session. */
        const val CLIENT_USB_GNSS = "usb_gnss"

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





