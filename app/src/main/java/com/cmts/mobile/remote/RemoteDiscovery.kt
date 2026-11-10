package com.cmts.mobile.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Public discovery metadata only; never publishes pairing credentials. */
class RemoteDiscovery(context: Context, private val identity: RemoteIdentityStore) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    @Volatile var status = "未注册"
        private set
    @Volatile private var generation = 0
    private var listener: NsdManager.RegistrationListener? = null
    fun start() {
        if (listener != null) return
        val attempt = ++generation
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { if (attempt == generation) status = "已注册" }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { if (attempt == generation) { status = "注册失败 ($errorCode)，可手动连接"; listener = null } }
            override fun onServiceUnregistered(info: NsdServiceInfo) { if (attempt == generation) status = "已停止" }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { if (attempt == generation) status = "注销失败 ($errorCode)" }
        }
        listener = callback
        status = "注册中"
        val info = NsdServiceInfo().apply {
            serviceName = "CMTS-${identity.deviceId.take(8)}"
            serviceType = "_cmts._tcp."
            port = RemoteIdentityStore.DEFAULT_PORT
            setAttribute("discovery", "1")
            setAttribute("proto", "1")
            setAttribute("deviceId", identity.deviceId)
            setAttribute("label", identity.deviceLabel.take(60))
            setAttribute("model", identity.deviceModel.take(60))
            setAttribute("path", RemoteIdentityStore.WS_PATH)
            setAttribute("type", "cmts-android")
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback) }
            .onFailure { listener = null; status = "注册失败，可手动连接" }
    }
    fun stop() {
        generation++
        listener?.let { runCatching { manager.unregisterService(it) } }
        listener = null
        status = "已停止"
    }
}
