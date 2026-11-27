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
    val deviceId: String = "",
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
    val natHint: Boolean = false,
    /** Own changes that have not been confirmed by every paired device yet. */
    val outgoingPending: Int = 0,
    /** Remote changes received on this device but not applied yet. */
    val incomingPending: Int = 0,
    val lastSyncAt: Long = 0L,
    val error: String? = null,
)

/** Owns the pairing state machine, the encrypted outbox and the ACK lifecycle. */
object DeviceSyncEngine {

    private const val NO_PATH_ERROR =
        "No se pudo contactar con el otro dispositivo. Comprueba que ambos estén en la misma Wi-Fi " +
            "(sin aislamiento de punto de acceso). Si uno es WSA/emulador, publica desde el teléfono " +
            "y escanea su QR desde WSA; las respuestas se recogen automáticamente."

    // One envelope with thousands of entries tried to allocate ~129 MB in a single char array
    // and crashed the app. Every message below is bounded well under that limit.
    private const val MAX_EVENTS_PER_BATCH = 24
    private const val MAX_CHARS_PER_BATCH = 256_000
    private const val MAX_BATCHES_PER_TICK = 32
    private const val MAX_INBOX_PER_DRAIN = 48
    private const val MAX_ACKS_PER_MESSAGE = 500
    private const val MAX_MESSAGE_CHARS = 400_000
    private const val MAX_ENVELOPE_CHARS = 800_000

    private val transport = DeviceSyncTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val inboxMutex = Mutex()
    private val _state = MutableStateFlow(DeviceSyncUiState())
    val state = _state.asStateFlow()

