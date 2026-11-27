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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

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

    var relayUrl: String = ""

    fun start() {
        if (server != null) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
        server = socket
        endpoint = "${lanAddress() ?: "127.0.0.1"}:${socket.localPort}"
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

    private suspend fun acceptLoop(socket: ServerSocket) {
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
                    if (body.isNotBlank()) _messages.tryEmit(path to body)
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    clientSocket.getOutputStream().write(response.toByteArray())
                    clientSocket.getOutputStream().flush()
                }
            }
        }
    }

    private suspend fun discoveryLoop(socket: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }
            val text = String(packet.data, 0, packet.length)
            if (text.isNotBlank()) _messages.tryEmit(DISCOVERY_PATH to text)
        }
    }

    private fun lanAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { address ->
            !address.isLoopbackAddress && address is InetAddress && address.isSiteLocalAddress
        }?.hostAddress

    private companion object {
        const val DISCOVERY_PORT = 8788
        const val DISCOVERY_PATH = "/discovery"
        val JSON = "application/json".toMediaType()
    }
}
