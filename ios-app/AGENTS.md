# AGENTS.md

## Scope

This folder contains the iPhone BLE client. It connects to the ESP32 controller
and writes commands; it does not own physical relay behavior.

## Key Files

- `D5EvoBleGate/GateBLEManager.swift`
- `D5EvoBleGate/ContentView.swift`
- `D5EvoBleGate/Config/Common.xcconfig`
- `D5EvoBleGate/Config/LocalSecrets.example.xcconfig`

## Invariants

- Scan by service UUID `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1`.
- Write `PED` for the pedestrian trigger.
- Keep the auth algorithm identical to firmware and Android.
- Keep CoreBluetooth operations serialized through the manager queue.
- Keep local passphrase config in ignored `D5EvoBleGate/Config/LocalSecrets.xcconfig`.

## Check

```bash
xcodebuild -project D5EvoBleGate.xcodeproj -scheme D5EvoBleGate -sdk iphonesimulator -configuration Debug build
```

Run from `ios-app/`. For BLE behavior changes, also test on a real iPhone.
