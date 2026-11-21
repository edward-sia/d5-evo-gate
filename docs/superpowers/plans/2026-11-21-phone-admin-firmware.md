# Phone Admin: Firmware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ESP32 keeps a list of joined phones, lets one admin phone add and remove them with one-time codes, and still accepts the shared passphrase until the admin turns it off.

**Architecture:** The auth and admin logic moves out of `src/main.cpp` into a small library, `lib/gate_core`, that has no Arduino or BLE dependency. That library is unit tested on the laptop. `main.cpp` keeps BLE and the relay and calls the library. A Python reference implementation defines every key derivation and produces the test vectors that the firmware and, later, both apps must match.

**Tech Stack:** PlatformIO, Arduino-ESP32 2.0.17 (mbedTLS 2.28, `Preferences` for NVS), Unity for native tests, Python 3.12 standard library, `bleak` for the bench client.

**Spec:** `docs/superpowers/specs/2026-11-21-phone-admin-design.md`

**This is plan 1 of 3.** The iOS plan and the Android plan are written after this firmware passes its bench test. They consume `docs/test-vectors.json` and the command table in the spec.

## Global Constraints

- Relay code is not changed: `GPIO23`, active-high default, 500 ms pulse, cooldown, momentary only.
- Existing UUIDs do not change. The one new characteristic is `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc7`, read only.
- `AUTHRESP <hex>`, `PED` and `TRIGGER` keep their current behaviour and the legacy algorithm (label `D5-EVO-AUTH-V1|`, 2048 rounds) is byte-for-byte unchanged.
- Every keyed hash is HMAC-SHA256. Hashed messages are ASCII. `C`, `Np`, `Ni` and ids appear in messages as uppercase hex text.
- No key or secret is ever written to a BLE characteristic or printed to serial.
- All new command arguments are hex or digits, because commands are uppercased before parsing.
- Limit of 16 phones including waiting codes. Names are 1 to 24 bytes.
- Waiting codes live in RAM only. Phones, the admin key and the shared-passphrase switch live in NVS.
- Real secrets stay in ignored files. Do not print or commit `include/app_config_local.h`.
- When behaviour changes, update `README.md`, `AGENTS.md`, `CLAUDE.md` and `docs/architecture.md` in the same task.
- If `pio` is not on `PATH`, use `~/.platformio/penv/bin/pio`.
- Native tests link against CommonCrypto, so they run on macOS only.

## Review Focus

1. Reboot or power cut after a phone joins: the phone must still unlock after restart. Pinned in Task 3, `test_phones_survive_reload`.
2. Malformed commands from any nearby phone (odd-length hex, missing arguments, 500 bytes of junk, lowercase): the gate must answer `ERR BAD-ARGS` or `bad-command`, change nothing and not crash. Pinned in Task 4, `test_malformed_commands_change_nothing`.
3. An old app against new firmware once the admin has turned the shared passphrase off: `AUTHRESP` with the correct passphrase must be denied and `PED` must stay locked. Pinned in Task 4, `test_legacy_off_denies_correct_passphrase`.
4. The list is full: the 17th `INVITE` must fail with `ERR FULL`, and removing a phone must free a place. Pinned in Task 5, `test_invite_fails_when_full_then_succeeds_after_revoke`.
5. Uptime beyond 49.7 days (past 32-bit milliseconds): a 7-day code created then must expire 7 days later, not at once and not never. Pinned in Task 3, `test_invite_expiry_past_32bit_uptime`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `tools/gate_crypto.py` | Reference implementation of every derivation. The source of truth. |
| `tools/gen_vectors.py` | Writes `docs/test-vectors.json` from fixed inputs. |
| `tools/test_gate_crypto.py` | Fails if the JSON file and the reference disagree. |
| `tools/gate_cli.py` | Bench client. Acts as admin phone or tenant phone from the laptop. |
| `lib/gate_core/crypto_primitives.h` | HMAC-SHA256, SHA-256, PBKDF2 declarations. |
| `lib/gate_core/crypto_primitives_mbedtls.cpp` | ESP32 backend. |
| `lib/gate_core/crypto_primitives_commoncrypto.cpp` | macOS test backend. |
| `lib/gate_core/gate_crypto.h/.cpp` | Derivations and proofs built on the primitives. Hex helpers. |
| `lib/gate_core/gate_store.h/.cpp` | Phones, waiting codes, admin key, shared-passphrase switch. |
| `lib/gate_core/gate_protocol.h/.cpp` | Session state and command handling. |
| `src/nvs_backend.h/.cpp` | `StoreBackend` on `Preferences`. |
| `src/main.cpp` | BLE, relay, wiring. Auth logic removed from here. |
| `test/support/test_support.h` | In-memory backend and counting random source for tests. |
| `test/test_crypto/`, `test/test_store/`, `test/test_protocol/` | Unity tests, native env. |

Shared fixed inputs for all vectors:

```text
setupCode = "correct horse battery staple"     legacyPin = "bench-pass"
C  = 000102030405060708090A0B0C0D0E0F          id = A1B2C3D4
Np = 101112131415161718191A1B1C1D1E1F          Ni = 202122232425262728292A2B2C2D2E2F
```

---

### Task 1: Python reference and test vectors

**Files:**
- Create: `tools/gate_crypto.py`, `tools/gen_vectors.py`, `tools/test_gate_crypto.py`, `docs/test-vectors.json`

**Interfaces:**
- Produces: `docs/test-vectors.json` with keys `inputs`, `rounds` and `expected`. Functions in `tools/gate_crypto.py`: `setup_key`, `fingerprint`, `claim_proof`, `admin_key`, `invite_secret`, `join_proof`, `phone_key`, `auth_proof`, `legacy_response`. All return `bytes`.

