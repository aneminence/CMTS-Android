package com.cmts.mobile.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.cmts.mobile.CaptureService
import com.cmts.mobile.Live
import com.cmts.mobile.RecordingCommands
import com.cmts.mobile.RecordingState
import com.cmts.mobile.RecordingStateMachine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol v0.1 bridge: WS server + sample fan-out + record/config handlers.
 */
object RemoteBridge {
    private const val TAG = "CmtsRemoteBridge"

    private val started = AtomicBoolean(false)
    private val seq = AtomicLong(0L)
    private var discovery: RemoteDiscovery? = null
    fun discoveryStatus(): String = discovery?.status ?: "未注册"
    fun connectedClients(): Int = server?.authenticatedCount() ?: 0
    fun subscribers(): Int = server?.subscriberCount() ?: 0
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null
    @Volatile private var store: RemoteIdentityStore? = null
    @Volatile private var server: RemoteWsServer? = null
    @Volatile private var wallAnchorMs: Long = 0L
    @Volatile private var elapsedAnchorNanos: Long = 0L
    @Volatile private var lastSampleEmitElapsed: Long = 0L
    @Volatile private var lastMeasureNanosEmitted: Long = 0L
    @Volatile private var lastStatusEmitMs: Long = 0L
    @Volatile private var remoteHold: Boolean = false

    private val tick = object : Runnable {
        override fun run() {
            if (!started.get()) return
            try {
                // Status/hold on main tick. IMU samples are driven by onImuSample().
                maybePushStatus()
                syncRemoteHold()
            } catch (e: Exception) {
                Log.w(TAG, "tick: ${e.message}")
            }
            main.postDelayed(this, 200L)
        }
    }

    fun currentSeq(): Long = seq.get()

    fun identity(): RemoteIdentityStore? = store

    fun isRunning(): Boolean = started.get() && server?.isRunning() == true

    fun listenEndpoint(): String =
        "ws://0.0.0.0:${RemoteIdentityStore.DEFAULT_PORT}${RemoteIdentityStore.WS_PATH}"

    fun start(context: Context) {
        val app = context.applicationContext
        val idStore = RemoteIdentityStore(app)
        store = idStore
        appContext = app
        wallAnchorMs = System.currentTimeMillis()
        elapsedAnchorNanos = SystemClock.elapsedRealtimeNanos()
        if (!idStore.remoteEnabled) {
            Log.i(TAG, "remote disabled in prefs")
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }
        val ws = RemoteWsServer(
            port = RemoteIdentityStore.DEFAULT_PORT,
            path = RemoteIdentityStore.WS_PATH,
            onText = { client, text -> handleText(client, text) },
            onOpen = { Log.i(TAG, "client open") },
            onClose = {
                it.subscribed = false
                it.authenticated = false
                syncRemoteHold()
            },
        )
        server = ws
        try {
            ws.start()
            discovery = RemoteDiscovery(app, idStore).also { it.start() }
            main.removeCallbacks(tick)
            main.post(tick)
        } catch (e: Exception) {
            Log.e(TAG, "WS start failed: ${e.message}")
            started.set(false)
            server = null
        }
    }

    fun stop() {
        discovery?.stop()
        discovery = null
        main.removeCallbacks(tick)
        if (!started.getAndSet(false)) {
            releaseRemoteHold()
            return
        }
        runCatching { server?.stop() }
        server = null
        releaseRemoteHold()
    }

    fun restart(context: Context) {
        stop()
        start(context)
    }

    fun resetToken(context: Context): String {
        val s = store ?: RemoteIdentityStore(context.applicationContext).also { store = it }
        val token = s.resetToken()
        if (started.get() || s.remoteEnabled) restart(context)
        return token
    }

    /**
     * Called from CaptureService sensor thread on IMU accept.
     * Throttled by subscribe.maxHz (default ~100). Does not wait for GNSS.
     */
    fun onImuSample(samples: Map<String, com.cmts.mobile.Sample>) {
        if (!started.get()) return
        val ws = server ?: return
        if (!ws.hasAuthenticatedSubscriber()) return
        try {
            maybePublishSample(ws, samples)
        } catch (e: Exception) {
            Log.w(TAG, "onImuSample: ${e.message}")
        }
    }

