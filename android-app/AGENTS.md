# AGENTS.md

## Scope

This folder contains the Android BLE client. It must remain a client for the
ESP32 firmware, not an authority for relay timing or safety behavior.

## Key File

- `app/src/main/java/com/newhaven/gate/MainActivity.kt`
- `app/src/main/java/com/newhaven/gate/BlePermissionPolicy.java`

`MainActivity.kt` owns permissions, BLE scanning, `BluetoothGatt`,
characteristic reads and writes, the serialized operation queue, auth signing,
and UI state. `BlePermissionPolicy.java` keeps the Android 12+ vs legacy
runtime permission split testable.

## Invariants

- Scan by service UUID `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1`.
- Write `PED` for the pedestrian trigger.
- Keep the auth algorithm identical to firmware and iOS.
- Keep GATT operations serialized through the queue.
- Disconnect when the activity stops so backgrounded phones release the gate.
- Keep local passphrase config in ignored `gate.local.properties`.
- On Android 12+, require and guard `BLUETOOTH_SCAN` plus `BLUETOOTH_CONNECT`.
- On Android 11 and older, keep `ACCESS_FINE_LOCATION` for BLE scanning.
- Do not commit `local.properties` or `gate.local.properties`.

## Check

```bash
./gradlew testDebugUnitTest --tests com.newhaven.gate.BlePermissionPolicyTest
./gradlew assembleDebug
```

Run from `android-app/`. For BLE behavior changes, also test on a real BLE-capable
Android phone.