- [ ] **Step 1: Write the failing test** in `tools/test_gate_crypto.py`

```python
import json, pathlib, unittest
import gate_crypto as g
import gen_vectors

VECTORS = pathlib.Path(__file__).parent.parent / "docs" / "test-vectors.json"

class VectorTest(unittest.TestCase):
    def test_file_matches_reference(self):
        self.assertEqual(json.loads(VECTORS.read_text()), gen_vectors.build())

    def test_pinned_values(self):
        e = gen_vectors.build()["expected"]
        self.assertEqual(e["setupKey"], "E6E9982D77ABFE9B31A9A2E07C418630EBCFE6DCC66C19E36560E0B893CC86A3")
        self.assertEqual(e["legacyResponse"], "F9A8B42C66D1B8C7C5F6B1076DF6227307825BF3E49BC19172A0E971CF057769")
        self.assertEqual(e["inviteSecret"], "0B752903D8BD576C7CA04E8057913644")
```

- [ ] **Step 2: Run it and see it fail**

Run: `cd tools && python3 -m unittest test_gate_crypto -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'gate_crypto'`

- [ ] **Step 3: Write `tools/gate_crypto.py`.** This body is the definition of the byte format, so copy it exactly.

```python
import hashlib, hmac

ROUNDS = 10000
SALT = b"D5-EVO-SETUP-V1"

def _h(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("ascii"), hashlib.sha256).digest()

def setup_key(code: str) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"), SALT, ROUNDS, 32)

def fingerprint(setup: bytes) -> bytes:        return _h(setup, "SETUPFP")[:8]
def claim_proof(setup, c, np) -> bytes:        return _h(setup, f"CLAIM|{c}|{np}")
def admin_key(setup, c, np) -> bytes:          return _h(setup, f"ADMINKEY|{c}|{np}")
def invite_secret(admin, id_, ni) -> bytes:    return _h(admin, f"INVITE|{id_}|{ni}")[:16]
def join_proof(secret, c, np) -> bytes:        return _h(secret, f"JOIN|{c}|{np}")
def phone_key(secret, c, np) -> bytes:         return _h(secret, f"PHONEKEY|{c}|{np}")
def auth_proof(key, c) -> bytes:               return _h(key, f"AUTH|{c}")

def legacy_response(pin: str, c: str) -> bytes:
    p, cb = pin.encode("utf-8"), c.encode("ascii")
    d = hashlib.sha256(b"D5-EVO-AUTH-V1|" + p + cb).digest()
    for _ in range(2047):
        d = hashlib.sha256(d + p + cb).digest()
    return d
```

`c`, `np`, `ni` and `id_` are uppercase hex strings.

- [ ] **Step 4: Write `tools/gen_vectors.py`** with `build() -> dict` and a `__main__` that writes the JSON file with `indent=2`. `build()` uses the shared fixed inputs above and returns `{"inputs": {...}, "rounds": 10000, "expected": {...}}`. `expected` has these keys, all uppercase hex: `setupKey`, `setupFingerprint`, `claimProof`, `adminKey`, `inviteSecret`, `joinProof`, `phoneKey`, `phoneAuthProof`, `adminAuthProof`, `legacyResponse`. `phoneAuthProof` is `auth_proof(phoneKey, C)`, `adminAuthProof` is `auth_proof(adminKey, C)`.

- [ ] **Step 5: Generate and test**

Run: `cd tools && python3 gen_vectors.py && python3 -m unittest test_gate_crypto -v`
Expected: `OK`, 2 tests.

- [ ] **Step 6: Commit** `tools/gate_crypto.py tools/gen_vectors.py tools/test_gate_crypto.py docs/test-vectors.json` with message `feat: add reference key derivations and test vectors`.

---

### Task 2: `gate_crypto` in C++ with a native test env

**Files:**
- Create: `lib/gate_core/crypto_primitives.h`, `lib/gate_core/crypto_primitives_mbedtls.cpp`, `lib/gate_core/crypto_primitives_commoncrypto.cpp`, `lib/gate_core/gate_crypto.h`, `lib/gate_core/gate_crypto.cpp`, `test/test_crypto/test_main.cpp`
- Modify: `platformio.ini`

**Interfaces:**
- Consumes: expected values from `docs/test-vectors.json`.
- Produces, all in `namespace gate`:

```cpp
// crypto_primitives.h
bool sha256(const uint8_t* data, size_t len, uint8_t out[32]);
bool hmacSha256(const uint8_t* key, size_t keyLen, const uint8_t* msg, size_t msgLen, uint8_t out[32]);
bool pbkdf2Sha256(const uint8_t* password, size_t passwordLen, const uint8_t* salt, size_t saltLen,
                  uint32_t rounds, uint8_t out[32]);

// gate_crypto.h
using Key = std::array<uint8_t, 32>;
using InviteSecret = std::array<uint8_t, 16>;
using SetupFingerprint = std::array<uint8_t, 8>;
constexpr uint32_t kSetupRounds = 10000;

std::string hexEncode(const uint8_t* bytes, size_t len);              // uppercase
bool hexDecode(const std::string& hex, uint8_t* out, size_t outLen);  // false unless exactly outLen*2 hex chars
bool constantTimeEquals(const uint8_t* a, const uint8_t* b, size_t len);

bool deriveSetupKey(const std::string& setupCode, Key& out);
bool deriveSetupFingerprint(const Key& setupKey, SetupFingerprint& out);
bool claimProof(const Key& setupKey, const std::string& cHex, const std::string& npHex, Key& out);
bool deriveAdminKey(const Key& setupKey, const std::string& cHex, const std::string& npHex, Key& out);
bool deriveInviteSecret(const Key& adminKey, const std::string& idHex, const std::string& niHex, InviteSecret& out);
bool joinProof(const InviteSecret& secret, const std::string& cHex, const std::string& npHex, Key& out);
bool derivePhoneKey(const InviteSecret& secret, const std::string& cHex, const std::string& npHex, Key& out);
bool authProof(const Key& key, const std::string& cHex, Key& out);
std::string legacyAuthResponseHex(const std::string& pin, const std::string& cHex);  // "" on failure
```

