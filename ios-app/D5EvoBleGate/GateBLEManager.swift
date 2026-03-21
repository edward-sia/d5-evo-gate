import CoreBluetooth
import CryptoKit
import Foundation

@MainActor
final class GateBLEManager: NSObject, ObservableObject {
    @Published var connectionState = "Idle"
    @Published var controllerStatus = "unknown"
    @Published var authStatus = "unknown"
    @Published var deviceInfo = "unknown"
    @Published var message = "Tap Find Nearby Gate when you're ready."

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

    private enum Operation {
        case write(String)
        case read(CBUUID)
    }

    private enum InFlightOperation {
        case write(CBUUID)
        case read(CBUUID)
    }

    private lazy var centralManager = CBCentralManager(delegate: self, queue: nil)
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var operationQueue: [Operation] = []
    private var operationInFlight = false
    private var inFlightOperation: InFlightOperation?
    private var isScanning = false
    private var scanTimeoutTask: Task<Void, Never>?
    private var authChallenge = ""
    private var pendingAuthPin: String?
    private var pendingTriggerAfterAuth = false

    private var commandCharacteristic: CBCharacteristic? {
        characteristics[Self.commandCharacteristicUUID]
    }

    func startScanAndConnect() {
        guard centralManager.state == .poweredOn else {
            message = centralStateMessage(for: centralManager.state)
            return
        }

        guard !isScanning else {
            message = "Already looking for your gate nearby."
            return
        }

        guard !isConnected else {
            message = "Your iPhone is already connected."
            return
        }

        clearTransientState()
        connectionState = "Scanning"
        message = "Searching nearby over Bluetooth."
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

    func triggerGate() {
        if authStatus == "disabled" || authStatus == "authorized" {
            enqueueWrite("PED")
            return
        }

        guard !Self.localAuthPin.isEmpty else {
            message = "This build does not include a local unlock phrase."
            return
        }

        requestAuthentication(pin: Self.localAuthPin, triggerAfterAuth: true)
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
        inFlightOperation = nil
        clearPendingAuthRequest()

        guard let peripheral else {
            connectionState = "Idle"
            message = "Disconnected."
            clearTransientState()
            return
        }

        message = "Disconnecting."
        centralManager.cancelPeripheralConnection(peripheral)
    }

    private func requestAuthentication(pin: String, triggerAfterAuth: Bool) {
        pendingAuthPin = pin
        pendingTriggerAfterAuth = triggerAfterAuth
        requestAuthChallengeRead()
    }

    private func requestAuthChallengeRead() {
        guard characteristics[Self.authChallengeCharacteristicUUID] != nil else {
            clearPendingAuthRequest()
            message = "This controller does not support passphrase unlock."
            return
        }

        operationQueue.append(.read(Self.authChallengeCharacteristicUUID))
        runNextOperation()
    }

    private func enqueueWrite(_ command: String) {
        guard commandCharacteristic != nil else {
            message = "Connect to the gate first."
            return
        }

        operationQueue.append(.write(command))
        runNextOperation()
    }

    private func enqueueStatusRefresh() {
        guard peripheral != nil else {
            message = "Connect to the gate first."
            return
        }

        let characteristicOrder = [
            Self.authStatusCharacteristicUUID,
            Self.authChallengeCharacteristicUUID,
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
                clearPendingAuthRequest()
                return
            }

            guard let data = command.data(using: .utf8) else { return }
            operationInFlight = true
            inFlightOperation = .write(characteristic.uuid)
            peripheral.writeValue(data, for: characteristic, type: .withResponse)

        case .read(let uuid):
            guard let characteristic = characteristics[uuid] else {
                operationQueue.removeFirst()
                runNextOperation()
                return
            }

            operationInFlight = true
            inFlightOperation = .read(characteristic.uuid)
            peripheral.readValue(for: characteristic)
        }
    }

    private func completeOperation() {
        if !operationQueue.isEmpty {
            operationQueue.removeFirst()
        }
        operationInFlight = false
        inFlightOperation = nil
        runNextOperation()
    }

    private func stopScan() {
        guard isScanning else { return }
        centralManager.stopScan()
        isScanning = false
    }

    private func clearPendingAuthRequest() {
        pendingAuthPin = nil
        pendingTriggerAfterAuth = false
    }

    private func clearTransientState() {
        characteristics.removeAll()
        operationQueue.removeAll()
        operationInFlight = false
        inFlightOperation = nil
        peripheral = nil
        authChallenge = ""
        controllerStatus = "unknown"
        authStatus = "unknown"
        deviceInfo = "unknown"
        clearPendingAuthRequest()
    }

