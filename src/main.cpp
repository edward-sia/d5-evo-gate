#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <esp_system.h>
#include <mbedtls/sha256.h>

#include "app_config.h"

namespace {

constexpr char kAuthVersionLabel[] = "D5-EVO-AUTH-V1|";
constexpr size_t kAuthChallengeBytes = 16;
constexpr size_t kAuthDigestBytes = 32;
constexpr uint16_t kAuthHashRounds = 2048;
constexpr uint16_t kNoBleConnection = 0xFFFF;
constexpr char kHexDigits[] = "0123456789ABCDEF";

BLEServer* g_bleServer = nullptr;
BLECharacteristic* g_controllerStatusChar = nullptr;
BLECharacteristic* g_authStatusChar = nullptr;
BLECharacteristic* g_authChallengeChar = nullptr;
BLECharacteristic* g_infoChar = nullptr;
bool g_deviceConnected = false;
bool g_bleDisconnectPending = false;
uint16_t g_bleConnectionId = kNoBleConnection;
unsigned long g_lastBleClientActivityMs = 0;
bool g_relayActive = false;
unsigned long g_relayStartedAtMs = 0;
unsigned long g_cooldownUntilMs = 0;
unsigned long g_authorizedUntilMs = 0;
unsigned long g_authLockoutUntilMs = 0;
String g_controllerStatus = "booting";
String g_authStatus = "disabled";
String g_authChallengeHex = "disabled";

int relayInactiveLevel() {
  return AppConfig::kRelayActiveLevel == HIGH ? LOW : HIGH;
}

bool authEnabled() {
  return AppConfig::kAuthPin[0] != '\0';
}

bool authWindowActive(unsigned long nowMs) {
  return authEnabled() && nowMs < g_authorizedUntilMs;
}

bool authLockoutActive(unsigned long nowMs) {
  return authEnabled() && nowMs < g_authLockoutUntilMs;
}

void notifyIfConnected(BLECharacteristic* characteristic) {
  if (g_deviceConnected && characteristic != nullptr) {
    characteristic->notify();
  }
}

void markBleClientActivity() {
  if (g_deviceConnected) {
    g_lastBleClientActivityMs = millis();
  }
}

String hexEncode(const uint8_t* bytes, size_t size) {
  String output;
  output.reserve(size * 2);
  for (size_t index = 0; index < size; ++index) {
    output += kHexDigits[(bytes[index] >> 4) & 0x0F];
    output += kHexDigits[bytes[index] & 0x0F];
  }
  return output;
}

bool appendSha256(mbedtls_sha256_context& context, const uint8_t* data, size_t size) {
  return size == 0 || mbedtls_sha256_update_ret(&context, data, size) == 0;
}

bool sha256Digest(const uint8_t* firstData, size_t firstSize, const uint8_t* secondData,
                  size_t secondSize, const uint8_t* thirdData, size_t thirdSize,
                  uint8_t output[kAuthDigestBytes]) {
  mbedtls_sha256_context context;
  mbedtls_sha256_init(&context);

  bool ok = mbedtls_sha256_starts_ret(&context, 0) == 0 &&
            appendSha256(context, firstData, firstSize) &&
            appendSha256(context, secondData, secondSize) &&
            appendSha256(context, thirdData, thirdSize) &&
            mbedtls_sha256_finish_ret(&context, output) == 0;

  mbedtls_sha256_free(&context);
  return ok;
}

String computeAuthResponseHex(const char* pin, const String& challengeHex) {
  const uint8_t* labelBytes = reinterpret_cast<const uint8_t*>(kAuthVersionLabel);
  const auto labelSize = strlen(kAuthVersionLabel);
  const uint8_t* pinBytes = reinterpret_cast<const uint8_t*>(pin);
  const auto pinSize = strlen(pin);
  const String uppercaseChallenge = challengeHex;
  const uint8_t* challengeBytes =
      reinterpret_cast<const uint8_t*>(uppercaseChallenge.c_str());
  const auto challengeSize = uppercaseChallenge.length();

  uint8_t digest[kAuthDigestBytes] = {0};
  if (!sha256Digest(labelBytes, labelSize, pinBytes, pinSize, challengeBytes, challengeSize,
                    digest)) {
    return "";
  }

  for (uint16_t round = 1; round < kAuthHashRounds; ++round) {
    if (!sha256Digest(digest, sizeof(digest), pinBytes, pinSize, challengeBytes, challengeSize,
                      digest)) {
      return "";
    }
  }

  return hexEncode(digest, sizeof(digest));
}

void updateAuthChallengeValue() {
  if (g_authChallengeChar != nullptr) {
    g_authChallengeChar->setValue(g_authChallengeHex.c_str());
  }
}

void refreshInfoValue() {
  if (g_infoChar == nullptr) {
    return;
  }

  String value;
  if (!authEnabled()) {
    value = "Write PED";
  } else if (authLockoutActive(millis())) {
    value = "Wait for auth retry window, then read challenge again";
  } else {
    value = "Read challenge, write AUTHRESP <hex>, then PED";
  }

  g_infoChar->setValue(value.c_str());
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
  refreshInfoValue();
  Serial.printf("Auth status: %s\n", value.c_str());
}

void generateAuthChallenge() {
  if (!authEnabled()) {
    g_authChallengeHex = "disabled";
    updateAuthChallengeValue();
    refreshInfoValue();
    return;
  }

  uint8_t challengeBytes[kAuthChallengeBytes] = {0};
  esp_fill_random(challengeBytes, sizeof(challengeBytes));
  g_authChallengeHex = hexEncode(challengeBytes, sizeof(challengeBytes));
  updateAuthChallengeValue();
  refreshInfoValue();
  Serial.println("Auth challenge rotated");
}

void syncAuthStatus(bool notify = true) {
  if (!authEnabled()) {
    if (g_authStatus != "disabled") {
      setAuthStatus("disabled", notify);
    } else {
      refreshInfoValue();
    }
    return;
  }

  const unsigned long nowMs = millis();
  if (g_authStatus == "denied" && authLockoutActive(nowMs)) {
    refreshInfoValue();
    return;
  }

  const String next = authWindowActive(nowMs) ? "authorized" : "required";
  if (g_authStatus != next) {
    setAuthStatus(next, notify);
  } else {
    refreshInfoValue();
  }
}

bool cooldownActive(unsigned long nowMs) {
  return nowMs < g_cooldownUntilMs;
}

bool pulsePedestrianRelay() {
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
  Serial.println("Pedestrian pulse started");
  return true;
}

bool authenticateSession(String responseHex) {
  if (!authEnabled()) {
    syncAuthStatus();
    return true;
  }

  if (authLockoutActive(millis())) {
    setAuthStatus("denied");
    return false;
  }

  responseHex.trim();
  responseHex.toUpperCase();

  const String expectedResponse = computeAuthResponseHex(AppConfig::kAuthPin, g_authChallengeHex);
  generateAuthChallenge();

  if (expectedResponse.length() > 0 && responseHex == expectedResponse) {
    g_authorizedUntilMs = millis() + AppConfig::kAuthSessionMs;
    g_authLockoutUntilMs = 0;
    setAuthStatus("authorized");
    return true;
  }

  g_authorizedUntilMs = 0;
  g_authLockoutUntilMs = millis() + AppConfig::kAuthLockoutMs;
  setAuthStatus("denied");
  return false;
}

String trimmedCommand(const std::string& raw) {
  String command = String(raw.c_str());
  command.trim();
  return command;
}

String extractArgument(const String& command, const String& prefix) {
  if (!command.startsWith(prefix)) {
    return "";
  }

  String argument = command.substring(prefix.length());
  argument.trim();
  return argument;
}

class GateServerCallbacks : public BLEServerCallbacks {
 public:
  void onConnect(BLEServer* server) override {
    g_bleServer = server;
    g_deviceConnected = true;
    g_bleDisconnectPending = false;
    g_bleConnectionId = server->getConnId();
    g_lastBleClientActivityMs = millis();
    (void)server;
    g_authorizedUntilMs = 0;
    g_authLockoutUntilMs = 0;
    generateAuthChallenge();
    setControllerStatus(g_controllerStatus, false);
    syncAuthStatus(false);
    notifyIfConnected(g_authStatusChar);
    Serial.printf("BLE client connected: conn_id=%u\n", g_bleConnectionId);
  }

