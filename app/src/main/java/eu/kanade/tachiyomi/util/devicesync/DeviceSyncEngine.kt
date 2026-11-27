package eu.kanade.tachiyomi.util.devicesync

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

enum class DeviceSyncPhase {
    IDLE,
    HOST_WAITING,
    HOST_PASSWORD_READY,
    JOIN_WAITING_PASSWORD,
    JOIN_WAITING_ACK,
    PAIRED,
}

data class DeviceSyncUiState(
    val phase: DeviceSyncPhase = DeviceSyncPhase.IDLE,
    val deviceName: String = "",
    val deviceModel: String = "",
    val code: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val endpoint: String = "",
    val endpoints: List<String> = emptyList(),
    val relayUrl: String = "",
    val candidates: List<DevicePairingMessage> = emptyList(),
    val confirmedCandidates: Set<String> = emptySet(),
    val joinConfirmed: Boolean = false,
    val peers: List<DeviceSyncPeer> = emptyList(),
    val syncDeviceSettings: Boolean = false,
    val pending: Int = 0,
    val lastSyncAt: Long = 0L,
    val error: String? = null,
)

/** Owns the pairing state machine, the encrypted outbox and the ACK lifecycle. */
object DeviceSyncEngine {

    private const val NO_PATH_ERROR =
        "No se pudo contactar con el otro dispositivo. Comprueba que ambos estén en la misma Wi-Fi " +
            "(sin aislamiento de punto de acceso) o configura un servidor relay."

    private val transport = DeviceSyncTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state = _state.asStateFlow()

