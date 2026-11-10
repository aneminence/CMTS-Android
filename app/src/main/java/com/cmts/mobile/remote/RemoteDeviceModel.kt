package com.cmts.mobile.remote

/** Hardware description is separate from the user-editable vehicle/device label. */
object RemoteDeviceModel {
    fun format(manufacturer: String?, model: String?): String {
        val maker = manufacturer.orEmpty().trim().takeUnless { it.equals("unknown", true) }.orEmpty()
        val name = model.orEmpty().trim().takeUnless { it.equals("unknown", true) }.orEmpty()
        val samsungNames = mapOf(
            "SM-S9180" to "Samsung Galaxy S23 Ultra",
            "SM-S918B" to "Samsung Galaxy S23 Ultra",
            "SM-S918U" to "Samsung Galaxy S23 Ultra",
            "SM-S918U1" to "Samsung Galaxy S23 Ultra",
            "SM-S9110" to "Samsung Galaxy S23",
            "SM-S9160" to "Samsung Galaxy S23+",
            "SM-N9860" to "Samsung Galaxy Note20 Ultra 5G",
            "SM-N986B" to "Samsung Galaxy Note20 Ultra 5G",
            "SM-N9810" to "Samsung Galaxy Note20 5G",
            "SM-S9280" to "Samsung Galaxy S24 Ultra",
            "SM-S928B" to "Samsung Galaxy S24 Ultra",
            "SM-S9380" to "Samsung Galaxy S25 Ultra",
            "SM-S938B" to "Samsung Galaxy S25 Ultra",
            "SM-F9560" to "Samsung Galaxy Z Fold6",
            "SM-F9660" to "Samsung Galaxy Z Fold7",
            "SM-F7410" to "Samsung Galaxy Z Flip6",
            "SM-F7610" to "Samsung Galaxy Z Flip7",
        )
        samsungNames[name.uppercase()]?.let { return it }
        return when {
            name.isEmpty() -> maker.ifEmpty { "Android" }
            maker.isEmpty() || name.equals(maker, true) || name.startsWith("$maker ", true) || name.startsWith("$maker-", true) -> name
            else -> "$maker $name"
        }
    }
}