  void onDisconnect(BLEServer* server) override {
    g_deviceConnected = false;
    g_bleDisconnectPending = false;
    g_bleConnectionId = kNoBleConnection;
    g_lastBleClientActivityMs = 0;
    g_authorizedUntilMs = 0;
    g_authLockoutUntilMs = 0;
    syncAuthStatus(false);
    generateAuthChallenge();
    delay(150);
    server->getAdvertising()->start();
    Serial.println("BLE client disconnected");
  }
};

class ClientActivityCallbacks : public BLECharacteristicCallbacks {
 public:
  void onRead(BLECharacteristic* characteristic) override {
    (void)characteristic;
    markBleClientActivity();
  }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic* characteristic) override {
    markBleClientActivity();
    const String command = trimmedCommand(characteristic->getValue());
    String normalized = command;
    normalized.toUpperCase();
    Serial.printf("Command received: %s\n", normalized.c_str());

    const String authResponse = extractArgument(normalized, "AUTHRESP ");
    if (authResponse.length() > 0) {
      authenticateSession(authResponse);
      return;
    }

    if (normalized == "PED" || normalized == "TRIGGER") {
      if (authEnabled() && !authWindowActive(millis())) {
        setControllerStatus("locked");
        syncAuthStatus();
        return;
      }
      pulsePedestrianRelay();
      return;
    }

    setControllerStatus("bad-command");
  }
};

