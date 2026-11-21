# Adding and removing phones from the admin phone

Status: revised proposal for owner review; implementation is not authorized
Created: 2026-11-21
Revised: 2026-11-22

The owner accepted the original product direction and the due-diligence
improvements. The defaults below are proposals, not decisions attributed to the
owner. This revision replaces the earlier protocol proposal; it is not an
appendix to be combined with the old command rules.

## Intent and scope

Replace one shared gate passphrase with individually revocable phone
credentials. Like replacing one building key with access cards, removing one
tenant should leave everyone else's access intact. Alice and Bob join with
different invitations; removing Alice must not require rebuilding Bob's app.

- One gate per app install, one admin, local BLE only.
- One app build contains no gate secret and can be distributed to everyone.
- The admin creates a one-time invitation at the gate, shared as QR or text.
- The gate stores each joined phone's credential and enforces revocation.
- Existing apps work during an explicit shared-passphrase migration period.
- Preserve pedestrian access through PED/COM, GPIO23, active-high default,
  500 ms momentary pulse and the existing 5 second cooldown policy.
- No position sensing, FRX, Status input, alternate boards, internet management,
  additional admins, access schedules, access history, or store publishing.
- Invitation expiry limits enrollment time; joined access lasts until revoked.

This PR changes documentation only. Runtime README/AGENTS/CLAUDE and architecture
documentation continue to describe current behavior. Updating those files,
firmware, app screens and tests belongs to a separately authorized implementation.
Canonical work stays in this spec and the linked firmware plan.

## Proposed defaults for review

Each decision is pending owner review. The rest of this document describes the
proposed result if these defaults are accepted. Rejection requires updating the
affected plan tasks before implementation.

| ID | Proposed default and reason | Tradeoff / alternative |
| --- | --- | --- |
| D1 | Keep per-phone symmetric keys; add mutual authentication and a MAC on every new command and reply. Keep the BLE link unencrypted for this scoped V1. | Names, counts and traffic remain observable. Authenticated encryption or suitably authenticated BLE pairing is an alternative requiring transport/enrollment review. |
| D2 | Every successful admin claim cancels all waiting invitations and keeps joined phones. | Invitations must be reissued after ordinary admin replacement. Retaining old admin secrets to regenerate old codes is rejected. |
| D3 | Treat suspected admin/setup compromise separately: rotate the setup secret, clear all phone/admin credentials, disable legacy access and reenroll everyone through a supervised local recovery procedure. | Disruptive, but replacement of the admin key alone cannot invalidate derived tenant keys or tenant entries an attacker added. |
| D4 | Generate a unique 128-bit random setup secret and gate ID at provisioning; store the recovery material in the owner's password manager. Use a fixed, versioned setup KDF. | Longer recovery code. Human-chosen passwords would need a different reviewed strength policy. |
| D5 | Require explicit physical provisioning/migration mode for an empty store; installed storage errors lock out electronic commands. | An erased store needs local recovery. There is no automatic fallback to the shared passphrase or bench mode. |
| D6 | Use bounded application framing that works with 20-byte BLE write payloads, rather than relying on long-write support or an increased MTU. | More protocol work; avoids platform-dependent long-write assumptions. Validate on both real phone platforms early. |
| D7 | Disconnect after 15 seconds without authentication regardless of reads; maximum connection tenure 60 seconds; new authenticated sessions last 30 seconds. | Very slow enrollment may need a reconnect. Radio jamming and repeated reconnection cannot be eliminated. |
| D8 | Require OS user authentication for admin mutations and revealing/sharing invitations; no extra biometric prompt for each pedestrian trigger in V1. | More owner friction; an unlocked tenant phone remains an access credential. |
| D9 | Defer/reject flash mutations while the relay is active. Proposed bench target: GPIO active for 500 ms +/-20 ms under supported powered operation, with no unexpected contact closure. | This is a release acceptance target to measure, not a proven hardware bound. Failure blocks live use and requires a reviewed timing solution. |

D1-D9 and the byte-level protocol contract must be resolved before production
implementation. Approving this document alone is not an instruction to execute.

## Approach and security boundary

The original shared-passphrase algorithm remains a compatibility path. The new
protocol must not treat a successful login as permission for unauthenticated
commands on the same connection. An intermediary that forwards a valid login
must not be able to substitute REVOKE for LIST or reenable legacy access.