- [ ] **Step 1: Add the native env to `platformio.ini`**

```ini
[env:esp32-usb-c-38pin]
; existing lines stay
test_ignore = *

[env:native]
platform = native
test_framework = unity
build_flags = -std=c++17 -I test/support
```

- [ ] **Step 2: Write the failing tests** in `test/test_crypto/test_main.cpp`. One Unity test per row below. Each calls the function with the shared fixed inputs and compares `hexEncode` of the result with the literal.

| Test | Call | Expected hex |
| --- | --- | --- |
| `test_setup_key` | `deriveSetupKey("correct horse battery staple")` | `E6E9982D77ABFE9B31A9A2E07C418630EBCFE6DCC66C19E36560E0B893CC86A3` |
| `test_fingerprint` | `deriveSetupFingerprint(setupKey)` | `61BAD2487040A2CE` |
| `test_claim_proof` | `claimProof(setupKey, C, Np)` | `1ED62E1175D8603BA5DCD52D6584EDCA2765F3F4D1E5734213B98C1F938EF054` |
| `test_admin_key` | `deriveAdminKey(setupKey, C, Np)` | `DAD34BE6276E9705CEBBD86F21A2E83BD9AD14459907070B2D3E41F207D76126` |
| `test_invite_secret` | `deriveInviteSecret(adminKey, "A1B2C3D4", Ni)` | `0B752903D8BD576C7CA04E8057913644` |
| `test_join_proof` | `joinProof(inviteSecret, C, Np)` | `8A8DB9C9C378BA1D9BDFB5FD05715997F4829F029F5ECFE5DB906C4B60252834` |
| `test_phone_key` | `derivePhoneKey(inviteSecret, C, Np)` | `E678A60014BDF132203C3FCE81C6B25B5EFE7BB9F97A37FA98C815C86C6A7517` |
| `test_phone_auth_proof` | `authProof(phoneKey, C)` | `3D2E910F0124CF136C2B5F779704A2604EAFEC3DB536302FFBFC9832B08CCF7C` |
| `test_admin_auth_proof` | `authProof(adminKey, C)` | `1935CEE147190DC85859820508B4ECEE482C09EB4C1EA9494159F1C9056C0AD8` |
| `test_legacy_response` | `legacyAuthResponseHex("bench-pass", C)` | `F9A8B42C66D1B8C7C5F6B1076DF6227307825BF3E49BC19172A0E971CF057769` |

Also `test_hex_decode_rejects_bad_input`: `hexDecode` returns false for `"0G"`, for `"ABC"` and for a string one byte too long or too short, and true for `"a1B2"` into 2 bytes giving `{0xA1, 0xB2}`.

- [ ] **Step 3: Run and see it fail**

Run: `pio test -e native -f test_crypto`
Expected: build error, `gate_crypto.h` not found.

- [ ] **Step 4: Implement the primitives.** `crypto_primitives_mbedtls.cpp` is wrapped in `#ifdef ARDUINO` and uses `mbedtls_md_hmac`, `mbedtls_sha256_ret` and `mbedtls_pkcs5_pbkdf2_hmac`. `crypto_primitives_commoncrypto.cpp` is wrapped in `#if defined(__APPLE__) && !defined(ARDUINO)` and uses `CCHmac`, `CC_SHA256` and `CCKeyDerivationPBKDF`.

- [ ] **Step 5: Implement `gate_crypto.cpp`.** Messages are built exactly as in `tools/gate_crypto.py`. PBKDF2 salt is the 15 ASCII bytes `D5-EVO-SETUP-V1`. `legacyAuthResponseHex` is the algorithm now in `src/main.cpp:95-119`, moved here unchanged.

- [ ] **Step 6: Run and see it pass**

Run: `pio test -e native -f test_crypto`
Expected: 11 tests, 0 failures.

- [ ] **Step 7: Check the ESP32 build still compiles**

Run: `pio run -e esp32-usb-c-38pin`
Expected: `SUCCESS`.

- [ ] **Step 8: Commit** with message `feat: add gate key derivations with native tests`.

---

### Task 3: `GateStore`

**Files:**
- Create: `lib/gate_core/gate_store.h`, `lib/gate_core/gate_store.cpp`, `test/support/test_support.h`, `test/test_store/test_main.cpp`

**Interfaces:**
- Consumes: `Key`, `InviteSecret`, `SetupFingerprint` from Task 2.
- Produces, in `namespace gate`:

```cpp
using PhoneId = std::array<uint8_t, 4>;
struct Phone  { PhoneId id; std::string name; Key key; };
struct Invite { PhoneId id; std::string name; InviteSecret secret; std::array<uint8_t, 16> ni; uint64_t expiresAtMs; };

class StoreBackend {
 public:
  virtual ~StoreBackend() = default;
  virtual bool load(const char* key, std::vector<uint8_t>& out) = 0;   // false when absent
  virtual bool save(const char* key, const std::vector<uint8_t>& data) = 0;
};

class GateStore {
 public:
  static constexpr size_t kMaxEntries = 16;
  static constexpr size_t kMaxNameBytes = 24;
  explicit GateStore(StoreBackend& backend);
  void begin(const SetupFingerprint* fingerprint);   // nullptr when no setup code is configured
  bool hasAdminKey() const;
  const Key& adminKey() const;
  bool setAdminKey(const Key& key, const SetupFingerprint& fingerprint);
  bool legacyEnabled() const;                        // default true
  bool setLegacyEnabled(bool enabled);
  const std::vector<Phone>& phones() const;
  const Phone* findPhone(const PhoneId& id) const;
  bool addPhone(const Phone& phone);                 // false when full or the id exists
  bool removePhone(const PhoneId& id);
  const std::vector<Invite>& invites(uint64_t nowMs);          // drops expired codes first
  const Invite* findInvite(const PhoneId& id, uint64_t nowMs);
  bool addInvite(const Invite& invite, uint64_t nowMs);        // false when phones + invites == 16
  bool removeInvite(const PhoneId& id);
};
```

