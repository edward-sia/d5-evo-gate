#pragma once

// Copy this file to app_config_local.h and change the values for your build.
// This keeps your personal BLE name and PIN out of the shared defaults.

// Common working defaults for the 38-pin USB-C ESP32 board and a single
// optocoupled 5V relay module.
#define D5_EVO_RELAY_PIN 23
#define D5_EVO_RELAY_ACTIVE_LEVEL LOW

// Recommended starting values for a personal local-only install.
#define D5_EVO_RELAY_PULSE_MS 500UL
#define D5_EVO_COOLDOWN_MS 5000UL
#define D5_EVO_AUTH_SESSION_MS 30000UL
#define D5_EVO_AUTH_LOCKOUT_MS 15000UL
#define D5_EVO_BLE_CLIENT_IDLE_TIMEOUT_MS 60000UL

// Personalize these before live use.
// Prefer a passphrase over a short PIN because it is easier to enter on a phone
// and much harder to guess. A good default is 4 random words or 14+ characters.
// Leave D5_EVO_AUTH_PIN empty only while bench testing if you want auth disabled.
#define D5_EVO_DEVICE_NAME "D5-EVO-Gate"
#define D5_EVO_AUTH_PIN ""
