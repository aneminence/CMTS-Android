package com.cmts.mobile.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal RFC6455 WebSocket server for [/cmts/v1].
 * Control frames are queued ahead of sample frames; sample queue drops oldest.
 */
class RemoteWsServer(
    private val port: Int,
    private val path: String,
    private val onText: (Client, String) -> Unit,
    private val onOpen: (Client) -> Unit = {},
    private val onClose: (Client) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "cmts-ws-client").apply { isDaemon = true }
    }
    private val clients = ConcurrentHashMap.newKeySet<Client>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port)
        ss.reuseAddress = true
        server = ss
        acceptThread = Thread({
            try {
                while (running.get()) {
                    val socket = try {
                        ss.accept()
                    } catch (_: IOException) {
                        break
                    }
                    pool.execute { handleSocket(socket) }
                }
            } finally {
                running.set(false)
            }
        }, "cmts-ws-accept").also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "WS listening on 0.0.0.0:$port$path")
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        clients.toList().forEach { it.closeQuietly() }
        clients.clear()
        pool.shutdownNow()
        acceptThread = null
        server = null
    }

    fun clientCount(): Int = clients.size

    fun broadcastControl(text: String) {
        clients.forEach { it.enqueueControl(text) }
    }

    fun broadcastSample(text: String) {
        clients.forEach { it.enqueueSample(text) }
    }

    fun hasAuthenticatedSubscriber(): Boolean =
        clients.any { it.authenticated && it.subscribed }

    /** Highest maxHz among authenticated subscribers; [defaultHz] if none yet. */
    fun maxSubscriberHz(defaultHz: Int): Int {
        var hz: Int? = null
        clients.forEach { c ->
            if (c.authenticated && c.subscribed) {
                hz = if (hz == null) c.maxHz else maxOf(hz!!, c.maxHz)
            }
        }
        return hz ?: defaultHz
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            if (!handshake(input, output)) {
                socket.close()
                return
            }
            val client = Client(socket, output)
            clients.add(client)
            onOpen(client)
            client.startWriter()
            try {
                while (running.get() && !socket.isClosed) {
                    val frame = readFrame(input) ?: break
                    when (frame.opcode) {
                        0x1 -> onText(client, String(frame.payload, StandardCharsets.UTF_8))
                        0x8 -> break
                        0x9 -> client.enqueueRaw(buildFrame(0xA, frame.payload))
                        0xA -> Unit
                        else -> Unit
                    }
                }
            } finally {
                clients.remove(client)
                client.closeQuietly()
                onClose(client)
            }
        } catch (e: Exception) {
            Log.w(TAG, "client error: ${e.message}")
            runCatching { socket.close() }
        }
    }

    private fun handshake(input: InputStream, output: OutputStream): Boolean {
        val lines = mutableListOf<String>()
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return false
            if (b == '\n'.code) {
                val line = buf.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
                buf.reset()
                if (line.isEmpty()) break
                lines.add(line)
            } else {
                buf.write(b)
            }
        }
        if (lines.isEmpty()) return false
        val parts = lines[0].split(" ")
        if (parts.size < 2 || parts[0] != "GET") return false
        val reqPath = parts[1].substringBefore('?')
        if (reqPath != path) {
            val body = "Not Found"
            output.write(
                "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    .toByteArray(StandardCharsets.UTF_8),
            )
            output.flush()
            return false
        }
        val headers = lines.drop(1).associate {
            val i = it.indexOf(':')
            if (i < 0) "" to ""
            else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }
        val key = headers["sec-websocket-key"] ?: return false
        if (!headers["upgrade"].equals("websocket", true)) return false
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + GUID).toByteArray(StandardCharsets.UTF_8)),
        )
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(resp.toByteArray(StandardCharsets.UTF_8))
        output.flush()
        return true
    }

    class Client internal constructor(
        private val socket: Socket,
        private val output: BufferedOutputStream,
    ) {
        @Volatile var authenticated: Boolean = false
        @Volatile var subscribed: Boolean = false
        @Volatile var maxHz: Int = RemoteIdentityStore.DEFAULT_MAX_HZ
        @Volatile var channels: Set<String> = setOf("core")
        private val controlQ = LinkedBlockingDeque<ByteArray>(64)
        private val sampleQ = LinkedBlockingDeque<ByteArray>(8)
        private val writerStarted = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        fun enqueueControl(text: String) {
            val bytes = buildFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))
            if (!controlQ.offer(bytes)) {
                controlQ.pollFirst()
                controlQ.offer(bytes)
            }
        }

        fun enqueueSample(text: String) {
            if (!subscribed || !authenticated) return
            val bytes = buildFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))
            if (!sampleQ.offer(bytes)) {
                sampleQ.pollFirst()
                sampleQ.offer(bytes)
            }
        }

        fun enqueueRaw(bytes: ByteArray) {
            controlQ.offer(bytes)
        }

        fun startWriter() {
            if (!writerStarted.compareAndSet(false, true)) return
            Thread({
                try {
                    while (!closed.get() && !socket.isClosed) {
                        val next = controlQ.poll() ?: sampleQ.poll()
                        if (next == null) {
                            Thread.sleep(2)
                            continue
                        }
                        synchronized(output) {
                            output.write(next)
                            output.flush()
                        }
                    }
                } catch (_: Exception) {
                    closeQuietly()
                }
            }, "cmts-ws-writer").also {
                it.isDaemon = true
                it.start()
            }
        }

        fun closeQuietly() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "CmtsRemoteWs"
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        internal fun buildFrame(opcode: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val header = when {
                len < 126 -> byteArrayOf((0x80 or opcode).toByte(), len.toByte())
                len < 65536 -> byteArrayOf(
                    (0x80 or opcode).toByte(),
                    126,
                    ((len shr 8) and 0xff).toByte(),
                    (len and 0xff).toByte(),
                )
                else -> error("frame too large")
            }
            return header + payload
        }

        private fun readFrame(input: InputStream): WsFrame? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) return null
            val opcode = b0 and 0x0f
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7f).toLong()
            when (len) {
                126L -> {
                    val b2 = input.read()
                    val b3 = input.read()
                    if (b2 < 0 || b3 < 0) return null
                    len = ((b2 shl 8) or b3).toLong()
                }
                127L -> {
                    var v = 0L
                    repeat(8) {
                        val b = input.read()
                        if (b < 0) return null
                        v = (v shl 8) or b.toLong()
                    }
                    len = v
                }
            }
            if (len > 1_000_000L) return null
            val mask = if (masked) {
                val m = ByteArray(4)
                if (!readFully(input, m)) return null
                m
            } else {
                null
            }
            val payload = ByteArray(len.toInt())
            if (!readFully(input, payload)) return null
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return WsFrame(opcode, payload)
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }

        private data class WsFrame(val opcode: Int, val payload: ByteArray)
    }
}