`test/support/test_support.h` produces `class FakeBackend : public gate::StoreBackend` backed by a `std::map<std::string, std::vector<uint8_t>>` that is public as `data`, plus `void countingRandom(uint8_t* out, size_t len)` which writes 0x00, 0x01, 0x02… continuing across calls, and `void resetCountingRandom()`.

Backend keys: `"admin"` (8 fingerprint bytes then 32 key bytes), `"legacy"` (1 byte), `"phones"` (version byte `1`, count byte, then per phone: 4 id bytes, 1 name-length byte, the name, 32 key bytes).

- [ ] **Step 1: Write the failing tests** in `test/test_store/test_main.cpp`

| Test | Asserts |
| --- | --- |
| `test_empty_store_defaults` | After `begin(&fp)`: `hasAdminKey()` false, `legacyEnabled()` true, `phones()` empty. |
| `test_phones_survive_reload` | Add two phones. Build a second `GateStore` on the same `FakeBackend` and call `begin`. `findPhone` returns both with the same name and key. |
| `test_admin_key_survives_reload_with_same_fingerprint` | `setAdminKey(k, fp)`, reload with `begin(&fp)`: `adminKey() == k`. |
| `test_admin_key_cleared_when_fingerprint_changes` | Reload with a different fingerprint, and separately with `nullptr`: `hasAdminKey()` false both times. Phones are still present. |
| `test_legacy_switch_survives_reload` | `setLegacyEnabled(false)`, reload: `legacyEnabled()` false. |
| `test_invites_do_not_survive_reload` | `addInvite`, reload: `invites(0)` empty. |
| `test_invite_expires` | Invite with `expiresAtMs = 1000`: found at `nowMs = 999`, not found at `1000`. |
| `test_invite_expiry_past_32bit_uptime` | `now = 5'000'000'000`. Invite with `expiresAtMs = now + 604'800'000`: found at `now + 604'799'999`, not found at `now + 604'800'000`. |
| `test_capacity_counts_phones_and_invites` | 10 phones and 6 invites succeed. An 11th phone and a 7th invite both return false. After `removeInvite`, `addPhone` succeeds. |
| `test_duplicate_id_rejected` | `addPhone` with an id already used by a phone returns false. `addInvite` with an id used by a phone or an invite returns false. |
| `test_remove_phone_persists` | `removePhone`, reload: `findPhone` returns `nullptr`. |
| `test_corrupt_phone_blob_loads_empty` | Set `backend.data["phones"]` to `{9, 200, 1, 2}` and to an empty vector. `begin` does not crash and `phones()` is empty. |
| `test_name_too_long_rejected` | `addPhone` and `addInvite` with a 25-byte name or an empty name return false. |

- [ ] **Step 2: Run and see it fail**

Run: `pio test -e native -f test_store`
Expected: build error, `gate_store.h` not found.

- [ ] **Step 3: Implement `test_support.h`, `gate_store.h` and `gate_store.cpp`.** Every change to phones, the admin key or the switch is saved before the method returns true. If `save` fails, the method returns false and the in-memory state is left as it was.

- [ ] **Step 4: Run and see it pass**

Run: `pio test -e native -f test_store`
Expected: 13 tests, 0 failures.

- [ ] **Step 5: Commit** with message `feat: add gate store for phones, codes and admin key`.

---

### Task 4: `GateProtocol` sessions and unlock commands

**Files:**
- Create: `lib/gate_core/gate_protocol.h`, `lib/gate_core/gate_protocol.cpp`, `test/test_protocol/test_main.cpp`

**Interfaces:**
- Consumes: Task 2 functions, `GateStore` from Task 3, `FakeBackend` and `countingRandom`.
- Produces, in `namespace gate`:

```cpp
struct GateConfig {
  std::string legacyPin;          // "" when not configured
  bool setupConfigured = false;
  Key setupKey{};
  SetupFingerprint setupFingerprint{};
  uint64_t sessionMs = 30000;
  uint64_t lockoutMs = 15000;
};
using RandomFn = void (*)(uint8_t* out, size_t len);
enum class Outcome { Done, Pulse, Locked, BadCommand };

class GateProtocol {
 public:
  static constexpr int kProtocolVersion = 2;
  GateProtocol(const GateConfig& config, GateStore& store, RandomFn random);
  void onConnect(uint64_t nowMs);
  void onDisconnect(uint64_t nowMs);
  Outcome handleCommand(const std::string& rawCommand, uint64_t nowMs);
  bool authEnabled() const;                       // legacyPin set, or setupConfigured
  bool legacyAccepted() const;                    // legacyPin set and store.legacyEnabled()
  std::string authStatus(uint64_t nowMs) const;   // disabled | required | authorized | denied | unknown
  const std::string& challengeHex() const;        // "disabled" when auth is off
  const std::string& reply() const;               // "" until a command sets it
};
```

Rules this task implements:

