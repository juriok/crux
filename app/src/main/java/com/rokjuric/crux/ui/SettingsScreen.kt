package com.rokjuric.crux.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokjuric.crux.BuildConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle()
    val unsynced by vm.unsynced.collectAsStateWithLifecycle()
    val icu by vm.icuConfigured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) }) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(2.dp))
                StatusLine(settings.paired, if (settings.paired) "Paired" else "Not paired yet")
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    supportingText = { Text("On the PC: python coach/coach.py pair") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = code.length == 6 && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = vm.repo.pair(url, code)
                                val syncError = vm.repo.sync.value.error
                                message = when {
                                    r.isFailure -> false to (r.exceptionOrNull()?.message ?: "Pairing failed")
                                    syncError != null -> false to "Paired, but the first sync failed: $syncError"
                                    else -> true to "Paired. Plan downloaded."
                                }
                                if (r.isSuccess) code = ""
                                busy = false
                            }
                        },
                    ) { Text("Pair") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = vm.repo.testConnection(url)
                                message = if (r.isSuccess) true to "Server reachable" else false to "Can't reach the server: ${r.exceptionOrNull()?.message}"
                                if (r.isSuccess && url.trimEnd('/') != settings.serverUrl) vm.repo.settings.setServerUrl(url)
                                busy = false
                            }
                        },
                    ) { Text("Test") }
                }
                message?.let { (ok, text) -> StatusLine(ok, text) }
                if (settings.paired) {
                    TextButton(onClick = { vm.repo.settings.unpair() }) { Text("Unpair this phone") }
                }
            }

            InfoCard {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        lastSync?.let { "Last sync ${Fmt.ago(it)}" } ?: "Never synced",
                        if (unsynced > 0) "$unsynced workout(s) waiting to upload" else "everything uploaded",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                sync.error?.let { StatusLine(false, it) }
                Spacer(Modifier.padding(4.dp))
                FilledTonalButton(onClick = { vm.refresh() }, enabled = settings.paired && !sync.running) {
                    Text(if (sync.running) "Syncing…" else "Sync now")
                }
            }

            InfoCard {
                Text("Watch (Huawei GT 5 Pro)", style = MaterialTheme.typography.titleMedium)
                when (icu) {
                    true -> StatusLine(true, "intervals.icu connected: runs, sleep and resting HR flow in, planned runs go out")
                    false -> StatusLine(false, "intervals.icu not connected on the server yet")
                    null -> Text("Status unknown until the first sync.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Huawei Health → intervals.icu → your server → this app. Set-up: link Huawei on intervals.icu " +
                        "(Settings → Connections), enable Health Service Kit in Huawei Health (Me → 3rd party services), " +
                        "then on the PC: python coach/coach.py icu-key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard {
                Text("Rest timer alerts", style = MaterialTheme.typography.titleMedium)
                StatusLine(notifGranted, if (notifGranted) "Notifications allowed" else "Notifications are off - you'll only get vibration")
                if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
                    FilledTonalButton(onClick = { askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                }
                Spacer(Modifier.padding(2.dp))
                Text(
                    "To feel the end of rest on your wrist: Huawei Health → Devices → your watch → Notifications → turn on Crux.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                "Crux ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            null,
            Modifier.size(18.dp),
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
