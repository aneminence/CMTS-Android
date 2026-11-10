package com.cmts.mobile.remote

/** Hardware description is separate from the user-editable vehicle/device label. */
object RemoteDeviceModel {
    fun format(manufacturer: String?, model: String?): String {
        val maker = manufacturer.orEmpty().trim().takeUnless { it.equals("unknown", true) }.orEmpty()
        val name = model.orEmpty().trim().takeUnless { it.equals("unknown", true) }.orEmpty()
        if (name.equals("SM-N9860", true)) return "Samsung Galaxy Note20 Ultra 5G"
        return when {
            name.isEmpty() -> maker.ifEmpty { "Android" }
            maker.isEmpty() || name.equals(maker, true) || name.startsWith("$maker ", true) || name.startsWith("$maker-", true) -> name
            else -> "$maker $name"
        }
    }
}