Alternatives considered:

1. Symmetric credentials plus authenticated messages: proposed. Fits the small
   controller, but needs careful enrollment, replay handling and recovery.
2. Phone-generated signing keys: gate stores public keys, reducing damage from
   gate credential extraction and avoiding this design's admin-derived tenant
   keys. It needs authenticated enrollment and platform support; signatures
   alone do not make short join codes safe. Hardware-backed key availability
   must be verified rather than assumed for every phone.
3. BLE pairing/security plus application authorization: potentially useful as
   defense in depth. OS pairing restrictions do not inherently prevent a named
   phone list or QR enrollment at the application layer. Authenticated pairing
   must be evaluated on the exact hardware and phone platforms; Just Works alone
   does not solve active enrollment impersonation.

Assume nearby attackers can record traffic, emulate the service UUID, forward
live exchanges, alter messages and repeatedly connect. Also consider a former
tenant, a lost phone, accidental storage failure and interrupted operations.

Required protection: unauthorized commands, changes to authenticated arguments
or replies, replay across sessions/gates/roles, and stale credentials must not
cause a new pulse or an admin mutation. A forged failure must not erase a phone's
valid credential. No secret/key goes into BLE values, logs or app builds.
These integrity guarantees apply to version 2. While legacy is enabled, its old
unauthenticated-command/session risk remains; new protocol protection cannot
retroactively secure old clients. Migration cutover is a security boundary.

Residual risks explicitly accepted only if D1 is accepted:

- MACs authenticate data but do not hide names, counts, identifiers or traffic.
  Use non-sensitive phone labels. Hex encoding is not encryption.
- Cryptography here does not prove physical proximity or stop transparent live
  relay attacks. There is no hands-free/proximity opening; triggers require a tap.
- There is no forward secrecy. An invitation secret plus its recorded join
  transcript permits recovery of that phone key. An old admin key plus recorded
  invitation/join transcripts can expose keys derived under that admin. A leaked
  setup secret can expose recorded claims and their descendants.
- Flash extraction, firmware replacement and physical relay bypass are outside
  V1's tamper-resistance claim. Secure boot, encrypted NVS and rollback protection
  need a separate provisioning/recovery decision; this spec does not enable them.
- Protocol limits reduce easy connection monopolization; they do not guarantee
  availability against an active radio attacker. Keep existing access methods.

## R1: Versioned identity, keys and protocol freeze

Use product name V1 for this first phone-admin release; its wire protocol is
version 2, distinct from the existing legacy auth algorithm. Assign the gate a
stable random 16-byte ID in ignored provisioning config. Invitation payloads
carry it; claimed/joined apps pin it. A UUID or advertised name is not identity.

Proposed setup format: 16 random bytes encoded as 32 uppercase hex characters.
UI may remove grouping spaces/hyphens and normalize case before strict decoding;
the KDF input is the decoded bytes. Proposed KDF suite 1 is
PBKDF2-HMAC-SHA256 with 10,000 rounds, 32 output bytes and salt consisting of the
ASCII label D5-EVO-SETUP-V2 followed by the 16 gate-ID bytes. The entropy comes
from random generation, not the iteration count. Boot target is under two
seconds. If it fails, report and revise/version the suite; never silently halve
rounds or regenerate matching tests to conceal a compatibility change.

All new keyed hashes use HMAC-SHA256 with full 32-byte authentication tags.
Derived keys are 32 bytes, invitation secrets 16 bytes, random phone IDs 4 bytes,
and random gate/phone nonces 16 bytes. Use secure random sources and constant-time
tag comparison; fail closed on primitive/RNG errors. Bound collision retries.
Names are valid UTF-8, 1-24 bytes, with control characters rejected. Encode names
as hex in messages; enforce the byte limit in both apps without splitting UTF-8.

The final byte contract is an explicit prerequisite in plan Task 0. It must
freeze field order, lengths, separators/length-prefixing, integer representation,
labels and error schemas for all rows below. No implementation may choose its
own concatenation. Old proposal vectors are historical only: they verified the
old arithmetic, not these strengthened messages. Keep the legacy vector and
generate independently cross-checked new vectors after the contract is frozen.

