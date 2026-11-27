package eu.kanade.tachiyomi.util.devicesync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The joiner identifies itself when collecting replies queued by the host. */
@Serializable
internal data class DevicePollRequest(
    val deviceId: String,
    val ackIds: List<String> = emptyList(),
)

/** One ciphertext-only reply. The plaintext never leaves [DeviceSyncEngine]. */
@Serializable
internal data class DeviceQueuedMessage(
    val id: String,
    val path: String,
    val body: String,
    val createdAt: Long = 0L,
)

@Serializable
internal data class DevicePollResponse(
    val messages: List<DeviceQueuedMessage> = emptyList(),
)

private fun DevicePollRequest.toJson(): String = deviceSyncJson.encodeToString(this)
private fun DevicePollResponse.toJson(): String = deviceSyncJson.encodeToString(this)
private fun String.toDevicePollRequest(): DevicePollRequest = deviceSyncJson.decodeFromString(this)

/** WSA/emulators share the PC's IP; other devices cannot open a connection into them. */
object DeviceSyncNat {
    fun isVirtualAndroid(manufacturer: String, model: String, brand: String): Boolean =
        listOf(manufacturer, model, brand).any { value ->
            value.contains("microsoft", ignoreCase = true) ||
                value.contains("subsystem for android", ignoreCase = true) ||
                value.contains("wsa", ignoreCase = true)
        }
}