- `handleCommand` trims and uppercases the input itself.
- The constructor draws no random bytes. The first challenge is made in `onConnect`.
- Arguments are checked before anything else, so a malformed command always replies `ERR BAD-ARGS`.
- When the store fails to save, `CLAIM`, `JOIN`, `REVOKE` and `LEGACY` reply `ERR STORE-FAILED` and change nothing.
- `onConnect` and `onDisconnect` end the session, clear the lockout, clear the reply and make a new challenge of 16 random bytes. This matches today.
- Every `AUTHRESP`, `AUTH2` and `CLAIM` makes a new challenge, pass or fail.
- `PED` and `TRIGGER`: `Pulse` when auth is off or a session is active, otherwise `Locked`.
- `AUTHRESP <hex>`: succeeds only when `legacyAccepted()` and the response matches. Any other case is `denied` and starts the lockout.
- `AUTH2 ADMIN <proof>`: `denied` plus lockout when there is no admin key or the proof is wrong.
- `AUTH2 <id8> <proof>`: an id that is not a joined phone gives status `unknown` and no lockout. A wrong proof gives `denied` plus lockout.
- During a lockout, `AUTHRESP` and `AUTH2` give `denied` without checking, and `CLAIM` replies `ERR LOCKED-OUT`.
- `CLAIM <np32> <proof64>`: replies `ERR NO-SETUP` when `setupConfigured` is false. A wrong proof replies `ERR DENIED` and starts the lockout. Success stores the new admin key, starts an admin session and replies `OK`.
- `STATE` needs no session and replies `STATE 2 <adminSet> <legacyOn> <joined> <waiting>` with `0` or `1` for the two flags.
- Status `unknown` lasts until the next auth command or disconnect.
- A known verb with wrong arguments replies `ERR BAD-ARGS` and returns `Done`. An unknown verb returns `BadCommand` and leaves the reply unchanged.
- Proofs are compared with `constantTimeEquals`.

- [ ] **Step 1: Write the failing tests** in `test/test_protocol/test_main.cpp`. Each test builds a fresh `FakeBackend`, `GateStore` and `GateProtocol`, calls `resetCountingRandom()` and then `onConnect(0)`. With `countingRandom` the first challenge is `000102030405060708090A0B0C0D0E0F`, so the Task 2 literals apply to the first command.

| Test | Asserts |
| --- | --- |
| `test_auth_disabled_when_nothing_configured` | Empty pin, no setup: `authStatus` is `disabled`, `challengeHex()` is `disabled`, `PED` returns `Pulse`. |
| `test_ped_locked_before_auth` | Pin `bench-pass`: `PED` returns `Locked`, status `required`. |
| `test_legacy_authresp_then_ped` | `AUTHRESP F9A8B42C…7769` (full literal from Task 2): status `authorized`, `PED` returns `Pulse`. `trigger` in lowercase also returns `Pulse`. |
| `test_session_expires` | After the above, at `nowMs = 30000` status is `required` and `PED` returns `Locked`. |
| `test_wrong_authresp_locks_out` | Wrong hex: status `denied`. The correct response for the new challenge at `nowMs = 1000` is still `denied`. At `nowMs = 15000` status is `required`. |
| `test_challenge_rotates_after_each_attempt` | `challengeHex()` differs before and after an `AUTHRESP`. |
| `test_legacy_off_denies_correct_passphrase` | `store.setLegacyEnabled(false)`, setup configured, pin `bench-pass`: the correct `AUTHRESP` gives `denied`, and `PED` returns `Locked`. |
| `test_setup_only_requires_auth` | Empty pin, setup configured: status `required`, any `AUTHRESP` gives `denied`. |
| `test_claim_with_vector` | Setup configured with the Task 2 `setupKey`. `CLAIM 101112131415161718191A1B1C1D1E1F 1ED62E11…F054`: reply `OK`, `hexEncode(store.adminKey())` is `DAD34BE6…6126`, status `authorized`, `PED` returns `Pulse`. |
| `test_claim_wrong_proof` | Reply `ERR DENIED`, `store.hasAdminKey()` false, a second correct claim at once replies `ERR LOCKED-OUT`. |
| `test_claim_without_setup` | Reply `ERR NO-SETUP`. |
| `test_second_claim_replaces_admin_key` | Claim, reconnect, claim again with proofs computed by `claimProof` for the new challenge: the stored admin key changed, and `AUTH2 ADMIN` with a proof from the first key gives `denied`. |
| `test_auth2_phone` | Add a phone with a known key to the store. `AUTH2 <id> <authProof(key, challenge)>`: status `authorized`, `PED` returns `Pulse`. |
| `test_auth2_unknown_id` | `AUTH2 DEADBEEF <64 hex>`: status `unknown`, `PED` returns `Locked`. A correct `AUTH2` for a real phone straight after succeeds, which shows no lockout started. |
| `test_auth2_wrong_proof_locks_out` | Status `denied`, then a correct proof at once is still `denied`. |
| `test_auth2_admin_without_admin_key` | Status `denied`. |
| `test_state_reply` | Fresh gate with setup and pin: `STATE` replies `STATE 2 0 1 0 0`. After a claim and one added phone: `STATE 2 1 1 1 0`. |
| `test_reconnect_ends_session` | Authorize, `onDisconnect`, `onConnect`: status `required`, reply is `""`. |
| `test_malformed_commands_change_nothing` | For each of `AUTH2`, `AUTH2 A1B2C3D4`, `AUTH2 A1B2C3D4 ABC`, `AUTH2 A1B2C3D4 <63 hex>`, `CLAIM 00 11`, `AUTHRESP`, and a 500-character string of `Z`: no crash, status stays `required`, phones and admin key unchanged. The first five reply `ERR BAD-ARGS`. `AUTHRESP` with no argument and the `Z` string return `BadCommand`. |