    private func centralStateMessage(for state: CBManagerState) -> String {
        switch state {
        case .unknown:
            return "Bluetooth is starting up."
        case .resetting:
            return "Bluetooth is resetting. Try again in a moment."
        case .unsupported:
            return "This iPhone does not support Bluetooth LE."
        case .unauthorized:
            return "Bluetooth access is not allowed for this app."
        case .poweredOff:
            return "Turn Bluetooth on in Settings first."
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
        case Self.authChallengeCharacteristicUUID:
            authChallenge = stringValue
        case Self.infoCharacteristicUUID:
            deviceInfo = stringValue
        default:
            break
        }
    }

    private func handleAuthChallengeValue(_ value: String) {
        guard let pin = pendingAuthPin else { return }

        if value == "disabled" {
            clearPendingAuthRequest()
            message = "Protection is turned off on the controller."
            return
        }

        guard let authCommand = buildAuthResponseCommand(pin: pin, challenge: value) else {
            clearPendingAuthRequest()
            message = "The unlock request expired. Refresh and try again."
            return
        }

        pendingAuthPin = nil
        enqueueWrite(authCommand)
        message = "Unlocking with your passphrase."
    }

    private func handleAuthStatusChange(_ value: String) {
        switch value {
        case "authorized":
            message = "Unlocked for a short time."
            if pendingTriggerAfterAuth {
                pendingTriggerAfterAuth = false
                enqueueWrite("PED")
            }
        case "denied":
            clearPendingAuthRequest()
            message = "That passphrase did not match. Wait a moment, then try again."
        case "required":
            if !pendingTriggerAfterAuth {
                message = "Unlock the gate controls first."
            }
        case "disabled":
            clearPendingAuthRequest()
        default:
            break
        }
    }

    private func buildAuthResponseCommand(pin: String, challenge: String) -> String? {
        let normalizedChallenge = challenge.trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()

        guard normalizedChallenge.count == 32 else {
            return nil
        }

        let pinData = Data(pin.utf8)
        let challengeData = Data(normalizedChallenge.utf8)
        var digest = Data(SHA256.hash(data: Self.authLabelData + pinData + challengeData))

        for _ in 1..<Self.authHashRounds {
            digest = Data(SHA256.hash(data: digest + pinData + challengeData))
        }

        let responseHex = digest.map { String(format: "%02X", $0) }.joined()
        return "AUTHRESP \(responseHex)"
    }

    private static var localAuthPin: String {
        (Bundle.main.object(forInfoDictionaryKey: "D5EVOAuthPin") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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
            self.message = "Connected. Finishing setup."
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
            self.message = error?.localizedDescription ?? "Disconnected from the gate."
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
                self.message = "This nearby device is not your gate controller."
                self.centralManager.cancelPeripheralConnection(peripheral)
                return
            }

            peripheral.discoverCharacteristics(
                [
                    Self.commandCharacteristicUUID,
                    Self.controllerStatusCharacteristicUUID,
                    Self.authChallengeCharacteristicUUID,
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

                if characteristic.uuid == Self.controllerStatusCharacteristicUUID ||
                    characteristic.uuid == Self.authStatusCharacteristicUUID {
                    peripheral.setNotifyValue(true, for: characteristic)
                }
            }

            self.connectionState = "Connected"
            self.message = "Ready to use."
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
                self.clearPendingAuthRequest()
            } else {
                self.message = "Action sent."
                self.enqueueStatusRefresh()
            }
            if case .write(let uuid)? = self.inFlightOperation, uuid == characteristic.uuid {
                self.completeOperation()
            }
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
                self.clearPendingAuthRequest()
            } else {
                let value = String(data: characteristic.value ?? Data(), encoding: .utf8) ?? "unknown"
                self.updateValue(value, for: characteristic.uuid)

                if characteristic.uuid == Self.authChallengeCharacteristicUUID {
                    self.handleAuthChallengeValue(value)
                } else if characteristic.uuid == Self.authStatusCharacteristicUUID {
                    self.handleAuthStatusChange(value)
                }
            }
            if case .read(let uuid)? = self.inFlightOperation, uuid == characteristic.uuid {
                self.completeOperation()
            }
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                self.message = error.localizedDescription
                return
            }

            if characteristic.isNotifying {
                self.message = "Live status updates enabled."
            }
        }
    }
}

extension GateBLEManager {
    private static let authHashRounds = 2048
    private static let authLabelData = Data("D5-EVO-AUTH-V1|".utf8)

    static let serviceUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1")
    static let commandCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2")
    static let controllerStatusCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3")
    static let authChallengeCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc4")
    static let infoCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5")
    static let authStatusCharacteristicUUID = CBUUID(string: "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6")
}
