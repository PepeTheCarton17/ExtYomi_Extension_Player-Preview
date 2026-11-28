package eu.kanade.presentation.more.settings.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.devicesync.DevicePairing
import eu.kanade.tachiyomi.util.devicesync.DevicePairingQr
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncDelivery
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncEngine
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncLinkCheck
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncPhase
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Lives under Más → Ajustes → Seguimiento, as a nested settings screen. */
object SettingsDeviceSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_device_sync

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val clipboard: ClipboardManager = LocalClipboardManager.current
        val state by DeviceSyncEngine.state.collectAsState()

        var relay by rememberSaveable { mutableStateOf("") }
        var inviteInput by rememberSaveable { mutableStateOf("") }
        var inviteError by rememberSaveable { mutableStateOf<String?>(null) }
        var password by rememberSaveable { mutableStateOf("") }
        var confirmDeviceSettings by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(Unit) { DeviceSyncEngine.init(context) }

        val isHost = state.phase == DeviceSyncPhase.HOST_WAITING ||
            state.phase == DeviceSyncPhase.HOST_PASSWORD_READY ||
            (state.phase == DeviceSyncPhase.PAIRED && state.passwordVisible)
        val showInvite = isHost && state.code.length == 8 && state.endpoint.isNotBlank()
        val pairingQr = remember(
            showInvite,
            state.code,
            state.deviceId,
            state.endpoint,
            state.endpoints,
            state.relayUrl,
        ) {
            if (!showInvite) {
                null
            } else {
                DevicePairingQr(
                    code = state.code,
                    deviceId = state.deviceId,
                    name = Build.MODEL,
                    model = Build.MODEL,
                    endpoint = state.endpoint,
                    endpoints = state.endpoints,
                    relayUrl = state.relayUrl,
                )
            }
        }
        val invite = remember(pairingQr) { pairingQr?.let { DevicePairing.encodeInvite(it) }.orEmpty() }
        val qrBitmap = remember(pairingQr) { pairingQr?.let { renderQr(DevicePairing.encodeQr(it)) } }
        val pairingBusy = state.phase != DeviceSyncPhase.IDLE && state.phase != DeviceSyncPhase.PAIRED
        val errorText = when (state.error) {
            DeviceSyncEngine.PASSWORD_REJECTED ->
                stringResource(MR.strings.device_sync_status_password_rejected)
            DeviceSyncEngine.PASSWORD_INVALID_FORMAT ->
                stringResource(MR.strings.device_sync_status_password_invalid)
            DeviceSyncEngine.MAILBOX_QUEUED ->
                stringResource(MR.strings.device_sync_status_mailbox)
            DeviceSyncEngine.MESSAGE_TOO_LARGE ->
                stringResource(MR.strings.device_sync_status_message_too_large)
            null -> null
            else -> stringResource(MR.strings.device_sync_status_no_path)
        }
        val statusText = when {
            state.passwordBusy -> stringResource(MR.strings.device_sync_password_working)
            state.syncBusy -> stringResource(MR.strings.device_sync_status_working)
            errorText != null -> errorText
            state.incomingPending > 0 -> stringResource(MR.strings.device_sync_status_incoming)
            state.phase == DeviceSyncPhase.JOIN_WAITING_ACK ->
                stringResource(MR.strings.device_sync_status_verify_pending)
            state.lastDelivery == DeviceSyncDelivery.MAILBOX ->
                stringResource(MR.strings.device_sync_status_mailbox)
            state.outgoingPending > 0 -> stringResource(MR.strings.device_sync_status_waiting_ack)
            state.peers.isNotEmpty() -> stringResource(MR.strings.device_sync_status_connected)
            else -> stringResource(MR.strings.device_sync_status_idle)
        }
        val linkCheckText = when (state.linkCheck) {
            DeviceSyncLinkCheck.OK -> stringResource(MR.strings.device_sync_status_link_ok)
            DeviceSyncLinkCheck.FAILED -> stringResource(MR.strings.device_sync_status_link_failed)
            else -> null
        }
        // Engine exceptions are not localized; keep the detail visible for diagnosis, but never
        // duplicate one of the sentinels that already have a proper translation above.
        val rawError = state.error?.takeIf {
            it !in setOf(
                DeviceSyncEngine.PASSWORD_REJECTED,
                DeviceSyncEngine.PASSWORD_INVALID_FORMAT,
                DeviceSyncEngine.MAILBOX_QUEUED,
                DeviceSyncEngine.MESSAGE_TOO_LARGE,
            )
        }
        val pendingConfirmations = state.candidates.filter { it.type == "CODE_CONFIRM" }
        val unconfirmed = pendingConfirmations.filter { it.from !in state.confirmedCandidates }

        val badInvite = stringResource(MR.strings.device_sync_bad_invite)
        fun join(text: String, autofill: Boolean = false) {
            val parsed = DevicePairing.decodeInvite(text)
            if (parsed == null) {
                inviteError = badInvite
                return
            }
            inviteError = null
            if (autofill) {
                // The QR carries the relay and the invite, but deliberately NOT the password:
                // it is the second factor of the encryption and must be entered out-of-band.
                inviteInput = text
                if (parsed.relayUrl.isNotBlank()) relay = parsed.relayUrl
            }
            scope.launch { DeviceSyncEngine.startJoinWithInvite(context, parsed) }
        }

        LaunchedEffect(Unit) {
            DeviceQrScannerResult.pending.collect { text ->
                if (text != null) {
                    DeviceQrScannerResult.consume()
                    join(text, autofill = true)
                }
            }
        }

        if (confirmDeviceSettings) {
            AlertDialog(
                onDismissRequest = { confirmDeviceSettings = false },
                title = { Text(stringResource(MR.strings.device_sync_device_settings_title)) },
                text = { Text(stringResource(MR.strings.device_sync_device_settings_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDeviceSettings = false
                            scope.launch {
                                DeviceSyncEngine.setSyncDeviceSettings(context, true)
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeviceSettings = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.pref_device_sync),
                content = {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.medium)
                            // Keep the focused field above the soft keyboard.
                            .imePadding(),
                    ) {
                        Text(stringResource(MR.strings.pref_device_sync_summary))
                        Text(stringResource(MR.strings.device_sync_ack_info))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.syncDeviceSettings,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        confirmDeviceSettings = true
                                    } else {
                                        scope.launch {
                                            DeviceSyncEngine.setSyncDeviceSettings(context, false)
                                        }
                                    }
                                },
                            )
                            Text(stringResource(MR.strings.device_sync_settings_toggle))
                        }
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = relay,
                            onValueChange = { relay = it },
                            label = { Text(stringResource(MR.strings.device_sync_relay)) },
                            enabled = !pairingBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(MR.strings.device_sync_relay_hint))
                        if (state.natHint) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(
                                    if (isHost) {
                                        MR.strings.device_sync_wsa_host_hint
                                    } else {
                                        MR.strings.device_sync_wsa_join_hint
                                    },
                                ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { scope.launch { DeviceSyncEngine.startHost(context, relay) } },
                            // WSA/emulators cannot accept an inbound connection from the LAN.
                            enabled = !pairingBusy && !state.natHint,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_host_start))
                        }

                        if (showInvite) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${stringResource(
                                    MR.strings.device_sync_code,
                                )}: ${DevicePairing.formatCode(state.code)}",
                            )
                            Text(stringResource(MR.strings.device_sync_invite))
                            SelectionContainer { Text(invite) }
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(invite)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(MR.strings.device_sync_copy_invite))
                            }
                            qrBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(220.dp)
                                        .align(Alignment.CenterHorizontally),
                                )
                            }
                            if (pendingConfirmations.isNotEmpty() && unconfirmed.isNotEmpty()) {
                                Text(stringResource(MR.strings.device_sync_select_device))
                                pendingConfirmations.forEach { candidate ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = candidate.from in state.confirmedCandidates,
                                            onCheckedChange = { checked ->
                                                val next = if (checked) {
                                                    state.confirmedCandidates + candidate.from
                                                } else {
                                                    state.confirmedCandidates - candidate.from
                                                }
                                                DeviceSyncEngine.confirmCandidates(next)
                                            },
                                        )
                                        Text("${candidate.deviceName} (${candidate.from.take(8)})")
                                    }
                                }
                                Button(
                                    onClick = { DeviceSyncEngine.confirmCandidates(state.confirmedCandidates) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(MR.strings.device_sync_confirm_devices))
                                }
                            } else if (state.phase == DeviceSyncPhase.HOST_WAITING) {
                                Text(stringResource(MR.strings.device_sync_waiting_code))
                            }
                            // The secret lives on this device; never gate it on an inbound
                            // CODE_CONFIRM, which cannot arrive through WSA/NAT.
                            if (state.passwordVisible) {
                                Text(stringResource(MR.strings.device_sync_reveal_password))
                                Text(DevicePairing.formatCode(state.password))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { navigator.push(DeviceQrScannerScreen) },
                            enabled = !pairingBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_join_qr))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inviteInput,
                            onValueChange = { inviteInput = it },
                            label = { Text(stringResource(MR.strings.device_sync_paste_invite)) },
                            enabled = !pairingBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { join(inviteInput) },
                            enabled = !pairingBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_join))
                        }
                        inviteError?.let { Text(it) }

                        if (state.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD ||
                            state.phase == DeviceSyncPhase.JOIN_WAITING_ACK
                        ) {
                            Spacer(Modifier.height(8.dp))
                            if (state.joinConfirmed) {
                                Text(stringResource(MR.strings.device_sync_code_confirmed))
                            } else {
                                Text(stringResource(MR.strings.device_sync_waiting_confirm))
                            }
                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it.uppercase()
                                        .filter(DevicePairing::isPasswordChar)
                                        .take(10)
                                },
                                label = { Text(stringResource(MR.strings.device_sync_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(MR.strings.device_sync_password_hint))
                            Button(
                                onClick = { scope.launch { DeviceSyncEngine.submitPassword(password) } },
                                enabled = !state.passwordBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.passwordBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(MR.strings.device_sync_password_working))
                                } else {
                                    Text(stringResource(MR.strings.device_sync_password))
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(MR.strings.device_sync_peers))
                        state.peers.forEach { peer ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${peer.name} (${peer.deviceId.take(8)})", Modifier.weight(1f))
                                    OutlinedButton(onClick = {
                                        scope.launch { DeviceSyncEngine.forgetPeer(peer.deviceId) }
                                    }) {
                                        Text(stringResource(MR.strings.device_sync_forget))
                                    }
                                }
                                Text(
                                    if (peer.lastSeen > 0L) {
                                        stringResource(
                                            MR.strings.device_sync_peer_last_seen,
                                            java.text.SimpleDateFormat.getInstance().format(peer.lastSeen),
                                        )
                                    } else {
                                        stringResource(MR.strings.device_sync_peer_never_seen)
                                    },
                                )
                            }
                        }
                        Text(stringResource(MR.strings.device_sync_outgoing, state.outgoingPending))
                        Text(stringResource(MR.strings.device_sync_incoming, state.incomingPending))
                        Text(statusText)
                        linkCheckText?.let { Text(it) }
                        Button(
                            onClick = { scope.launch { DeviceSyncEngine.syncNowManually() } },
                            enabled = !state.syncBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.syncBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(MR.strings.device_sync_sync_working))
                            } else {
                                Text(stringResource(MR.strings.device_sync_sync_now))
                            }
                        }
                        Button(
                            onClick = { scope.launch { DeviceSyncEngine.checkLink() } },
                            enabled = state.peers.isNotEmpty() &&
                                state.linkCheck != DeviceSyncLinkCheck.CHECKING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.linkCheck == DeviceSyncLinkCheck.CHECKING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(MR.strings.device_sync_check_link_working))
                            } else {
                                Text(stringResource(MR.strings.device_sync_check_link))
                            }
                        }
                        rawError?.let { Text(it) }
                    }
                },
            ),
        )
    }

    private fun renderQr(text: String) = runCatching {
        val size = 512
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) {
                for (y in 0 until size) setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }.asImageBitmap()
    }.getOrNull()
}
