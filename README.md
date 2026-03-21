# D5-Evo BLE Gate Trigger

This project is now trimmed to the exact hardware set below:

- Zaitronics ESP32 USB-C Wi-Fi/Bluetooth Development Board, 38-pin
- Zaitronics LM2596 buck regulator
- Zaitronics 5V 1-channel optocoupled relay module
- Zaitronics 830 tie-point solderless breadboard for bench testing
- Zaitronics 400 tie-point solderless breadboard for a compact prototype build

The repository contains:

- [`src/main.cpp`](/Users/queenie/repos/chat/d5-evo-ble-gate/src/main.cpp) for the ESP32 firmware
- [`android-app`](/Users/queenie/repos/chat/d5-evo-ble-gate/android-app) for the Android app
- [`ios-app`](/Users/queenie/repos/chat/d5-evo-ble-gate/ios-app) for the iPhone app

## Exact supported hardware behavior

This codebase supports one job only:

- the ESP32 drives one 5V relay input from `GPIO23`
- the relay closes a dry contact across D5-Evo `TRG` and `COM`
- the phone connects locally over BLE and sends `TRIGGER`

This codebase does not include:

- gate-position sensing
- D5-Evo `Status` input wiring
- `FRX` wiring
- alternative ESP32 board layouts
- alternative relay modules

## Wiring

### Bench wiring on the 830-point breadboard

Power:

- LM2596 `OUT+` -> ESP32 `5V`
- LM2596 `OUT-` -> ESP32 `GND`
- LM2596 `OUT+` -> relay `DC+` or `VCC`
- LM2596 `OUT-` -> relay `DC-` or `GND`

Relay control:

- ESP32 `GPIO23` -> relay `IN`

Bench-test only:

- do not connect relay `COM` and `NO` to the D5-Evo yet
- when you trigger from the phone app, the relay should click once for about 500 ms

### Final D5-Evo connection

Gate power to buck converter:

- D5-Evo `Aux 12V Out` -> LM2596 `VIN+`
- D5-Evo `COM` -> LM2596 `VIN-`

Relay dry contact:

- relay `COM` -> D5-Evo `COM`
- relay `NO` -> D5-Evo `TRG`

Important:

- set the LM2596 output to `5.0V` before connecting the ESP32 or relay
- keep the relay output momentary, not latched
- leave all existing D5-Evo safety wiring untouched

## Breadboard use

The two breadboards match the build stages like this:

- use the `830` tie-point board for first bring-up and relay bench testing
- use the `400` tie-point board only if you want a smaller prototype before final enclosure work

Neither breadboard is recommended as the final installed gate controller assembly.

## Firmware behavior

The ESP32 firmware:

- advertises as `D5-EVO-Gate` by default
- exposes one BLE command path for `AUTH <pin>` and `TRIGGER`
- pulses the relay for `500 ms`
- enforces a `5 second` cooldown
- publishes controller status and auth status over BLE

Controller status values:

- `ready`
- `pulsing`
- `cooldown`
- `busy`
- `locked`
- `bad-command`

Auth status values:

- `disabled`
- `required`
- `authorized`
- `denied`

## BLE characteristics

Service UUID:

- `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1`

Characteristics:

- Command: `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2`
- Controller status: `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3`
- Info: `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5`
- Auth status: `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6`

Supported write commands:

- `AUTH <pin>`
- `TRIGGER`

## Configuration

The project is configured specifically for the 38-pin USB-C ESP32 board and the 5V relay module:

- relay output pin is `GPIO23`
- relay trigger polarity defaults to `active-low`

Use local overrides instead of editing the shared defaults:

1. Copy [`app_config_local.example.h`](/Users/queenie/repos/chat/d5-evo-ble-gate/include/app_config_local.example.h) to `include/app_config_local.h`.
2. Set `D5_EVO_AUTH_PIN` to your own value.
3. Optionally change `D5_EVO_DEVICE_NAME`.
4. Only change `D5_EVO_RELAY_ACTIVE_LEVEL` if your relay energizes immediately on boot.

The firmware automatically includes `app_config_local.h` when present.

## Build and flash the ESP32

[`platformio.ini`](/Users/queenie/repos/chat/d5-evo-ble-gate/platformio.ini) now contains one board profile only:

- `esp32-usb-c-38pin`

Flash steps:

1. Install PlatformIO.
2. Connect the ESP32 USB-C board over USB.
3. Create `include/app_config_local.h` from the example file.
4. Confirm the board appears as a USB serial adapter such as `/dev/cu.usbserial-*` on macOS.
5. Run `pio run -e esp32-usb-c-38pin -t upload --upload-port /dev/cu.usbserial-XXXX`.
6. Open the serial monitor with `pio device monitor -p /dev/cu.usbserial-XXXX -b 115200`.
7. Confirm the startup summary shows the expected BLE name and auth mode.

This board uses a CP2102 USB-to-UART bridge. It does not use native USB CDC, so the normal Arduino `Serial` console is exposed through the USB serial port created by the bridge chip.

## Bench test flow

Do this before any connection to the D5-Evo:

1. Put the ESP32, LM2596, and relay module on the 830-point breadboard.
2. Set the LM2596 output to `5.0V`.
3. Power the ESP32 and relay from the LM2596 output.
4. Flash the ESP32 firmware.
5. Open the Android or iPhone app.
6. Connect to the BLE device.
7. Authenticate if you enabled a PIN.
8. Tap `Trigger Gate`.
9. Confirm one clean relay click.
10. Confirm an immediate second press shows cooldown behavior.

After that passes, connect relay `COM` and `NO` to the D5-Evo `COM` and `TRG`.

## Android app

The Android app in [`android-app`](/Users/queenie/repos/chat/d5-evo-ble-gate/android-app) is now aligned to the exact supported hardware:

- connect
- authenticate
- trigger relay
- read controller/auth/info status

Build steps:

1. Open [`android-app`](/Users/queenie/repos/chat/d5-evo-ble-gate/android-app) in Android Studio.
2. Let Gradle sync.
3. Install the app on an Android phone with BLE.
4. Enter the same PIN you set in `app_config_local.h`, if any.
5. Connect and trigger.

## iPhone app

The iPhone app in [`ios-app`](/Users/queenie/repos/chat/d5-evo-ble-gate/ios-app) is aligned to the same BLE flow:

- connect
- authenticate
- trigger relay
- read controller/auth/info status

Build steps:

1. Open [`D5EvoBleGate.xcodeproj`](/Users/queenie/repos/chat/d5-evo-ble-gate/ios-app/D5EvoBleGate.xcodeproj) in Xcode.
2. Select your Apple development team for signing if you want to install on a real iPhone.
3. Build and run.
4. Enter the same PIN you set in `app_config_local.h`, if any.
5. Connect and trigger.

## Removed from this repo

The following older paths were removed because they do not match the actual parts list:

- alternate `LOLIN32` build target
- gate-input/status sensing support
- extra command aliases such as `OPEN` and `1`
- `FRX`-based instructions
- generic multi-board instructions
