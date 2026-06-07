# AGENTS.md

## Project Role

This repository controls a real gate through a BLE-to-relay bridge. Treat every
change as safety-sensitive even when it looks like a small UI or docs edit. The
firmware is the source of truth for physical behavior; mobile apps are clients
that authenticate and write commands.

## Fast Map

- Root firmware: `src/main.cpp`, `include/app_config.h`, `include/app_config_local.example.h`, `platformio.ini`
- Android app: `android-app/app/src/main/java/com/newhaven/gate/MainActivity.kt`
- Android resources: `android-app/app/src/main/res/`
- iPhone app: `ios-app/D5EvoBleGate/GateBLEManager.swift`, `ios-app/D5EvoBleGate/ContentView.swift`
- iOS config: `ios-app/D5EvoBleGate/Config/`
- Human architecture docs: `docs/architecture.md`

## Hard Invariants

- The supported command is pedestrian access through D5-Evo `PED` and `COM`.
- Do not add gate-position sensing, `FRX`, `Status` input, or alternate board support unless explicitly requested.
- Keep relay control momentary.
- Keep the default relay polarity active-high unless bench evidence shows the relay is energized at idle.
- Keep firmware relay timing and cooldown authoritative.
- Keep the firmware, Android, and iOS BLE UUIDs in sync.
- Keep the auth label `D5-EVO-AUTH-V1|` and `2048` hash rounds in sync across firmware, Android, and iOS.
- Never commit real passphrases or generated local secret files.
- When changing behavior, update README, AGENTS, CLAUDE, and docs as part of the same work.

## Code Style And Language Preference

- When writing new code in this repo, prefer TypeScript or Java if those are viable for the task. Existing subsystem languages still win for local edits: C++ for firmware, Kotlin for Android, Swift for iOS.
- Keep docs ASCII-only unless the existing file already needs non-ASCII.
- Follow the compact, direct style already used in the repo. Avoid generic IoT prose.

## Firmware Notes

`src/main.cpp` owns:

- BLE service/characteristic creation
- challenge generation with `esp_fill_random`
- SHA-256 auth response validation
- idle BLE client timeout and disconnect recovery
- relay pulse start/end
- cooldown and auth lockout state
- serial startup summary

Local firmware config is included through `include/app_config_local.h` when
present. The shared defaults live in `include/app_config.h`.

Relevant checks:

```bash
pio run -e esp32-usb-c-38pin
pio run -e esp32-usb-c-38pin -t upload --upload-port /dev/cu.usbserial-XXXX
pio device monitor -p /dev/cu.usbserial-XXXX -b 115200
```

## Android Notes

`MainActivity.kt` is intentionally self-contained. It owns:

- runtime Bluetooth permissions
- BLE scan filtered by service UUID
- `BluetoothGatt` connection and service discovery
- a serialized GATT operation queue
- local auth response calculation
- compact status presentation

Android 12+ uses `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; Android 11 and older
use `ACCESS_FINE_LOCATION` for BLE scanning. Guard direct BLE API calls so a
denied or revoked permission cannot crash the app.

Local Android auto-auth config is read from ignored `android-app/gate.local.properties`.

Relevant check:

```bash
cd android-app && ./gradlew testDebugUnitTest --tests com.newhaven.gate.BlePermissionPolicyTest
cd android-app && ./gradlew assembleDebug
```

## iOS Notes

`GateBLEManager.swift` owns BLE and auth state. `ContentView.swift` is the SwiftUI
presentation layer.

Local iOS auto-auth config is read from ignored
`ios-app/D5EvoBleGate/Config/LocalSecrets.xcconfig` through `Info.plist`.

Relevant check:

```bash
xcodebuild -project ios-app/D5EvoBleGate.xcodeproj -scheme D5EvoBleGate -sdk iphonesimulator -configuration Debug build
```

## Documentation Requirements

When behavior changes, update all applicable docs:

- root `README.md` for human setup and operation
- root `AGENTS.md` for agent execution guidance
- root `CLAUDE.md` for Claude-specific guidance
- `docs/architecture.md` for system, protocol, and state diagrams
- subproject README/AGENTS/CLAUDE files when Android or iOS behavior changes

## Verification Expectations

- Docs-only changes: run text-level checks such as `rg` to verify names, UUIDs, and commands are consistent.
- Firmware changes: build with PlatformIO and, if hardware is available, bench-test the relay before live wiring.
- Android changes: build `assembleDebug`; test on a BLE-capable phone when touching permissions, scanning, GATT, or auth.
- iOS changes: build with Xcode; test on a real iPhone for BLE behavior.

Always state clearly when hardware verification was not performed.
