import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bleManager: GateBLEManager

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                statusStrip
                primaryCard

                if !bleManager.message.isEmpty {
                    Text(bleManager.message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 320)
                }

                Spacer(minLength: 0)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Newhaven")
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

    private var statusStrip: some View {
        HStack(spacing: 12) {
            compactStatus(title: "Gate", presentation: controllerPresentation)
            compactStatus(title: "Lock", presentation: authPresentation)
            compactStatus(title: "Link", presentation: connectionPresentation)
        }
    }

    private var primaryCard: some View {
        VStack(spacing: 18) {
            Image(systemName: primarySymbol)
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(primaryTint)

            Text(primaryTitle)
                .font(.title2.weight(.semibold))

            Button(primaryButtonTitle) {
                primaryAction()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(primaryTint)
            .disabled(primaryDisabled)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 20)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
    }

    private func compactStatus(title: String, presentation: StatusPresentation) -> some View {
        VStack(spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(presentation.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(presentation.tint)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var primaryTitle: String {
        if !bleManager.isConnected {
            return "Nearby control"
        }

        switch bleManager.controllerStatus.lowercased() {
        case "pulsing":
            return "Opening"
        case "cooldown":
            return "Please wait"
        default:
            return "Ready"
        }
    }

    private var primaryButtonTitle: String {
        bleManager.isConnected ? "Open Gate" : "Connect"
    }

    private var primaryDisabled: Bool {
        if bleManager.isConnected {
            return bleManager.controllerStatus.lowercased() == "pulsing"
        }

        return !bleManager.canStartScan
    }

    private var primarySymbol: String {
        if !bleManager.isConnected {
            return "dot.radiowaves.left.and.right"
        }

        return bleManager.controllerStatus.lowercased() == "cooldown"
            ? "hourglass"
            : "bolt.horizontal.circle.fill"
    }

    private var primaryTint: Color {
        if !bleManager.isConnected {
            return .blue
        }

        switch bleManager.controllerStatus.lowercased() {
        case "ready":
            return .green
        case "cooldown":
            return .orange
        case "locked", "bad-command":
            return .red
        default:
            return .blue
        }
    }

    private func primaryAction() {
        if bleManager.isConnected {
            bleManager.triggerGate()
        } else {
            bleManager.startScanAndConnect()
        }
    }

    private var connectionPresentation: StatusPresentation {
        switch bleManager.connectionState.lowercased() {
        case "connected":
            return .init(title: "On", tint: .green)
        case "scanning":
            return .init(title: "Scan", tint: .blue)
        case "connecting", "discovering services":
            return .init(title: "Join", tint: .blue)
        case "not found":
            return .init(title: "Miss", tint: .orange)
        case "connect failed", "service discovery failed", "characteristic discovery failed", "wrong device":
            return .init(title: "Error", tint: .red)
        default:
            return .init(title: "Off", tint: .secondary)
        }
    }

    private var controllerPresentation: StatusPresentation {
        switch bleManager.controllerStatus.lowercased() {
        case "ready":
            return .init(title: "Ready", tint: .green)
        case "pulsing":
            return .init(title: "Open", tint: .blue)
        case "cooldown":
            return .init(title: "Wait", tint: .orange)
        case "locked":
            return .init(title: "Lock", tint: .red)
        case "bad-command":
            return .init(title: "Error", tint: .red)
        default:
            return .init(title: "Idle", tint: .secondary)
        }
    }

    private var authPresentation: StatusPresentation {
        switch bleManager.authStatus.lowercased() {
        case "authorized":
            return .init(title: "Open", tint: .green)
        case "required":
            return .init(title: "Need", tint: .orange)
        case "denied":
            return .init(title: "No", tint: .red)
        case "disabled":
            return .init(title: "Off", tint: .secondary)
        default:
            return .init(title: "Idle", tint: .secondary)
        }
    }
}

private struct StatusPresentation {
    let title: String
    let tint: Color
}
