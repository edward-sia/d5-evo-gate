#pragma once

#include <Arduino.h>

#if __has_include("app_config_local.h")
#include "app_config_local.h"
#endif

namespace AppConfig {

#ifndef D5_EVO_RELAY_PIN
#define D5_EVO_RELAY_PIN 23
#endif

#ifndef D5_EVO_RELAY_ACTIVE_LEVEL
#define D5_EVO_RELAY_ACTIVE_LEVEL LOW
#endif

#ifndef D5_EVO_RELAY_PULSE_MS
#define D5_EVO_RELAY_PULSE_MS 500UL
#endif

#ifndef D5_EVO_COOLDOWN_MS
#define D5_EVO_COOLDOWN_MS 5000UL
#endif

#ifndef D5_EVO_AUTH_SESSION_MS
#define D5_EVO_AUTH_SESSION_MS 30000UL
#endif

#ifndef D5_EVO_DEVICE_NAME
#define D5_EVO_DEVICE_NAME "D5-EVO-Gate"
#endif

#ifndef D5_EVO_AUTH_PIN
#define D5_EVO_AUTH_PIN ""
#endif

// Defaults suit the common 38-pin ESP32 dev board plus the common 5V
// optocoupled relay module used in this project.
constexpr int kRelayPin = D5_EVO_RELAY_PIN;
constexpr int kRelayActiveLevel = D5_EVO_RELAY_ACTIVE_LEVEL;

constexpr unsigned long kRelayPulseMs = D5_EVO_RELAY_PULSE_MS;
constexpr unsigned long kCooldownMs = D5_EVO_COOLDOWN_MS;
constexpr unsigned long kAuthSessionMs = D5_EVO_AUTH_SESSION_MS;

constexpr char kDeviceName[] = D5_EVO_DEVICE_NAME;

// Leave empty to disable command authentication.
// Set this to a unique value before installing on a live gate.
constexpr char kAuthPin[] = D5_EVO_AUTH_PIN;

// Custom BLE service and characteristics used by the phone app or a BLE tool.
constexpr char kServiceUuid[] = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc1";
constexpr char kCommandCharUuid[] = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc2";
constexpr char kControllerStatusCharUuid[] = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc3";
constexpr char kInfoCharUuid[] = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc5";
constexpr char kAuthStatusCharUuid[] = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc6";

}  // namespace AppConfig
