# AGENTS.md

## Scope

This folder contains the Android BLE client. It must remain a client for the
ESP32 firmware, not an authority for relay timing or safety behavior.

## Key File

- `app/src/main/java/com/newhaven/gate/MainActivity.kt`

This file owns permissions, BLE scanning, `BluetoothGatt`, characteristic reads
and writes, the serialized operation queue, auth signing, and UI state.

## Invariants

- Scan by service UUID `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1`.
- Write `PED` for the pedestrian trigger.
- Keep the auth algorithm identical to firmware and iOS.
- Keep GATT operations serialized through the queue.
- Keep local passphrase config in ignored `gate.local.properties`.
- Do not commit `local.properties` or `gate.local.properties`.

## Check

```bash
./gradlew assembleDebug
```

Run from `android-app/`. For BLE behavior changes, also test on a real BLE-capable
Android phone.