| Derivation/proof | Required binding |
| --- | --- |
| Setup fingerprint | KDF suite, setup key and gate identity; used to detect configuration change, not authentication |
| CLAIM proof and derived admin key | Separate labels, version, suite, gate ID, current gate challenge C, fresh phone nonce Np, claim operation ID and requested claim mode |
| Invitation secret | Separate label, admin key, version, gate ID, phone ID and fresh gate nonce Ni |
| JOIN proof and derived phone key | Separate labels, version, gate ID, phone ID, C, Np and join operation ID |
| AUTH2 client proof and gate proof | Different direction labels, credential key, version, gate ID, explicit role/ID, C and Np |
| Session ID and directional session keys | Separate labels and complete successful AUTH2 transcript; never reuse the long-term key directly as a command MAC key |
| Command MAC | Direction, version, gate ID, role/ID, session ID, sequence, request ID, verb and exact arguments |
| Reply MAC | Opposite direction, same session/request identity, sequence, hash of request bytes, result code and exact result payload |
| CLAIM/JOIN persistence receipt | Separate receipt label, derived candidate key, full enrollment transcript hash and committed result |

The legacy label D5-EVO-AUTH-V1| and 2048 hash rounds remain byte-for-byte
unchanged. New protocol parsing must not uppercase arbitrary framed bytes before
MAC validation. Canonicalize permitted user input before encoding; authenticate
the exact canonical wire representation and reject noncanonical messages.

## R2: Sessions, commands and replies

Keep all six existing UUIDs. Add read-only reply characteristic
4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc7. Existing command writes carry legacy text
or explicitly marked version-2 frames; there is no heuristic downgrade.

STATE before authentication exposes only version/suite, gate ID, challenge and
recovery/compatibility hints. It is untrusted discovery data. Counts, names and
authoritative admin/legacy state require an authenticated admin reply. Both apps
must verify the gate's proof before calling the session authenticated.

Every connect/disconnect ends the session and rotates the challenge. Every
well-formed CLAIM/JOIN/AUTH2 attempt consumes its challenge, pass or fail.
Malformed/partial frames do not mutate enrollment state. Challenges cannot be
reused for another role or operation. Authentication failures end any active
privilege on that connection. Successful CLAIM/JOIN stores a credential but does
not grant a command session; follow it with mutual AUTH2.
Successful CLAIM also clears all existing sessions/reply caches and invalidates
queued privileged work before another command is accepted. At dispatch, recheck
credential existence/generation, role, legacy flag and expiry. A session timer
alone is insufficient after credentials or policy change.

One request is outstanding at a time. Sequence starts at 1 in each fresh session,
is strictly increasing and cannot wrap; exhaustion requires fresh authentication.
An exact duplicate of the most recent accepted request returns the cached signed
reply without repeating side effects. Same sequence with changed bytes, older
sequences, wrong gate/session/role or invalid tags are rejected. Consume and cache
authenticated error results too. Cache lifetime ends with the session.

| Operation | Authorization and effect |
| --- | --- |
| STATE | Unauthenticated discovery only; authenticated admin form returns current counts, admin generation, legacy flag and store revision |
| CLAIM | Setup proof; atomically replaces admin, advances admin generation, cancels waiting invites, keeps joined phones and legacy setting |
| JOIN | Valid waiting invitation proof; atomically consumes its reserved slot into one persisted phone |
| AUTH2 | Mutual proof using ADMIN or phone ID; establishes explicit admin or tenant role |
| PED | Authenticated request envelope for new clients; firmware checks pulse/cooldown before reporting accepted/busy/cooldown |
| INVITE | Admin envelope; TTL 600, 86400 or 604800 seconds, UTF-8 name; returns ID and Ni, never the secret |
| LIST | Admin envelope; joined then waiting rows, total and revision; reject/restart pagination if revision changes or invitation expiry changes the list |
| REVOKE | Admin envelope; persist deletion before success; cancel waiting invite or delete joined phone |
| LEGACY | Admin envelope; persist explicit 0/1 before success; cannot be invoked by a legacy/tenant session |

Use reason codes for locked out, bad args, unknown credential, expired invite,
not admin, full, busy, stale list, unsupported version and storage failure. An
unauthenticated error/status notification is only a hint. It cannot establish
revocation, ownership transfer, successful enrollment or a committed mutation.
Retain credentials on denied/unknown, timeout or bad server proof. Show a retry
or owner-help state; forgetting/replacing credentials requires explicit action.

