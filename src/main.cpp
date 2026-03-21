#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>

#include "app_config.h"

namespace {

BLECharacteristic* g_controllerStatusChar = nullptr;
BLECharacteristic* g_authStatusChar = nullptr;
bool g_deviceConnected = false;
bool g_relayActive = false;
unsigned long g_relayStartedAtMs = 0;
unsigned long g_cooldownUntilMs = 0;
unsigned long g_authorizedUntilMs = 0;
String g_controllerStatus = "booting";
String g_authStatus = "disabled";

int relayInactiveLevel() {
  return AppConfig::kRelayActiveLevel == HIGH ? LOW : HIGH;
}

bool authEnabled() {
  return AppConfig::kAuthPin[0] != '\0';
}

bool authWindowActive(unsigned long nowMs) {
  return authEnabled() && nowMs < g_authorizedUntilMs;
}

void notifyIfConnected(BLECharacteristic* characteristic) {
  if (g_deviceConnected && characteristic != nullptr) {
    characteristic->notify();
  }
}

void setControllerStatus(const String& value, bool notify = true) {
  g_controllerStatus = value;
  if (g_controllerStatusChar != nullptr) {
    g_controllerStatusChar->setValue(value.c_str());
    if (notify) {
      notifyIfConnected(g_controllerStatusChar);
    }
  }
  Serial.printf("Controller status: %s\n", value.c_str());
}

void setAuthStatus(const String& value, bool notify = true) {
  g_authStatus = value;
  if (g_authStatusChar != nullptr) {
    g_authStatusChar->setValue(value.c_str());
    if (notify) {
      notifyIfConnected(g_authStatusChar);
    }
  }
  Serial.printf("Auth status: %s\n", value.c_str());
}

void syncAuthStatus(bool notify = true) {
  if (!authEnabled()) {
    if (g_authStatus != "disabled") {
      setAuthStatus("disabled", notify);
    }
    return;
  }

  const String next = authWindowActive(millis()) ? "authorized" : "required";
  if (g_authStatus != next) {
    setAuthStatus(next, notify);
  }
}

bool cooldownActive(unsigned long nowMs) {
  return nowMs < g_cooldownUntilMs;
}

bool triggerRelay() {
  const unsigned long nowMs = millis();
  if (g_relayActive) {
    setControllerStatus("busy");
    return false;
  }

  if (cooldownActive(nowMs)) {
    setControllerStatus("cooldown");
    return false;
  }

  digitalWrite(AppConfig::kRelayPin, AppConfig::kRelayActiveLevel);
  g_relayActive = true;
  g_relayStartedAtMs = nowMs;
  g_cooldownUntilMs = nowMs + AppConfig::kCooldownMs;
  setControllerStatus("pulsing");
  Serial.println("Gate pulse started");
  return true;
}

bool authenticateSession(const String& pin) {
  if (!authEnabled()) {
    syncAuthStatus();
    return true;
  }

  if (pin == AppConfig::kAuthPin) {
    g_authorizedUntilMs = millis() + AppConfig::kAuthSessionMs;
    setAuthStatus("authorized");
    return true;
  }

  g_authorizedUntilMs = 0;
  setAuthStatus("denied");
  return false;
}

String normalizedCommand(const std::string& raw) {
  String command = String(raw.c_str());
  command.trim();
  command.toUpperCase();
  return command;
}

String extractAuthPin(const String& command) {
  if (command.startsWith("AUTH ")) {
    return command.substring(5);
  }

  return "";
}

class GateServerCallbacks : public BLEServerCallbacks {
 public:
  void onConnect(BLEServer* server) override {
    g_deviceConnected = true;
    (void)server;
    setControllerStatus(g_controllerStatus, false);
    syncAuthStatus(false);
    notifyIfConnected(g_authStatusChar);
    Serial.println("BLE client connected");
  }

