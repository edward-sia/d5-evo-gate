# Phone Admin: Firmware Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans when implementation is explicitly authorized. Steps use checkbox syntax for tracking. This document does not authorize execution.

Status: protocol finalized; implementation and bench testing not authorized or performed
Created: 2026-11-21
Revised: 2026-11-22

**Goal:** Add individually revocable phones and one local admin without exposing
keys, accepting altered commands, losing access policy on storage errors, or
changing the intended pedestrian pulse.

**Architecture:** Keep BLE and hardware integration in src/main.cpp; isolate
crypto, storage, protocol and framing in lib/gate_core. BLE callbacks enqueue
bounded work; one owner serializes authorization and persistence. New clients
use mutually authenticated sessions and authenticated requests/replies. Legacy
clients retain a deliberately restricted migration path.

**Tech Stack:** platformio/espressif32 7.0.1, framework-arduinoespressif32
3.20017.241212+sha.dcc1105b, bundled mbedTLS 2.28.7, native NVS C APIs, C++/Unity,
Node 22.22.1 TypeScript crypto/fixtures, existing Kotlin/Swift client probes.
These are the inspected local baseline pinned by spec C8 for future implementation;
no package installation, configuration change or clean build occurred in this PR.

**Spec:** [Phone admin design](../specs/2026-11-21-phone-admin-design.md), revision
2026-11-22, accepted decisions D1-D9, requirements R1-R7, normative contract
C1-C9 and synthetic examples C10. D9 numeric tolerance remains provisional.

This replaces the earlier seven-task plan and its 59-test promise. The previous
crypto literals describe an obsolete proposal except the unchanged legacy
algorithm. Do not implement old message rules from PR history.

## Authorization and sequence

Current authorization covers only finalizing protocol documentation in PR #1.
D1-D9 are accepted, with D9 numeric timing tolerance provisional until tested.
Task 0 records completed documentation work; every implementation task remains
unchecked. Code requires a separate explicit instruction; PR merge and design
approval do not authorize implementation, firmware flashing or gate operation.

This remains the firmware plan, with a narrow early mobile compatibility proof.
Detailed iOS and Android product plans follow that proof, before full admin UI.
The mobile proof touches only diagnostic drivers and their existing BLE entry
points, not the final onboarding screens. Its future file scope is named below.

## Global Constraints

- Preserve GPIO23, active-high default, PED/COM only, 500 ms momentary pulse and
  existing 5 second cooldown policy. No position sensing/FRX/Status/board changes.
- Keep the six existing UUIDs; reply UUID is
  4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc7, read only.
- Preserve legacy AUTHRESP, D5-EVO-AUTH-V1| and 2048 rounds byte for byte. Legacy
  sessions never grant admin access. Raw PED/TRIGGER cannot use new-session auth.
- Every new mutation/pulse requires a verified request envelope; every trusted
  response requires a verified reply bound to the exact request.
- New protocol version is 2; setup KDF suite 1 uses 10,000 rounds.
  Do not tune a released KDF silently. Setup/gate ID are each 16 random bytes.
- Keys/tags are 32 bytes; invitation secret and random nonces 16 bytes; phone ID
  4 bytes. Names valid UTF-8, 1-24 bytes, no control characters.
- Capacity is 16 joined + waiting, excluding admin. TTL choices are exactly
  600, 86400, 604800 seconds. Use 64-bit monotonic uptime.
- Framing must support 20-byte write payloads, 512-byte reassembled messages,
  one outstanding request and a 5 second assembly deadline.
- Session duration 30 seconds, unauthenticated connection deadline
  15 seconds, hard connection cap 60 seconds, auth backoff 15 seconds across
  reconnects within one boot; preserve the existing 60 second idle safeguard.
- Installed storage/crypto failures lock electronic commands. No implicit
  no-auth, legacy reenablement, NVS erase or rollback to stale permissions.
- No automatic retry of a physical trigger after an uncertain result.
- No secrets in BLE values, logs, tracked files or app builds. Do not read or
  print real local secret files. Use fixed synthetic inputs for test evidence.
