# Android App

Native Android client for the D5-Evo BLE Pedestrian Trigger. The app scans for
the ESP32's custom BLE service, connects locally, reads controller/auth state,
signs the auth challenge when configured, and writes `PED`.

## Stack

- Kotlin activity
- AndroidX AppCompat and Core KTX
- Material Components
- Android BLE APIs: `BluetoothLeScanner`, `BluetoothGatt`, `BluetoothGattCallback`
- Gradle Kotlin DSL
- Minimum SDK 26, target SDK 34, compile SDK 34

## Main Files

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/newhaven/gate/MainActivity.kt` | BLE scanning, connection, serialized GATT operations, auth response, UI state. |
| `app/src/main/java/com/newhaven/gate/BlePermissionPolicy.java` | Android runtime permission split for BLE scan/connect. |
| `app/src/test/java/com/newhaven/gate/BlePermissionPolicyTest.java` | JVM tests for Android 12+ and legacy BLE permission requirements. |
| `app/src/main/AndroidManifest.xml` | BLE feature and runtime permission declarations. |
| `app/src/main/res/layout/activity_main.xml` | Single-screen gate control UI. |
| `app/src/main/res/values/strings.xml` | User-facing strings. |
| `app/src/main/res/values/colors.xml` | Light theme status colors. |
| `app/src/main/res/values-night/colors.xml` | Dark theme status colors. |
| `app/build.gradle.kts` | Android config and local auth BuildConfig injection. |
| `gate.local.properties.example` | Template for ignored local auth passphrase config. |

## BLE Flow

```mermaid
sequenceDiagram
    participant User
    participant App as MainActivity
    participant Scanner as BluetoothLeScanner
    participant Gatt as BluetoothGatt
    participant ESP32 as ESP32 BLE service

    User->>App: Tap Connect
    App->>Scanner: Scan for service UUID
    Scanner-->>App: Matching device found
    App->>Gatt: connectGatt
    Gatt->>ESP32: Discover service and characteristics
    App->>Gatt: Queue status reads
    Gatt-->>App: Auth, challenge, controller, info values
    User->>App: Tap Pedestrian
    alt Auth disabled or authorized
        App->>Gatt: Write PED
    else Auth required
        App->>Gatt: Read challenge
        App->>App: Compute AUTHRESP with MessageDigest
        App->>Gatt: Write AUTHRESP <hex>
        App->>Gatt: Refresh auth status
        App->>Gatt: Write PED after authorized
    end
    App->>Gatt: Disconnect when activity stops
```

## Runtime Permissions

The manifest declares legacy Bluetooth/location permissions through Android 11
and Android 12+ Nearby devices permissions separately.

- Android 12 and newer request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Android 11 and older request `ACCESS_FINE_LOCATION` for BLE scanning.

The activity checks permission before starting scans, reading device labels,
connecting with `BluetoothGatt`, refreshing status, writing commands, stopping
scans, or disconnecting. If permission is denied or revoked while BLE is active,
the app clears local BLE state and shows `Permission needed` instead of calling
the Android BLE API without permission.

## Local Auth Setup

For local development builds that should auto-auth:

1. Copy `gate.local.properties.example` to `gate.local.properties`.
2. Set `gate.auth.pin` to the same passphrase used in firmware.
3. Keep `gate.local.properties` out of git.

If `BuildConfig.GATE_AUTH_PIN` is blank and firmware requires auth, the app will
connect and read status but will not trigger the gate.

## Build

```bash
./gradlew assembleDebug
```

Use Android Studio for device install and BLE testing.

Focused permission-policy test:

```bash
./gradlew testDebugUnitTest --tests com.newhaven.gate.BlePermissionPolicyTest
```

## Agent Notes

- Keep BLE UUIDs in sync with `include/app_config.h` and the iOS manager.
- Keep `AUTH_HASH_ROUNDS = 2048` and auth label `D5-EVO-AUTH-V1|` in sync with firmware and iOS.
- The operation queue is intentional; Android GATT operations should stay serialized.
- Runtime permissions differ before and after Android 12. Preserve both paths and guard direct BLE API calls.
- Disconnect on activity stop so a backgrounded phone does not hold the ESP32 client slot.
- Do not add open/close gate controls unless the firmware and docs are deliberately expanded beyond pedestrian access.
