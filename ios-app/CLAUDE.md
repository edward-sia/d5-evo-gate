# CLAUDE.md

iOS-specific guidance for the D5-Evo BLE Pedestrian Trigger.

## Preserve

- The app is a local BLE client for pedestrian access.
- The ESP32 firmware owns relay timing, cooldown, and auth lockout.
- iOS writes `AUTHRESP <hex>` and `PED`; it does not directly model relay wiring.
- CoreBluetooth operations stay serialized through `GateBLEManager`.
- Foreground scene changes should release the BLE connection.
- `ContentView` should stay focused on presentation.

## Useful Files

- `D5EvoBleGate/GateBLEManager.swift`
- `D5EvoBleGate/ContentView.swift`
- `D5EvoBleGate/Info.plist`
- `D5EvoBleGate/Config/Common.xcconfig`

## Build

```bash
xcodebuild -project D5EvoBleGate.xcodeproj -scheme D5EvoBleGate -sdk iphonesimulator -configuration Debug build
```

When auth logic changes, compare the algorithm with root `src/main.cpp` and
`android-app/app/src/main/java/com/newhaven/gate/MainActivity.kt`.
