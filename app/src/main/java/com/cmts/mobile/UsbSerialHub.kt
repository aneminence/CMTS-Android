package com.cmts.mobile

import android.app.Application
import android.hardware.usb.UsbDevice
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

enum class UsbHubOwner { DEBUG, GNSS }

interface UsbByteListener {
    fun onBytes(port: Int, data: ByteArray)
    fun onCounts(counts: LongArray) {}
    fun onStatus(message: String) {}
}

/**
 * Single owner of the WCH UART session so the debug page and external GNSS can share one device.
 * The session stays open until every owner releases it.
 */
object UsbSerialHub {
    private val lock = Any()
    private var transport: UsbTransport? = null
    private var application: Application? = null
    private val owners = mutableSetOf<UsbHubOwner>()
    private val listeners = CopyOnWriteArrayList<UsbByteListener>()
    @Volatile private var openDeviceName: String? = null
    @Volatile private var opened = false
    private val startGeneration = AtomicInteger(0)

    fun isOpen(): Boolean = opened

    fun deviceName(): String? = openDeviceName

    fun owners(): Set<UsbHubOwner> = synchronized(lock) { owners.toSet() }

    fun addListener(listener: UsbByteListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: UsbByteListener) {
        listeners.remove(listener)
    }

    fun acquire(
        app: Application,
        owner: UsbHubOwner,
        device: UsbDevice,
        baud: Int = 115200,
        assertDtr: Boolean = false,
        saveDir: File? = null,
        stamp: Long = System.currentTimeMillis(),
        focusPort: Int? = null,
    ): Boolean {
        synchronized(lock) {
            if (opened && openDeviceName != null && openDeviceName != device.deviceName) {
                dispatchStatus("USB 已被占用：当前设备 ${openDeviceName}，无法再打开 ${device.deviceName}")
                return false
            }
            owners.add(owner)
            if (opened) return true
            application = app
            val session = transport ?: UsbTransport(app).also { transport = it }
            opened = true
            openDeviceName = device.deviceName
            val gen = startGeneration.incrementAndGet()
            session.connect(
                device,
                baud,
                focusPort,
                assertDtr,
                saveDir,
                stamp,
                { port, data -> if (startGeneration.get() == gen) dispatchBytes(port, data) },
                { counts -> if (startGeneration.get() == gen) dispatchCounts(counts) },
                { message ->
                    if (startGeneration.get() == gen) {
                        if (message.startsWith("接收已结束")) {
                            synchronized(lock) {
                                opened = false
                                openDeviceName = null
                            }
                        }
                        dispatchStatus(message)
                    }
                },
            )
            return true
        }
    }

    fun release(owner: UsbHubOwner) {
        val shouldStop = synchronized(lock) {
            owners.remove(owner)
            owners.isEmpty() && opened
        }
        if (shouldStop) stopSession()
    }

    fun stopSession() {
        startGeneration.incrementAndGet()
        transport?.stop()
        synchronized(lock) {
            owners.clear()
            opened = false
            openDeviceName = null
        }
    }

    private fun dispatchBytes(port: Int, data: ByteArray) {
        listeners.forEach { runCatching { it.onBytes(port, data) } }
    }

    private fun dispatchCounts(counts: LongArray) {
        listeners.forEach { runCatching { it.onCounts(counts) } }
    }

    private fun dispatchStatus(message: String) {
        listeners.forEach { runCatching { it.onStatus(message) } }
    }
}
