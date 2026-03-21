import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bleManager: GateBLEManager
    @State private var authPin = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header
                    statusCard
                    pinCard
                    actionCard
                    messageCard
                }
                .padding(20)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("D5-EVO Gate")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ESP32 38-pin USB-C local Bluetooth trigger for a Centurion D5-Evo gate motor.")
                .font(.headline)
            Text("This app scans for the relay controller, signs a local auth challenge when needed, and sends a momentary trigger.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            statusRow(title: "Connection", value: bleManager.connectionState)
            statusRow(title: "Controller", value: bleManager.controllerStatus)
            statusRow(title: "Auth", value: bleManager.authStatus)
            statusRow(title: "Info", value: bleManager.deviceInfo)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var pinCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("PIN or Passphrase")
                .font(.headline)
            SecureField("PIN or passphrase from app_config_local.h (not stored)", text: $authPin)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text("Leave this blank only if you kept authentication disabled in the firmware.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("Bench test first: the relay should click for about half a second when triggered.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private var actionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(bleManager.connectButtonTitle) {
                bleManager.startScanAndConnect()
            }
            .buttonStyle(.borderedProminent)
            .disabled(!bleManager.canStartScan)

            Button("Authenticate") {
                bleManager.authenticate(pin: authPin)
            }
            .buttonStyle(.bordered)
            .disabled(!bleManager.isConnected)

            Button("Trigger Gate") {
                bleManager.triggerGate(pin: authPin)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .disabled(!bleManager.isConnected)

            Button("Refresh Status") {
                bleManager.refreshStatus()
            }
            .buttonStyle(.bordered)
            .disabled(!bleManager.isConnected)

            Button("Disconnect") {
                bleManager.disconnect()
            }
            .buttonStyle(.bordered)
            .disabled(!bleManager.canDisconnect)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var messageCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Last Message")
                .font(.headline)
            Text(bleManager.message)
                .font(.body)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func statusRow(title: String, value: String) -> some View {
        HStack(alignment: .top) {
            Text(title)
                .fontWeight(.semibold)
            Spacer(minLength: 16)
            Text(value)
                .multilineTextAlignment(.trailing)
                .foregroundStyle(.secondary)
        }
    }
}
