package com.cmts.mobile.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDeviceModelTest {
    @Test fun combinesManufacturerAndModel() = assertEquals("Samsung Galaxy Note20 Ultra 5G", RemoteDeviceModel.format("samsung", "SM-N9860"))
    @Test fun avoidsDuplicatedManufacturer() = assertEquals("Samsung Galaxy", RemoteDeviceModel.format("samsung", "Samsung Galaxy"))
    @Test fun normalizesWhitespaceAndMissingValues() {
        assertEquals("Pixel", RemoteDeviceModel.format("unknown", " Pixel "))
        assertEquals("Android", RemoteDeviceModel.format(null, "unknown"))
    }
}
