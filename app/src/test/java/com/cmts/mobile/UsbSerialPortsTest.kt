package com.cmts.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSerialPortsTest {
    @Test fun directCh340IsRecognizedWithoutAcceptingUnrelatedDevices() {
        assertTrue(UsbSerialPorts.isGnssBridge(0x1A86, 0x7523))
        assertTrue(UsbSerialPorts.isGnssBridge(0x1A86, 0x55D5))
        org.junit.Assert.assertFalse(UsbSerialPorts.isGnssBridge(0x1234, 0x7523))
        org.junit.Assert.assertFalse(UsbSerialPorts.isGnssBridge(0x1A86, 0x1234))
        val hint = UsbSerialPorts.deviceHint(1, 0x1A86, 0x7523)
        assertTrue(hint.contains("CH340"))
        assertTrue(hint.contains("UART0"))
        org.junit.Assert.assertFalse(hint.contains("未识别"))
    }

    @Test fun ch344qEightInterfacesMapToFourUarts() {
        assertEquals(4, UsbSerialPorts.likelySerialCount(8))
        assertEquals("控制 CDC · UART0", UsbSerialPorts.interfaceRole(0, 8))
        assertEquals("数据 · UART0", UsbSerialPorts.interfaceRole(1, 8))
        assertEquals("控制 CDC · UART1", UsbSerialPorts.interfaceRole(2, 8))
        assertEquals("数据 · UART1", UsbSerialPorts.interfaceRole(3, 8))
        assertEquals("数据 · UART3", UsbSerialPorts.interfaceRole(7, 8))
    }

    @Test fun oddInterfaceCountIsNotPaired() {
        assertNull(UsbSerialPorts.likelySerialCount(1))
        assertNull(UsbSerialPorts.likelySerialCount(3))
        assertNull(UsbSerialPorts.interfaceRole(0, 1))
    }

    @Test fun deviceHintExplainsIndexIsNotInterfaceId() {
        val hint = UsbSerialPorts.deviceHint(8, 0x1A86, 0x55D5)
        assertTrue(hint.contains("VID 1A86 / PID 55D5"))
        assertTrue(hint.contains("4 路串口"))
        assertTrue(hint.contains("UART0–UART3"))
        assertTrue(hint.contains("串口索引不是 USB 接口号"))
    }

    @Test fun formatCountsMarksFocusedPort() {
        assertEquals(
            "UART0（当前保存）: 0  UART1: 128  UART2: 0  UART3: 0 字节",
            UsbSerialPorts.formatCounts(longArrayOf(0, 128, 0, 0), 0),
        )
    }

    @Test fun firstPortWithDataSkipsEmptyLeadingPorts() {
        assertEquals(2, UsbSerialPorts.firstPortWithData(longArrayOf(0, 0, 15, 3)))
        assertNull(UsbSerialPorts.firstPortWithData(longArrayOf(0, 0, 0)))
    }

    @Test fun suggestsOtherPortOnlyWhenFocusIsEmpty() {
        assertEquals(
            "UART0 仍为 0 字节，但 UART2 已收到 40 字节。预览可点该口切换；数据已写入对应文件。",
            UsbSerialPorts.suggestOtherPort(0, longArrayOf(0, 0, 40, 0)),
        )
        assertNull(UsbSerialPorts.suggestOtherPort(2, longArrayOf(0, 0, 40, 0)))
        assertNull(UsbSerialPorts.suggestOtherPort(0, longArrayOf(12, 0, 40, 0)))
        assertNull(UsbSerialPorts.suggestOtherPort(null, longArrayOf(0, 8)))
    }

    @Test fun captureNameIsPerPort() {
        assertEquals("usb-123-uart2.bin", UsbSerialPorts.captureName("usb-123", 2))
    }
}
