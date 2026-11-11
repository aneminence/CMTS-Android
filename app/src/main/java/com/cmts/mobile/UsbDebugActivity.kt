package com.cmts.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

class UsbDebugActivity : ComponentActivity() {
    private lateinit var usb: UsbManager
    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var status by mutableStateOf("请选择设备申请权限，然后连接。只接收数据，不发送 GNSS 指令。")
    private var preview by mutableStateOf(byteArrayOf())
    private var count by mutableLongStateOf(0)
    private var portCounts by mutableStateOf(longArrayOf())
    private var previewIndex by mutableIntStateOf(0)
    private var hex by mutableStateOf(false)
    private var active by mutableStateOf(false)
    private var baud by mutableStateOf("115200")
    private var port by mutableStateOf("all")
    private var assertDtr by mutableStateOf(false)
    private var capture by mutableStateOf<File?>(null)
    private val lock = Any()
    private var tail = byteArrayOf()
    private var total = 0L
    private var totals = longArrayOf()
    private var previewPort = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                preview = tail
                count = total
                portCounts = totals.copyOf()
                previewIndex = previewPort
            }
            handler.postDelayed(this, 100)
        }
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = capture
        if (uri != null && file != null) Thread {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("无法打开保存位置")
                runOnUiThread { status = "原始二进制数据已导出：${file.name}" }
            } catch (e: Exception) {
                runOnUiThread { status = "保存失败：${e.message}" }
            }
        }.start()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                status = if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    "已授权，请点击连接"
                } else {
                    "USB 授权被拒绝，可重新申请"
                }
            }
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (d?.deviceName == connectedName || d?.deviceName == UsbSerialHub.deviceName()) {
                    if (UsbHubOwner.GNSS !in UsbSerialHub.owners()) UsbSerialHub.stopSession()
                    status = "设备已拔出，正在关闭本页接收"
                }
            }
            refresh()
        }
    }
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private var connectedName: String? = null
    private var capturePrefix: String? = null
    private fun refresh() { devices = usb.deviceList.values.sortedBy { it.deviceName } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        UsbSerialHub.addListener(debugListener)
        val prefs = getPreferences(MODE_PRIVATE)
        baud = prefs.getString("baud", "115200") ?: "115200"
        port = prefs.getString("port", "all") ?: "all"
        assertDtr = prefs.getBoolean("dtr", false)
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        refresh()
        handler.post(tick)
        val gold = Color(UsbSerialPorts.GOLD)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("USB 设备调试", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { finish() }) { Text("返回") }
                        if (Live.gnssSource == GnssSource.EXTERNAL) {
                            Text("定位源已选外接 UM982 时，返回后接收会在后台继续。切源请用主界面「定位源」。")
                        }
                        Text(status)
                        OutlinedTextField(
                            baud,
                            { if (!active) baud = it },
                            label = { Text("波特率（默认值自动记忆）") },
                            enabled = !active,
                        )
                        Row {
                            listOf(115200, 230400, 460800, 921600).forEach { value ->
                                TextButton(onClick = { baud = value.toString() }, enabled = !active) { Text("$value") }
                            }
                        }
                        Text("串口（CH344Q 为 UART0–UART3；可先选全部监听，看哪一路有数据）")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { if (!active) port = "all" },
                                enabled = !active,
                            ) { Text("全部监听", color = if (port == "all") gold else Color.Unspecified) }
                            listOf("0", "1", "2", "3").forEach { value ->
                                TextButton(
                                    onClick = {
                                        port = value
                                        val index = value.toInt()
                                        if (active) {
                                            synchronized(lock) {
                                                previewPort = index
                                                tail = byteArrayOf()
                                                total = totals.getOrElse(index) { 0L }
                                            }
                                            capturePrefix?.let { prefix ->
                                                val file = File(File(filesDir, "usb"), UsbSerialPorts.captureName(prefix, index))
                                                if (file.exists()) capture = file
                                            }
                                        }
                                    },
                                    enabled = !active || portCounts.isNotEmpty(),
                                ) { Text(UsbSerialPorts.portLabel(value.toInt()), color = portColor(value, gold)) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = assertDtr, onCheckedChange = { if (!active) assertDtr = it }, enabled = !active)
                            Text("拉低 DTR（部分转接板需要，默认关闭）")
                        }
                        TextButton(onClick = { refresh() }) { Text("刷新设备 (${devices.size})") }
                        if (devices.isEmpty()) Text("未检测到 USB 设备，请检查 OTG 转接头、供电和数据线。")
                        devices.forEach { d -> DeviceBlock(d, gold) }
                        Row {
                            Button(onClick = { UsbSerialHub.release(UsbHubOwner.DEBUG); active = false }, enabled = active) { Text("断开") }
                            TextButton(onClick = { hex = !hex }) { Text(if (hex) "切换 ASCII" else "切换 HEX") }
                            TextButton(
                                onClick = { save.launch(capture!!.name) },
                                enabled = !active && capture?.exists() == true,
                            ) { Text("导出原始数据") }
                        }
                        val suggestion = UsbSerialPorts.suggestOtherPort(selectedPortOrNull(), portCounts)
                        Text(UsbSerialPorts.formatCounts(portCounts, selectedPortOrNull() ?: previewIndex.takeIf { portCounts.isNotEmpty() }))
                        if (suggestion != null) Text(suggestion, color = gold)
                        Text("已接收（当前预览口）$count 字节；窗口仅显示最近 4096 字节。连接期间各口原始字节分别保存。无天线时模块仍可能输出状态或空 NMEA。")
                        Text(
                            if (hex) preview.joinToString(" ") { "%02X".format(it.toInt() and 255) }
                            else preview.map {
                                val n = it.toInt() and 255
                                if (n in 32..126 || n == 10 || n == 13 || n == 9) n.toChar() else '.'
                            }.joinToString(""),
                        )
                    }
                }
            }
        }
    }

    private fun portColor(value: String, gold: Color): Color {
        val index = value.toInt()
        val hasData = portCounts.getOrElse(index) { 0L } > 0L
        return when {
            port == value -> gold
            hasData -> gold.copy(alpha = 0.7f)
            else -> Color.Unspecified
        }
    }

    private fun selectedPortOrNull(): Int? = port.toIntOrNull()

    @Composable
    private fun DeviceBlock(d: UsbDevice, gold: Color) {
        Text("${d.deviceName}\n" + UsbSerialPorts.deviceHint(d.interfaceCount, d.vendorId, d.productId), color = gold)
        repeat(d.interfaceCount) { i ->
            val intf = d.getInterface(i)
            val role = UsbSerialPorts.interfaceRole(intf.id, d.interfaceCount)
            val roleText = if (role != null) "（$role）" else ""
            Text("接口 ${intf.id}$roleText：class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} protocol=${intf.interfaceProtocol} endpoints=${intf.endpointCount}")
        }
        Row {
            TextButton(
                enabled = !active,
                onClick = {
                    try {
                        usb.requestPermission(
                            d,
                            PendingIntent.getBroadcast(
                                this@UsbDebugActivity,
                                d.deviceId,
                                Intent(permissionAction).setPackage(packageName),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    } catch (e: Exception) {
                        status = "权限申请失败：${e.message}"
                    }
                },
            ) { Text(if (usb.hasPermission(d)) "已授权" else "申请授权") }
            Button(
                enabled = !active && usb.hasPermission(d),
                onClick = { startCapture(d) },
            ) { Text("连接") }
        }
    }

    private fun startCapture(d: UsbDevice) {
        val speed = baud.toIntOrNull()
        val listenAll = port == "all"
        val index = port.toIntOrNull()
        if (speed == null || speed <= 0 || (!listenAll && (index == null || index < 0))) {
            status = "请输入有效波特率，并选择串口或全部监听"
            return
        }
        getPreferences(MODE_PRIVATE).edit()
            .putString("baud", baud)
            .putString("port", port)
            .putBoolean("dtr", assertDtr)
            .apply()
        val dir = File(filesDir, "usb").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        capturePrefix = "usb-$stamp"
        capture = if (index != null) File(dir, UsbSerialPorts.captureName("usb-$stamp", index)) else null
        synchronized(lock) {
            tail = byteArrayOf()
            total = 0
            totals = longArrayOf()
            previewPort = index ?: 0
        }
        active = true
        connectedName = d.deviceName
        val already = UsbSerialHub.isOpen()
        val ok = UsbSerialHub.acquire(
            application,
            UsbHubOwner.DEBUG,
            d,
            baud = speed,
            assertDtr = assertDtr,
            saveDir = if (already) null else dir,
            stamp = stamp,
            focusPort = index,
        )
        if (!ok) {
            active = false
            connectedName = null
            status = "无法打开设备：USB 会话可能被占用"
            return
        }
        if (already) status = "已加入现有 USB 会话，本页仅预览；原始文件由先打开的会话决定。"
    }

    private val debugListener = object : UsbByteListener {
        override fun onBytes(srcPort: Int, bytes: ByteArray) {
            synchronized(lock) {
                if (port == "all" && total == 0L && srcPort != previewPort) {
                    previewPort = srcPort
                    tail = byteArrayOf()
                }
                if (srcPort == previewPort) {
                    total += bytes.size
                    tail = (tail + bytes).takeLast(4096).toByteArray()
                }
            }
        }
        override fun onCounts(counts: LongArray) {
            synchronized(lock) { totals = counts.copyOf() }
            if (capture == null) {
                val dir = File(filesDir, "usb")
                UsbSerialPorts.firstPortWithData(counts)?.let { hit ->
                    val prefix = capturePrefix
                    if (prefix != null) capture = File(dir, UsbSerialPorts.captureName(prefix, hit))
                }
            }
        }
        override fun onStatus(message: String) {
            runOnUiThread {
                status = message
                if (message.startsWith("接收已结束")) active = false
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(receiver)
        UsbSerialHub.removeListener(debugListener)
        UsbSerialHub.release(UsbHubOwner.DEBUG)
        super.onDestroy()
    }
}
