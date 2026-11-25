package com.podlewanieroslinek.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

// Link status shown in the UI.
enum class LinkState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

// One BLE peripheral seen during the scan.
data class BleDeviceItem(val name: String, val address: String, val rssi: Int)

// Telemetry parsed from one device → app JSON line (docs/ble-protocol.md).
data class PumpStatus(
    val soil: Int = -1,
    val pct: Int = -1,
    val water: Boolean = false,
    val pumping: Boolean = false,
    val msLeft: Long = 0,
    val error: String? = null,
) {
    companion object {
        fun parse(line: String): PumpStatus? {
            if (!line.startsWith("{")) return null // human debug line, ignore
            return try {
                val o = JSONObject(line)
                PumpStatus(
                    soil = o.optInt("soil", -1),
                    pct = o.optInt("pct", -1),
                    water = o.optBoolean("water", false),
                    pumping = o.optBoolean("pumping", false),
                    msLeft = o.optLong("msLeft", 0),
                    error = o.optString("error", null).takeUnless { it.isNullOrEmpty() },
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

// BLE link to the HM-10 / AT-09 bridge (fixed UUIDs 0xFFE0/0xFFE1).
// Same JSON lines as the cable protocol; long lines are chunked into
// 20-byte writes because that is all one GATT write carries.
@SuppressLint("MissingPermission") // callers grant SCAN/CONNECT at runtime
class BlePumpController(private val appContext: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val CHUNK = 20
        const val SCAN_MS = 12_000L
        // HM-10 advertises this service; the name is set via AT+NAME.
        const val NAME_HINT = "Roslinki"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _link = MutableStateFlow(LinkState.DISCONNECTED)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDeviceItem>>(emptyList())
    val devices: StateFlow<List<BleDeviceItem>> = _devices.asStateFlow()

    private val _status = MutableStateFlow(PumpStatus())
    val status: StateFlow<PumpStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val btAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var uartChar: BluetoothGattCharacteristic? = null

    // Android allows a single outstanding GATT operation: serialize everything.
    private sealed interface GattOp {
        data class Write(val bytes: ByteArray) : GattOp
        data object EnableNotify : GattOp
    }
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false

    private val pending = StringBuilder()
    private val seen = LinkedHashMap<String, BleDeviceItem>()

    fun bluetoothReady(): Boolean = btAdapter?.isEnabled == true

    // --- Scanning ----------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val addr = dev.address ?: return
            val name = try { dev.name } catch (_: SecurityException) { null }
                ?: result.scanRecord?.deviceName
                ?: "(bez nazwy)"
            seen[addr] = BleDeviceItem(name, addr, result.rssi)
            _devices.value = seen.values.sortedByDescending { isMatch(it) }
            if (isMatch(seen[addr]!!)) appendLog("# znaleziono: $name [$addr]")
        }
    }

    private fun isMatch(d: BleDeviceItem): Boolean =
        d.name.contains(NAME_HINT, ignoreCase = true)

    fun startScan() {
        val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            _link.value = LinkState.ERROR
            appendLog("# BLE niedostępne (włącz Bluetooth)")
            return
        }
        seen.clear()
        _devices.value = emptyList()
        _link.value = LinkState.SCANNING
        appendLog("# skanowanie BLE (12 s)…")
        try {
            scanner.startScan(scanCallback)
        } catch (e: Exception) {
            _link.value = LinkState.ERROR
            appendLog("# startScan: ${e.message}")
            return
        }
        scope.launch {
            delay(SCAN_MS)
            stopScanInternal()
            if (_link.value == LinkState.SCANNING) {
                _link.value = if (seen.isEmpty()) LinkState.DISCONNECTED else _link.value
                if (seen.isEmpty()) appendLog("# nic nie znaleziono")
            }
        }
    }

    private fun stopScanInternal() {
        try { btAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
    }

    // --- Connection --------------------------------------------------------

    fun connect(item: BleDeviceItem) {
        stopScanInternal()
        closeQuietly()
        val dev: BluetoothDevice = try {
            btAdapter?.getRemoteDevice(item.address)
        } catch (_: Exception) { null } ?: run {
            _link.value = LinkState.ERROR
            return
        }
        _link.value = LinkState.CONNECTING
        appendLog("# łączenie: ${item.name} [${item.address}]…")
        gatt = dev.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        closeQuietly()
        _link.value = LinkState.DISCONNECTED
        appendLog("# rozłączono")
    }

    fun isConnected(): Boolean = _link.value == LinkState.CONNECTED

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("# GATT połączony, szukam 0xFFE0…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (_link.value != LinkState.DISCONNECTED) {
                    appendLog("# GATT rozłączony (status=$status)")
                }
                closeQuietly()
                _link.value = LinkState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _link.value = LinkState.ERROR
                appendLog("# discoverServices: status=$status")
                return
            }
            val svc = g.getService(SERVICE_UUID)
            val ch = svc?.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                _link.value = LinkState.ERROR
                appendLog("# brak serwisu 0xFFE0/0xFFE1 (zły moduł?)")
                return
            }
            uartChar = ch
            _link.value = LinkState.CONNECTED
            appendLog("# połączono (FFE0/FFE1)")
            enqueue(GattOp.EnableNotify)
            sendStatus()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
        ) {
            opInFlight = false
            if (opQueue.firstOrNull() is GattOp.EnableNotify) opQueue.removeFirst()
            if (status != BluetoothGatt.GATT_SUCCESS) appendLog("# CCC: status=$status")
            pumpQueue()
        }

        @Deprecated("deprecated in API 33, still used below it")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            onBytes(c.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            onBytes(value)
        }

        @Deprecated("deprecated in API 33, still used below it")
        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            opInFlight = false
            if (opQueue.firstOrNull() is GattOp.Write) opQueue.removeFirst()
            if (status != BluetoothGatt.GATT_SUCCESS) appendLog("# write: status=$status")
            pumpQueue()
        }

        private fun onBytes(bytes: ByteArray) {
            pending.append(String(bytes, Charsets.US_ASCII))
            var idx = pending.indexOf("\n")
            while (idx >= 0) {
                val line = pending.substring(0, idx).trimEnd('\r').trim()
                pending.delete(0, idx + 1)
                if (line.isNotEmpty()) onLine(line)
                idx = pending.indexOf("\n")
            }
        }
    }

    // --- Commands (docs/ble-protocol.md; one line, chunked to 20 B) --------

    fun sendWater(ms: Long) = sendLine("{\"cmd\":\"water\",\"ms\":$ms}")
    fun sendStop() = sendLine("{\"cmd\":\"stop\"}")
    fun sendStatus() = sendLine("{\"cmd\":\"status\"}")

    private fun sendLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(CHUNK, bytes.size - i)
            enqueue(GattOp.Write(bytes.copyOfRange(i, i + n)))
            i += n
        }
        appendLog("> $line")
    }

    private fun enqueue(op: GattOp) {
        opQueue.addLast(op)
        pumpQueue()
    }

    private fun pumpQueue() {
        if (opInFlight) return
        val g = gatt ?: return
        val op = opQueue.firstOrNull() ?: return
        val ch = uartChar
        when (op) {
            is GattOp.EnableNotify -> {
                if (ch == null) return
                opInFlight = true
                try {
                    g.setCharacteristicNotification(ch, true)
                    val ccc = ch.getDescriptor(CCC_UUID)
                    if (ccc == null) {
                        opQueue.removeFirst()
                        opInFlight = false
                        appendLog("# brak deskryptora CCC")
                        return
                    }
                    @Suppress("DEPRECATION")
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(ccc)
                } catch (e: Exception) {
                    opInFlight = false
                    appendLog("# notify: ${e.message}")
                }
            }
            is GattOp.Write -> {
                if (ch == null) return // wait for service discovery
                opInFlight = true
                try {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    ch.value = op.bytes
                    @Suppress("DEPRECATION")
                    if (!g.writeCharacteristic(ch)) {
                        opInFlight = false
                        opQueue.removeFirst()
                        appendLog("# writeCharacteristic → false")
                    }
                } catch (e: Exception) {
                    opInFlight = false
                    appendLog("# write: ${e.message}")
                }
            }
        }
    }

    // --- Internals ---------------------------------------------------------

    private fun onLine(line: String) {
        appendLog("< $line")
        PumpStatus.parse(line)?.let { _status.value = it }
    }

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }

    private fun closeQuietly() {
        stopScanInternal()
        opQueue.clear()
        opInFlight = false
        uartChar = null
        pending.clear()
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
    }

    fun destroy() {
        closeQuietly()
        scope.cancel()
    }
}
