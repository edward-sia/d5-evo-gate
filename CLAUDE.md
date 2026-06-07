# CLAUDE.md

## Working Context

This is a focused D5-Evo pedestrian gate trigger project. An ESP32 exposes a BLE
service, validates optional challenge-response auth, and momentarily pulses a
relay wired across D5-Evo `PED` and `COM`. Android and iPhone apps are local BLE
clients only.

## What To Preserve

- One supported hardware path: 38-pin ESP32 USB-C board, LM2596 buck regulator, 5V optocoupled relay.
- Relay control stays on `GPIO23`.
- Default relay polarity is active-high and must leave relay `COM` to `NO` open at idle.
- Relay behavior stays momentary and firmware-controlled.
- Stale BLE clients must not be allowed to reserve the controller indefinitely.
- Mobile apps write `PED`; firmware also currently accepts legacy `TRIGGER`.
- UUIDs, auth label, and hash rounds must stay identical across firmware, Android, and iOS.
- Real local auth secrets must stay ignored and out of commits.

## Key Files

- Firmware: `src/main.cpp`
- Firmware config: `include/app_config.h`
- Firmware local template: `include/app_config_local.example.h`
- PlatformIO profile: `platformio.ini`
- Android BLE app: `android-app/app/src/main/java/com/newhaven/gate/MainActivity.kt`
- Android local auth template: `android-app/gate.local.properties.example`
- iOS BLE manager: `ios-app/D5EvoBleGate/GateBLEManager.swift`
- iOS SwiftUI view: `ios-app/D5EvoBleGate/ContentView.swift`
- iOS local auth template: `ios-app/D5EvoBleGate/Config/LocalSecrets.example.xcconfig`
- Architecture docs: `docs/architecture.md`

## Commands

```bash
pio run -e esp32-usb-c-38pin
cd android-app && ./gradlew assembleDebug
xcodebuild -project ios-app/D5EvoBleGate.xcodeproj -scheme D5EvoBleGate -sdk iphonesimulator -configuration Debug build
```

Use the command that matches the subsystem changed. For BLE and relay behavior,
software builds are not enough; call out when physical bench testing was not run.

## Documentation Rule

When code or behavior changes, update the relevant README, AGENTS, CLAUDE, and
docs files in the same turn. The user explicitly wants these kept current.

## Local Secrets

Ignored secret/config files:

- `include/app_config_local.h`
- `android-app/gate.local.properties`
- `android-app/local.properties`
- `ios-app/D5EvoBleGate/Config/LocalSecrets.xcconfig`

Do not print or commit real values from those files.