Raw PED/TRIGGER are accepted only in a legacy-authenticated session while legacy
is enabled, or explicit non-installed bench mode. A new admin/tenant session must
not enable raw PED/TRIGGER. Old AUTHRESP never grants admin privileges. The new
apps never fall back to legacy or disabled auth following a protocol error.

After an uncertain PED result, show "Trigger outcome unknown; check the gate".
Do not automatically submit another pulse, including after reconnect. A signed
accepted reply means the controller accepted the pulse, not that the gate opened.

## R3: Enrollment, recovery and invitation format

The admin connects, authenticates, requests INVITE, verifies its reply, derives
the secret and disconnects before displaying the code. The recipient must be at
the gate to join. A code is a bearer invitation: anyone with a copy can enroll
first. A wrong proof must not consume/cancel it. Reboot, expiry, explicit cancel
and successful admin reclaim invalidate waiting invitations.

Proposed shared QR/text payload: a D5G2 prefix plus unpadded RFC 4648 Base32 of
version byte 2, 16 gate-ID bytes, 4 phone-ID bytes, 16 secret bytes, followed by
the first 4 SHA-256 bytes of those preceding payload bytes as a typo checksum.
This is 41 bytes / 66 Base32 characters, plus prefix and optional group separators.
The checksum is not authentication. Accept lowercase/group separators at input;
reject bad versions, lengths, checksum or nonzero padding bits. QR carries the
same text, not a remotely loaded URL. This replaces the inaccurate 26-character
promise, which counted only the secret. QR/paste is the primary experience.

For both JOIN and CLAIM, save the candidate key and complete operation transcript
securely on the phone BEFORE transmitting. If secure storage fails, send nothing.
After a lost receipt, first attempt mutual AUTH2 with the candidate key. Preserve
the previous admin credential until the new one is confirmed. If the result is
uncertain, retain both candidate and enrollment material; never overwrite them
solely because of an unauthenticated unknown/denied reply. Do not automatically
reenroll on failed AUTH2. Retry authentication with saved candidates; a fresh
CLAIM/JOIN requires an explicit "Retry setup/join" action and fresh C/Np.
Proposed limit: two unresolved candidate attempts per gate and enrollment role.
At the limit preserve candidates and show recovery guidance instead of evicting
one or sending another enrollment. Include app-kill recovery in the tests.
Delete setup/invitation material after confirmed enrollment and session proof.

Ordinary admin replacement preserves tenants but cancels all waiting codes. An
old admin credential loses admin access; a separate tenant credential that the
same phone holds is not implicitly revoked. Review the phone list after takeover.

Compromise recovery (D3) is a supervised USB/local operation, not a new remote
factory-reset command. Use a fresh setup secret and gate ID, erase/recommission
the credential store, initialize legacy off, and reenroll every phone. Rotate or
remove the legacy passphrase if it might be known. Keep an existing independent
access method available throughout. A setup-secret change alone preserves tenant
entries and invalidates admin; it is not sufficient compromise recovery.

The same owner can reopen a waiting invitation using the current admin key,
ID and Ni. After reclaim there are no old waiting codes to regenerate. If the
admin phone is lost, use the setup secret; if both are lost, recover locally.
Public distribution/signing and operating instructions remain separate work.

## R4: Durable state, capacity and safe defaults

NVS stores one versioned authorization snapshot containing commissioning state,
gate ID, setup fingerprint/suite, admin key/generation, legacy switch, store
revision and joined phone IDs/names/keys. Invitations remain RAM-only, with
64-bit uptime expiry. Admin is not a tenant slot. Maximum joined + waiting is 16.

JOIN is a single logical commit: replace the reserved waiting slot with a joined
phone. Do not add a seventeenth entry and then delete the invitation. Persist the
candidate snapshot before changing visible RAM state or sending success. Failure
preserves old state/invitation while power remains; reboot cancels RAM invitations.
If a JOIN committed before a power cut, its saved phone key works after restart.

Use distinguishable load results: valid, missing, corrupt, unsupported schema,
I/O failure. Mutation results distinguish committed, definitely not committed,
and indeterminate. On an indeterminate write, stop command execution and reload
or enter recovery; do not claim "nothing changed" or use stale RAM permissions.
Never fall back to an older authorization snapshot after an acknowledged revoke
or legacy-off. Storage checksums detect accidents, not malicious flash editing.

