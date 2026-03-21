import SwiftUI

@main
struct D5EvoBleGateApp: App {
    @StateObject private var bleManager = GateBLEManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleManager)
        }
    }
}