    /**
     * Explicit zero/clear signal for Win plot buffers.
     * Win clears curves on this control frame. Subsequent stream.sample vehicle.* /
     * core ax,ay,az,yawRateDps follow Dashboard mount-zero transform while enabled.
     *
     * type: stream.reset
     * payload: {
     *   reason: "mount_zero" | "mount_zero_clear",
     *   mountZeroEnabled: bool,
     *   zeroedAtElapsedNanos: long|null,
     *   nextSeq: long
     * }
     */
    fun notifyStreamReset(
        reason: String,
        mountZeroEnabled: Boolean,
        zeroedAtElapsedNanos: Long? = null,
    ) {
        if (!started.get()) return
        val idStore = store ?: return
        val ws = server ?: return
        val payload = JSONObject()
            .put("reason", reason)
            .put("mountZeroEnabled", mountZeroEnabled)
            .put("nextSeq", seq.get())
        RemoteProtocol.putNullableLong(payload, "zeroedAtElapsedNanos", zeroedAtElapsedNanos)
        val msg = RemoteProtocol.envelope(
            type = "stream.reset",
            deviceId = idStore.deviceId,
            payload = payload,
        )
        ws.broadcastControl(msg.toString())
        Log.i(TAG, "stream.reset reason=$reason enabled=$mountZeroEnabled")
    }

    private fun maybePublishSample(ws: RemoteWsServer, samples: Map<String, com.cmts.mobile.Sample>) {
        val defaultHz = store?.maxHz ?: RemoteSampleTiming.DEFAULT_MAX_HZ
        val hz = RemoteSampleTiming.clampHz(ws.maxSubscriberHz(defaultHz))
        val gps = samples["phone.gps"]
        val accel = samples["vehicle.accel"] ?: samples["phone.accel"]
        val gyro = samples["vehicle.gyro"] ?: samples["phone.gyro"]
        val measureNanos = RemoteSampleTiming.measureNanos(accel?.nanos, gyro?.nanos, gps?.nanos)
        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val lastMeasure = lastMeasureNanosEmitted.takeIf { it > 0L }
        if (!RemoteSampleTiming.shouldEmit(nowElapsed, lastSampleEmitElapsed, hz, measureNanos, lastMeasure)) {
            return
        }
        lastSampleEmitElapsed = nowElapsed
        lastMeasureNanosEmitted = measureNanos ?: lastMeasureNanosEmitted
        // This method runs on the capture thread; copy only after an actual emission is due.
        val snapshot = samples.toMap()
        Live.remoteSamples = snapshot
        publishCoreSample(ws, snapshot, gps, accel, gyro, measureNanos)
    }

    private fun publishCoreSample(
        ws: RemoteWsServer,
        samples: Map<String, com.cmts.mobile.Sample>,
        gps: com.cmts.mobile.Sample?,
        accel: com.cmts.mobile.Sample?,
        gyro: com.cmts.mobile.Sample?,
        measureNanos: Long?,
    ) {
        val idStore = store ?: return

        // IMU-driven: measureTimeMs follows sensor nanos, not GNSS 1 Hz.
        val measureTimeMs = measureNanos?.let { elapsedToUnixMs(it) }

        val payload = RemoteSamplePayload.build(
            seq = seq.getAndIncrement(),
            measureTimeMs = measureTimeMs,
            receiveTimeMs = System.currentTimeMillis(),
            vehicleLabel = idStore.deviceLabel,
            samples = samples,
            gps = gps,
            accel = accel,
            gyro = gyro,
            mountZero = Live.mountZero,
        )

        val msg = RemoteProtocol.envelope(
            type = "stream.sample",
            deviceId = idStore.deviceId,
            payload = payload,
        )
        ws.broadcastSample(msg.toString())
    }

    private fun elapsedToUnixMs(sampleElapsedNanos: Long): Long {
        val deltaMs = (sampleElapsedNanos - elapsedAnchorNanos) / 1_000_000L
        return wallAnchorMs + deltaMs
    }

    private fun maybePushStatus() {
        val now = System.currentTimeMillis()
        if (now - lastStatusEmitMs < 1000L) return
        lastStatusEmitMs = now
        pushDeviceStatus()
    }