- [ ] **Step 2: Run and see it fail**

Run: `pio test -e native -f test_protocol`
Expected: build error, `gate_protocol.h` not found.

- [ ] **Step 3: Implement `gate_protocol.h` and `gate_protocol.cpp`** for the rules above. Leave `INVITE`, `JOIN`, `LIST`, `REVOKE` and `LEGACY` returning `BadCommand` for now.

- [ ] **Step 4: Run and see it pass**

Run: `pio test -e native -f test_protocol`
Expected: 19 tests, 0 failures.

- [ ] **Step 5: Commit** with message `feat: add gate protocol sessions, claim and per-phone unlock`.

---

### Task 5: `GateProtocol` admin commands

**Files:**
- Modify: `lib/gate_core/gate_protocol.cpp`, `test/test_protocol/test_main.cpp`

**Interfaces:**
- Consumes: everything from Tasks 2 to 4.
- Produces: no new C++ names. These commands and replies:

| Command | Reply on success | Failure replies |
| --- | --- | --- |
| `INVITE <ttlSeconds> <nameHex>` | `INVITE <id8> <ni32>` | `ERR NOT-ADMIN`, `ERR BAD-ARGS`, `ERR FULL` |
| `JOIN <id8> <np32> <proof64>` | `OK` | `ERR BAD-ARGS`, `ERR LOCKED-OUT`, `ERR NOT-FOUND`, `ERR DENIED`, `ERR STORE-FAILED` |
| `LIST <index>` | `PHONE <index> <total> <id8> J 0 <nameHex> -` or `PHONE <index> <total> <id8> W <secondsLeft> <nameHex> <ni32>` | `ERR NOT-ADMIN`, `ERR BAD-ARGS`, `ERR NOT-FOUND` |
| `REVOKE <id8>` | `OK` | `ERR NOT-ADMIN`, `ERR BAD-ARGS`, `ERR NOT-FOUND` |
| `LEGACY <0 or 1>` | `OK` | `ERR NOT-ADMIN`, `ERR BAD-ARGS` |

Rules:

- `INVITE`, `LIST`, `REVOKE` and `LEGACY` need an active admin session. A phone session or a legacy session gets `ERR NOT-ADMIN`.
- `INVITE`: `ttlSeconds` is 60 to 604800. `nameHex` is 2 to 48 hex characters with an even length. The gate draws 4 random bytes for the id, then 16 for `Ni`. If the id is already in use it draws again. The secret is `deriveInviteSecret(adminKey, id, Ni)`. Expiry is `nowMs + ttlSeconds * 1000`.
- `JOIN` needs no session and does not start one. It makes a new challenge, pass or fail. A wrong proof replies `ERR DENIED`, starts the lockout and keeps the code. A missing or expired code replies `ERR NOT-FOUND` with no lockout. Success stores the phone with `derivePhoneKey(secret, challenge, Np)` and the code's name, then deletes the code. If the phone cannot be saved, the code is kept.
- `LIST` orders joined phones first, in stored order, then waiting codes. `<total>` is the count of both. `<secondsLeft>` rounds down.
- `REVOKE` removes a joined phone or a waiting code.

- [ ] **Step 1: Write the failing tests.** A helper `claimAdmin(protocol, config)` claims with a proof from `claimProof`. A helper `inviteAndJoin(...)` runs `INVITE`, reconnects, then sends `JOIN` with proofs from `joinProof`, and returns the phone key.

| Test | Asserts |
| --- | --- |
| `test_admin_commands_need_admin` | With no session, with a legacy session and with a phone session: `INVITE 600 414243`, `LIST 0`, `REVOKE A1B2C3D4` and `LEGACY 0` each reply `ERR NOT-ADMIN`, and the store is unchanged. |
| `test_invite_reply_and_secret` | After a claim, `INVITE 600 414243` replies `INVITE <8 hex> <32 hex>`. The stored invite has name `ABC`, and its secret equals `deriveInviteSecret(adminKey, id, ni)` computed from the reply. |
| `test_join_then_unlock` | `inviteAndJoin`, then `AUTH2 <id> <authProof(phoneKey, challenge)>` gives `authorized`, `PED` returns `Pulse`. The invite is gone and the phone's stored name is `ABC`. |
| `test_join_does_not_start_session` | After a successful `JOIN`, `PED` returns `Locked`. |
| `test_join_wrong_proof_keeps_code` | Reply `ERR DENIED`. The invite still exists. After the lockout time, a correct `JOIN` replies `OK`. |
| `test_auth2_for_waiting_code_is_unknown` | `AUTH2` with the id of a code that has not been used yet gives status `unknown` and no lockout. The joining app relies on this to know it must send `JOIN` again. |
| `test_join_code_works_once` | A second `JOIN` with the same id replies `ERR NOT-FOUND`. |
| `test_join_expired_code` | `INVITE 60 …` at `nowMs = 0`. `JOIN` at `nowMs = 60000` replies `ERR NOT-FOUND`. |
| `test_list_phones_then_codes` | One joined phone `ABC` and one waiting code `DEF` with 600 s left, listed at 1500 ms after it was created: `LIST 0` is `PHONE 0 2 <id> J 0 414243 -`, `LIST 1` is `PHONE 1 2 <id> W 598 444546 <ni>`, `LIST 2` is `ERR NOT-FOUND`. |
| `test_revoke_phone_gives_unknown` | Revoke a joined phone, reconnect: its `AUTH2` gives status `unknown` and `PED` returns `Locked`. |
| `test_revoke_waiting_code` | Revoke a waiting code: `JOIN` for it replies `ERR NOT-FOUND`. |
| `test_revoke_unknown_id` | Reply `ERR NOT-FOUND`. |
| `test_legacy_switch` | `LEGACY 0` replies `OK` and `store.legacyEnabled()` is false. `STATE` shows `legacyOn` as `0`. `LEGACY 1` turns it back on. `LEGACY 2` replies `ERR BAD-ARGS`. |
| `test_invite_fails_when_full_then_succeeds_after_revoke` | Fill the store to 16 entries. `INVITE` replies `ERR FULL`. After one `REVOKE`, `INVITE` succeeds. |
| `test_invite_bad_args` | `INVITE 59 4142`, `INVITE 604801 4142`, `INVITE 600 414`, `INVITE 600` followed by 50 hex characters, and `INVITE 600 ZZ` each reply `ERR BAD-ARGS`. |
| `test_codes_survive_admin_reclaim` | Create a code, reconnect, claim admin again. The earlier code still joins. |

