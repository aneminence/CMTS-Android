package com.cmts.mobile

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

enum class GnssSource { PHONE, EXTERNAL }

object GnssSourceSettings {
    const val PREFS = "gnss_source"
    const val KEY = "source"
    const val CH344Q_VID = 0x1A86
    const val CH344Q_PID = 0x55D5

    fun load(context: Context): GnssSource =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "phone") == "external") {
            GnssSource.EXTERNAL
        } else {
            GnssSource.PHONE
        }

    fun save(context: Context, source: GnssSource) {
        val value = if (source == GnssSource.EXTERNAL) "external" else "phone"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
        Live.gnssSource = source
    }

    fun findUsbGnss(usb: UsbManager): UsbDevice? =
        usb.deviceList.values
            .filter { UsbSerialPorts.isGnssBridge(it.vendorId, it.productId) }
            .sortedWith(compareByDescending<UsbDevice> { it.deviceName == UsbSerialHub.deviceName() }
                .thenByDescending { it.productId == CH344Q_PID }.thenBy { it.deviceName })
            .firstOrNull()

    fun noFixStatus(): String = "外接模块已连接，尚无定位（可能未接天线或未锁定）"

    fun locatedStatus(port: Int): String = "外接 UM982 · ${UsbSerialPorts.portLabel(port)} · 已定位"

    fun connectedWaitingNmea(): String = "外接模块已连接，正在查找 NMEA 数据口…"
}