    fun pushDeviceStatus() {
        val idStore = store ?: return
        val ws = server ?: return
        val snap = if (Live.remoteSamples.isNotEmpty()) Live.remoteSamples else Live.samples
        val sat = snap["phone.satellites"]
        val gps = snap["phone.gps"]
        val payload = JSONObject()
            .put("recordState", RecordingStateMachine.snapshot().name)
            .put("batteryPct", JSONObject.NULL)
            .put("gnssFix", gps != null && gps.values.getOrNull(0) != null)
            .put(
                "satellitesVisible",
                sat?.values?.getOrNull(0)?.let { it } ?: JSONObject.NULL,
            )
            .put(
                "satellitesUsed",
                sat?.values?.getOrNull(1)?.let { it } ?: JSONObject.NULL,
            )
            .put("rtk", JSONObject.NULL)
            .put(
                "storageFreeMb",
                appContext?.filesDir?.usableSpace?.div(1024 * 1024) ?: JSONObject.NULL,
            )
            .put("serviceAlive", Live.serviceAlive)
            .put("running", Live.running)
        ws.broadcastControl(
            RemoteProtocol.envelope("device.status", idStore.deviceId, payload).toString(),
        )
    }

    private fun handleText(client: RemoteWsServer.Client, text: String) {
        val msg = try {
            RemoteProtocol.parse(text)
        } catch (_: Exception) {
            return
        }
        val type = RemoteProtocol.typeOf(msg)
        val id = RemoteProtocol.idOf(msg)
        val payload = RemoteProtocol.payloadOf(msg)
        val idStore = store ?: return
        val ctx = appContext ?: return

        when (type) {
            "device.discover" -> {
                if (msg.optInt("v", 0) != 1) return
                client.enqueueControl(RemoteProtocol.envelope("device.discover", idStore.deviceId,
                    JSONObject().put("discoveryVersion", 1).put("deviceType", "cmts-android")
                        .put("deviceLabel", idStore.deviceLabel).put("deviceModel", idStore.deviceModel).put("protoMin", 1).put("protoMax", 1)
                        .put("port", RemoteIdentityStore.DEFAULT_PORT).put("path", RemoteIdentityStore.WS_PATH)
                        .put("authRequired", false).put("pairingSupported", false), id = id).toString())
            }
            "device.hello" -> handleHello(client, id, payload, idStore)
            "stream.subscribe" -> {
                if (!client.authenticated) {
                    replyError(client, "stream.subscribe", id, "auth_failed", "hello required")
                    return
                }
                val channels = RemoteProtocol.stringList(payload.optJSONArray("channels"))
                    .ifEmpty { listOf("core") }
                client.channels = channels.toSet()
                client.maxHz = RemoteSampleTiming.clampHz(payload.optInt("maxHz", idStore.maxHz))
                client.subscribed = true
                syncRemoteHold()
                client.enqueueControl(
                    RemoteProtocol.envelope(
                        "stream.subscribe",
                        idStore.deviceId,
                        JSONObject()
                            .put("ok", true)
                            .put("channels", JSONArray(channels))
                            .put("maxHz", client.maxHz),
                        id = id,
                    ).toString(),
                )
            }
            "record.start" -> {
                if (!client.authenticated) return
                main.post {
                    val state = RecordingCommands.start(ctx)
                    sendRecordStatus(client, id, state)
                    pushDeviceStatus()
                }
            }
            "record.stop" -> {
                if (!client.authenticated) return
                main.post {
                    val state = RecordingCommands.stop(ctx)
                    sendRecordStatus(client, id, state)
                    pushDeviceStatus()
                }
            }
            "record.status" -> {
                if (!client.authenticated) return
                sendRecordStatus(client, id, RecordingStateMachine.snapshot())
            }
            "config.get" -> {
                if (!client.authenticated) return
                val values = JSONObject()
                    .put("maxHz", idStore.maxHz)
                    .put("deviceLabel", idStore.deviceLabel).put("deviceModel", idStore.deviceModel)
                    .put("recordMode", "manual")
                    .put("port", RemoteIdentityStore.DEFAULT_PORT)
                    .put("path", RemoteIdentityStore.WS_PATH)
                client.enqueueControl(
                    RemoteProtocol.envelope(
                        "config.get",
                        idStore.deviceId,
                        JSONObject().put("ok", true).put("values", values),
                        id = id,
                    ).toString(),
                )
            }
            "config.set" -> {
                if (!client.authenticated) return
                val values = payload.optJSONObject("values") ?: JSONObject()
                if (values.has("maxHz")) idStore.maxHz = values.optInt("maxHz", idStore.maxHz)
                if (values.has("deviceLabel")) {
                    idStore.deviceLabel = values.optString("deviceLabel", idStore.deviceLabel)
                }
                client.enqueueControl(
                    RemoteProtocol.envelope(
                        "config.set",
                        idStore.deviceId,
                        JSONObject()
                            .put("ok", true)
                            .put(
                                "values",
                                JSONObject()
                                    .put("maxHz", idStore.maxHz)
                                    .put("deviceLabel", idStore.deviceLabel).put("deviceModel", idStore.deviceModel)
                                    .put("recordMode", "manual"),
                            ),
                        id = id,
                    ).toString(),
                )
            }
            else -> Unit
        }
    }

