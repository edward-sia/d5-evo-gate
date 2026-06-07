# CLAUDE.md

Android-specific guidance for the D5-Evo BLE Pedestrian Trigger.

## Preserve

- The app is a BLE client for nearby pedestrian access.
- The firmware owns relay timing, cooldown, and auth lockout.
- Android writes `AUTHRESP <hex>` and `PED`; it does not directly model relay wiring.
- GATT operations stay serialized.
- Backgrounded activities should release the BLE connection.
- Runtime BLE permission handling must support Android 12+ and older Android versions.
- Android 12+ BLE calls must be guarded by `BLUETOOTH_SCAN` or `BLUETOOTH_CONNECT` as appropriate.

## Useful Files

- `app/src/main/java/com/newhaven/gate/MainActivity.kt`
- `app/src/main/java/com/newhaven/gate/BlePermissionPolicy.java`
- `app/src/test/java/com/newhaven/gate/BlePermissionPolicyTest.java`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/build.gradle.kts`

## Build

```bash
./gradlew testDebugUnitTest --tests com.newhaven.gate.BlePermissionPolicyTest
./gradlew assembleDebug
```

When auth logic changes, compare the algorithm with root `src/main.cpp` and
`ios-app/D5EvoBleGate/GateBLEManager.swift`.