- Runtime docs change alongside future runtime implementation, not in this PR.
- New reference tooling uses TypeScript. Keep C++/Kotlin/Swift for existing
  subsystems. Native CommonCrypto backend may remain macOS-only if documented.

## Review Focus and traceability

| Failure class | Required result | Owning tasks/tests |
| --- | --- | --- |
| Proxy changes an authenticated command/reply or replays it elsewhere | Reject without mutation/pulse; retain client credentials on untrusted errors | Tasks 1/3/6: tamper_binding, role_and_gate_binding, stale_reply, no_credential_deletion |
| Full capacity, reclaim and interrupted enrollment | Convert a reserved slot; cancel old invites on reclaim; reconcile saved candidates | Tasks 2/4/6: join_at_capacity, reclaim_cancels_invites, lost_claim_receipt, lost_join_receipt |
| NVS error/power cut around revoke or legacy-off | No acknowledged policy rollback or silent fallback; indeterminate write enters recovery | Tasks 2/5/7: store_failure_matrix, revoke_power_cut, legacy_off_power_cut |
| Minimum MTU, hostile fragments and connection monopolization | Complete bounded messages only; expire old connections/work; no stale OK | Tasks 3/5/6: framing_matrix, connection_deadlines, stale_connection_work |
| Flash/crypto workload around a relay pulse | Measured timing bound, no duplicate pulse or unexpected closure on recovery | Tasks 5/7: pulse_workload_matrix, reset_brownout, uncertain_ped |

R1 maps to Tasks 0-1/3; R2 to 3-4/6; R3 to 1-2/4/6;
R4 to 2/5/7; R5 to 0/3/5/6; R6 to 5/7; R7 to 6-8.
A test count is not a substitute for this coverage.

## Planned file structure

These are future implementation paths, not files created by this docs-only PR.

| Path | Responsibility |
| --- | --- |
| tools/gate_crypto.ts, tools/gate_crypto.test.ts | Independent Node crypto reference and frozen-vector assertions |
| tools/gen_vectors.ts, tools/gate_protocol.test.ts | Deterministic fixtures and message/encoding tests |
| docs/test-vectors.json | Versioned inputs, canonical bytes and expected outputs |
| lib/gate_core/crypto_primitives.h, crypto_primitives_mbedtls.cpp, crypto_primitives_commoncrypto.cpp | Crypto provider interfaces and backends |
| lib/gate_core/gate_crypto.h/.cpp | Reviewed derivations, proofs and comparison |
| lib/gate_core/gate_store.h/.cpp | Snapshot validation, atomic enrollment, persistent policy |
| lib/gate_core/gate_protocol.h/.cpp | Explicit roles, sessions, requests and reply correlation |
| lib/gate_core/gate_transport.h/.cpp | Bounded framing/reassembly |
| src/nvs_backend.h/.cpp | Native ESP-IDF NVS adapter, gatecfg partition and C7 failure classification |
| src/main.cpp, include/app_config.h, include/app_config_local.example.h | Queue integration, relay arbitration, provisioning profiles/config |
| platformio.ini, partitions.csv | Pinned target/native tests and C8 partition layout preserving firmware offsets |
| test/support/test_support.h | Fault-injecting backend, fake clock and deterministic random source |
| test/test_crypto/test_main.cpp, test/test_store/test_main.cpp | Crypto/store tests |
| test/test_protocol/test_main.cpp, test/test_transport/test_main.cpp, test/test_scheduler/test_main.cpp | Protocol/transport/scheduling tests |
| android-app/app/src/main/java/com/newhaven/gate/ProtocolProbe.kt | Debug-only Android diagnostic driver, not production admin UI |
| ios-app/D5EvoBleGate/ProtocolProbe.swift | Debug-only iOS diagnostic driver, not production admin UI |
| docs/architecture.md | Implemented protocol and dated verification evidence when work is authorized |

The original laptop Python reference/CLI is replaced by TypeScript reference
tests plus native phone probes. This follows the repository language preference
and makes real mobile interoperability a prerequisite. A standalone laptop BLE
client is optional follow-up tooling, not a release dependency.

## Task 0: Finalize the protocol documents (completed, docs only)

