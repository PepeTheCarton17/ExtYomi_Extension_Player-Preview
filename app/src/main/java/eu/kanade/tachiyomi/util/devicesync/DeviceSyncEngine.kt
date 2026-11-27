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

    private val transport = DeviceSyncTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state = _state.asStateFlow()

    private var store: DeviceSyncStore? = null
    private var key: SecretKeySpec? = null
    private var config = DeviceSyncConfig()
    private var joinTarget: DevicePairingMessage? = null
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
        transport.relayUrl = invite.relayUrl.ifBlank { config.relayUrl }
        transport.start()
        joinTarget = DevicePairingMessage(
            type = "OFFER",
            from = "",
            deviceName = invite.name,
            deviceModel = invite.model,
            endpoint = invite.endpoint,
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
            relayUrl = transport.relayUrl,
            candidates = emptyList(),
            confirmedCandidates = emptySet(),
            joinConfirmed = false,
            error = null,
        )
        watchMessages()
        sendPairing(requireNotNull(joinTarget), "CODE_CONFIRM")
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

    suspend fun submitPassword(password: String) {
        val target = joinTarget ?: return
        if (password.length != 10) {
            _state.value = _state.value.copy(error = "La contraseña debe tener 10 letras")
            return
        }
        key = DeviceSyncCrypto.deriveKey(target.code, password.uppercase())
        val message = DeviceSyncMessage(
            type = "VERIFY",
            from = config.deviceId,
            deviceName = Build.MODEL,
            deviceModel = Build.MODEL,
            endpoint = transport.endpoint,
        )
        sendEncrypted(target, message)
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.JOIN_WAITING_ACK,
            error = null,
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
                            joinTarget?.endpoint.isNullOrBlank() &&
                            message.code == joinTarget?.code
                        ) {
                            joinTarget = message
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
                        if (message.from !in confirmedCandidates) return
                        val peers = requireStore().loadConfig().peers.toMutableList()
                        if (peers.none { it.deviceId == message.from }) {
                            peers += DeviceSyncPeer(
                                deviceId = message.from,
                                name = message.deviceName,
                                model = message.deviceModel,
                                endpoint = message.endpoint,
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
                                code = config.code,
                            ).toPairingTarget(),
                            DeviceSyncMessage(
                                type = "VERIFY_ACK",
                                from = config.deviceId,
                                to = message.from,
                                endpoint = transport.endpoint,
                            ),
                        )
                        _state.value = _state.value.copy(
                            phase = DeviceSyncPhase.PAIRED,
                            peers = peers,
                        )
                        recordLocalChanges()
                    }
                    "VERIFY_ACK" -> {
                        val peers = requireStore().loadConfig().peers.toMutableList()
                        if (peers.none { it.deviceId == message.from }) {
                            peers += DeviceSyncPeer(
                                deviceId = message.from,
                                name = message.deviceName,
                                model = message.deviceModel,
                                endpoint = joinTarget?.endpoint.orEmpty(),
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
            code = target.code,
            to = target.from,
        )
        when {
            target.relayUrl.isNotBlank() || transport.relayUrl.isNotBlank() ->
                transport.relaySend(target.code, "/pairing", pairing.toJson())
            target.endpoint.isNotBlank() ->
                transport.send(target.endpoint, "/pairing", pairing.toJson())
            else -> transport.broadcast(pairing)
        }
    }

    private suspend fun sendEncrypted(target: DevicePairingMessage, message: DeviceSyncMessage) {
        val activeKey = key ?: return
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
        if (target.relayUrl.isNotBlank() || transport.relayUrl.isNotBlank()) {
            transport.relaySend(target.code, "/sync", envelope)
        } else {
            transport.send(target.endpoint, "/sync", envelope)
        }
    }

    private fun requireStore(): DeviceSyncStore = store ?: error("Device sync is not initialized")

    private fun DeviceSyncPeer.toPairingTarget() = DevicePairingMessage(
        type = "PEER",
        from = deviceId,
        endpoint = endpoint,
        code = code,
    )

    private fun DeviceSyncMessage.toDeviceSyncPeer() = DeviceSyncPeer(
        deviceId = from,
        name = deviceName,
        model = deviceModel,
        endpoint = endpoint,
        code = config.code,
    )
}