/** Small HTTP + UDP bridge. Payloads are already encrypted before they reach this class. */
class DeviceSyncTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _messages = MutableSharedFlow<Pair<String, String>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages = _messages.asSharedFlow()

    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private var jobs: List<Job> = emptyList()

    /** `host:port` of this device on the LAN, or a public URL when a relay is configured. */
    var endpoint: String = ""
        private set

    /** All reachable LAN addresses; the first one alone can be a virtual/VPN adapter. */
    var endpoints: List<String> = emptyList()
        private set

    var relayUrl: String = ""

    private data class ReplyEntry(
        val id: String,
        val dedupeKey: String,
        val path: String,
        val body: String,
        val createdAt: Long,
    )

    private val replyQueue = ConcurrentHashMap<String, MutableList<ReplyEntry>>()

    /**
     * WSA (and some routers) block inbound connections, so CONFIRM/VERIFY_ACK/ACK are stored
     * here and collected by the other device over the connection it opens itself.
     */
    fun enqueueReply(
        deviceId: String,
        path: String,
        body: String,
        dedupeKey: String = "$path:$body",
    ) {
        if (deviceId.isBlank() || body.isBlank()) return
        val now = System.currentTimeMillis()
        val bucket = replyQueue.computeIfAbsent(deviceId) { mutableListOf() }
        synchronized(bucket) {
            bucket.removeAll { now - it.createdAt > REPLY_TTL_MS }
            if (bucket.none { it.dedupeKey == dedupeKey }) {
                bucket += ReplyEntry(UUID.randomUUID().toString(), dedupeKey, path, body, now)
            }
            while (bucket.size > MAX_REPLIES_PER_DEVICE) bucket.removeAt(0)
        }
    }

    private fun takeReplies(request: DevicePollRequest): List<ReplyEntry> {
        val bucket = replyQueue[request.deviceId] ?: return emptyList()
        return synchronized(bucket) {
            val now = System.currentTimeMillis()
            bucket.removeAll { it.id in request.ackIds || now - it.createdAt > REPLY_TTL_MS }
            bucket.take(MAX_REPLIES_PER_POLL)
        }
    }

    /** Collect replies addressed to [deviceId]; only ciphertext travels back. */
    internal suspend fun poll(
        endpoint: String,
        deviceId: String,
        ackIds: List<String> = emptyList(),
    ): Result<List<DeviceQueuedMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (endpoint.startsWith("http")) "$endpoint$POLL_PATH" else "http://$endpoint$POLL_PATH"
            val request = Request.Builder()
                .url(url)
                .post(DevicePollRequest(deviceId, ackIds).toJson().toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Peer replied ${response.code}" }
                deviceSyncJson.decodeFromString<DevicePollResponse>(
                    response.body?.string().orEmpty(),
                ).messages
            }
        }
    }

    fun start() {
        if (server != null) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        server = socket
        val addresses = lanAddresses()
        endpoints = addresses.map { "$it:${socket.localPort}" }
        endpoint = endpoints.firstOrNull() ?: "127.0.0.1:${socket.localPort}"
        val udp = runCatching {
            DatagramSocket(DISCOVERY_PORT).apply {
                reuseAddress = true
                broadcast = true
            }
        }.getOrNull()
        discovery = udp
        jobs = listOfNotNull(
            scope.launch { acceptLoop(socket) },
            udp?.let { scope.launch { discoveryLoop(it) } },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        runCatching { server?.close() }
        runCatching { discovery?.close() }
        server = null
        discovery = null
        replyQueue.clear()
    }

    suspend fun broadcast(message: DevicePairingMessage) = withContext(Dispatchers.IO) {
        val data = message.toJson().toByteArray()
        val socket = discovery ?: return@withContext
        runCatching {
            val packet = DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
            socket.send(packet)
        }
    }

    suspend fun send(target: String, path: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (target.startsWith("http")) "$target$path" else "http://$target$path"
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Peer replied ${response.code}" }
            }
        }
    }

    /** Relay endpoints are simple mailboxes; only ciphertext is stored there. */
    suspend fun relaySend(code: String, path: String, body: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(relayUrl.isNotBlank()) { "No relay configured" }
            val url = "${relayUrl.trimEnd('/')}$path/$code"
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Relay replied ${response.code}" }
            }
        }
    }

    suspend fun relayPoll(code: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(relayUrl.isNotBlank()) { "No relay configured" }
            val url = "${relayUrl.trimEnd('/')}$path/$code"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "Relay replied ${response.code}" }
                val text = response.body?.string().orEmpty()
                deviceSyncJson.decodeFromString<List<String>>(text)
            }
        }
    }

    private suspend fun CoroutineScope.acceptLoop(socket: ServerSocket) {
        while (isActive) {
            val connection = runCatching { socket.accept() }.getOrNull() ?: return
            runCatching {
                connection.use { clientSocket ->
                    val input = clientSocket.getInputStream().bufferedReader()
                    val requestLine = input.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val body = CharArray(length).let { chars ->
                        var read = 0
                        while (read < length) {
                            val count = input.read(chars, read, length - read)
                            if (count < 0) break
                            read += count
                        }
                        String(chars, 0, read)
                    }
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    if (path == POLL_PATH) {
                        // A poll is a transport concern, never a pairing/sync message.
                        val poll = runCatching { body.toDevicePollRequest() }.getOrNull()
                        val response = if (poll == null || poll.deviceId.isBlank()) {
                            DevicePollResponse()
                        } else {
                            DevicePollResponse(
                                messages = takeReplies(poll).map {
                                    DeviceQueuedMessage(it.id, it.path, it.body, it.createdAt)
                                },
                            )
                        }
                        writeResponse(clientSocket, response.toJson())
                    } else {
                        if (body.isNotBlank()) _messages.tryEmit(path to body)
                        writeResponse(clientSocket, "ok")
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.discoveryLoop(socket: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }
            val text = String(packet.data, 0, packet.length)
            if (text.isNotBlank()) _messages.tryEmit(DISCOVERY_PATH to text)
        }
    }

    private fun writeResponse(socket: Socket, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }

    private fun lanAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filter { address ->
            !address.isLoopbackAddress && address is InetAddress && address.isSiteLocalAddress
        }
        .map { it.hostAddress }
        .distinct()

    private companion object {
        const val DISCOVERY_PORT = 8788
        const val DISCOVERY_PATH = "/discovery"
        const val POLL_PATH = "/poll"
        const val REPLY_TTL_MS = 5 * 60_000L
        const val MAX_REPLIES_PER_DEVICE = 32
        const val MAX_REPLIES_PER_POLL = 16
        val JSON = "application/json".toMediaType()
    }
}