**Files:** this plan and the linked spec only.
**Consumes:** owner's acceptance of D1-D9 on 2026-11-22, with D9 timing
provisional; read-only inspection of current firmware/provider sources.
**Produces:** exact C1-C9 contract and synthetic examples C10. This is design
completion, not production implementation or validation.

- [x] Record D1-D9 acceptance, readability/privacy tradeoff and D9 qualification.
- [x] Freeze binary field order, big-endian widths, F/M domain encoding, KDF,
  proof/key/receipt inputs and mutually authenticated session messages (C1-C3).
- [x] Freeze command permissions/arguments/results, error precedence, counters,
  correlation, replay behavior and credential retention (C4-C5).
- [x] Freeze minimum-MTU frame/reply layouts, bounds, timers, duplicates and
  queued/in-flight work behavior (C6).
- [x] Freeze single-snapshot schema, precise NVS error classification,
  provisioning/installed behavior and partition layout (C7-C8).
- [x] Pin inspected toolchain/provider baseline and invitation format (C8-C9).
- [x] Check synthetic examples/byte counts via temporary TypeScript and OpenSSL
  CLI calculations; include results and verification limits in C10.
- [x] Perform a builder check of the written contract and incorporate material
  findings before publishing the document revision.

Exit: the contract is ready for implementation review, with D9 physical limits
explicitly provisional. Do not execute Task 1 without a new explicit request.
Future clean builds, native/mobile fixtures, BLE and physical checks remain
unchecked; document arithmetic checks do not satisfy those gates.

## Task 1: Independent reference and crypto fixtures

**Files:** tools/gate_crypto.ts, tools/gate_crypto.test.ts,
tools/gen_vectors.ts, tools/gate_protocol.test.ts, docs/test-vectors.json;
lib/gate_core/crypto_primitives.h and both backends; gate_crypto.h/.cpp;
test/test_crypto/test_main.cpp; platformio.ini.

**Interfaces:** Implement the exact C1-C9 contract, not configurable serializers.
TypeScript exports deriveSetupKey, deriveAdminKey,
deriveInviteSecret, derivePhoneKey, claimProof, joinProof, authClientProof,
authGateProof, deriveSessionKeys, commandTag, replyTag, enrollmentReceipt,
encodeInvitation, decodeInvitation, legacyResponse. Each consumes the frozen
typed transcript/input and returns bytes or a structured validation error.
C++ equivalents use fixed-size arrays and explicit success/failure, never
zero-filled substitute keys. The spec is authoritative over either implementation.

- [ ] Write failing fixture tests for C10 values and every C1-C9 derivation,
  receipt, request/reply, invitation and unchanged legacy response. Include tamper_binding:
  change each bound field independently and reject the old proof.
- [ ] Implement TypeScript with Node crypto using C1's F/M encoding. Reproduce
  C10 via mbedTLS and add admin auth, all command/result variants and boundary
  fixtures. Pin them in docs/test-vectors.json. C10's Node/OpenSSL CLI agreement
  is not an independent engine check; mbedTLS/Kotlin/Swift verification remains
  required. Comparing a generator only to its own output is insufficient.
- [ ] Implement C++ provider wrappers and derivations against the pinned bytes.
  Cover crypto failure, constant-time tag comparison, invalid hex/Base32,
  invalid UTF-8, 24/25-byte names, version/checksum/padding mismatch and full
  66-character Base32 payload round-trip.
- [ ] Run node --experimental-strip-types --test tools/gate_crypto.test.ts tools/gate_protocol.test.ts
  on Node 22.22.1, and pio test -e native -f test_crypto. Expect every named fixture to pass;
  build pio run -e esp32-usb-c-38pin to exercise the ESP32 provider.
- [ ] Record provider/runtime versions and commit only synthetic fixtures/code.

Exit: independently checked vectors cover new protocol plus unchanged legacy;
no prior vector is silently reused for a newly bound transcript.

## Task 2: Durable store and atomic reserved-slot conversion

**Files:** lib/gate_core/gate_store.h/.cpp,
test/support/test_support.h, test/test_store/test_main.cpp.