    private var store: DeviceSyncStore? = null
    private var key: SecretKeySpec? = null
    private var config = DeviceSyncConfig()
    private var joinTarget: DevicePairingMessage? = null
    private var joinEndpoints: List<String> = emptyList()
    private var pendingVerify: DeviceSyncMessage? = null
    private var pollAckIds: Set<String> = emptySet()
    private var confirmedCandidates: Set<String> = emptySet()
    private var backgroundJobs: List<Job> = emptyList()
    private var pollJob: Job? = null
    private var relayJob: Job? = null
    private var syncJob: Job? = null
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
            deviceId = id,
            deviceName = Build.MODEL,
            deviceModel = Build.MODEL,
            peers = config.peers,
            syncDeviceSettings = config.syncDeviceSettings,
            natHint = DeviceSyncNat.isVirtualAndroid(Build.MANUFACTURER, Build.MODEL, Build.BRAND),
            relayUrl = config.relayUrl,
            outgoingPending = requireStore().outgoingPending(),
            incomingPending = requireStore().inboxCount(),
            error = null,
        )
        // The password is stored only on this device. Derive the key again after a restart;
        // otherwise every message is dropped because the envelope cannot be decrypted.
        if (key == null && config.password.isNotBlank() && config.code.isNotBlank()) {
            key = DeviceSyncCrypto.deriveKey(config.code, config.password)
        }
        // A paired device stays paired across restarts. Never leave it in IDLE: the periodic
        // drain/sync loop used to require PAIRED, so both outboxes froze until the user re-paired.
        if (config.peers.isNotEmpty() && key != null) {
            _state.value = _state.value.copy(phase = DeviceSyncPhase.PAIRED)
        }
        // Keep the connection alive without opening the settings screen. Both devices poll the
        // relay and each other's mailbox, so replies flow in either direction after a restart.
        if (config.peers.isNotEmpty() || config.code.isNotBlank() || config.relayUrl.isNotBlank()) {
            transport.relayUrl = config.relayUrl
            transport.start()
            if (collector?.isActive != true) watchMessages()
            ensureRelayLoop()
            ensurePollLoop()
            ensureSyncLoop()
        }
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
            // The secret is already on this device; waiting for an inbound CODE_CONFIRM
            // to reveal it created a deadlock on NAT/WSA networks.
            phase = DeviceSyncPhase.HOST_PASSWORD_READY,
            code = config.code,
            password = config.password,
            passwordVisible = true,
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
        )
        ensureRelayLoop()
        ensureSyncLoop()
    }

    suspend fun startJoin(context: Context, target: DevicePairingMessage) {
        startJoinWithInvite(
            context,
            DevicePairingQr(
                code = target.code,
                deviceId = target.from,
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
        // Remember the relay from the invite: without it the joiner loses the only path to the
        // host as soon as the app restarts and the LAN endpoints change.
        config = config.copy(code = invite.code, relayUrl = invite.relayUrl)
        requireStore().saveConfig(config)
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
            from = invite.deviceId,
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
        // CONFIRM / VERIFY_ACK / ACK can be queued by the host when it cannot open a
        // connection back to us (WSA, NAT, firewall). Collect them over our own connection.
        ensurePollLoop()
        ensureSyncLoop()
    }

    /** The password is local state; no inbound confirmation is allowed to hide it again. */
    fun confirmCandidates(ids: Set<String>) {
        confirmedCandidates = ids
        _state.value = _state.value.copy(
            phase = DeviceSyncPhase.HOST_PASSWORD_READY,
            passwordVisible = true,
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
        // Keep the secret in app-private storage so a restart can derive the key again. It is
        // never sent over the network and never added to the QR/invite.
        config = config.copy(code = target.code, password = normalized)
        requireStore().saveConfig(config)
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
        if (delivered) {
            // Possession of the shared secret is proven by a VERIFY the host can decrypt.
            // Do not wait for CONFIRM/VERIFY_ACK: those are best effort and may be queued
            // on the host, to be collected by pollHost() over this same outgoing connection.
            persistJoinerPeer(target)
            scope.launch { recordLocalChanges() }
        }
        _state.value = _state.value.copy(
            phase = if (delivered) DeviceSyncPhase.PAIRED else DeviceSyncPhase.JOIN_WAITING_ACK,
            joinConfirmed = true,
            error = if (delivered) null else NO_PATH_ERROR,
        )
    }

    private suspend fun persistJoinerPeer(target: DevicePairingMessage) {
        val hostId = target.from
        if (hostId.isBlank()) return
        val peers = requireStore().loadConfig().peers.toMutableList()
        if (peers.none { it.deviceId == hostId }) {
            peers += DeviceSyncPeer(
                deviceId = hostId,
                name = target.deviceName,
                model = target.deviceModel,
                endpoint = target.endpoint,
                endpoints = target.endpoints,
                code = config.code,
                trustedAt = System.currentTimeMillis(),
            )
            config = config.copy(peers = peers)
            requireStore().saveConfig(config)
        }
        _state.value = _state.value.copy(peers = peers)
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            if (key == null) return@withLock
            val peers = requireStore().loadConfig().peers
            if (peers.isEmpty()) return@withLock
            val delivered = mutableListOf<Boolean>()
            peers.forEach { peer ->
                val target = peer.toPairingTarget()
                var batches = 0
                while (batches < MAX_BATCHES_PER_TICK) {
                    val cursor = requireStore().outboxCursor(peer.deviceId)
                    val events = requireStore().outboxBatch(
                        cursor,
                        MAX_EVENTS_PER_BATCH,
                        MAX_CHARS_PER_BATCH,
                    )
                    if (events.isEmpty()) break
                    val message = DeviceSyncMessage(
                        type = "EVENTS",
                        from = config.deviceId,
                        to = peer.deviceId,
                        endpoint = transport.endpoint,
                        endpoints = transport.endpoints,
                        events = events,
                        sentAt = System.currentTimeMillis(),
                    )
                    val ok = sendEncrypted(target, message)
                    delivered += ok
                    // A failed batch stays in the outbox; retry it on the next tick instead of
                    // advancing past it and silently losing those changes.
                    if (!ok) break
                    val last = events.last()
                    // Persist by event identity, not by file name: ACKs may delete the file while
                    // the next batch is being prepared.
                    requireStore().saveOutboxCursor(
                        peer.deviceId,
                        OutboxCursor(last.createdAt, last.opId),
                    )
                    batches++
                }
                if (batches == 0) {
                    delivered += sendEncrypted(
                        target,
                        DeviceSyncMessage(
                            type = "HEARTBEAT",
                            from = config.deviceId,
                            to = peer.deviceId,
                            endpoint = transport.endpoint,
                            endpoints = transport.endpoints,
                            sentAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            flushPendingAcks(peers)
            refreshCounts(peers)
            val ok = delivered.isNotEmpty() && delivered.all { it }
            _state.value = _state.value.copy(
                lastSyncAt = if (ok) System.currentTimeMillis() else _state.value.lastSyncAt,
                error = if (ok) null else NO_PATH_ERROR,
            )
        }
    }

    /** Retries ACKs whose first delivery failed, aggregated to stay inside the mailbox cap. */
    private suspend fun flushPendingAcks(peers: List<DeviceSyncPeer>) {
        peers.forEach { peer ->
            val ops = requireStore().pendingAcks(peer.deviceId, MAX_ACKS_PER_MESSAGE)
            if (ops.isEmpty()) return@forEach
            val ok = sendEncrypted(
                peer.toPairingTarget(),
                DeviceSyncMessage(
                    type = "ACK",
                    from = config.deviceId,
                    to = peer.deviceId,
                    endpoint = transport.endpoint,
                    endpoints = transport.endpoints,
                    acks = ops,
                    sentAt = System.currentTimeMillis(),
                ),
            )
            if (ok) requireStore().removePendingAcks(peer.deviceId, ops)
        }
    }

    private suspend fun refreshCounts(peers: List<DeviceSyncPeer>) {
        _state.value = _state.value.copy(
            peers = peers,
            outgoingPending = requireStore().outgoingPending(),
            incomingPending = requireStore().inboxCount(),
        )
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
            refreshCounts(requireStore().loadConfig().peers)
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
        pollJob?.cancel()
        pollJob = null
        relayJob?.cancel()
        relayJob = null
        syncJob?.cancel()
        syncJob = null
        collector?.cancel()
        collector = null
        joinEndpoints = emptyList()
        pendingVerify = null
        pollAckIds = emptySet()
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
                            // A bare 8-digit code has no host id either; learn it from the OFFER.
                            if (joinTarget?.from.isNullOrBlank()) {
                                joinTarget = joinTarget?.copy(from = message.from)
                            }
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
                            val auto = _state.value.confirmedCandidates.isEmpty()
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
                        // With a bare 8-digit code there was no endpoint at all; the CONFIRM
                        // message is the first chance to learn where the host actually lives.
                        val merged = (joinEndpoints + message.endpoints + message.endpoint)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                        if (merged.isNotEmpty()) {
                            joinEndpoints = merged
                            joinTarget = joinTarget?.copy(
                                from = joinTarget?.from?.ifBlank { message.from }.orEmpty(),
                                endpoint = message.endpoint.ifBlank { merged.first() },
                                endpoints = merged,
                            )
                            _state.value = _state.value.copy(endpoints = merged)
                        }
                        _state.value = _state.value.copy(joinConfirmed = true, error = null)
                    }
                }
            }
            "/sync" -> {
                // Reject an oversized body before Base64/JSON decoding allocates a huge char array.
                if (body.length > MAX_ENVELOPE_CHARS) return
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
                        if (joinTarget?.from.isNullOrBlank()) {
                            joinTarget = joinTarget?.copy(from = message.from)
                        }
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
                        // Persist first, apply in bounded chunks on the next sync tick. This keeps
                        // the "Changes to receive" counter visible and means a crash mid-apply
                        // cannot lose remote changes.
                        requireStore().enqueueIncoming(message.from, message.events)
                        refreshCounts(requireStore().loadConfig().peers)
                    }
                    "ACK" -> {
                        requireStore().acknowledge(message.from, message.acks)
                        refreshCounts(requireStore().loadConfig().peers)
                    }
                    "HEARTBEAT" -> Unit
                }
            }
        }
    }

    /** One collector for queued host replies, regardless of who started the session. */
    private fun ensurePollLoop() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                pollHost()
                delay(3_000L)
            }
        }
    }

    /** The relay is a shared mailbox; both devices must keep polling it, also after a restart. */
    private fun ensureRelayLoop() {
        if (relayJob?.isActive == true) return
        relayJob = scope.launch {
            while (isActive) {
                pollRelay()
                delay(3_000L)
            }
        }
    }

    /**
     * Periodic drain so EVENTS and ACKs keep flowing. This must not depend on the UI phase: a
     * restart used to leave the engine in IDLE and silently stop all synchronization.
     */
    private fun ensureSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                if (requireStore().loadConfig().peers.isEmpty()) continue
                runCatching { drainInbox() }
                syncNow()
            }
        }
    }

    /**
     * Applies a bounded number of received events. Only applied events are ACKed, so an event is
     * never dropped before its data made it into the local database.
     */
    private suspend fun drainInbox() = withContext(Dispatchers.IO) {
        inboxMutex.withLock {
            val batch = requireStore().inboxBatch(MAX_INBOX_PER_DRAIN)
            if (batch.isEmpty()) return@withLock
            val applier = DeviceSyncSnapshotApplier(requireStore())
            val acksByPeer = mutableMapOf<String, MutableList<String>>()
            val appliedOps = mutableListOf<String>()
            batch.forEach { incoming ->
                val event = incoming.event
                val applied = runCatching {
                    event.entry?.let { applier.apply(it) }
                    event.filter?.let { filter ->
                        // Filters are LWW by key and use the same applied cursor as entries.
                        val appliedFilters = requireStore().loadApplied().toMutableMap()
                        if (filter.updatedAt > (appliedFilters[filter.key] ?: 0L)) {
                            DeviceSyncSettings.apply(filter, config.syncDeviceSettings)
                            appliedFilters[filter.key] = filter.updatedAt
                            requireStore().saveApplied(appliedFilters)
                        }
                    }
                    event.snapshot?.entries?.forEach { applier.apply(it) }
                }.isSuccess
                // A failed event stays in the inbox for the next tick and is not acknowledged.
                if (applied) {
                    appliedOps += event.opId
                    acksByPeer.getOrPut(incoming.from) { mutableListOf() }.add(event.opId)
                }
            }
            requireStore().dropIncoming(appliedOps)
            acksByPeer.forEach { (peerId, opIds) ->
                val target = requireStore().loadConfig().peers
                    .firstOrNull { it.deviceId == peerId }?.toPairingTarget()
                val ok = if (target != null) {
                    sendEncrypted(
                        target,
                        DeviceSyncMessage(
                            type = "ACK",
                            from = config.deviceId,
                            to = peerId,
                            endpoint = transport.endpoint,
                            endpoints = transport.endpoints,
                            acks = opIds,
                            sentAt = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    false
                }
                if (!ok) requireStore().addPendingAcks(peerId, opIds)
            }
            refreshCounts(requireStore().loadConfig().peers)
        }
    }

    /** Collects replies the host could not push to us, over the connection we opened. */
    private suspend fun pollHost() {
        val target = joinTarget
            ?: requireStore().loadConfig().peers.firstOrNull()?.toPairingTarget()
            ?: return
        val endpoints = endpointsOf(target)
        if (endpoints.isEmpty()) return
        val acked = mutableSetOf<String>()
        for (endpoint in endpoints) {
            val messages = transport.poll(endpoint, config.deviceId, pollAckIds.toList())
                .getOrNull() ?: continue
            messages.forEach { queued ->
                // A message that needs the shared key cannot be processed before the password
                // is entered. Leave it in the mailbox instead of acknowledging and losing it.
                if (queued.path == "/sync" && key == null) return@forEach
                runCatching { handleMessage(queued.path, queued.body) }
                    .onSuccess { acked += queued.id }
                    .onFailure { _state.value = _state.value.copy(error = it.message) }
            }
            // Only acknowledge what we could process. A message that arrives before the
            // password is entered must stay queued until the key exists.
            if (acked.isNotEmpty()) pollAckIds = acked
            return
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
        val endpoints = endpointsOf(target)
        if (relay.isNotBlank()) attempts += transport.relaySend(target.code, "/pairing", body)
        endpoints.forEach { endpoint ->
            if (attempts.lastOrNull()?.isSuccess == true) return@forEach
            attempts += transport.send(endpoint, "/pairing", body)
        }
        if (attempts.any { it.isSuccess }) {
            if (_state.value.error == NO_PATH_ERROR) _state.value = _state.value.copy(error = null)
            return
        }
        // Either device may be behind NAT/WSA: leave the reply in our mailbox so the other
        // device's next poll delivers it. A bare 8-digit code has no addressee to queue for.
        if (target.from.isNotBlank()) {
            transport.enqueueReply(
                deviceId = target.from,
                path = "/pairing",
                body = body,
                dedupeKey = "${pairing.type}:${pairing.code}:${pairing.to}",
            )
            if (_state.value.error == NO_PATH_ERROR) _state.value = _state.value.copy(error = null)
        } else if (relay.isNotBlank() || endpoints.isNotEmpty()) {
            _state.value = _state.value.copy(error = NO_PATH_ERROR)
        }
        // Last chance when a device only allows UDP on the local network.
        transport.broadcast(pairing)
    }

    private suspend fun sendEncrypted(target: DevicePairingMessage, message: DeviceSyncMessage): Boolean {
        val activeKey = key ?: return false
        val plaintext = message.toJson()
        if (plaintext.length > MAX_MESSAGE_CHARS) {
            _state.value =
                _state.value.copy(error = "Mensaje de sincronización demasiado grande; se dividirá en el próximo ciclo")
            return false
        }
        val (iv, ciphertext) = DeviceSyncCrypto.encrypt(plaintext, activeKey)
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
        if (attempts.any { it.isSuccess }) return true
        if (target.from.isBlank()) return false
        val dedupe = listOf(
            message.type,
            config.deviceId,
            message.to,
            (message.acks + message.events.map { it.opId }).joinToString(","),
        ).joinToString(":")
        // Only the envelope (iv + ciphertext) is stored; the key and plaintext never leave.
        transport.enqueueReply(target.from, "/sync", envelope, dedupe)
        return true
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
        relayUrl = transport.relayUrl,
    )
}
