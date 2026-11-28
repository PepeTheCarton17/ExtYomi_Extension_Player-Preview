package eu.kanade.tachiyomi.util.devicesync

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The outbox is selected by per-peer ACK, not by "I sent it". A mailbox queue or a stale endpoint
 * that answered HTTP 200 must not make an event disappear from the retry set.
 */
class DeviceSyncOutboxLifecycleTest {

    @TempDir
    lateinit var filesDir: File

    private fun store(): DeviceSyncStore {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        return DeviceSyncStore(context)
    }

    private fun event(opId: String, createdAt: Long) = DeviceSyncEvent(
        opId = opId,
        author = "phone",
        seq = createdAt,
        createdAt = createdAt,
        entry = DeviceSyncEntry(
            key = "anime:1:/show-$opId",
            kind = "anime",
            sourceId = 1L,
            url = "/show-$opId",
            title = "Show $opId",
            favorite = true,
            updatedAt = createdAt,
            chapters = emptyList(),
        ),
    )

    private suspend fun DeviceSyncStore.configure(vararg peers: DeviceSyncPeer) {
        saveConfig(
            DeviceSyncConfig(
                deviceId = "phone",
                code = "12345678",
                peers = peers.toList(),
            ),
        )
    }

    private fun peer(id: String) = DeviceSyncPeer(
        deviceId = id,
        name = id,
        model = "Test",
        endpoint = "",
        code = "12345678",
    )

    @Test
    fun `unacknowledged events are retried after a mailbox-only send`() = runTest {
        val store = store()
        store.configure(peer("wsa"))
        val events = listOf(event("op-1", 1L), event("op-2", 2L), event("op-3", 3L))
        events.forEach { store.enqueue(it) }

        // Simulate the old bug: the cursor advanced even though no ACK came back.
        store.saveOutboxCursor("wsa", OutboxCursor(3L, "op-3"))

        assertEquals(events.map { it.opId }, store.outboxBatchForPeer("wsa", 24, 400_000).map { it.opId })
        assertEquals(3, store.outgoingPending())
    }

    @Test
    fun `ack removes the event only when every active peer confirms`() = runTest {
        val store = store()
        store.configure(peer("phone2"), peer("wsa"))
        val events = listOf(event("op-1", 1L), event("op-2", 2L))
        events.forEach { store.enqueue(it) }

        store.acknowledge("phone2", listOf("op-1"))
        assertEquals(2, store.outgoingPending())
        assertEquals(listOf("op-2"), store.outboxBatchForPeer("phone2", 24, 400_000).map { it.opId })
        assertEquals(events.map { it.opId }, store.outboxBatchForPeer("wsa", 24, 400_000).map { it.opId })

        store.acknowledge("wsa", listOf("op-1", "op-2"))
        store.acknowledge("phone2", listOf("op-2"))
        assertEquals(0, store.outgoingPending())
        assertTrue(store.outboxBatchForPeer("phone2", 24, 400_000).isEmpty())
        assertTrue(store.outboxBatchForPeer("wsa", 24, 400_000).isEmpty())
    }

    @Test
    fun `a peer from an old session does not block pruning`() = runTest {
        val store = store()
        store.configure(peer("phone2"), peer("wsa"), peer("old").copy(code = "87654321"))
        val only = event("op-1", 1L)
        store.enqueue(only)

        store.acknowledge("phone2", listOf("op-1"))
        store.acknowledge("wsa", listOf("op-1"))

        assertEquals(0, store.outgoingPending())
    }

    @Test
    fun `pending join survives a restart as waiting for password`() = runTest {
        val store = store()
        val target = DevicePairingMessage(
            type = "OFFER",
            from = "host-id",
            deviceName = "Host",
            code = "12345678",
            endpoint = "192.168.1.2:8789",
        )
        store.saveConfig(DeviceSyncConfig(deviceId = "joiner", code = "12345678", joinTarget = target))

        val restored = store.loadConfig()
        assertEquals(target, restored.joinTarget)
        assertEquals("host-id", restored.joinTarget?.from)
    }

    @Test
    fun `forgetting the last peer ends the published session`() = runTest {
        val store = store()
        store.saveConfig(
            DeviceSyncConfig(
                deviceId = "phone",
                code = "12345678",
                password = "ABCDEF23456",
                hosting = true,
                peers = listOf(peer("wsa")),
            ),
        )

        store.forgetPeer("wsa")
        val config = store.loadConfig()
        assertTrue(config.peers.isEmpty())
        assertEquals(false, config.hosting)
        assertEquals(null, config.joinTarget)
    }

    @Test
    fun `forgetting one of two peers keeps the published session`() = runTest {
        val store = store()
        store.saveConfig(
            DeviceSyncConfig(
                deviceId = "phone",
                code = "12345678",
                password = "ABCDEF23456",
                hosting = true,
                peers = listOf(peer("phone2"), peer("wsa")),
            ),
        )

        store.forgetPeer("phone2")
        val config = store.loadConfig()
        assertEquals(listOf("wsa"), config.peers.map { it.deviceId })
        assertEquals(true, config.hosting)
    }

    @Test
    fun `configs written before the hosting flag do not publish a QR`() {
        val legacy = """{"deviceId":"phone","code":"12345678","password":"ABCDEF23456","peers":[]}"""
        assertEquals(false, deviceSyncJson.decodeFromString<DeviceSyncConfig>(legacy).hosting)
    }
}