  void onDisconnect(BLEServer* server) override {
    g_deviceConnected = false;
    g_authorizedUntilMs = 0;
    syncAuthStatus(false);
    delay(150);
    server->getAdvertising()->start();
    Serial.println("BLE client disconnected");
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic* characteristic) override {
    const String command = normalizedCommand(characteristic->getValue());
    Serial.printf("Command received: %s\n", command.c_str());

    const String authPin = extractAuthPin(command);
    if (authPin.length() > 0 || command == "AUTH") {
      authenticateSession(authPin);
      return;
    }

    if (command == "TRIGGER") {
      if (authEnabled() && !authWindowActive(millis())) {
        setControllerStatus("locked");
        syncAuthStatus();
        return;
      }
      triggerRelay();
      return;
    }

    setControllerStatus("bad-command");
  }
};

void initBle() {
  BLEDevice::init(AppConfig::kDeviceName);

  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new GateServerCallbacks());

  BLEService* service = server->createService(AppConfig::kServiceUuid);

  BLECharacteristic* commandChar = service->createCharacteristic(
      AppConfig::kCommandCharUuid, BLECharacteristic::PROPERTY_WRITE);
  commandChar->setCallbacks(new CommandCallbacks());

  g_controllerStatusChar = service->createCharacteristic(
      AppConfig::kControllerStatusCharUuid,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  g_controllerStatusChar->addDescriptor(new BLE2902());
  g_controllerStatusChar->setValue(g_controllerStatus.c_str());

  BLECharacteristic* infoChar = service->createCharacteristic(
      AppConfig::kInfoCharUuid, BLECharacteristic::PROPERTY_READ);
  infoChar->setValue(authEnabled() ? "Write AUTH <pin>, then TRIGGER"
                                   : "Write TRIGGER");

  g_authStatusChar = service->createCharacteristic(
      AppConfig::kAuthStatusCharUuid,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  g_authStatusChar->addDescriptor(new BLE2902());
  g_authStatusChar->setValue(g_authStatus.c_str());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(AppConfig::kServiceUuid);
  advertising->setScanResponse(true);
  advertising->start();
  Serial.println("BLE advertising started");
}

void updateRelayPulse() {
  if (!g_relayActive) {
    return;
  }

  const unsigned long nowMs = millis();
  if (nowMs - g_relayStartedAtMs < AppConfig::kRelayPulseMs) {
    return;
  }

  digitalWrite(AppConfig::kRelayPin, relayInactiveLevel());
  g_relayActive = false;
  setControllerStatus(cooldownActive(nowMs) ? "cooldown" : "ready");
  Serial.println("Gate pulse ended");
}

void updateCooldown() {
  if (g_relayActive) {
    return;
  }

  const unsigned long nowMs = millis();
  if (cooldownActive(nowMs)) {
    return;
  }

  if (g_controllerStatus != "ready") {
    setControllerStatus("ready");
  }
}

void updateAuthWindow() {
  if (!authEnabled()) {
    return;
  }

  if (g_authStatus != "authorized") {
    return;
  }

  if (authWindowActive(millis())) {
    return;
  }

  syncAuthStatus();
}

void printStartupSummary() {
  Serial.println();
  Serial.println("D5-Evo BLE Gate");
  Serial.printf("Device name: %s\n", AppConfig::kDeviceName);
  Serial.printf("Relay pin: GPIO%d\n", AppConfig::kRelayPin);
  Serial.printf("Relay trigger level: %s\n",
                AppConfig::kRelayActiveLevel == LOW ? "active-low" : "active-high");
  Serial.printf("Relay pulse: %lu ms\n", AppConfig::kRelayPulseMs);
  Serial.printf("Cooldown: %lu ms\n", AppConfig::kCooldownMs);
  Serial.printf("Authentication: %s\n", authEnabled() ? "enabled" : "disabled");
  Serial.println("Bench test flow: connect phone app -> trigger relay -> hear relay click");
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(AppConfig::kRelayPin, OUTPUT);
  digitalWrite(AppConfig::kRelayPin, relayInactiveLevel());

  initBle();
  setControllerStatus("ready", false);
  syncAuthStatus(false);
  printStartupSummary();
}

void loop() {
  updateRelayPulse();
  updateCooldown();
  updateAuthWindow();
  delay(10);
}