    private var store: DeviceSyncStore? = null
    private var key: SecretKeySpec? = null
    private var config = DeviceSyncConfig()
    private var joinTarget: DevicePairingMessage? = null
    private var joinEndpoints: List<String> = emptyList()
    private var pendingVerify: DeviceSyncMessage? = null
    private var confirmedCandidates: Set<String> = emptySet()
    private var backgroundJobs: List<Job> = emptyList()
    private var seq = 0L
    private var collector: Job? = null
    private val scheduled = AtomicBoolean(false)

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (store == null) store = DeviceSyncStore(context)
        val id = requireStore().deviceId()
        config = requireStore().loadConfig()
        if (config.deviceId != id) {
            config = config.copy(deviceId = id)
            requireStore().saveConfig(config)
        }
        _state.value = _state.value.copy(
            deviceName = Build.MODEL,
            deviceModel = Build.MODEL,
            peers = config.peers,
            syncDeviceSettings = config.syncDeviceSettings,
            relayUrl = config.relayUrl,
            pending = requireStore().pendingCount(),
            error = null,
        )
    }

    suspend fun startHost(context: Context, relayUrl: String = "") {
        init(context)
        stop()
        config = config.copy(
            code = DevicePairing.newCode(),
            password = DevicePairing.newPassword(),
            relayUrl = relayUrl.trim(),
        )
        requireStore().saveConfig(config)
        key = DeviceSyncCrypto.deriveKey(config.code, config.password)
        transport.relayUrl = config.relayUrl
        transport.start()
        joinTarget = null
        confirmedCandidates = emptySet()
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.HOST_WAITING,
            code = config.code,
            password = config.password,
            passwordVisible = false,
            endpoint = transport.endpoint,
            endpoints = transport.endpoints,
            relayUrl = config.relayUrl,
            candidates = emptyList(),
            confirmedCandidates = emptySet(),
            joinConfirmed = false,
            error = null,
        )
        watchMessages()
        backgroundJobs = listOf(
            scope.launch {
                while (isActive) {
                    transport.broadcast(
                        DevicePairingMessage(
                            type = "OFFER",
                            from = config.deviceId,
                            deviceName = Build.MODEL,
                            deviceModel = Build.MODEL,
                            endpoint = transport.endpoint,
                            endpoints = transport.endpoints,
                            code = config.code,
                            relayUrl = config.relayUrl,
                        ),
                    )
                    delay(3_000L)
                }
            },
            scope.launch {
                while (isActive) {
                    pollRelay()
                    delay(3_000L)
                }
            },
        )
    }

    suspend fun startJoin(context: Context, target: DevicePairingMessage) {
        startJoinWithInvite(
            context,
            DevicePairingQr(
                code = target.code,
                name = target.deviceName,
                model = target.deviceModel,
                endpoint = target.endpoint,
                endpoints = target.endpoints,
                relayUrl = target.relayUrl,
            ),
        )
    }

    /**
     * The second device only pastes the link/QR payload shown by the first one.
     * Confirmation is sent straight to the endpoint (or relay) inside the link,
     * so it no longer depends on LAN broadcasts reaching the other device.
     */
    suspend fun startJoinWithInvite(context: Context, invite: DevicePairingQr) {
        init(context)
        stop()
        // Use only the relay carried by the invite: a stale local relay would send the
        // confirmation to a mailbox the host is not polling.
        transport.relayUrl = invite.relayUrl
        transport.start()
        joinEndpoints = (invite.endpoints + invite.endpoint)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        joinTarget = DevicePairingMessage(
            type = "OFFER",
            from = "",
            deviceName = invite.name,
            deviceModel = invite.model,
            endpoint = invite.endpoint,
            endpoints = joinEndpoints,
            code = invite.code,
            relayUrl = invite.relayUrl,
        )
        key = null
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.JOIN_WAITING_PASSWORD,
            code = invite.code,
            password = "",
            passwordVisible = false,
            endpoint = transport.endpoint,
            endpoints = joinEndpoints,
            relayUrl = transport.relayUrl,
            candidates = emptyList(),
            confirmedCandidates = emptySet(),
            joinConfirmed = false,
            error = null,
        )
        watchMessages()
        val target = requireNotNull(joinTarget)
        sendPairing(target, "CODE_CONFIRM")
        // The invite endpoint can be unreachable (different networks, virtual adapters, sleep).
        // Keep asking until the host confirms, and keep polling the relay for CONFIRM/VERIFY_ACK:
        // the joiner previously only polled on LAN and stayed stuck when a relay was used.
        backgroundJobs += scope.launch {
            while (isActive &&
                (
                    _state.value.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD ||
                        _state.value.phase == DeviceSyncPhase.JOIN_WAITING_ACK
                    )
            ) {
                delay(3_000L)
                if (_state.value.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD &&
                    !_state.value.joinConfirmed
                ) {
                    sendPairing(target, "CODE_CONFIRM")
                }
                // A wrong/unreachable endpoint on the first try must not make the manually
                // entered password useless: keep retrying the encrypted VERIFY as well.
                if (_state.value.phase == DeviceSyncPhase.JOIN_WAITING_ACK) {
                    pendingVerify?.let { sendEncrypted(target, it) }
                }
                if (transport.relayUrl.isNotBlank()) pollRelay()
            }
        }
    }

    /** The first device reveals the password only after confirming at least one device. */
    fun confirmCandidates(ids: Set<String>) {
        confirmedCandidates = ids
        _state.value = _state.value.copy(
            phase = if (ids.isEmpty()) DeviceSyncPhase.HOST_WAITING else DeviceSyncPhase.HOST_PASSWORD_READY,
            passwordVisible = ids.isNotEmpty(),
            confirmedCandidates = ids,
        )
        ids.forEach { id ->
            val candidate = _state.value.candidates.firstOrNull { it.from == id } ?: return@forEach
            scope.launch { sendPairing(candidate.copy(to = id, confirmed = true), "CONFIRM") }
        }
    }

    /** Fallback for when the join request never arrives: the password is the real secret. */
    fun revealPassword() {
        if (_state.value.phase != DeviceSyncPhase.HOST_WAITING) return
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.HOST_PASSWORD_READY,
            passwordVisible = true,
        )
    }

    suspend fun submitPassword(password: String) {
        val target = joinTarget ?: return
        val normalized = password.trim().uppercase()
        if (normalized.length != 10 || normalized.any { !DevicePairing.isPasswordChar(it) }) {
            _state.value = _state.value.copy(
                error = "La contraseña debe tener 10 letras o números (sin 0, 1, I ni O)",
            )
            return
        }
        key = DeviceSyncCrypto.deriveKey(target.code, normalized)
        val message = DeviceSyncMessage(
            type = "VERIFY",
            from = config.deviceId,
            deviceName = Build.MODEL,
            deviceModel = Build.MODEL,
            endpoint = transport.endpoint,
            endpoints = transport.endpoints,
        )
        pendingVerify = message
        val delivered = sendEncrypted(target, message)
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.JOIN_WAITING_ACK,
            error = if (delivered) null else NO_PATH_ERROR,
        )
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val activeKey = key ?: return@withLock
            val peers = requireStore().loadConfig().peers
            if (peers.isEmpty()) return@withLock
            val events = requireStore().outboxEvents()
            peers.forEach { peer ->
                val message = DeviceSyncMessage(
                    type = if (events.isEmpty()) "HEARTBEAT" else "EVENTS",
                    from = config.deviceId,
                    to = peer.deviceId,
                    endpoint = transport.endpoint,
                    endpoints = transport.endpoints,
                    events = events,
                    sentAt = System.currentTimeMillis(),
                )
                sendEncrypted(peer.toPairingTarget(), message)
            }
            _state.value = _state.value.copy(
                peers = peers,
                pending = events.size,
                lastSyncAt = System.currentTimeMillis(),
                error = null,
            )
        }
    }

    /** Coalesced entry point used by library/category observers in [android.app.Application]. */
    fun requestLocalChange(context: Context) {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(350L)
                init(context)
                recordLocalChanges()
            } finally {
                scheduled.set(false)
            }
        }
    }

    /** Diffs the local library against the last snapshot and queues only changed entries. */
    suspend fun recordLocalChanges() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (requireStore().loadConfig().peers.isEmpty()) return@withLock
            val snapshot = DeviceSyncSnapshotBuilder(requireStore(), config.deviceId).build()
            val hashes = requireStore().loadHashes().toMutableMap()
            val liveKeys = snapshot.entries.map { it.key }.toSet()
            var changed = false

            snapshot.entries.forEach { entry ->
                val fingerprint = requireStore().fingerprint(entry)
                if (hashes[entry.key] != fingerprint) {
                    requireStore().enqueue(
                        DeviceSyncEvent(
                            opId = UUID.randomUUID().toString(),
                            author = config.deviceId,
                            seq = ++seq,
                            createdAt = System.currentTimeMillis(),
                            entry = entry,
                        ),
                    )
                    hashes[entry.key] = fingerprint
                    changed = true
                }
            }
            hashes.keys.minus(liveKeys).forEach { key ->
                val parts = key.split(":", limit = 3)
                if (parts.size != 3) return@forEach
                requireStore().enqueue(
                    DeviceSyncEvent(
                        opId = UUID.randomUUID().toString(),
                        author = config.deviceId,
                        seq = ++seq,
                        createdAt = System.currentTimeMillis(),
                        entry = DeviceSyncEntry(
                            key = key,
                            kind = parts[0],
                            sourceId = parts[1].toLongOrNull() ?: 0L,
                            url = parts[2],
                            deleted = true,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    ),
                )
                hashes.remove(key)
                changed = true
            }
            if (changed) requireStore().saveHashes(hashes)

            val now = System.currentTimeMillis()
            val (filters, settingHashes) = DeviceSyncSettings.collect(
                requireStore().loadSettingHashes(),
                now,
                config.syncDeviceSettings,
            )
            filters.forEach { filter ->
                requireStore().enqueue(
                    DeviceSyncEvent(
                        opId = UUID.randomUUID().toString(),
                        author = config.deviceId,
                        seq = ++seq,
                        createdAt = now,
                        filter = filter,
                    ),
                )
            }
            if (filters.isNotEmpty()) requireStore().saveSettingHashes(settingHashes)
            _state.value = _state.value.copy(pending = requireStore().pendingCount())
        }
        syncNow()
    }

    /** Device-specific settings sync is per device and off by default. */
    suspend fun setSyncDeviceSettings(context: Context, enabled: Boolean) {
        init(context)
        config = config.copy(syncDeviceSettings = enabled)
        requireStore().saveConfig(config)
        _state.value = _state.value.copy(syncDeviceSettings = enabled)
        requireStore().saveSettingHashes(emptyMap())
        recordLocalChanges()
    }

    suspend fun forgetPeer(peerId: String) {
        requireStore().forgetPeer(peerId)
        config = requireStore().loadConfig()
        _state.value = _state.value.copy(peers = config.peers)
    }

    fun stop() {
        backgroundJobs.forEach { it.cancel() }
        backgroundJobs = emptyList()
        collector?.cancel()
        collector = null
        joinEndpoints = emptyList()
        pendingVerify = null
        transport.stop()
        if (_state.value.phase != DeviceSyncPhase.IDLE) {
            _state.value = _state.value.copy(phase = DeviceSyncPhase.PAIRED)
        }
    }

    private fun watchMessages() {
        collector?.cancel()
        collector = scope.launch {
            transport.messages.collect { (path, body) ->
                runCatching { handleMessage(path, body) }
                    .onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    private suspend fun handleMessage(path: String, body: String) {
        when (path) {
            "/discovery", "/pairing" -> {
                val message = runCatching { body.toDevicePairingMessage() }.getOrNull() ?: return
                if (message.from == config.deviceId) return
                when (message.type) {
                    "OFFER" -> {
                        if (
                            _state.value.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD &&
                            message.code == joinTarget?.code
                        ) {
                            if (joinTarget?.endpoint.isNullOrBlank()) joinTarget = message
                            // A bare 8-digit code has no endpoint; a broadcast OFFER supplies one,
                            // possibly with more addresses than the QR carried.
                            val merged = (joinEndpoints + message.endpoints + message.endpoint)
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()
                            if (merged.isNotEmpty()) {
                                joinEndpoints = merged
                                joinTarget = joinTarget?.copy(endpoints = merged)
                                _state.value = _state.value.copy(endpoints = merged)
                            }
                        }
                        val current = _state.value.candidates
                        if (current.none { it.from == message.from && it.code == message.code }) {
                            _state.value = _state.value.copy(candidates = current + message)
                        }
                    }
                    "CODE_CONFIRM" -> {
                        if (message.code != config.code) return
                        if (message.to.isNotBlank() && message.to != config.deviceId) return
                        val current = _state.value.candidates
                        if (current.none { it.from == message.from }) {
                            val updated = current + message
                            // First requester is confirmed automatically so the password appears
                            // without an extra tap; further devices can still be selected in the UI.
                            val auto = _state.value.phase == DeviceSyncPhase.HOST_WAITING &&
                                _state.value.confirmedCandidates.isEmpty()
                            val confirmed = if (auto) setOf(message.from) else _state.value.confirmedCandidates
                            confirmedCandidates = confirmed
                            _state.value = _state.value.copy(
                                candidates = updated,
                                confirmedCandidates = confirmed,
                                phase = if (auto) DeviceSyncPhase.HOST_PASSWORD_READY else _state.value.phase,
                                passwordVisible = auto || _state.value.passwordVisible,
                            )
                            if (auto) {
                                sendPairing(message.copy(to = message.from, confirmed = true), "CONFIRM")
                            }
                        }
                    }
                    "CONFIRM" -> {
                        if (message.code != joinTarget?.code) return
                        if (message.to.isNotBlank() && message.to != config.deviceId) return
                        _state.value = _state.value.copy(joinConfirmed = true, error = null)
                    }
                }
            }
            "/sync" -> {
                val envelope = runCatching { body.toDeviceSyncEnvelope() }.getOrNull() ?: return
                if (envelope.from == config.deviceId) return
                val activeKey = key ?: return
                val message = runCatching {
                    DeviceSyncCrypto.decrypt(envelope.iv, envelope.ciphertext, activeKey)
                        .toDeviceSyncMessage()
                }.getOrNull() ?: return
                when (message.type) {
                    "VERIFY" -> {
                        // Reaching here means the payload decrypted with the code + password.
                        // That proves possession of the secret, so a separate CODE_CONFIRM/CONFIRM
                        // round trip is no longer required to trust the device. Delivery of those
                        // messages is best effort and can fail on real networks.
                        val peers = requireStore().loadConfig().peers.toMutableList()
                        if (peers.none { it.deviceId == message.from }) {
                            peers += DeviceSyncPeer(
                                deviceId = message.from,
                                name = message.deviceName,
                                model = message.deviceModel,
                                endpoint = message.endpoint,
                                endpoints = message.endpoints,
                                code = config.code,
                                trustedAt = System.currentTimeMillis(),
                            )
                            config = config.copy(peers = peers)
                            requireStore().saveConfig(config)
                        }
                        sendEncrypted(
                            DeviceSyncPeer(
                                deviceId = message.from,
                                name = message.deviceName,
                                model = message.deviceModel,
                                endpoint = message.endpoint,
                                endpoints = message.endpoints,
                                code = config.code,
                            ).toPairingTarget(),
                            DeviceSyncMessage(
                                type = "VERIFY_ACK",
                                from = config.deviceId,
                                to = message.from,
                                endpoint = transport.endpoint,
                                endpoints = transport.endpoints,
                            ),
                        )
                        _state.value = _state.value.copy(
                            phase = DeviceSyncPhase.PAIRED,
                            peers = peers,
                        )
                        recordLocalChanges()
                    }
                    "VERIFY_ACK" -> {
                        pendingVerify = null
                        val peers = requireStore().loadConfig().peers.toMutableList()
                        if (peers.none { it.deviceId == message.from }) {
                            peers += DeviceSyncPeer(
                                deviceId = message.from,
                                name = message.deviceName,
                                model = message.deviceModel,
                                endpoint = message.endpoint.ifBlank { joinTarget?.endpoint.orEmpty() },
                                endpoints = message.endpoints.ifEmpty { joinTarget?.endpoints.orEmpty() },
                                code = config.code,
                                trustedAt = System.currentTimeMillis(),
                            )
                            config = config.copy(peers = peers)
                            requireStore().saveConfig(config)
                        }
                        _state.value = _state.value.copy(
                            phase = DeviceSyncPhase.PAIRED,
                            peers = peers,
                        )
                        recordLocalChanges()
                    }
                    "EVENTS" -> {
                        val applier = DeviceSyncSnapshotApplier(requireStore())
                        message.events.forEach { event ->
                            event.entry?.let { applier.apply(it) }
                            event.filter?.let { filter ->
                                // Filters are LWW by key and use the same applied cursor as entries.
                                val applied = requireStore().loadApplied().toMutableMap()
                                if (filter.updatedAt > (applied[filter.key] ?: 0L)) {
                                    DeviceSyncSettings.apply(filter, config.syncDeviceSettings)
                                    applied[filter.key] = filter.updatedAt
                                    requireStore().saveApplied(applied)
                                }
                            }
                            event.snapshot?.entries?.forEach { applier.apply(it) }
                        }
                        requireStore().acknowledge(message.from, message.events.map { it.opId })
                        sendEncrypted(
                            message.toDeviceSyncPeer().toPairingTarget(),
                            DeviceSyncMessage(
                                type = "ACK",
                                from = config.deviceId,
                                to = message.from,
                                endpoint = transport.endpoint,
                                endpoints = transport.endpoints,
                                acks = message.events.map { it.opId },
                            ),
                        )
                    }
                    "ACK" -> requireStore().acknowledge(message.from, message.acks)
                    "HEARTBEAT" -> Unit
                }
            }
        }
    }

    private suspend fun pollRelay() {
        val joining = _state.value.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD ||
            _state.value.phase == DeviceSyncPhase.JOIN_WAITING_ACK
        val code = (if (joining) joinTarget?.code ?: config.code else config.code).orEmpty()
        if (code.isBlank() || transport.relayUrl.isBlank()) return
        listOf("/pairing", "/sync").forEach { path ->
            transport.relayPoll(code, path).getOrNull()?.forEach { body ->
                handleMessage(path, body)
            }
        }
    }

    private suspend fun sendPairing(target: DevicePairingMessage, type: String) {
        val pairing = DevicePairingMessage(
            type = type,
            from = config.deviceId,
            deviceName = Build.MODEL,
            deviceModel = Build.MODEL,
            endpoint = transport.endpoint,
            endpoints = transport.endpoints,
            code = target.code,
            // Carry the relay so the other device replies on the same channel. Without this,
            // a relay-backed pairing could confirm but never complete the handshake.
            relayUrl = target.relayUrl.ifBlank { transport.relayUrl },
            to = target.from,
        )
        val body = pairing.toJson()
        val attempts = mutableListOf<Result<Unit>>()
        val relay = pairing.relayUrl
        if (relay.isNotBlank()) attempts += transport.relaySend(target.code, "/pairing", body)
        endpointsOf(target).forEach { endpoint ->
            if (attempts.lastOrNull()?.isSuccess == true) return@forEach
            attempts += transport.send(endpoint, "/pairing", body)
        }
        if (attempts.any { it.isSuccess }) {
            if (_state.value.error == NO_PATH_ERROR) _state.value = _state.value.copy(error = null)
            return
        }
        // Last chance when a device only allows UDP on the local network.
        transport.broadcast(pairing)
        _state.value = _state.value.copy(error = NO_PATH_ERROR)
    }

    private suspend fun sendEncrypted(target: DevicePairingMessage, message: DeviceSyncMessage): Boolean {
        val activeKey = key ?: return false
        val (iv, ciphertext) = DeviceSyncCrypto.encrypt(message.toJson(), activeKey)
        val envelope = DeviceSyncEnvelope(
            type = message.type,
            from = config.deviceId,
            to = target.from,
            code = target.code,
            iv = iv,
            ciphertext = ciphertext,
            sentAt = System.currentTimeMillis(),
        ).toJson()
        val relay = target.relayUrl.ifBlank { transport.relayUrl }
        val attempts = mutableListOf<Result<Unit>>()
        if (relay.isNotBlank()) attempts += transport.relaySend(target.code, "/sync", envelope)
        endpointsOf(target).forEach { endpoint ->
            if (attempts.lastOrNull()?.isSuccess == true) return@forEach
            attempts += transport.send(endpoint, "/sync", envelope)
        }
        return attempts.any { it.isSuccess }
    }

    /** The QR used to carry a single address; a phone can have Wi-Fi, VPN and hotspot at once. */
    private fun endpointsOf(target: DevicePairingMessage): List<String> =
        (target.endpoints + target.endpoint)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun requireStore(): DeviceSyncStore = store ?: error("Device sync is not initialized")

    private fun DeviceSyncPeer.toPairingTarget() = DevicePairingMessage(
        type = "PEER",
        from = deviceId,
        endpoint = endpoint,
        endpoints = endpoints,
        code = code,
    )

    private fun DeviceSyncMessage.toDeviceSyncPeer() = DeviceSyncPeer(
        deviceId = from,
        name = deviceName,
        model = deviceModel,
        endpoint = endpoint,
        endpoints = endpoints,
        code = config.code,
    )
}