void initBle() {
  BLEDevice::init(AppConfig::kDeviceName);

  BLEServer* server = BLEDevice::createServer();
  g_bleServer = server;
  server->setCallbacks(new GateServerCallbacks());

  BLEService* service = server->createService(AppConfig::kServiceUuid);

  BLECharacteristic* commandChar = service->createCharacteristic(
      AppConfig::kCommandCharUuid, BLECharacteristic::PROPERTY_WRITE);
  commandChar->setCallbacks(new CommandCallbacks());

  g_controllerStatusChar = service->createCharacteristic(
      AppConfig::kControllerStatusCharUuid,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  g_controllerStatusChar->addDescriptor(new BLE2902());
  g_controllerStatusChar->setCallbacks(new ClientActivityCallbacks());
  g_controllerStatusChar->setValue(g_controllerStatus.c_str());

  g_authChallengeChar = service->createCharacteristic(
      AppConfig::kAuthChallengeCharUuid, BLECharacteristic::PROPERTY_READ);
  g_authChallengeChar->setCallbacks(new ClientActivityCallbacks());
  g_authChallengeChar->setValue(g_authChallengeHex.c_str());

  g_infoChar =
      service->createCharacteristic(AppConfig::kInfoCharUuid, BLECharacteristic::PROPERTY_READ);
  g_infoChar->setCallbacks(new ClientActivityCallbacks());

  g_authStatusChar = service->createCharacteristic(
      AppConfig::kAuthStatusCharUuid,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  g_authStatusChar->addDescriptor(new BLE2902());
  g_authStatusChar->setCallbacks(new ClientActivityCallbacks());
  g_authStatusChar->setValue(g_authStatus.c_str());

  generateAuthChallenge();
  refreshInfoValue();
  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(AppConfig::kServiceUuid);
  advertising->setScanResponse(true);
  advertising->start();
  Serial.println("BLE advertising started");
}

void updateBleClientLease() {
  if (!g_deviceConnected || g_bleDisconnectPending ||
      AppConfig::kBleClientIdleTimeoutMs == 0 || g_bleServer == nullptr ||
      g_bleConnectionId == kNoBleConnection) {
    return;
  }

  if (millis() - g_lastBleClientActivityMs < AppConfig::kBleClientIdleTimeoutMs) {
    return;
  }

  g_authorizedUntilMs = 0;
  g_authLockoutUntilMs = 0;
  syncAuthStatus(false);
  g_bleDisconnectPending = true;
  Serial.printf("BLE client idle for %lu ms; disconnecting conn_id=%u\n",
                AppConfig::kBleClientIdleTimeoutMs, g_bleConnectionId);
  g_bleServer->disconnect(g_bleConnectionId);
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
  Serial.println("Pedestrian pulse ended");
}

void updateCooldown() {
  if (g_relayActive) {
    return;
  }

  if (cooldownActive(millis())) {
    return;
  }

  if (g_controllerStatus != "ready") {
    setControllerStatus("ready");
  }
}

void updateAuthState() {
  if (!authEnabled()) {
    return;
  }

  if (g_authStatus == "authorized" && authWindowActive(millis())) {
    return;
  }

  syncAuthStatus();
}

void printStartupSummary() {
  Serial.println();
  Serial.println("D5-Evo BLE Pedestrian");
  Serial.printf("Device name: %s\n", AppConfig::kDeviceName);
  Serial.printf("Relay pin: GPIO%d\n", AppConfig::kRelayPin);
  Serial.printf("Relay trigger level: %s\n",
                AppConfig::kRelayActiveLevel == LOW ? "active-low" : "active-high");
  Serial.printf("Relay pulse: %lu ms\n", AppConfig::kRelayPulseMs);
  Serial.printf("Cooldown: %lu ms\n", AppConfig::kCooldownMs);
  Serial.printf("Authentication: %s\n", authEnabled() ? "challenge-response" : "disabled");
  Serial.printf("BLE client idle timeout: %lu ms\n", AppConfig::kBleClientIdleTimeoutMs);
  if (authEnabled()) {
    Serial.printf("Auth session: %lu ms\n", AppConfig::kAuthSessionMs);
    Serial.printf("Auth lockout: %lu ms\n", AppConfig::kAuthLockoutMs);
  }
  Serial.println("Bench test flow: connect phone app -> authenticate -> pulse PED relay");
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
  updateBleClientLease();
  updateRelayPulse();
  updateCooldown();
  updateAuthState();
  delay(10);
}
