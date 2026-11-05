package com.cmts.mobile

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.wch.uartlib.WCHUARTManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Raw byte transport only: deliberately exposes no GNSS command/write API. */
class UsbTransport(private val app: android.app.Application) {
 private val manager = WCHUARTManager.getInstance().also { it.init(app) }
 private val worker = Executors.newSingleThreadExecutor()
 private val running = AtomicBoolean(false)
 private var device: UsbDevice? = null
 fun connect(d: UsbDevice, baud: Int, port: Int, file: File, data: (ByteArray) -> Unit, status: (String) -> Unit) {
  if (!running.compareAndSet(false, true)) return
  worker.execute {
   var failure: String? = null
   try {
    require(manager.enumDevice().any { it.deviceName == d.deviceName }) { "驱动不支持此设备" }
    check(manager.openDevice(d)) { "无法打开设备，请检查 OTG 连接及权限" }
    device = d
    require(port in 0 until manager.getSerialCount(d)) { "串口索引超出设备范围" }
    check(manager.setSerialParameter(d, port, baud, 8, 1, 0, false)) { "设备拒绝此波特率" }
    check(manager.enableSerial(d, port, true)) { "无法启用串口" }
    status("已连接 ${manager.getChipType(d)} · $baud · 8N1；正在保存 ${file.name}")
    file.outputStream().buffered().use { out ->
     while (running.get()) {
      check(manager.isConnected(d)) { "USB 已断开" }
      val bytes = manager.readData(d, port)
      if (bytes != null && bytes.isNotEmpty()) { out.write(bytes); data(bytes) }
      else Thread.sleep(5)
     }
    }
   } catch (e: Exception) { failure = "USB 接收停止：${e.message ?: e.javaClass.simpleName}" }
   finally {
    try { device?.let { manager.disconnect(it) } } catch(e: Exception) { status("关闭设备失败：${e.message}") }
    device = null; running.set(false)
    status("接收已结束：${failure ?: "正常停止"}；原始文件：${file.name}")
   }
  }
 }
 fun stop() { running.set(false) }
 fun close() { stop(); worker.execute { manager.close(app) }; worker.shutdown() }
}

class UsbDebugActivity : ComponentActivity() {
 private lateinit var usb: UsbManager
 private lateinit var transport: UsbTransport
 private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
 private var status by mutableStateOf("请选择设备申请权限，然后连接。只接收数据，不发送 GNSS 指令。")
 private var preview by mutableStateOf(byteArrayOf())
 private var count by mutableLongStateOf(0)
 private var hex by mutableStateOf(false)
 private var active by mutableStateOf(false)
 private var baud by mutableStateOf("115200")
 private var port by mutableStateOf("0")
 private var capture: File? = null
 private val lock = Any()
 private var tail = byteArrayOf()
 private var total = 0L
 private val handler = Handler(Looper.getMainLooper())
 private val tick = object : Runnable { override fun run() {
  synchronized(lock) { preview = tail; count = total }; handler.postDelayed(this, 100)
 } }
 private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  val file = capture
  if (uri != null && file != null) Thread {
   try { contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法打开保存位置")
    runOnUiThread { status = "原始二进制数据已导出" }
   } catch(e: Exception) { runOnUiThread { status = "保存失败：${e.message}" } }
  }.start()
 }
 private val receiver = object : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
   if(intent.action == permissionAction) status = if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "已授权，请点击连接" else "USB 授权被拒绝，可重新申请"
   if(intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
    @Suppress("DEPRECATION") val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
    if(d?.deviceName == connectedName) { transport.stop(); status = "设备已拔出，正在关闭接收" }
   }
   refresh()
  }
 }
 private val permissionAction get() = "$packageName.USB_PERMISSION"
 private var connectedName: String? = null
 private fun refresh() { devices = usb.deviceList.values.sortedBy { it.deviceName } }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  usb = getSystemService(UsbManager::class.java)
  transport = UsbTransport(application)
  baud = getPreferences(MODE_PRIVATE).getString("baud", "115200") ?: "115200"
  val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
  if(Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
  refresh(); handler.post(tick)
  setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
   Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("USB 设备调试", style = MaterialTheme.typography.headlineSmall)
    TextButton(onClick = { finish() }) { Text("返回（停止 USB 接收）") }
    Text(status)
    OutlinedTextField(baud, { if(!active) baud = it }, label = { Text("波特率（默认值自动记忆）") }, enabled = !active)
    Row { listOf(115200,230400,460800,921600).forEach { value -> TextButton(onClick = { baud = value.toString() }, enabled = !active) { Text("$value") } } }
    OutlinedTextField(port, { port = it }, label = { Text("串口索引（从 0 开始）") }, enabled = !active)
    TextButton(onClick = { refresh() }) { Text("刷新设备 (${devices.size})") }
    if(devices.isEmpty()) Text("未检测到 USB 设备，请检查 OTG 转接头、供电和数据线。")
    devices.forEach { d ->
     Text("${d.deviceName}\nVID %04X / PID %04X".format(d.vendorId,d.productId))
     repeat(d.interfaceCount) { i -> val intf = d.getInterface(i); Text("接口 ${intf.id}：class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}") }
     Row {
      TextButton(enabled = !active, onClick = {
       try { usb.requestPermission(d, PendingIntent.getBroadcast(this@UsbDebugActivity, d.deviceId, Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
       catch(e: Exception) { status = "权限申请失败：${e.message}" }
      }) { Text(if(usb.hasPermission(d)) "已授权" else "申请授权") }
      Button(enabled = !active && usb.hasPermission(d), onClick = {
       val speed = baud.toIntOrNull(); val index = port.toIntOrNull()
       if(speed == null || speed <= 0 || index == null || index < 0) status = "请输入有效波特率和串口索引"
       else {
        getPreferences(MODE_PRIVATE).edit().putString("baud", baud).apply()
        val dir = File(filesDir,"usb").apply { mkdirs() }
        val file = File(dir,"usb-${System.currentTimeMillis()}.bin"); capture = file
        synchronized(lock) { tail = byteArrayOf(); total = 0 }
        active = true; connectedName = d.deviceName
        transport.connect(d,speed,index,file, { bytes -> synchronized(lock) { total += bytes.size; tail = (tail + bytes).takeLast(4096).toByteArray() } }, { message -> runOnUiThread { status = message; if(message.startsWith("接收已结束")) active = false } })
       }
      }) { Text("连接") }
     }
    }
    Row {
     Button(onClick = { transport.stop() }, enabled = active) { Text("断开") }
     TextButton(onClick = { hex = !hex }) { Text(if(hex) "切换 ASCII" else "切换 HEX") }
     TextButton(onClick = { save.launch(capture!!.name) }, enabled = !active && capture?.exists() == true) { Text("导出原始数据") }
    }
    Text("已接收 $count 字节；窗口仅显示最近 4096 字节。连接期间自动保存全部原始字节。")
    Text(if(hex) preview.joinToString(" ") { "%02X".format(it.toInt() and 255) } else preview.map { val n = it.toInt() and 255; if(n in 32..126 || n == 10 || n == 13 || n == 9) n.toChar() else '.' }.joinToString(""))
   }
  } } }
 }
 override fun onDestroy() { handler.removeCallbacks(tick); unregisterReceiver(receiver); transport.close(); super.onDestroy() }
}

