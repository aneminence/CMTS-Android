package com.cmts.mobile

import android.app.*
import android.content.Intent
import android.hardware.*
import android.location.*
import android.os.*
import androidx.compose.runtime.*
import java.io.File
import java.io.BufferedWriter
import org.json.JSONObject

object Live {
 val emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.HARDWARE in listOf("goldfish", "ranchu")
 var running by mutableStateOf(false)
 var status by mutableStateOf("未采集")
 var samples by mutableStateOf(mapOf<String, Sample>())
 var rates by mutableStateOf(mapOf<String, Double?>())
 var track by mutableStateOf(listOf<TrackPoint>())
 var startedNanos by mutableStateOf(0L)
}
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
 private var wake: PowerManager.WakeLock? = null
 override fun onBind(intent: Intent?) = null
 override fun onCreate() {
  super.onCreate(); thread.start(); handler = Handler(thread.looper)
  sensors = getSystemService(SENSOR_SERVICE) as SensorManager
  locations = getSystemService(LOCATION_SERVICE) as LocationManager
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  if (Live.running) return START_NOT_STICKY
  val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
  nm.createNotificationChannel(NotificationChannel("capture", "CMTS 采集", NotificationManager.IMPORTANCE_LOW))
  startForeground(1, Notification.Builder(this, "capture").setContentTitle("CMTS 正在记录真实传感器")
   .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentText(if (Live.emulator) "模拟器数据 · 非硬件验收" else "设备回调 · 待硬件验证").build())
  Live.running = true; Live.samples = emptyMap(); Live.rates = emptyMap(); latestSamples.clear(); latestRates.clear(); lastUiPostNanos = 0L
  Live.track = emptyList(); Live.startedNanos = SystemClock.elapsedRealtimeNanos()
  handler.post {
   try {
    folder = File(filesDir, "sessions/${java.util.UUID.randomUUID()}").apply { mkdirs() }
    File(folder, "metadata.json").writeText(JSONObject().put("schema", 1).put("sessionId", folder!!.name)
     .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("startedUtcMs", System.currentTimeMillis()).put("requestedImuPeriodUs", 10000)
     .put("attitude", "phone.attitude: pitch X, roll Y, azimuth -Z, heading accuracy; degrees; Android getOrientation natural device axes; magnetic north; derived TYPE_ROTATION_VECTOR")
     .put("rotationVector", "phone.rotation_vector: x*sin(theta/2), y*sin(theta/2), z*sin(theta/2), cos(theta/2), heading accuracy radians (negative unavailable); raw Android values")
     .put("vehicleFrame", "X=device Y (top/forward), Y=-device X (left), Z=device Z (screen outward/up); fixed mounting, no bias/gravity removal")
     .put("vehicleAttitude", "vehicle.attitude: roll X, pitch Y, yaw Z degrees; right-handed; roll positive left side up, pitch positive nose down, yaw positive left from magnetic north; Euler singularity at pitch +/-90 deg")
     .put("status", "incomplete").put("environment", if (Live.emulator) "emulator_simulated" else "device_unverified").put("imuFrame", "Android device XYZ; acceleration includes gravity").toString(2))
    writer = File(folder, "samples.csv").bufferedWriter().apply { write("sequence,source,sample_elapsed_ns,callback_elapsed_ns,utc_ms,quality,v1,v2,v3,v4,v5,v6,v7\n"); flush() }
    val missing = mutableListOf<String>()
    for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR)) {
     val sensor = sensors.getDefaultSensor(type)
     if (sensor == null || !sensors.registerListener(this, sensor, 10_000, handler)) missing.add("sensor:$type")
    }
    if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
     throw SecurityException("精确定位权限未授予或已撤销")
    }
    try {
     locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, thread.looper)
    } catch (e: SecurityException) { fail(e); return@post }
    wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CMTS:capture").apply { acquire() }
    main.post { Live.status = "记录中；缺失：${missing.joinToString().ifEmpty { "无" }}；等待 GNSS" }
   } catch (e: Exception) { fail(e) }
  }
  return START_NOT_STICKY
 }
 private fun fail(e: Exception) { main.post { Live.status = "采集失败：${e.javaClass.simpleName}: ${e.message}"; stopSelf() } }
 private fun accept(sample: Sample) {
  try {
   if (filesDir.usableSpace < 10 * 1024 * 1024) error("剩余空间不足 10 MiB")
   val labeled = if (Live.emulator) sample.copy(quality = "SIMULATED_${sample.quality}") else sample
   writer?.apply { write(labeled.csv(++sequence, SystemClock.elapsedRealtimeNanos())); newLine(); if(sequence % 50L == 0L) flush() }
   val rate = rates.getOrPut(sample.source) { Rate() }.update(sample.nanos)
   latestSamples[sample.source] = labeled
   if (sample.source == "phone.gps") {
    val point = TrackPoint(sample.values[0]!!, sample.values[1]!!, sample.nanos)
    main.post { Live.track = (Live.track + point).takeLast(10000) }
   }
   latestRates[sample.source] = rate
   // Raw samples are recorded at sensor rate; Compose is deliberately capped at ~10 Hz.
   val now = SystemClock.elapsedRealtimeNanos()
   if (now - lastUiPostNanos >= 100_000_000L) {
    lastUiPostNanos = now
    val sampleSnapshot = latestSamples.toMap()
    val rateSnapshot = latestRates.toMap()
    main.post { Live.samples = sampleSnapshot; Live.rates = rateSnapshot }
   }
  } catch(e: Exception) { fail(e) }
 }
 override fun onSensorChanged(e: SensorEvent) {
  if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
   accept(Sample("phone.rotation_vector", e.timestamp, null, e.values.take(5).map { it.toDouble() }, "accuracy_${e.accuracy}"))
   val matrix = FloatArray(9)
   val angles = FloatArray(3)
   SensorManager.getRotationMatrixFromVector(matrix, e.values)
   SensorManager.getOrientation(matrix, angles)
   accept(Sample("vehicle.attitude", e.timestamp, null, vehicleAttitude(matrix), "derived_rotation_vector_magnetic_north_accuracy_${e.accuracy}"))
   accept(Sample("phone.attitude", e.timestamp, null,
    attitudeDegrees(angles[0].toDouble(), angles[1].toDouble(), angles[2].toDouble()) +
     listOf(e.values.getOrNull(4)?.takeIf { it >= 0f }?.let { Math.toDegrees(it.toDouble()) }),
    "derived_rotation_vector_magnetic_north_accuracy_${e.accuracy}"))
  } else {
   val source = when(e.sensor.type) {
    Sensor.TYPE_ACCELEROMETER -> "phone.accel"
    Sensor.TYPE_GYROSCOPE -> "phone.gyro"
    else -> return
   }
   accept(Sample(source, e.timestamp, null, e.values.take(3).map { it.toDouble() }, "accuracy_${e.accuracy}"))
   accept(Sample(source.replace("phone.", "vehicle."), e.timestamp, null, vehicleAxes(e.values.take(3).map { it.toDouble() }), "transformed_device_axes_accuracy_${e.accuracy}"))
  }
 }
 override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
 override fun onLocationChanged(l: Location) { accept(Sample("phone.gps", l.elapsedRealtimeNanos, l.time,
  listOf(l.latitude, l.longitude, if(l.hasAltitude()) l.altitude else null, if(l.hasSpeed()) l.speed.toDouble() else null,
   if(l.hasBearing()) l.bearing.toDouble() else null, if(l.hasAccuracy()) l.accuracy.toDouble() else null,
   if(l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null), if(l.isFromMockProvider) "MOCK" else "provider_reported")) }
 override fun onProviderDisabled(provider: String) { main.post { Live.status = "GNSS 已关闭；IMU 继续记录" } }
 override fun onProviderEnabled(provider: String) { main.post { Live.status = "GNSS 已开启，等待定位" } }
 override fun onDestroy() {
  sensors.unregisterListener(this); locations.removeUpdates(this)
  handler.post { try { writer?.close(); folder?.let { File(it, "closed.marker").writeText("${System.currentTimeMillis()}") } } finally { wake?.let { if(it.isHeld) it.release() }; thread.quitSafely() } }
  Live.running = false; super.onDestroy()
 }
}
