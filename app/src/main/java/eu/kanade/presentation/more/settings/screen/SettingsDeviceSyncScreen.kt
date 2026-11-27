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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.devicesync.DevicePairing
import eu.kanade.tachiyomi.util.devicesync.DevicePairingQr
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncEngine
import eu.kanade.tachiyomi.util.devicesync.DeviceSyncPhase
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
        val state by DeviceSyncEngine.state.collectAsState()

        var relay by rememberSaveable { mutableStateOf("") }
        var manualCode by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var pastedQr by rememberSaveable { mutableStateOf("") }
        var selected by remember { mutableStateOf(emptySet<String>()) }

        LaunchedEffect(Unit) { DeviceSyncEngine.init(context) }

        val showQr = state.code.length == 8 &&
            state.phase != DeviceSyncPhase.IDLE &&
            state.phase != DeviceSyncPhase.JOIN_WAITING_PASSWORD
        val qrBitmap = remember(showQr, state.code, state.endpoint, relay) {
            if (!showQr) {
                null
            } else {
                val payload = DevicePairing.encodeQr(
                    DevicePairingQr(
                        code = state.code,
                        name = Build.MODEL,
                        model = Build.MODEL,
                        endpoint = state.endpoint,
                        relayUrl = relay,
                    ),
                )
                renderQr(payload)
            }
        }

        return listOf(
            Preference.PreferenceItem.CustomPreference(
                title = stringResource(MR.strings.pref_device_sync),
                content = {
                    Column(Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.padding.medium)) {
                        Text(stringResource(MR.strings.pref_device_sync_summary))
                        Text(stringResource(MR.strings.device_sync_ack_info))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.syncSettings,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        DeviceSyncEngine.setSyncSettings(context, enabled)
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
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { scope.launch { DeviceSyncEngine.startHost(context, relay) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_host_start))
                        }
                        if (state.phase == DeviceSyncPhase.HOST_WAITING ||
                            state.phase == DeviceSyncPhase.HOST_PASSWORD_READY ||
                            state.phase == DeviceSyncPhase.PAIRED
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${stringResource(
                                    MR.strings.device_sync_code,
                                )}: ${DevicePairing.formatCode(state.code)}",
                            )
                            qrBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally),
                                )
                            }
                            if (state.phase == DeviceSyncPhase.HOST_WAITING) {
                                Text(stringResource(MR.strings.device_sync_waiting_code))
                                Text(stringResource(MR.strings.device_sync_select_device))
                                state.candidates.filter { it.type == "CODE_CONFIRM" }.forEach { candidate ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = candidate.from in selected,
                                            onCheckedChange = { checked ->
                                                selected =
                                                    if (checked) {
                                                        selected + candidate.from
                                                    } else {
                                                        selected -
                                                            candidate.from
                                                    }
                                            },
                                        )
                                        Text("${candidate.deviceName} (${candidate.from.take(8)})")
                                    }
                                }
                                Button(
                                    onClick = { DeviceSyncEngine.confirmCandidates(selected) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(MR.strings.device_sync_confirm_devices))
                                }
                            }
                            if (state.passwordVisible) {
                                Text(stringResource(MR.strings.device_sync_reveal_password))
                                Text(DevicePairing.formatCode(state.password))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Row {
                            Button(
                                onClick = {
                                    navigator.push(
                                        DeviceQrScannerScreen { text ->
                                            val qr = DevicePairing.decodeQr(text)
                                            if (qr != null) {
                                                scope.launch {
                                                    DeviceSyncEngine.startJoin(context, qr.toPairingMessage())
                                                }
                                            }
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(MR.strings.device_sync_join_qr))
                            }
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        DeviceSyncEngine.startJoinByCode(
                                            context,
                                            manualCode.filter {
                                                it.isDigit()
                                            }.take(8),
                                            relay,
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(MR.strings.device_sync_join_manual))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { manualCode = it.filter { c -> c.isDigit() }.take(8) },
                            label = { Text(stringResource(MR.strings.device_sync_code)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pastedQr,
                            onValueChange = { pastedQr = it },
                            label = { Text(stringResource(MR.strings.device_sync_paste_qr)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                val qr = DevicePairing.decodeQr(pastedQr.trim())
                                if (qr != null) {
                                    scope.launch { DeviceSyncEngine.startJoin(context, qr.toPairingMessage()) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_paste_qr))
                        }

                        if (state.phase == DeviceSyncPhase.JOIN_WAITING_PASSWORD ||
                            state.phase == DeviceSyncPhase.JOIN_WAITING_ACK
                        ) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it.uppercase().filter { c -> c.isLetter() }.take(10) },
                                label = { Text(stringResource(MR.strings.device_sync_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { scope.launch { DeviceSyncEngine.submitPassword(password) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(MR.strings.device_sync_password))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(MR.strings.device_sync_peers))
                        state.peers.forEach { peer ->
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
                        }
                        Text(stringResource(MR.strings.device_sync_pending, state.pending))
                        Button(
                            onClick = { scope.launch { DeviceSyncEngine.recordLocalChanges() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.device_sync_sync_now))
                        }
                        state.error?.let { Text(it) }
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

    private fun DevicePairingQr.toPairingMessage() = eu.kanade.tachiyomi.util.devicesync.DevicePairingMessage(
        type = "OFFER",
        from = "",
        deviceName = name,
        deviceModel = model,
        endpoint = endpoint,
        code = code,
        relayUrl = relayUrl,
    )
}