**Interfaces:** StoreBackend.load returns Valid/Missing/Corrupt/Unsupported/
IoFailure plus bytes; StoreBackend.commit returns Committed/NotCommitted/
Indeterminate exactly as C7 (including its no-fallback and recovery rules). GateStore.begin returns Ready/NeedsProvisioning/RecoveryRequired.
GateStore.consumeInvite(id, candidateKey, nowMs), replaceAdmin(candidateKey,
fingerprint), revoke(id), setLegacyEnabled(bool) return classified mutation
results and new revision on success. Invites use 64-bit expiry; committed
snapshot uses C7's exact schema-2 bytes, SHA checksum and 1114-byte maximum.

- [ ] Write failing store_failure_matrix tests for read errors, short blobs,
  bad checksum/version, duplicate IDs, impossible lengths and missing installed
  state. Require RecoveryRequired; no default legacy-on or empty healthy store.
- [ ] Write join_at_capacity for 15 joined + 1 waiting and for 16 waiting:
  conversion succeeds with total unchanged; a seventeenth reservation fails.
  Test duplicate join/id collision, expired invitation and failed persistence.
- [ ] Write reclaim_cancels_invites and reload tests: replacement persists new
  admin and generation, keeps joined phones/legacy policy, cancels all waiting
  codes, and invalidates old admin use.
- [ ] Write commit-boundary tests for all persistent operations: failure before
  nvs_set_blob is NotCommitted; any error after invoking it is Indeterminate;
  only native commit success plus exact validated readback is Committed.
  Uncertainty disables commands until C7 recovery; no older snapshot fallback
  may restore revoked access after reported success.
- [ ] Test that BLE reconnect cannot clear RecoveryRequired. Physical reboot
  may load only the single valid stored snapshot; corruption stays locked and
  requires local repair, never automatic reprovisioning.
- [ ] Implement versioned snapshot validation and mutation staging. Keep RAM
  state unpublished until committed. Failed JOIN retains its invitation unless
  reboot occurs; committed JOIN reloads even if no success receipt was delivered.
- [ ] Test setup-fingerprint changes/removal as atomic boot transitions:
  clear admin/advance generation while keeping tenants and legacy policy;
  confirmed commit survives reboot and any failed/uncertain commit enters
  recovery. Gate-ID mismatch instead enters recovery without migration.
  Also test explicit provisioning, expiry beyond 32-bit uptime and capacity.
- [ ] Run pio test -e native -f test_store, inspect every fault case, and commit.

Exit: no separate addPhone/removeInvite sequence; no boolean conflating an empty
store with I/O failure or a rejected write with an uncertain commit.

## Task 3: Bounded transport, roles and authenticated sessions

**Files:** lib/gate_core/gate_transport.h/.cpp,
lib/gate_core/gate_protocol.h/.cpp, test/test_transport/test_main.cpp,
test/test_protocol/test_main.cpp.

**Interfaces:** GateTransport.acceptFragment(connectionGeneration, bytes, now)
returns Incomplete/Complete/Rejected using C6's exact 11-byte header, 9-byte
chunks and 8-byte SELECT writes; clearConnection drops assembly/replies.
GateProtocol.onConnect/onDisconnect rotate challenge and clear privileges.
GateProtocol.handle(message, now) returns a correlated reply and optional
authorized effect. Explicit session roles: None, Legacy, Tenant, Admin.
No callback decides authorization by reading the status string.

- [ ] Write framing_matrix: C6's 20-byte frames, 512-byte assembly/57-frame limit,
  incomplete/duplicate/inconsistent/out-of-order fragments, total mismatch,
  5-second expiry and reconnect mid-message. No partial dispatch or allocation
  above the bounds. Reads must reconstruct the matching stable reply.
- [ ] Write mutual-auth, role_and_gate_binding, stale_reply, replay and sequence
  tests against C2/C3/C5, including H/type bases, F label encoding and result codes. An altered verb/argument/session/gate/role/version/direction or reply
  must fail. Exact last-request retry returns cached result with no new effect.
