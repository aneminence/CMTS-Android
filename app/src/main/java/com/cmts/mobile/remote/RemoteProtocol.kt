package com.cmts.mobile.remote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object RemoteProtocol {
    const val V = 1

    fun envelope(
        type: String,
        deviceId: String?,
        payload: JSONObject,
        id: String = UUID.randomUUID().toString(),
        ts: Long = System.currentTimeMillis(),
    ): JSONObject = JSONObject()
        .put("v", V)
        .put("type", type)
        .put("id", id)
        .put("ts", ts)
        .apply { if (deviceId != null) put("deviceId", deviceId) }
        .put("payload", payload)

    fun parse(text: String): JSONObject = JSONObject(text)

    fun typeOf(msg: JSONObject): String = msg.optString("type", "")

    fun idOf(msg: JSONObject): String = msg.optString("id", UUID.randomUUID().toString())

    fun payloadOf(msg: JSONObject): JSONObject =
        msg.optJSONObject("payload") ?: JSONObject()

    fun putNullable(obj: JSONObject, key: String, value: Double?) {
        if (value == null || !value.isFinite()) {
            obj.put(key, JSONObject.NULL)
        } else {
            obj.put(key, value)
        }
    }

    fun putNullableLong(obj: JSONObject, key: String, value: Long?) {
        if (value == null) obj.put(key, JSONObject.NULL) else obj.put(key, value)
    }

    fun jsonMap(map: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Boolean, is Number, is String -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o
    }

    fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
