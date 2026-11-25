import SwiftUI

// Phone ↔ Mega 2560 over BLE via the HM-10 bridge (docs/ble-protocol.md).
// Service 0xFFE0, char 0xFFE1, JSON lines chunked into 20-byte writes.
struct ContentView: View {
    @StateObject private var controller = BlePumpController()
    @State private var selectedID: UUID?

    private var connected: Bool { controller.link == .connected }
    private var busy: Bool {
        controller.link == .scanning || controller.link == .connecting
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Moduł HM-10 (BLE)") {
                    Text("Status: \(controller.link.rawValue)")
                        .foregroundStyle(statusColor)
                    if !controller.bluetoothOn {
                        Text("Bluetooth jest wyłączony — włącz go w systemie.")
                            .foregroundStyle(.red)
                    }
                    if controller.link == .error {
                        Text("Sprawdź, czy moduł HM-10 jest zasilony i rozgłasza RoslinkiPump.")
                            .font(.caption)
                    }
                    Picker("Urządzenie", selection: $selectedID) {
                        Text("Wybierz…").tag(nil as UUID?)
                        ForEach(controller.devices) { d in
                            Text("\(d.name) (\(d.rssi) dBm)").tag(d.id as UUID?)
                        }
                    }
                    HStack {
                        Button("Skanuj BLE", action: controller.startScan)
                            .disabled(busy || connected)
                        Spacer()
                        if connected {
                            Button("Rozłącz", action: controller.disconnect)
                        } else {
                            Button("Połącz", action: connectSelected)
                                .disabled(selectedDevice() == nil || busy)
                        }
                    }
                }

                Section("Stan podlewania") {
                    Text("Gleba: \(controller.status.pct) % (ADC \(controller.status.soil))")
                    Text("Woda w zbiorniku: \(controller.status.water ? "TAK" : "BRAK")")
                    Text("Pompka: \(controller.status.pumping ? "PRACUJE" : "stoi")")
                    if controller.status.pumping {
                        Text("Pozostało: \(controller.status.msLeft) ms")
                    }
                    if let e = controller.status.error {
                        Text("Błąd sterownika: \(e)").foregroundStyle(.red)
                    }
                }

                Section("Sterowanie") {
                    HStack {
                        Button("Podlej 5 s") { controller.sendWater(ms: 5_000) }
                            .disabled(!connected)
                        Button("Podlej 30 s") { controller.sendWater(ms: 30_000) }
                            .disabled(!connected)
                    }
                    HStack {
                        Button("Stop", action: controller.sendStop)
                            .disabled(!connected)
                        Button("Status", action: controller.sendStatus)
                            .disabled(!connected)
                    }
                }

                Section("Log łącza") {
                    ForEach(Array(controller.logLines.suffix(20).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption).monospaced()
                    }
                }
            }
            .navigationTitle("PodlewanieRoślinek")
        }
    }

    private var statusColor: Color {
        switch controller.link {
        case .connected: return .green
        case .error: return .red
        default: return .primary
        }
    }

    private func selectedDevice() -> ScannedDevice? {
        controller.devices.first(where: { $0.id == selectedID })
            ?? controller.devices.first(where: { $0.name.localizedCaseInsensitiveContains("Roslinki") })
            ?? controller.devices.first
    }

    private func connectSelected() {
        guard let d = selectedDevice() else { return }
        selectedID = d.id
        controller.connect(d)
    }
}

#Preview {
    ContentView()
}