- [ ] Test exact cached CLAIM/JOIN/AUTH2 retries before challenge consumption:
  no new key/write/nonce rotation or session extension. Test expired AUTH2 and
  COMMAND caches through both SELECT and plain reads. Valid-MAC sequence gaps,
  changed reused IDs and exhaustion must yield E0/REPLAY, end the session and
  leave counters/effects unchanged; an exact final-sequence duplicate is cached.
- [ ] Test legacy unchanged with AUTHRESP/PED/TRIGGER during migration; legacy
  cannot LIST/INVITE/REVOKE/LEGACY. New sessions cannot authorize raw PED/TRIGGER.
  Legacy-off denies even the correct old passphrase; errors never downgrade.
- [ ] Test current-credential checks at dispatch and successful CLAIM invalidation:
  authenticate old admin, claim a replacement, then reject old-session envelopes
  and queued work. Revoked/changed credentials and disabled legacy cannot borrow
  an otherwise unexpired session. Document legacy's remaining integrity risk.
- [ ] Test connection_deadlines: repeated reads cannot extend the 15-second
  unauthenticated deadline or 60-second hard cap; failure backoff survives
  reconnect within one boot. Confirm session expiry at 30 seconds.
- [ ] Implement C1-C6 using Task 1 crypto and
  explicit role state; bound work and caches. Authentication failure clears
  privilege; unauthenticated status remains advisory.
- [ ] Run pio test -e native -f test_transport and
  pio test -e native -f test_protocol, then commit.

Exit: no unprotected privileged verb remains reachable through version 2;
discovery is deliberately distinct from authenticated state.

## Task 4: Enrollment and administration with trustworthy outcomes

**Files:** lib/gate_core/gate_protocol.h/.cpp,
test/test_protocol/test_main.cpp; use Task 2 store interfaces.

**Interfaces:** C4's exact six verbs and C5 results map to consumeInvite, replaceAdmin,
revoke and setLegacyEnabled. Enrollment success produces a candidate-key receipt,
not an implicitly authorized session. Pending/failed storage is not OK.

- [ ] Write invite/join/revoke/list/legacy happy-path tests plus rejection for
  wrong role, bad proof, malformed arguments, stale list revision and invalid TTL.
- [ ] Write lost_join_receipt: committed candidate authenticates after reload;
  uncommitted join keeps the invitation while powered; reboot cancels it.
  Wrong JOIN proof does not consume the invitation; used code cannot join twice.
- [ ] Write lost_claim_receipt and reclaim tests: candidate admin authenticates
  after lost acknowledgment; old admin fails; joined tenants survive and waiting
  codes cannot be reopened/reused. CLAIM/JOIN alone must not permit PED.
- [ ] Test failure/indeterminate persistence for every mutation, signed error
  results after valid session auth, mismatched receipts and replayed receipts.
- [ ] Implement command effects, list revision/expiry semantics and persistence
  receipts. Remove named entries by ID, not by a stale list index.
- [ ] Run pio test -e native -f test_protocol and all native tests; commit.

Exit: R2/R3 effects and R4 durability hold for both success and uncertain outcomes.

## Task 5: ESP32 integration and relay scheduling

**Files:** src/main.cpp, src/nvs_backend.h/.cpp, platformio.ini, partitions.csv,
include/app_config.h, include/app_config_local.example.h,
test/test_scheduler/test_main.cpp; root README.md/AGENTS.md/CLAUDE.md and
docs/architecture.md as runtime behavior is implemented.

**Interfaces:** NvsBackend uses native nvs_flash_init_partition, nvs_open_from_partition,
nvs_get_blob, nvs_set_blob and nvs_commit against gatecfg/d5gate/state. Implement
C7 classifications and exact readback, never Preferences' ambiguous size-only result.
A bounded command queue carries connection generation and original deadline.
One owner serializes protocol/store access. Reply reads obtain an immutable
snapshot for a request; asynchronous PENDING cannot expose a previous OK.

- [ ] Write scheduler tests: pulse release precedes optional work; no NVS write
  starts during a pulse; full queue rejects safely; reconnect/expiry cancels old
  work; no pulse is retained for later execution. Test millis rollover at the
  existing cooldown boundary as well as 64-bit protocol time.
