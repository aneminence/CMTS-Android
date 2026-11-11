package com.cmts.mobile

import android.app.Application
import android.hardware.usb.UsbDevice
import android.util.Log
import cn.wch.uartlib.WCHUARTManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Raw byte transport only: deliberately exposes no GNSS command/write API. */
class UsbTransport(private val app: Application) {
    private val manager = WCHUARTManager.getInstance().also {
        it.init(app)
        WCHUARTManager.setDebug(true)
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var device: UsbDevice? = null

    fun connect(
        d: UsbDevice,
        baud: Int,
        focusPort: Int?,
        assertDtr: Boolean,
        dir: File,
        stamp: Long,
        onBytes: (port: Int, data: ByteArray) -> Unit,
        onCounts: (LongArray) -> Unit,
        status: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            var failure: String? = null
            var files: Array<File> = emptyArray()
            try {
                require(manager.enumDevice().any { it.deviceName == d.deviceName }) { "驱动不支持此设备" }
                check(manager.openDevice(d)) { "无法打开设备，请检查 OTG 连接及权限" }
                device = d
                val serialCount = manager.getSerialCount(d)
                require(serialCount > 0) { "未读到串口数量" }
                if (focusPort != null) {
                    require(focusPort in 0 until serialCount) { "串口索引超出设备范围（0–${serialCount - 1}）" }
                }
                val chip = manager.getChipType(d)?.toString() ?: "未知芯片"
                Log.i(TAG, "opened $chip name=${d.deviceName} serialCount=$serialCount baud=$baud dtr=$assertDtr focus=$focusPort")
                for (port in 0 until serialCount) {
                    check(manager.setSerialParameter(d, port, baud, 8, 1, 0, false)) {
                        "设备拒绝 ${UsbSerialPorts.portLabel(port)} 的波特率 $baud"
                    }
                    // Official WCH docs: enableSerial is required only for CH9114; it is a no-op on CH344Q.
                    check(manager.enableSerial(d, port, true)) { "无法启用 ${UsbSerialPorts.portLabel(port)}" }
                    if (assertDtr) {
                        check(manager.setDTR(d, port, true)) { "无法拉低 ${UsbSerialPorts.portLabel(port)} 的 DTR" }
                    }
                }
                dir.mkdirs()
                val prefix = "usb-$stamp"
                files = Array(serialCount) { port -> File(dir, UsbSerialPorts.captureName(prefix, port)) }
                val focusName = if (focusPort != null) {
                    UsbSerialPorts.captureName(prefix, focusPort)
                } else {
                    "usb-$stamp-uart*.bin"
                }
                status("已连接 $chip · $baud · 8N1 · ${serialCount} 路串口；正在保存 $focusName。已对全部串口配置波特率并同时计数。")
                val counts = LongArray(serialCount)
                val streams = files.map { it.outputStream().buffered() }
                try {
                    while (running.get()) {
                        check(manager.isConnected(d)) { "USB 已断开" }
                        var any = false
                        for (port in 0 until serialCount) {
                            val bytes = manager.readData(d, port)
                            if (bytes != null && bytes.isNotEmpty()) {
                                any = true
                                streams[port].write(bytes)
                                counts[port] += bytes.size.toLong()
                                onBytes(port, bytes)
                                if (counts[port] == bytes.size.toLong()) {
                                    Log.i(TAG, "first bytes on ${UsbSerialPorts.portLabel(port)}: ${bytes.size}")
                                }
                            }
                        }
                        onCounts(counts.copyOf())
                        if (!any) Thread.sleep(5)
                    }
                    streams.forEach { it.flush() }
                } finally {
                    streams.forEach { runCatching { it.close() } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "receive stopped", e)
                failure = "USB 接收停止：${e.message ?: e.javaClass.simpleName}"
            } finally {
                try {
                    device?.let { manager.disconnect(it) }
                } catch (e: Exception) {
                    status("关闭设备失败：${e.message}")
                }
                device = null
                running.set(false)
                val saved = files.filter { it.exists() && it.length() > 0L }.joinToString("、") { "${it.name}（${it.length()} 字节）" }
                val savedText = if (saved.isBlank()) "各口文件均为 0 字节" else "已写入 $saved"
                status("接收已结束：${failure ?: "正常停止"}；$savedText")
            }
        }
    }

    fun stop() { running.set(false) }

    fun close() {
        stop()
        worker.execute { manager.close(app) }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "CMTS:usb"
    }
}
