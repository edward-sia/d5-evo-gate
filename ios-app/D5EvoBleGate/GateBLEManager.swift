import CoreBluetooth
import Foundation

@MainActor
final class GateBLEManager: NSObject, ObservableObject {
    @Published var connectionState = "Idle"
    @Published var controllerStatus = "unknown"
    @Published var authStatus = "unknown"
    @Published var deviceInfo = "unknown"
    @Published var message = "Tap Connect to look for the gate controller."

    var isConnected: Bool {
        peripheral != nil && commandCharacteristic != nil
    }

    var canStartScan: Bool {
        switch centralManager.state {
        case .poweredOn:
            return !isScanning && !isConnected
        default:
            return false
        }
    }

    var canDisconnect: Bool {
        isScanning || peripheral != nil
    }

    var connectButtonTitle: String {
        isScanning ? "Scanning..." : "Connect"
    }

    private enum Operation {
        case write(String)
        case read(CBUUID)
    }

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var operationQueue: [Operation] = []
    private var operationInFlight = false
    private var isScanning = false
    private var scanTimeoutTask: Task<Void, Never>?

    private var commandCharacteristic: CBCharacteristic? {
        characteristics[Self.commandCharacteristicUUID]
    }

    func startScanAndConnect() {
        guard centralManager.state == .poweredOn else {
            message = centralStateMessage(for: centralManager.state)
            return
        }

        guard !isScanning else {
            message = "Already scanning for the gate controller."
            return
        }

        guard !isConnected else {
            message = "Already connected to the gate controller."
            return
        }

        clearTransientState()
        connectionState = "Scanning"
        message = "Looking for D5-EVO-Gate over Bluetooth."
        isScanning = true
        centralManager.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )

        scanTimeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard let self, self.isScanning else { return }
            self.stopScan()
            self.connectionState = "Not found"
            self.message = "No matching gate controller was found nearby."
        }
    }

    func authenticate(pin: String) {
        enqueueWrite(pin.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "AUTH" : "AUTH \(pin)")
    }

    func triggerGate(pin: String) {
        let trimmedPin = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedPin.isEmpty {
            enqueueWrite("AUTH \(trimmedPin)")
        }
        enqueueWrite("TRIGGER")
    }

    func refreshStatus() {
        enqueueStatusRefresh()
    }

    func disconnect() {
        stopScan()
        scanTimeoutTask?.cancel()
        scanTimeoutTask = nil
        operationQueue.removeAll()
        operationInFlight = false

        guard let peripheral else {
            connectionState = "Idle"
            message = "Disconnected."
            clearTransientState()
            return
        }

        message = "Disconnecting from the gate controller."
        centralManager.cancelPeripheralConnection(peripheral)
    }

    private func enqueueWrite(_ command: String) {
        guard commandCharacteristic != nil else {
            message = "Connect to the gate controller first."
            return
        }

        operationQueue.append(.write(command))
        runNextOperation()
    }

    private func enqueueStatusRefresh() {
        guard peripheral != nil else {
            message = "Connect to the gate controller first."
            return
        }

        let characteristicOrder = [
            Self.authStatusCharacteristicUUID,
            Self.controllerStatusCharacteristicUUID,
            Self.infoCharacteristicUUID,
        ]

        for uuid in characteristicOrder where characteristics[uuid] != nil {
            operationQueue.append(.read(uuid))
        }

        runNextOperation()
    }

    private func runNextOperation() {
        guard !operationInFlight else { return }
        guard let peripheral else { return }
        guard let nextOperation = operationQueue.first else { return }

        switch nextOperation {
        case .write(let command):
            guard let characteristic = commandCharacteristic else {
                message = "Command characteristic is unavailable."
                operationQueue.removeAll()
                return
            }

            guard let data = command.data(using: .utf8) else { return }
            operationInFlight = true
            peripheral.writeValue(data, for: characteristic, type: .withResponse)

        case .read(let uuid):
            guard let characteristic = characteristics[uuid] else {
                operationQueue.removeFirst()
                runNextOperation()
                return
            }

            operationInFlight = true
            peripheral.readValue(for: characteristic)
        }
    }

    private func completeOperation() {
        if !operationQueue.isEmpty {
            operationQueue.removeFirst()
        }
        operationInFlight = false
        runNextOperation()
    }

    private func stopScan() {
        guard isScanning else { return }
        centralManager.stopScan()
        isScanning = false
    }

    private func clearTransientState() {
        characteristics.removeAll()
        operationQueue.removeAll()
        operationInFlight = false
        peripheral = nil
        controllerStatus = "unknown"
        authStatus = "unknown"
        deviceInfo = "unknown"
    }

    private func centralStateMessage(for state: CBManagerState) -> String {
        switch state {
        case .unknown:
            return "Bluetooth is still starting up."
        case .resetting:
            return "Bluetooth is resetting. Try again in a moment."
        case .unsupported:
            return "This iPhone does not support Bluetooth LE."
        case .unauthorized:
            return "Bluetooth access is not allowed for this app."
        case .poweredOff:
            return "Turn Bluetooth on in iPhone settings first."
        case .poweredOn:
            return "Bluetooth is ready."
        @unknown default:
            return "Bluetooth is unavailable right now."
        }
    }

    private func updateValue(_ stringValue: String, for uuid: CBUUID) {
        switch uuid {
        case Self.controllerStatusCharacteristicUUID:
            controllerStatus = stringValue
        case Self.authStatusCharacteristicUUID:
            authStatus = stringValue
        case Self.infoCharacteristicUUID:
            deviceInfo = stringValue
        default:
            break
        }
    }
}

