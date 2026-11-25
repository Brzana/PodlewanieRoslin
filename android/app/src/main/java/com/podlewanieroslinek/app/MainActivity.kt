package com.podlewanieroslinek.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// Phone ↔ Mega 2560 over BLE via the HM-10 bridge (docs/ble-protocol.md).
// Service 0xFFE0, char 0xFFE1, JSON lines chunked into 20-byte writes.
class MainActivity : ComponentActivity() {

    private lateinit var controller: BlePumpController
    private var permissionsOk by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionsOk = grants.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BlePumpController(applicationContext)
        refreshPermissionState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val link by controller.link.collectAsState()
                    val devices by controller.devices.collectAsState()
                    val status by controller.status.collectAsState()
                    val log by controller.log.collectAsState()

                    var selected by remember { mutableStateOf<BleDeviceItem?>(null) }

                    DisposableEffect(Unit) {
                        onDispose { controller.destroy() }
                    }

                    PumpScreen(
                        link = link,
                        permissionsOk = permissionsOk,
                        btOn = controller.bluetoothReady(),
                        onGrantPermissions = { askPermissions() },
                        devices = devices,
                        selected = selected,
                        onSelect = { selected = it },
                        onScan = {
                            selected = null
                            controller.startScan()
                        },
                        onConnect = {
                            val d = selected ?: devices.firstOrNull { it.name.contains("Roslinki", true) }
                                ?: devices.firstOrNull() ?: return@PumpScreen
                            controller.connect(d)
                        },
                        onDisconnect = { controller.disconnect() },
                        status = status,
                        log = log,
                        onWater5s = { controller.sendWater(5_000) },
                        onWater30s = { controller.sendWater(30_000) },
                        onStop = { controller.sendStop() },
                        onStatus = { controller.sendStatus() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun refreshPermissionState() {
        permissionsOk = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun askPermissions() {
        val missing = requiredPermissions()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isEmpty()) {
            permissionsOk = true
            return
        }
        permissionLauncher.launch(missing)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PumpScreen(
    link: LinkState,
    permissionsOk: Boolean,
    btOn: Boolean,
    onGrantPermissions: () -> Unit,
    devices: List<BleDeviceItem>,
    selected: BleDeviceItem?,
    onSelect: (BleDeviceItem) -> Unit,
    onScan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    status: PumpStatus,
    log: List<String>,
    onWater5s: () -> Unit,
    onWater30s: () -> Unit,
    onStop: () -> Unit,
    onStatus: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val connected = link == LinkState.CONNECTED
    val busy = link == LinkState.SCANNING || link == LinkState.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PodlewanieRoślinek — BLE", style = MaterialTheme.typography.titleLarge)
        Text(
            when (link) {
                LinkState.DISCONNECTED -> "Status: rozłączony"
                LinkState.SCANNING -> "Status: skanowanie BLE…"
                LinkState.CONNECTING -> "Status: łączenie…"
                LinkState.CONNECTED -> "Status: połączony (HM-10)"
                LinkState.ERROR -> "Status: błąd (włącz Bluetooth, sprawdź moduł)"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!permissionsOk) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Aplikacja potrzebuje uprawnień Bluetooth, żeby znaleźć moduł HM-10.")
                    Button(onClick = onGrantPermissions) { Text("Daj uprawnienia") }
                }
            }
        }
        if (!btOn) {
            Text(
                "Bluetooth jest wyłączony — włącz go w systemie.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Device picker (BLE scan results, RoslinkiPump first).
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selected?.let { "${it.name} [${it.address}]" }
                    ?: devices.firstOrNull()?.let { "${it.name} [${it.address}]" }
                    ?: "Brak urządzeń — przeskanuj",
                onValueChange = {},
                readOnly = true,
                label = { Text("Moduł HM-10 (BLE)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                devices.forEach { d ->
                    DropdownMenuItem(
                        text = { Text("${d.name} [${d.address}] (${d.rssi} dBm)") },
                        onClick = {
                            onSelect(d)
                            expanded = false
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, enabled = permissionsOk && !busy && !connected) {
                Text("Skanuj BLE")
            }
            if (connected) {
                OutlinedButton(onClick = onDisconnect) { Text("Rozłącz") }
            } else {
                Button(
                    onClick = onConnect,
                    enabled = permissionsOk && devices.isNotEmpty() && !busy,
                ) { Text("Połącz") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Gleba: ${status.pct} % (ADC ${status.soil})")
                Text("Woda w zbiorniku: ${if (status.water) "TAK" else "BRAK"}")
                Text("Pompka: ${if (status.pumping) "PRACUJE" else "stoi"}")
                if (status.pumping) Text("Pozostało: ${status.msLeft} ms")
                status.error?.let {
                    Text("Błąd sterownika: $it", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onWater5s, enabled = connected) { Text("Podlej 5 s") }
            Button(onClick = onWater30s, enabled = connected) { Text("Podlej 30 s") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onStop, enabled = connected) { Text("Stop") }
            OutlinedButton(onClick = onStatus, enabled = connected) { Text("Status") }
        }

        Text("Log łącza:", style = MaterialTheme.typography.titleSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(8.dp)) {
                log.takeLast(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
