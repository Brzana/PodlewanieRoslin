import Foundation
import CoreBluetooth

// Link status shown in the UI.
enum LinkState: String {
    case disconnected = "rozłączony"
    case scanning = "skanowanie BLE…"
    case connecting = "łączenie…"
    case connected = "połączony (HM-10)"
    case error = "błąd"
}

// Telemetry parsed from one device → app JSON line (docs/ble-protocol.md).
struct PumpStatus {
    var soil: Int = -1
    var pct: Int = -1
    var water: Bool = false
    var pumping: Bool = false
    var msLeft: Int = 0
    var error: String? = nil

    static func parse(_ line: String) -> PumpStatus? {
        guard line.hasPrefix("{"),
              let data = line.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil } // human debug line, ignore
        return PumpStatus(
            soil: o["soil"] as? Int ?? -1,
            pct: o["pct"] as? Int ?? -1,
            water: o["water"] as? Bool ?? false,
            pumping: o["pumping"] as? Bool ?? false,
            msLeft: o["msLeft"] as? Int ?? 0,
            error: o["error"] as? String
        )
    }
}

struct ScannedDevice: Identifiable {
    let id: UUID // peripheral identifier
    let peripheral: CBPeripheral
    var name: String
    var rssi: Int
}

// BLE link to the HM-10 / AT-09 bridge (fixed UUIDs 0xFFE0/0xFFE1).
// Same JSON lines as the cable protocol; long lines are chunked into
// 20-byte writes because that is all one GATT write carries.
final class BlePumpController: NSObject, ObservableObject {
    static let serviceUUID = CBUUID(string: "FFE0")
    static let charUUID = CBUUID(string: "FFE1")
    static let chunkSize = 20
    static let nameHint = "Roslinki"

    @Published var link: LinkState = .disconnected
    @Published var bluetoothOn = false
    @Published var devices: [ScannedDevice] = []
    @Published var status = PumpStatus()
    @Published var logLines: [String] = []

    private var central: CBCentralManager!
    private var active: CBPeripheral?
    private var uartChar: CBCharacteristic?
    private var writeQueue: [Data] = []
    private var writeBusy = false
    private var incoming = Data()

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Scanning

    func startScan() {
        guard central.state == .poweredOn else {
            appendLog("# Bluetooth wyłączony")
            return
        }
        devices.removeAll()
        link = .scanning
        appendLog("# skanowanie BLE…")
        // No service filter: clones (AT-09/JDY-08) do not always advertise FFE0.
        central.scanForPeripherals(withServices: nil, options: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self, self.link == .scanning else { return }
            self.central.stopScan()
            if self.devices.isEmpty {
                self.link = .disconnected
                self.appendLog("# nic nie znaleziono")
            }
        }
    }

    // MARK: - Connection

    func connect(_ item: ScannedDevice) {
        central.stopScan()
        disconnectSilently()
        link = .connecting
        appendLog("# łączenie: \(item.name)…")
        active = item.peripheral
        item.peripheral.delegate = self
        central.connect(item.peripheral, options: nil)
    }

    func disconnect() {
        if let p = active { central.cancelPeripheralConnection(p) }
        disconnectSilently()
        link = .disconnected
        appendLog("# rozłączono")
    }

    // MARK: - Commands (docs/ble-protocol.md; one line, chunked to 20 B)

    func sendWater(ms: Int) { sendLine("{\"cmd\":\"water\",\"ms\":\(ms)}") }
    func sendStop() { sendLine("{\"cmd\":\"stop\"}") }
    func sendStatus() { sendLine("{\"cmd\":\"status\"}") }

    private func sendLine(_ line: String) {
        guard let bytes = (line + "\n").data(using: .utf8) else { return }
        var i = bytes.startIndex
        while i < bytes.endIndex {
            let j = bytes.index(i, offsetBy: Self.chunkSize, limitedBy: bytes.endIndex) ?? bytes.endIndex
            writeQueue.append(bytes[i..<j])
            i = j
        }
        appendLog("> \(line)")
        pumpQueue()
    }

    private func pumpQueue() {
        guard !writeBusy,
              let p = active, p.state == .connected,
              let ch = uartChar,
              !writeQueue.isEmpty else { return }
        writeBusy = true
        p.writeValue(writeQueue.removeFirst(), for: ch, type: .withResponse)
    }

    // MARK: - Internals

    private func onLine(_ line: String) {
        appendLog("< \(line)")
        if let s = PumpStatus.parse(line) { status = s }
    }

    private func appendLog(_ line: String) {
        logLines = (logLines + [line]).suffix(120).map { $0 }
    }

    private func disconnectSilently() {
        writeQueue.removeAll()
        writeBusy = false
        uartChar = nil
        incoming.removeAll()
        active = nil
    }
}

// MARK: - CBCentralManagerDelegate

extension BlePumpController: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothOn = (central.state == .poweredOn)
        if central.state != .poweredOn, link == .scanning || link == .connecting {
            link = .disconnected
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        let name = peripheral.name
            ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
            ?? "(bez nazwy)"
        if let idx = devices.firstIndex(where: { $0.id == peripheral.identifier }) {
            devices[idx].rssi = RSSI.intValue
            if devices[idx].name.hasPrefix("(bez") { devices[idx].name = name }
        } else {
            devices.append(ScannedDevice(id: peripheral.identifier,
                                         peripheral: peripheral,
                                         name: name,
                                         rssi: RSSI.intValue))
            devices.sort { matchScore($0) > matchScore($1) }
        }
        if name.localizedCaseInsensitiveContains(Self.nameHint) {
            appendLog("# znaleziono: \(name)")
        }
    }

    private func matchScore(_ d: ScannedDevice) -> Int {
        (d.name.localizedCaseInsensitiveContains(Self.nameHint) ? 1000 : 0) + d.rssi
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        appendLog("# połączono, szukam 0xFFE0…")
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        link = .error
        appendLog("# nie połączono: \(error?.localizedDescription ?? "?")")
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        if link != .disconnected { appendLog("# rozłączono przez moduł") }
        disconnectSilently()
        link = .disconnected
    }
}

// MARK: - CBPeripheralDelegate

extension BlePumpController: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil,
              let svc = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID })
        else {
            link = .error
            appendLog("# brak serwisu 0xFFE0 (zły moduł?)")
            return
        }
        peripheral.discoverCharacteristics([Self.charUUID], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil,
              let ch = service.characteristics?.first(where: { $0.uuid == Self.charUUID })
        else {
            link = .error
            appendLog("# brak charakterystyki 0xFFE1")
            return
        }
        uartChar = ch
        link = .connected
        appendLog("# połączono (FFE0/FFE1)")
        peripheral.setNotifyValue(true, for: ch)
        sendStatus()
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard let data = characteristic.value else { return }
        incoming.append(data)
        while let nl = incoming.firstIndex(of: 0x0A) {
            let lineData = incoming[incoming.startIndex..<nl]
            incoming.removeSubrange(incoming.startIndex...nl)
            let line = String(data: lineData, encoding: .utf8)?
                .trimmingCharacters(in: .init(charactersIn: "\r\n ")) ?? ""
            if !line.isEmpty { onLine(line) }
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        writeBusy = false
        if let e = error { appendLog("# write: \(e.localizedDescription)") }
        pumpQueue()
    }
}