- [ ] Add C8's explicit Bench/Provisioning/Installed profiles, partitions.csv,
  setup secret/gate ID placeholders, reply characteristic, bounded queue and
  status adapter. Use the explicit 24-handle BLE service budget. Set relay idle
  before KDF, store initialization or advertising; failures enter recovery.
- [ ] Implement C7/C8 startup. Default nvs must remain the first NVS partition;
  the inspected core can erase it on initialization failure. Credentials reside
  only in separately initialized gatecfg. Never erase/reinitialize gatecfg after
  an error in Installed mode. Before a future flash, verify 4 MB layout and no
  existing SPIFFS user data; a mismatch blocks upload. Provisioning disables
  relay commands, initializes only empty state, and is replaced by Installed
  firmware before use.
- [ ] Integrate onConnect/onDisconnect/activity deadlines and synchronized
  read replies. Remove raw command/proof logging. Time KDF without printing
  inputs; do not lower rounds automatically if slow.
- [ ] Keep pulse/cooldown intent fixed. Prefer rejecting mutations as BUSY while
  pulsing. If existing rollover or integration behavior prevents the acceptance
  target, surface a specific supporting fix and revise scope before changing it.
- [ ] Run all native tests and pio run -e esp32-usb-c-38pin; review the actual
  hardware-control diff. Builds are necessary but do not establish timing.
- [ ] Update runtime docs with implemented behavior, new config, migration,
  compatibility limits and unperformed checks; commit.

Exit: software integration verified, no claim of hardware readiness yet.

## Task 6: Early real Android and iPhone proof

**Files:** future debug-only ProtocolProbe.kt and ProtocolProbe.swift paths above;
existing MainActivity.kt and GateBLEManager.swift only for probe entry/transport
wiring; ios-app/D5EvoBleGate.xcodeproj/project.pbxproj if needed for the probe.
Update applicable app README/AGENTS/CLAUDE files and docs/architecture.md.
Do not build full admin screens under this task.

**Interfaces:** each probe consumes docs/test-vectors.json and implements the
same framed request/reply and candidate-storage rules. Probe installation is on
owner-controlled test phones with synthetic bench credentials.

- [ ] Add vector tests in the native app test targets; create target wiring if
  absent. Test no_credential_deletion for denied/unknown, forged server replies,
  timeout and stale challenge. Save candidates before send; fail storage -> no send.
- [ ] Test app kill and lost receipt with up to two unresolved candidate keys:
  try mutual AUTH2 first, never auto-reenroll, require explicit retry setup/join
  and preserve both candidates at the cap. Bad server proof cannot cause eviction.
- [ ] Implement only claim, invite export/import, join, mutual auth, PED,
  revoke and legacy-toggle diagnostic flows. Gate admin mutation/export with
  OS authentication per D8; do not store setup secrets in builds.
- [ ] Test the maximum messages at minimum supported payload, normal negotiated
  MTU, reply correlation, background/disconnect and reconnect on actual Android
  and iPhone. Exercise interrupted CLAIM/JOIN before and after device persistence.
- [ ] Test replay/tamper without a real gate attached, uncertain-PED no retry,
  secure-storage failure, restore exclusion and invalidation/reinstall recovery.
  Authentication failures must retain recoverable candidate/previous keys.
- [ ] Run Android permission tests and assembleDebug; run the iOS simulator
  build plus new app vector tests. Then record real-device BLE results separately.
- [ ] Record phone/OS/framework/firmware versions and evidence in
  docs/architecture.md. Commit the minimal probes and documentation.

Exit: both platforms establish interoperable transport and recovery behavior.
A missing phone or failing platform leaves this gate pending; do not substitute
a simulator, TypeScript arithmetic check or laptop BLE success.

## Task 7: Fault injection and measured disconnected bench gate

**Files:** test/support/test_support.h and relevant native tests for newly found
faults; docs/architecture.md for dated measured evidence.
**Requires:** owner, ESP32/relay on bench, suitable pulse measurement equipment,
and NO connection from relay contacts to the live D5-Evo.

- [ ] Run the matrix below with synthetic credentials; record actual outcomes
  rather than pre-marking rows passed. Separate automated failure injection from
  physical power-cut tests.
