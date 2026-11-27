package eu.kanade.tachiyomi.util.devicesync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceSyncFoundationTest {

    @Test
    fun `base64 round trips arbitrary bytes`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.toList(), DeviceBase64.decode(DeviceBase64.encode(bytes)).toList())
    }

    @Test
    fun `aes gcm round trips and rejects a wrong key`() {
        val key = DeviceSyncCrypto.deriveKey("12345678", "ABCDEFGHJK")
        val (iv, ciphertext) = DeviceSyncCrypto.encrypt("{\"hello\":\"mundo\"}", key)

        assertEquals("{\"hello\":\"mundo\"}", DeviceSyncCrypto.decrypt(iv, ciphertext, key))
        assertThrows(Exception::class.java) {
            DeviceSyncCrypto.decrypt(
                iv,
                ciphertext,
                DeviceSyncCrypto.deriveKey("12345678", "JKHGFEDCBA"),
            )
        }
    }

    @Test
    fun `pairing values have the agreed shape`() {
        repeat(20) {
            assertTrue(DevicePairing.newCode().all { c -> c in '0'..'9' })
            assertEquals(8, DevicePairing.newCode().length)
            val password = DevicePairing.newPassword()
            assertEquals(10, password.length)
            assertTrue(password.all { c -> c in 'A'..'Z' || c in '2'..'9' })
        }
        assertEquals("1234 5678", DevicePairing.formatCode("12345678"))
    }

    @Test
    fun `password field accepts the same alphabet the generator uses`() {
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".forEach { char ->
            assertTrue(DevicePairing.isPasswordChar(char), char.toString())
            assertTrue(DevicePairing.isPasswordChar(char.lowercaseChar()), char.toString())
        }
        listOf('0', '1', 'I', 'O', '-', ' ', 'ñ').forEach { char ->
            assertFalse(DevicePairing.isPasswordChar(char), char.toString())
        }
        repeat(20) {
            assertTrue(DevicePairing.newPassword().all(DevicePairing::isPasswordChar))
        }
    }

    @Test
    fun `qr payload survives a round trip and rejects foreign codes`() {
        val qr = DevicePairingQr(
            code = "12345678",
            name = "Mi ExtYomi",
            model = "Pixel 8",
            endpoint = "192.168.1.10:8787",
            endpoints = listOf("192.168.1.10:8787", "10.0.0.5:8787"),
            relayUrl = "https://relay.example.com",
        )
        val decoded = DevicePairing.decodeQr(DevicePairing.encodeQr(qr))
        assertEquals(qr, decoded)
        assertEquals(null, DevicePairing.decodeQr("otra cosa"))
    }

    @Test
    fun `invite link round trips and accepts a bare code`() {
        val qr = DevicePairingQr(
            code = "12345678",
            name = "Mi ExtYomi",
            model = "Pixel 8",
            endpoint = "192.168.1.10:8787",
            endpoints = listOf("192.168.1.10:8787", "10.0.0.5:8787"),
            relayUrl = "https://relay.example.com",
        )
        val invite = DevicePairing.encodeInvite(qr)

        assertTrue(invite.startsWith("extyomisync://pair#"))
        assertEquals(qr, DevicePairing.decodeInvite(invite))
        assertEquals("87654321", DevicePairing.decodeInvite(" 87654321 ")?.code)
        assertEquals(qr, DevicePairing.decodeInvite(DevicePairing.encodeQr(qr)))
    }

    @Test
    fun `invite link rejects foreign payloads`() {
        assertEquals(null, DevicePairing.decodeInvite("https://example.com/pair"))
        assertEquals(null, DevicePairing.decodeInvite("extyomisync://pair#not-base64"))
        assertEquals(null, DevicePairing.decodeInvite("1234567"))
    }

    @Test
    fun `pairing messages keep the relay and every address so the reply can reach us`() {
        val confirm = DevicePairingMessage(
            type = "CODE_CONFIRM",
            from = "second-device",
            code = "12345678",
            endpoint = "192.168.1.11:8787",
            endpoints = listOf("192.168.1.11:8787", "10.0.0.9:8787"),
            relayUrl = "https://relay.example.com",
            to = "first-device",
        )

        assertEquals(confirm, confirm.toJson().toDevicePairingMessage())
    }

    @Test
    fun `sync messages keep alternate addresses for VERIFY retries`() {
        val verify = DeviceSyncMessage(
            type = "VERIFY",
            from = "second-device",
            endpoint = "192.168.1.11:8787",
            endpoints = listOf("192.168.1.11:8787", "10.0.0.9:8787"),
        )
        val peer = DeviceSyncPeer(
            deviceId = "second-device",
            name = "Second",
            model = "Pixel",
            endpoint = verify.endpoint,
            endpoints = verify.endpoints,
            code = "12345678",
        )

        val config = DeviceSyncConfig(code = "12345678", peers = listOf(peer))
        assertEquals(verify.endpoints, verify.toJson().toDeviceSyncMessage().endpoints)
        assertEquals(peer.endpoints, config.toJson().toDeviceSyncConfig().peers.single().endpoints)
    }

    @Test
    fun `verify needs only the shared code and password`() {
        val code = "12345678"
        val password = "ABCDEFGHJK"
        // The joiner derives its key from the invite alone; the host from its own stored secret.
        val joinerKey = DeviceSyncCrypto.deriveKey(code, password)
        val hostKey = DeviceSyncCrypto.deriveKey(code, password)
        val verify = DeviceSyncMessage(
            type = "VERIFY",
            from = "second-device",
            deviceName = "Second",
        ).toJson()
        val (iv, ciphertext) = DeviceSyncCrypto.encrypt(verify, joinerKey)
        val envelope = DeviceSyncEnvelope(
            type = "VERIFY",
            from = "second-device",
            to = "first-device",
            code = code,
            iv = iv,
            ciphertext = ciphertext,
        ).toJson()

        val decoded = envelope.toDeviceSyncEnvelope().let {
            DeviceSyncCrypto.decrypt(it.iv, it.ciphertext, hostKey).toDeviceSyncMessage()
        }
        assertEquals("VERIFY", decoded.type)
        assertEquals("second-device", decoded.from)
        assertEquals("Second", decoded.deviceName)
    }

    @Test
    fun `progress is monotonic and a newer tombstone wins`() {
        val older = entry(updatedAt = 100L, seen = false, favorite = true)
        val newer = entry(updatedAt = 200L, seen = true, deleted = true)
        val merged = DeviceSyncMerge.mergeEntry(older, newer)

        assertTrue(merged.deleted)
        assertFalse(merged.favorite)
        assertTrue(merged.chapters.single().seen)
        assertEquals(200L, merged.updatedAt)
    }

    @Test
    fun `category removal survives an older add`() {
        val added = entry(updatedAt = 100L).copy(
            categories = listOf("Shonen"),
            categoryVersions = mapOf("Shonen" to 100L),
        )
        val removed = entry(updatedAt = 120L).copy(
            categories = emptyList(),
            categoryVersions = mapOf("Shonen" to -120L),
        )

        assertTrue(DeviceSyncMerge.mergeEntry(added, removed).categories.isEmpty())
        assertTrue(DeviceSyncMerge.mergeEntry(removed, added).categories.isEmpty())
    }

    @Test
    fun `library settings sync always but device settings require consent`() {
        val libraryKeys = listOf(
            "pref_display_mode_library",
            "pref_filter_animelib_unread_v2",
        )
        val deviceKeys = listOf(
            // Decodificador
            "pref_try_hwdec",
            "pref_gpu_next",
            "pref_video_debanding",
            "use_yuv420p",
            "wsa_player_defaults_applied",
            // Reproductor interno
            "pref_player_speed",
            "player_fullscreen",
            "pref_player_statistics_page",
            "external_player_preference",
            // Visor / lector
            "pref_default_reading_mode_key",
            "pref_reader_theme_key",
            "reader_navigation_mode_webtoon",
            // Avanzadas
            "pref_mpv_conf",
            "mpv_scripts",
        )

        libraryKeys.forEach { key ->
            assertTrue(DeviceSyncSettings.canSync(key, false), key)
            assertTrue(DeviceSyncSettings.canSync(key, true), key)
        }
        deviceKeys.forEach { key ->
            assertFalse(DeviceSyncSettings.canSync(key, false), key)
            assertTrue(DeviceSyncSettings.canSync(key, true), key)
        }
    }

    @Test
    fun `filters are last write wins per key`() {
        val current = DeviceSyncSnapshot(
            deviceId = "a",
            filters = listOf(DeviceSyncFilter("library.sort", "title", 100L)),
        )
        val incoming = DeviceSyncSnapshot(
            deviceId = "b",
            filters = listOf(DeviceSyncFilter("library.sort", "date", 200L)),
        )

        assertEquals("date", DeviceSyncMerge.merge(current, incoming).filters.single().value)
    }

    private fun entry(
        updatedAt: Long,
        seen: Boolean = false,
        favorite: Boolean = true,
        deleted: Boolean = false,
    ) = DeviceSyncEntry(
        key = "anime:12:/show",
        kind = "anime",
        sourceId = 12L,
        url = "/show",
        title = "Show",
        favorite = favorite,
        updatedAt = updatedAt,
        deleted = deleted,
        chapters = listOf(
            DeviceSyncChapter(url = "/show/1", seen = seen, updatedAt = updatedAt),
        ),
    )
}