Installed mode boots locked on missing/corrupt/unreadable/unsupported state,
identity mismatch or crypto initialization failure. Preserve diagnostic evidence;
do not auto-erase NVS, accept legacy by default, or announce no-auth mode.
A commissioning marker in the same NVS can be erased too, so the marker alone
cannot distinguish a new device from a wiped installation.

Proposed solution: an explicit provisioning build/physical USB workflow may
initialize an empty store once, then an installed build without that permission
must be loaded and verified before live use. Bench no-auth behavior is available
only through an explicit bench configuration, never inferred in installed mode.

| Explicit provisioning choice | Initial access |
| --- | --- |
| Bench, no setup secret or shared passphrase | Raw PED allowed with gate disconnected |
| Legacy-only migration with current passphrase | Old apps allowed; no admin features until setup is configured |
| Phone-admin migration with setup and current passphrase | Legacy initially on; enrolled phones also work |
| Fresh phone-admin installation with setup only | Legacy off; claim/join required |

Later installed boots honor the stored legacy flag, not compiled defaults.
Setup removal never silently enables no-auth. Unknown schema/downgraded firmware
must be rejected by the documented maintenance process; without secure boot this
is not a claim of protection against deliberate firmware rollback.

A gate-ID mismatch requires recovery. A setup-fingerprint change with the same
gate ID and a valid store is a different, explicit boot transition: atomically
clear the admin key, advance its generation, preserve joined phones and the
legacy flag, and commit the new fingerprint before serving requests. Setup
removal uses the same transition and disables CLAIM. If this commit cannot be
confirmed, enter recovery rather than partially applying the transition. Reload
must never restore the old admin. No-auth remains limited to explicit bench mode.

## R5: Transport and connection availability

Task 0 freezes a bounded frame contract with an explicit version marker, request
ID, fragment index/count, total length and response correlation. Use writes with
response, one logical request at a time, maximum reassembled message 512 bytes,
one assembly buffer and a 5 second assembly deadline. Support 20-byte write
payloads; larger MTUs are an optimization. Validate all limits before allocation.
Never dispatch partial messages. Reject inconsistent duplicates, overlaps and
out-of-order fragments; exact retry behavior is fixed in the contract. Disconnect
clears assembly and cached responses; a session cannot cross connections.

Reply reads must return a stable, correlated response through a defined chunk or
offset scheme at minimum MTU. Reads/notifications of the legacy status remain
advisory for new clients. Transport ACK means received/queued, not committed.
If an asynchronous response is pending, report that explicitly; never return the
last operation's OK. Long reads and writes must both be tested on actual phones.

Keep the existing 60 second idle safeguard, plus D7's 15 second unauthenticated
deadline and 60 second hard connection cap. Reads do not extend either cap.
Use a 15 second auth-failure backoff across reconnects during the current boot;
do not persist every failure to flash. Apply equivalent limits to CLAIM/JOIN.
Bound parsing/crypto/queue work and terminate abusive connections. Document the
remaining possibility of denial of service; the lockout is not a password defense.

## R6: Firmware scheduling and physical acceptance

BLE callbacks validate/copy bounded frames into a bounded queue. A single owner
processes protocol/store mutations; callbacks and loop must not race on session,
store or reply objects. Define queue-full and disconnect cancellation semantics.
Never execute work from a previous connection or an expired session.

Relay release runs before any optional work. While pulsing, reject/defer flash
mutations and expensive work; never replay a deferred pulse after reconnect.
Moving NVS writes into loop() alone is not proof of safe timing. Flash operations
can affect both CPUs. Keep current pulse/cooldown policy; scheduler integration
is permitted only to preserve it and needs measured verification.

On the disconnected bench, measure GPIO and COM/NO with suitable instrumentation,
not only an audible click. D9's proposed powered-operation bound is 480-520 ms at
GPIO, no retrigger within the existing cooldown, and contacts open at idle.
Record mechanical contact duration separately. Test storage writes near pulse
boundaries, fragmented/malformed traffic, floods, disconnect and watchdog reset.
Reset, brownout or power interruption can shorten a pulse; there is no minimum
duration under those faults. Proposed fault target is deenergization within
520 ms of the original activation, no additional pulse, and contacts open after
supply stabilizes. Measure actual GPIO/contact cutoff and boot/reset/brownout
behavior on the relay module. Any unmet or unmeasurable bound blocks live wiring
until the owner reviews a revised solution; do not report the target as proven.
This is not a safety certification or a promise that software prevents every
hardware fault. Leave the D5-Evo's existing safety wiring untouched.