extension GateBLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            if self.peripheral == nil {
                self.connectionState = "Idle"
            }
            self.message = self.centralStateMessage(for: central.state)
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            self.stopScan()
            self.scanTimeoutTask?.cancel()
            self.scanTimeoutTask = nil
            self.peripheral = peripheral
            self.connectionState = "Connecting"
            self.message = "Connecting to \(peripheral.name ?? peripheral.identifier.uuidString)."
            peripheral.delegate = self
            self.centralManager.connect(peripheral, options: nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.connectionState = "Discovering services"
            self.message = "Connected. Discovering BLE services."
            peripheral.discoverServices([Self.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            self.clearTransientState()
            self.connectionState = "Connect failed"
            self.message = error?.localizedDescription ?? "Connection failed."
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            self.clearTransientState()
            self.connectionState = "Disconnected"
            self.message = error?.localizedDescription ?? "Disconnected from the gate controller."
        }
    }
}

extension GateBLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            if let error {
                self.connectionState = "Service discovery failed"
                self.message = error.localizedDescription
                return
            }

            guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
                self.connectionState = "Wrong device"
                self.message = "Connected device does not expose the D5-EVO BLE service."
                self.centralManager.cancelPeripheralConnection(peripheral)
                return
            }

            peripheral.discoverCharacteristics(
                [
                    Self.commandCharacteristicUUID,
                    Self.controllerStatusCharacteristicUUID,
                    Self.infoCharacteristicUUID,
                    Self.authStatusCharacteristicUUID,
                ],
                for: service
            )
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                self.connectionState = "Characteristic discovery failed"
                self.message = error.localizedDescription
                return
            }

            for characteristic in service.characteristics ?? [] {
                self.characteristics[characteristic.uuid] = characteristic
            }

            self.connectionState = "Connected"
            self.message = "Gate controller ready."
            self.enqueueStatusRefresh()
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                self.message = error.localizedDescription
            } else {
                let command = String(data: characteristic.value ?? Data(), encoding: .utf8) ?? "command"
                self.message = "Sent \(command)."
                self.enqueueStatusRefresh()
            }
            self.completeOperation()
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                self.message = error.localizedDescription
            } else {
                let value = String(data: characteristic.value ?? Data(), encoding: .utf8) ?? "unknown"
                self.updateValue(value, for: characteristic.uuid)
            }
            self.completeOperation()
        }
    }
}

extension GateBLEManager {
    static let serviceUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1")
    static let commandCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2")
    static let controllerStatusCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3")
    static let infoCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5")
    static let authStatusCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6")
}