- [ ] **Step 2: Run and see it fail**

Run: `pio test -e native -f test_protocol`
Expected: the 16 new tests fail, the 19 from Task 4 pass.

- [ ] **Step 3: Implement the five commands** in `gate_protocol.cpp`.

- [ ] **Step 4: Run all native tests**

Run: `pio test -e native`
Expected: 59 tests, 0 failures.

- [ ] **Step 5: Commit** with message `feat: add invite, join, list, revoke and legacy switch commands`.

---

### Task 6: Wire the library into the firmware

**Files:**
- Create: `src/nvs_backend.h`, `src/nvs_backend.cpp`
- Modify: `src/main.cpp`, `include/app_config.h`, `include/app_config_local.example.h`, `README.md`, `AGENTS.md`, `CLAUDE.md`, `docs/architecture.md`

**Interfaces:**
- Consumes: `gate::GateProtocol`, `gate::GateStore`, `gate::StoreBackend`, `gate::GateConfig`, `gate::deriveSetupKey`, `gate::deriveSetupFingerprint`.
- Produces: `class NvsBackend : public gate::StoreBackend` using `Preferences` namespace `"d5gate"`. Config values `AppConfig::kAdminSetupCode` (from `D5_EVO_ADMIN_SETUP_CODE`, default `""`) and `AppConfig::kReplyCharUuid = "4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc7"`.

This task has no native test. Its checks are the two builds and the bench test in Task 7.

- [ ] **Step 1: Add the config values** to `include/app_config.h` and document `D5_EVO_ADMIN_SETUP_CODE` in `include/app_config_local.example.h`. The comment says: leave empty to turn admin features off; use at least four random words or 20 characters; keep it in a password manager because it is the recovery key.

- [ ] **Step 2: Implement `NvsBackend`.** `load` uses `getBytesLength` and `getBytes`. `save` uses `putBytes` and returns false when fewer bytes were written than asked.

- [ ] **Step 3: Replace the auth code in `src/main.cpp`.**
  - Remove `computeAuthResponseHex`, `sha256Digest`, `appendSha256`, `authenticateSession`, `generateAuthChallenge`, `authWindowActive`, `authLockoutActive`, and the globals `g_authorizedUntilMs`, `g_authLockoutUntilMs`, `g_authChallengeHex`.
  - Add `uint64_t uptimeMs()` returning `esp_timer_get_time() / 1000`. Pass it wherever the protocol wants `nowMs`.
  - In `setup()`, before `initBle()`: build `GateConfig` from `AppConfig`. When `kAdminSetupCode` is not empty, call `deriveSetupKey`, time it with `millis()`, and print `Setup key stretch: <n> ms`. Call `store.begin(...)` with the fingerprint, or `nullptr` when no setup code is set.
  - `GateServerCallbacks` calls `protocol.onConnect` and `protocol.onDisconnect`.
  - `CommandCallbacks::onWrite` calls `protocol.handleCommand(raw, uptimeMs())` and maps the outcome: `Pulse` calls `pulsePedestrianRelay()`, `Locked` sets controller status `locked`, `BadCommand` sets `bad-command`, `Done` sets nothing. After every command it writes `protocol.challengeHex()` to the challenge characteristic, `protocol.reply()` to the reply characteristic, and refreshes auth status. All of this happens inside the callback, so the reply is in place before the phone's write completes.
  - `syncAuthStatus` and `updateAuthState` take the status string from `protocol.authStatus(uptimeMs())`.
  - `updateBleClientLease` ends the session through `protocol.onDisconnect` before it disconnects an idle client.
  - Add the reply characteristic in `initBle()`: read only, with `ClientActivityCallbacks`, initial value `""`.
  - Info text: `Write PED` when auth is off. `Read challenge, write AUTHRESP <hex>, then PED` when `legacyAccepted()`. Otherwise `Join with a code from the gate owner`.
  - `printStartupSummary` prints whether a setup code is configured, whether an admin key is stored, whether the shared passphrase is accepted, and the phone count. It prints no key and no code.
  - `loop()` may call only `protocol.authStatus`. Every other call on the protocol and the store happens inside BLE callbacks, as the auth code does today.
  - Do not touch `pulsePedestrianRelay`, `updateRelayPulse`, `updateCooldown` or `relayInactiveLevel`.

- [ ] **Step 4: Build**

Run: `pio run -e esp32-usb-c-38pin`
Expected: `SUCCESS`.

- [ ] **Step 5: Run the native tests again**

Run: `pio test -e native`
Expected: 59 tests, 0 failures.

- [ ] **Step 6: Check the relay code is untouched**

Run: `git diff main -- src/main.cpp | grep -E '^[-+].*(digitalWrite|kRelay|g_relay|kCooldownMs)'`
Expected: no output.

