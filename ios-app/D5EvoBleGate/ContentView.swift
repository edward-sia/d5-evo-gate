import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bleManager: GateBLEManager
    @State private var authPin = ""
    @State private var revealsPassphrase = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    heroCard
                }

                Section("Status") {
                    LabeledContent("Connection") {
                        statusBadge(connectionPresentation)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        LabeledContent("Gate") {
                            statusBadge(controllerPresentation)
                        }

                        Text(controllerPresentation.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        LabeledContent("Security") {
                            statusBadge(authPresentation)
                        }

                        Text(authPresentation.detail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        LabeledContent("Controller") {
                            Text(bleManager.deviceInfoDisplay)
                                .foregroundStyle(.secondary)
                        }

                        Text("Nearby over Bluetooth from your local gate controller.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Security") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Use the same passphrase you set in the firmware.")
                            .foregroundStyle(.secondary)

                        HStack(spacing: 12) {
                            Group {
                                if revealsPassphrase {
                                    TextField("Passphrase", text: $authPin)
                                } else {
                                    SecureField("Passphrase", text: $authPin)
                                }
                            }
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .privacySensitive()

                            Button(revealsPassphrase ? "Hide" : "Show") {
                                revealsPassphrase.toggle()
                            }
                            .buttonStyle(.borderless)
                            .foregroundStyle(.secondary)
                        }

                        Text("The app keeps this only in memory while it is open.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    if bleManager.authStatus != "disabled" {
                        Button {
                            bleManager.authenticate(pin: authPin)
                        } label: {
                            Label("Unlock for 30 seconds", systemImage: "lock.open")
                        }
                        .disabled(!bleManager.isConnected)
                    } else {
                        Label("Protection is off in firmware right now.", systemImage: "exclamationmark.shield")
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    Button {
                        if bleManager.isConnected {
                            bleManager.triggerGate(pin: authPin)
                        } else {
                            bleManager.startScanAndConnect()
                        }
                    } label: {
                        Label(primaryActionTitle, systemImage: primaryActionSymbol)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(primaryActionDisabled)
                    .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))

                    if bleManager.isConnected {
                        Button {
                            bleManager.refreshStatus()
                        } label: {
                            Label("Refresh Status", systemImage: "arrow.clockwise")
                        }
                    }
                }

                Section("Recent Activity") {
                    Text(bleManager.message)
                        .foregroundStyle(.secondary)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Gate")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if bleManager.canDisconnect {
                        Button("Disconnect") {
                            bleManager.disconnect()
                        }
                    }
                }
            }
        }
    }

    private var heroCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text(heroTitle)
                    .font(.title3.weight(.semibold))
            } icon: {
                Image(systemName: heroSymbol)
                    .font(.title2)
                    .foregroundStyle(heroTint)
            }

            Text(heroSubtitle)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 8)
    }

    private var primaryActionTitle: String {
        bleManager.isConnected ? "Activate Gate" : "Find Nearby Gate"
    }

    private var primaryActionSymbol: String {
        bleManager.isConnected ? "bolt.horizontal.circle.fill" : "dot.radiowaves.left.and.right"
    }

    private var primaryActionDisabled: Bool {
        bleManager.isConnected ? false : !bleManager.canStartScan
    }

    private var heroTitle: String {
        if bleManager.isConnected {
            return "Ready when you are"
        }

        switch bleManager.connectionState.lowercased() {
        case "scanning":
            return "Looking nearby"
        case "connecting", "discovering services":
            return "Connecting"
        case "disconnected":
            return "Disconnected"
        case "not found":
            return "Gate not found"
        default:
            return "Nearby gate control"
        }
    }

    private var heroSubtitle: String {
        if bleManager.isConnected {
            return "Sends a single momentary trigger, like pressing your remote."
        }

        switch bleManager.connectionState.lowercased() {
        case "scanning":
            return "Keep the phone close to the controller while Bluetooth scans."
        case "not found":
            return "Make sure the controller is powered, then try again."
        default:
            return "Connect over Bluetooth, then activate the gate from this iPhone."
        }
    }

    private var heroSymbol: String {
        bleManager.isConnected ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right"
    }

    private var heroTint: Color {
        bleManager.isConnected ? .green : .blue
    }

    private var connectionPresentation: StatusPresentation {
        switch bleManager.connectionState.lowercased() {
        case "connected":
            return .init(title: "Connected", detail: "Your iPhone is linked to the gate controller.", tint: .green)
        case "scanning":
            return .init(title: "Searching", detail: "Looking for your gate controller nearby.", tint: .blue)
        case "connecting", "discovering services":
            return .init(title: "Connecting", detail: "Finishing the Bluetooth connection.", tint: .blue)
        case "not found":
            return .init(title: "Not Found", detail: "The controller was not found nearby.", tint: .orange)
        case "disconnected":
            return .init(title: "Disconnected", detail: "Reconnect when you want to use the gate again.", tint: .secondary)
        case "connect failed", "service discovery failed", "characteristic discovery failed", "wrong device":
            return .init(title: "Problem", detail: "The connection did not finish successfully.", tint: .red)
        default:
            return .init(title: bleManager.connectionState, detail: "Bluetooth is used only for local, nearby control.", tint: .secondary)
        }
    }

    private var controllerPresentation: StatusPresentation {
        switch bleManager.controllerStatus.lowercased() {
        case "ready":
            return .init(title: "Ready", detail: "The controller can accept a gate trigger now.", tint: .green)
        case "pulsing":
            return .init(title: "Activating", detail: "The relay is currently sending the momentary trigger.", tint: .blue)
        case "cooldown":
            return .init(title: "Waiting", detail: "A short safety pause is active before the next trigger.", tint: .orange)
        case "locked":
            return .init(title: "Locked", detail: "Too many failed attempts. Wait for the lockout to end.", tint: .red)
        case "busy":
            return .init(title: "Busy", detail: "The controller is handling another action right now.", tint: .orange)
        case "bad-command":
            return .init(title: "Needs Attention", detail: "The controller rejected the last command.", tint: .red)
        default:
            return .init(title: "Checking", detail: "Status updates appear here once connected.", tint: .secondary)
        }
    }

    private var authPresentation: StatusPresentation {
        switch bleManager.authStatus.lowercased() {
        case "authorized":
            return .init(title: "Unlocked", detail: "This phone can activate the gate for a short time.", tint: .green)
        case "required":
            return .init(title: "Protected", detail: "Enter your passphrase, then unlock before use.", tint: .orange)
        case "denied":
            return .init(title: "Denied", detail: "The passphrase did not match. Try again after the brief delay.", tint: .red)
        case "disabled":
            return .init(title: "Off", detail: "No passphrase is required on the controller right now.", tint: .secondary)
        default:
            return .init(title: "Checking", detail: "Security status appears here once connected.", tint: .secondary)
        }
    }

    @ViewBuilder
    private func statusBadge(_ presentation: StatusPresentation) -> some View {
        Text(presentation.title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(presentation.tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(presentation.tint.opacity(0.14))
            .clipShape(Capsule(style: .continuous))
    }
}

private struct StatusPresentation {
    let title: String
    let detail: String
    let tint: Color
}

private extension GateBLEManager {
    var deviceInfoDisplay: String {
        deviceInfo == "unknown" ? "Checking" : deviceInfo
    }
}