    private fun handleHello(
        client: RemoteWsServer.Client,
        id: String,
        payload: JSONObject,
        idStore: RemoteIdentityStore,
    ) {
        val protoMin = payload.optInt("protoMin", 1)
        val protoMax = payload.optInt("protoMax", 1)
        if (!RemoteHelloPolicy.accepts(protoMin, protoMax)) {
            client.authenticated = false
            client.enqueueControl(
                RemoteProtocol.envelope(
                    "device.hello",
                    idStore.deviceId,
                    JSONObject()
                        .put("ok", false)
                        .put("errorCode", "proto_mismatch")
                        .put("proto", RemoteIdentityStore.PROTO),
                    id = id,
                ).toString(),
            )
            return
        }
        client.authenticated = true
        client.enqueueControl(
            RemoteProtocol.envelope(
                "device.hello",
                idStore.deviceId,
                JSONObject()
                    .put("ok", true)
                    .put("deviceLabel", idStore.deviceLabel).put("deviceModel", idStore.deviceModel)
                    .put("capabilities", JSONArray(listOf("gnss", "imu", "record")))
                    .put("recordState", RecordingStateMachine.snapshot().name)
                    .put("proto", RemoteIdentityStore.PROTO),
                id = id,
            ).toString(),
        )
        pushDeviceStatus()
    }

    private fun sendRecordStatus(client: RemoteWsServer.Client, id: String, state: RecordingState) {
        val idStore = store ?: return
        val p = JSONObject()
        for ((k, v) in RecordingStateMachine.statusPayload()) {
            when (v) {
                null -> p.put(k, JSONObject.NULL)
                else -> p.put(k, v)
            }
        }
        p.put("state", state.name)
        val msg = RemoteProtocol.envelope("record.status", idStore.deviceId, p, id = id).toString()
        client.enqueueControl(msg)
        server?.broadcastControl(msg)
    }

    private fun replyError(
        client: RemoteWsServer.Client,
        type: String,
        id: String,
        code: String,
        message: String,
    ) {
        val idStore = store ?: return
        client.enqueueControl(
            RemoteProtocol.envelope(
                type,
                idStore.deviceId,
                JSONObject().put("ok", false).put("errorCode", code).put("message", message),
                id = id,
            ).toString(),
        )
    }

    private fun syncRemoteHold() {
        val need = server?.hasAuthenticatedSubscriber() == true
        if (need) acquireRemoteHold() else releaseRemoteHold()
    }

    private fun acquireRemoteHold() {
        if (remoteHold) return
        val ctx = appContext ?: return
        remoteHold = true
        main.post {
            ctx.startForegroundService(
                CaptureService.acquireIntent(ctx, CaptureService.CLIENT_REMOTE),
            )
        }
    }

    private fun releaseRemoteHold() {
        if (!remoteHold) return
        val ctx = appContext ?: return
        remoteHold = false
        main.post {
            ctx.startService(CaptureService.releaseIntent(ctx, CaptureService.CLIENT_REMOTE))
        }
    }
}