- [ ] Record min/max GPIO and contact pulse durations under each load, idle
  continuity, boot/reset behavior and cooldown. Proposed GPIO acceptance target
  is 480-520 ms while normally powered. Reset/brownout/power interruption has no
  minimum duration; proposed fault target is deenergization within 520 ms of the
  original activation, no extra pulse and open contacts after supply stabilizes.
  Record actual GPIO and contact cutoff, not just the software command time.
- [ ] On any failure, stop dependent release work, preserve evidence and revise
  the spec/plan before selecting a broader timing/hardware fix.
- [ ] Commit evidence and necessary authorized fixes with their tests. Hardware
  verification remains incomplete until every applicable row has an outcome.

| Bench case | Acceptance |
| --- | --- |
| Old app before migration cutover | Same legacy auth/pulse works; no admin privileges |
| Legacy disabled, then reboot | Correct old passphrase denied; no contact closure |
| Claim/reclaim with pending invitation | New admin works; old admin and old invitation fail; existing tenant works |
| Join with all 16 entries reserved | Conversion succeeds; total unchanged; seventeenth invitation rejected |
| Drop link before/after CLAIM or JOIN save/receipt | Candidate recovery is reliable; no silent key deletion or false success |
| Inject NVS open/read/write failures and truncated/corrupt state | Installed mode refuses electronic access; no reset to legacy/no-auth |
| Cut power during REVOKE and LEGACY 0, before/after acknowledgment | Acknowledged policy survives; uncertain operation reconciles or enters recovery |
| Replay/modify frames, requests and replies | No unintended pulse/mutation; no client credential deletion |
| Read flood without auth and repeated reconnect | Deadlines/backoff enforced; no indefinite single-connection ownership |
| Storage/crypto/fragment load at pulse boundaries | GPIO within proposed bound; no double pulse; contacts open at idle |
| Reboot, watchdog reset, supply dip, power restoration | No unintended closure/retrigger; waiting invitations canceled |
| Fresh provisioning then installed build; erase installed NVS | Expected initial policy; erased installed state stays locked |
| Routine admin replacement vs compromise recovery | Routine keeps tenants; full local recovery invalidates all old credentials |
| Unknown outcome after PED | No automatic repeat, including after cooldown or reconnect |
| Mobile background/permission revocation/secure storage failure | Safe disconnect/retry state, no stale queued action |
| Protocol clock beyond 49.7 days (injected clock) | Invitations/session/connection deadlines behave correctly |

## Task 8: Documentation handoff and full app plans

**Files:** docs/architecture.md, root/app README.md/AGENTS.md/CLAUDE.md as
applicable; future iOS and Android plans under docs/superpowers/plans/.

- [ ] Audit implementation and tests against R1-R7, C1-C10 and accepted D1-D9. Record
  any incomplete requirements instead of declaring completion from green builds.
- [ ] Document exact provisioning -> installed workflow, backup/recovery,
  legacy cutover/re-enable warning, upgrade/schema compatibility, no rollback
  guarantee without secure boot, and fallback access.
- [ ] Write separate full app plans covering R7 screens, QR scanning permissions,
  device-only storage, OS-authenticated admin actions, offline state, honest
  failure/trigger copy and migration. Use the proven contract and fixtures.
- [ ] Require a supported app installation/signing route before everyday use.
  Store publishing and additional features remain separate work.
- [ ] Record which hardware checks were actually performed. A later supervised
  live-gate test requires explicit installation authorization and completed bench
  checks; this plan does not authorize operating the gate.
- [ ] Commit the handoff documentation and present remaining decisions to owner.

## Current review status

- Task 0 documents finalized; no firmware/app implementation or bench test run.
- D1-D9 accepted; D9 numeric tolerances remain provisional until tested and
  confirmed by the owner. Readable labels/activity are accepted for V1.
- C1-C9 define the contract; C10 provides synthetic examples checked through
  Node/OpenSSL CLI paths, not independent platform or hardware certification.
- Tasks 1-8 remain unchecked and require separate implementation authorization.
- No fresh decision about framing, crypto concatenation, error schemas,
  provisioning or storage is delegated to individual implementers.