- [ ] **Step 7: Update the docs.**
  - `docs/architecture.md`: add the reply characteristic to the BLE surface table. Add the command table and the keys-and-proofs table from the spec. Replace the auth state diagram so it shows `unknown` and the two ways to get a session. Add the test vectors by pointing at `docs/test-vectors.json` and `tools/gate_crypto.py`. Add `pio test -e native` and `cd tools && python3 -m unittest test_gate_crypto` to the verification matrix. Describe what is in NVS and that `pio run -t erase` wipes it.
  - `README.md`: the setup code, the four config combinations from the spec, and the four migration steps.
  - `CLAUDE.md` and `AGENTS.md`: add `lib/gate_core`, `tools/` and the test commands to key files and commands. Add to "What To Preserve": no key or secret over BLE or serial; derivations must match `tools/gate_crypto.py`.

- [ ] **Step 8: Commit** with message `feat: use gate_core in firmware and store phones in NVS`.

---

### Task 7: Bench client and bench test

**Files:**
- Create: `tools/gate_cli.py`, `tools/requirements.txt` (one line: `bleak`)
- Modify: `.gitignore` (add `tools/.gate_cli.json`), `CLAUDE.md` and `AGENTS.md` (list that file under local secrets), `docs/architecture.md` (how to run the bench client)

**Interfaces:**
- Consumes: `tools/gate_crypto.py`, the UUIDs in `include/app_config.h`, the commands from Tasks 4 and 5.
- Produces: a command-line client with these subcommands. Each one scans for the service UUID, connects, does its work and disconnects.

| Subcommand | What it does |
| --- | --- |
| `state` | Writes `STATE`, prints the reply and the auth status. |
| `claim` | Asks for the setup code with `getpass`. Sends `CLAIM`. Saves the admin key. |
| `invite --name NAME --ttl SECONDS` | Admin session, `INVITE`. Prints the id and the code secret as hex. |
| `join --id ID --secret HEX --as LABEL` | Saves the candidate phone key under `LABEL`, sends `JOIN`, then checks with `AUTH2`. |
| `ped --as LABEL` | `AUTH2` with that phone's key, or the admin key for `--as admin`, then `PED`. Prints both statuses. |
| `list` | Admin session, `STATE`, then `LIST 0..n-1`. Prints names decoded from hex. |
| `revoke --id ID` | Admin session, `REVOKE`. |
| `legacy --on` / `legacy --off` | Admin session, `LEGACY`. |
| `legacy-ped` | Asks for the shared passphrase with `getpass`, sends `AUTHRESP`, then `PED`. Stands in for an old app. |

Keys are saved in `tools/.gate_cli.json`. The file is ignored by git. The setup code and the passphrase are never taken from command-line arguments and never saved.

- [ ] **Step 1: Write `tools/gate_cli.py`** with `argparse` and `bleak`. All writes are write-with-response. After each command write, read the reply characteristic, then the auth status characteristic.

- [ ] **Step 2: Check it starts**

Run: `python3 -m pip install -r tools/requirements.txt && python3 tools/gate_cli.py --help`
Expected: the nine subcommands are listed.

- [ ] **Step 3: Commit** with message `feat: add laptop bench client for the gate`.

- [ ] **Step 4: Bench test on real hardware.** This step needs the owner, the ESP32 on the bench with the relay connected, and nothing wired to the gate. Flash with a shared passphrase and a setup code set in `include/app_config_local.h`. Watch the serial monitor throughout. `CLAIM`, `JOIN`, `REVOKE` and `LEGACY` write to flash inside the BLE callback. If the serial log shows a stack overflow or a reset during rows 5, 6, 10 or 12, stop and report it. The fix is to move saves into `loop()`, which changes Task 3 and needs the plan revised.

| # | Do | Expect |
| --- | --- | --- |
| 1 | Boot | `Setup key stretch` is under 2000 ms. If it is over, halve `kSetupRounds` in `gate_crypto.h` and `ROUNDS` in `tools/gate_crypto.py`, rerun `gen_vectors.py`, update the literals in `test/test_crypto` and `tools/test_gate_crypto.py`, and start Task 7 Step 4 again. |
| 2 | `state` | `STATE 2 0 1 0 0` |
| 3 | `legacy-ped` | Relay clicks once. |
| 4 | Open the current phone app and press Pedestrian | Relay clicks once. This is the old-app check. |
| 5 | `claim` with a wrong code, then the right code after 15 s | `ERR DENIED`, then `OK`. |
| 6 | `invite --name Bench --ttl 600`, then `join … --as bench`, then `ped --as bench` | Relay clicks once. |
| 7 | `list` | One joined phone named `Bench`. |
| 8 | Unplug and replug the ESP32, then `ped --as bench` | Relay clicks once. |
| 9 | `invite --name Lost --ttl 600`, unplug and replug, `list` | `Lost` is gone. `Bench` is still there. |
| 10 | `legacy --off`, then `legacy-ped`, then the current phone app | Both denied. No click. |
| 11 | `legacy --on`, then `legacy-ped` | Relay clicks once. |
| 12 | `revoke` Bench's id, then `ped --as bench` | Auth status `unknown`. No click. |
| 13 | `ped --as admin` | Relay clicks once. |
| 14 | Connect with any BLE tool and stay idle for 60 s | The gate disconnects the client. |
| 15 | At idle, measure relay `COM` to `NO` | Open. |

- [ ] **Step 5: Record the result.** Add a dated line to `docs/architecture.md` under the verification matrix saying which rows passed, and commit. If any row failed, stop and report it. Do not mark the plan done.

---

## After this plan

- Write the iOS plan, then the Android plan. Each uses `docs/test-vectors.json` for unit tests and the command table in the spec.
- Publishing to the App Store and Play Store is a separate project.