## R7: App behavior and migration

Preserve the agreed screens: Join a gate (scan/paste and owner entry), tenant
home (Gate/Lock/Link and Pedestrian), Owner setup, admin home with Phones,
Joined/Waiting/Old app versions list, Add phone, Code/share/cancel and Remove
confirmation. iOS uses pushed screens/sheets and swipe removal; Android uses
screens and row menus. Admin row is drawn separately, not removable. Offline
lists are visibly stale/read-only. Reconnect and authenticate before any mutation.

Replace definitive "This phone was removed" with "Access could not be verified.
Retry near your gate or ask the owner." Keep credentials until explicit removal
or replacement. No access/error screen may silently start reenrollment.
Claim confirmation warns that waiting invitations will be canceled and existing
admin access replaced. Show pending invitations as canceled after restart.

While legacy is on, removal confirmation says: "Remove this phone credential?
Anyone who still has the shared passphrase can continue using an old app until
shared access is turned off." Show the same warning in Phones. After legacy is
off, confirm that future authenticated requests from the removed credential are
rejected; revocation cannot undo a pulse already accepted. Signed success follows
durable removal. Reenabling legacy requires explicit confirmation of restored old
access and OS user authentication under D8.

iOS credentials use Keychain device-only, non-synced storage; Android uses
Keystore-protected storage excluded from backup/device transfer. Test reinstall,
restore and key invalidation; do not assume uninstall erases every Keychain item.
Require OS authentication for admin mutations and invitation disclosure (D8).
No secret in clipboard without an explicit copy/share action, logs, analytics,
screenshots in test evidence, build config or crash reports. Warn that recipients
of a shared invitation can enroll and that shared copies cannot be recalled.

Migration: back up recovery material, bench-verify new firmware, provision the
existing passphrase plus setup secret and gate ID, verify installed-mode reboot,
claim admin, enroll tenants, test each phone, then explicitly disable legacy.
The new app must report unsupported firmware without downgrade. Missing setup,
store faults and expired invitations get distinct guidance. Store publishing,
signing/renewal arrangements and a supported installation route must be decided
before depending on new apps for everyday access.

## Acceptance and unresolved implementation gates

R1-R7 are requirements, not claims of completed implementation or testing.

- D1-D9: owner review pending. Alternatives and costs are in the decision table.
- Exact byte/framing/error/KDF suite contract: plan Task 0, reviewed before code.
- Firmware plus minimal real iOS/Android transport proof precedes full admin UI.
  A laptop-only bench client cannot establish mobile compatibility.
- Each requirement maps to named tests and fault injection in the firmware plan.
  Updated vector results, builds, cross-platform results and hardware evidence
  must be recorded with versions and dates; no fixed test count stands for coverage.
- Production crypto/provider versions, BLE handle capacity and callback/stack
  behavior must be verified for the pinned PlatformIO framework before release.
- If bounds or platform behavior fail, revise these documents before expanding
  architecture/hardware scope. No implementation is authorized by this PR update.

## References and related plan

- [Firmware plan](../plans/2026-11-21-phone-admin-firmware.md).
- [Bluetooth SIG Security and Privacy Best Practices](https://www.bluetooth.com/download/bluetooth-security-and-privacy-best-practices-guide/): integrity, authenticated pairing, relay attacks and availability limits.
- [Espressif NVS documentation](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/storage/nvs_flash.html): power-loss resilience and physical tampering limits do not define application recovery policy.
- [ESP-IDF 4.4 flash concurrency](https://docs.espressif.com/projects/esp-idf/en/release-v4.4/esp32/api-reference/storage/spi_flash_concurrency.html): flash operations can block other CPU work; measure timing.

Sources informed the 2026-11-21 review. Hardware timing, mobile transport and the
revised protocol have not been validated. Runtime docs to update during future
implementation: root and applicable app README/AGENTS/CLAUDE files,
docs/architecture.md and include/app_config_local.example.h.
