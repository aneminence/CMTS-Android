package com.cmts.mobile

/**
 * Pure helpers for WCH multi-UART USB mapping.
 *
 * CH344Q (VID 1A86 / PID 55D5) enumerates 8 USB interfaces in CDC pairs:
 * even ids are communication/control, odd ids are bulk data. The official
 * WCHUARTManager maps serial index n to interface pair (2n, 2n+1), i.e.
 * UART0..UART3 — not to raw interface 0 (which has no bulk data).
 */
object UsbSerialPorts {
    const val GOLD = 0xFFD4AF37.toInt()

    fun isGnssBridge(vendorId: Int, productId: Int): Boolean =
        vendorId == 0x1A86 && productId in setOf(0x55D5, 0x7523)

    fun likelySerialCount(interfaceCount: Int): Int? =
        if (interfaceCount >= 2 && interfaceCount % 2 == 0) interfaceCount / 2 else null

    fun portLabel(index: Int): String = "UART$index"

    fun interfaceRole(interfaceId: Int, interfaceCount: Int): String? {
        if (likelySerialCount(interfaceCount) == null) return null
        val uart = interfaceId / 2
        return if (interfaceId % 2 == 0) "控制 CDC · ${portLabel(uart)}" else "数据 · ${portLabel(uart)}"
    }

    fun deviceHint(interfaceCount: Int, vendorId: Int, productId: Int): String {
        val count = likelySerialCount(interfaceCount)
        val ids = "VID %04X / PID %04X".format(vendorId, productId)
        if (vendorId == 0x1A86 && productId == 0x7523) {
            return "$ids。CH340 USB 转串口，1 路串口：选择 UART0 或全部监听，可直连 UM982 板载 USB。"
        }
        return if (count != null) {
            val last = count - 1
            "$ids。$interfaceCount 个接口配对为 $count 路串口（UART0–UART$last）。" +
                "串口索引不是 USB 接口号：偶数接口是控制口，数据在奇数接口。" +
                "紫板 CH344Q 的 UM982 可能接在 UART0 以外的口，默认索引 0 可能一直是 0 字节。"
        } else {
            "$ids。未识别为成对 CDC 接口，请按驱动给出的串口索引连接。"
        }
    }

    fun formatCounts(counts: LongArray, focusPort: Int?): String {
        if (counts.isEmpty()) return "尚未读到各口计数。"
        val parts = counts.mapIndexed { i, n ->
            val mark = if (focusPort == i) "（当前保存）" else ""
            "${portLabel(i)}$mark: $n"
        }
        return parts.joinToString("  ") + " 字节"
    }

    fun firstPortWithData(counts: LongArray): Int? =
        counts.indexOfFirst { it > 0 }.takeIf { it >= 0 }

    fun suggestOtherPort(focusPort: Int?, counts: LongArray): String? {
        val hit = firstPortWithData(counts) ?: return null
        if (focusPort == null || focusPort == hit) return null
        val focusedBytes = focusPort.takeIf { it in counts.indices }?.let { counts[it] } ?: 0L
        if (focusedBytes > 0L) return null
        return "${portLabel(focusPort ?: -1)} 仍为 0 字节，但 ${portLabel(hit)} 已收到 ${counts[hit]} 字节。预览可点该口切换；数据已写入对应文件。"
    }

    fun captureName(prefix: String, port: Int): String = "$prefix-uart$port.bin"
}
