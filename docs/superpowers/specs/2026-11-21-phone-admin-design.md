# Adding and removing phones from the admin phone

Status: protocol finalized for docs review; implementation is not authorized
Created: 2026-11-21
Revised: 2026-11-22

On 2026-11-22 the owner accepted D1-D9, including readable labels/activity for
V1, with D9's numeric timing tolerance provisional until tested. C1-C9 below
freeze the resulting protocol. Finalizing these documents does not authorize
implementation, firmware flashing or gate operation. This revision replaces the
earlier protocol proposal, rather than adding optional rules alongside it.

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

## Accepted decisions

D1-D9 were accepted on 2026-11-22. D9's numeric bounds remain provisional;
measurement and owner confirmation are required before treating them as release
limits. Alternatives below record tradeoffs, not unresolved decisions.

| ID | Accepted decision and reason | Tradeoff / alternative |
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

The exact byte-level contract is C1-C9. D9's numeric validation and production
verification remain implementation/release gates. Approval or merge of this
document alone is not an instruction to execute.

## Approach and security boundary

The original shared-passphrase algorithm remains a compatibility path. The new
protocol must not treat a successful login as permission for unauthenticated
commands on the same connection. An intermediary that forwards a valid login
must not be able to substitute REVOKE for LIST or reenable legacy access.

Alternatives considered:

1. Symmetric credentials plus authenticated messages: accepted. Fits the small
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

Residual risks accepted for the scoped V1 under D1:

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

## R1: Versioned identity and keys

Use product name V1 for this first phone-admin release; its wire protocol is
version 2, distinct from the existing legacy auth algorithm. Assign the gate a
stable random 16-byte ID in ignored provisioning config. Invitation payloads
carry it; claimed/joined apps pin it. A UUID or advertised name is not identity.

Setup format: 16 random bytes encoded as 32 uppercase hex characters.
UI may remove grouping spaces/hyphens and normalize case before strict decoding;
the KDF input is the decoded bytes. KDF suite 1 is
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
as length-prefixed raw UTF-8 bytes (C1); enforce the byte limit in both apps
without splitting UTF-8.

C1-C9 freeze field order, lengths, integer representation, labels, message/error
schemas and storage. Implementers must not choose their own concatenation.
Old proposal vectors are historical only: they verified the old arithmetic,
not these strengthened messages. Keep the legacy vector; implement shared
fixtures for C1-C9 and the document examples during the authorized build.

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

The shared QR/text payload is a D5G2: prefix plus unpadded RFC 4648 Base32 of
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
Limit: two unresolved candidate attempts per gate and enrollment role.
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

The dedicated gatecfg NVS partition stores one versioned authorization snapshot containing commissioning state,
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
and indeterminate. On an indeterminate write, enter RecoveryRequired and stop
command execution. Only C7's physical reboot procedure may reload the stored
snapshot; do not claim "nothing changed" or use stale RAM permissions.
Never fall back to an older authorization snapshot after an acknowledged revoke
or legacy-off. Storage checksums detect accidents, not malicious flash editing.

Installed mode boots locked on missing/corrupt/unreadable/unsupported state,
identity mismatch or crypto initialization failure. Preserve diagnostic evidence;
do not auto-erase NVS, accept legacy by default, or announce no-auth mode.
A commissioning marker in the same NVS can be erased too, so the marker alone
cannot distinguish a new device from a wiped installation.

An explicit provisioning build/physical USB workflow may
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

C6 defines the bounded frame contract with an explicit version marker, request
ID, fragment index/count, total length and response correlation. Use writes with
response, one logical request at a time, maximum reassembled message 512 bytes,
one assembly buffer and a 5 second assembly deadline. Support 20-byte write
payloads; V2 frames remain <=20 bytes even when a larger MTU is available. Validate all limits before allocation.
Never dispatch partial messages. Reject inconsistent duplicates, overlaps and
out-of-order fragments; exact duplicate/retry behavior is fixed in C6. Disconnect
clears assembly and cached responses; a session cannot cross connections.

Reply reads return stable, correlated chunks selected with C6's SELECT frames
at minimum MTU. Reads/notifications of the legacy status remain
advisory for new clients. Transport ACK means received/queued, not committed.
If an asynchronous response is pending, report that explicitly; never return the
last operation's OK. Fragmented writes and paged replies must both be tested on
actual phones; no reliance on long ATT reads/writes is permitted for version 2.

Keep the existing 60 second idle safeguard, plus D7's 15 second unauthenticated
deadline and 60 second hard connection cap. Reads do not extend either cap.
Use a 15 second auth-failure backoff across reconnects during the current boot;
do not persist every failure to flash. Apply equivalent limits to CLAIM/JOIN.
Bound parsing/crypto/queue work and terminate abusive connections. Document the
remaining possibility of denial of service; the lockout is not a password defense.

## R6: Firmware scheduling and physical acceptance

BLE callbacks validate/copy bounded frames into a bounded queue. A single owner
processes protocol/store mutations; callbacks and loop must not race on session,
store or reply objects. C6 defines queue-full and disconnect cancellation semantics.
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


## C1: Frozen binary contract (wire version 2, suite 1)

C1-C9 are normative. Multibyte integers are unsigned big-endian, with no padding
or native-struct serialization. B[n] means exactly n raw bytes; U8/U16/U32/U64
mean 1/2/4/8 bytes. Concatenation is written ||. SHA means SHA-256 returning
32 raw bytes. Hex in this document is display notation, not bytes to hash.
All lengths below include every header/tag but exclude BLE frame overhead.

A logical header H is U8(2) || U8(type) || U32(requestId), six bytes. IDs are
nonzero, increase for each new request on a connection, start at 1, and never
wrap. A retry uses identical bytes and the same ID. Responses echo the ID.
After using request ID 0xFFFFFFFF, finish reading its reply and disconnect;
fresh authentication requires a new connection if no request IDs remain.
Disconnect requires a fresh connection/challenge/session and resets transport
IDs; it never authorizes reexecution of an uncertain action.

An exact full retry of the cached last bootstrap request (DISCOVER, CLAIM,
JOIN or AUTH2) is recognized before challenge checks/consumption. Return its
immutable prior reply without rotating C, replacing/deriving credentials,
writing flash or extending any session/deadline. This is delivery retry, not a
new authentication attempt. Changed bytes under that request ID -> REPLAY.
Successful AUTH2 cache is valid only while its original session is valid;
CLAIM/JOIN receipts can remain until the next request/disconnect because they
prove a past commit, not current authorization. Expiry is checked before any
SELECT/plain read too: replace an expired AUTH2/COMMAND cached reply with the
untrusted SESSION_EXPIRED result for that request ID. Never expose a stale
success by bypassing this check through the reply characteristic.

The parser rejects trailing bytes, wrong fixed lengths, invalid enum values,
noncanonical lengths and unsupported versions. Variable values always have an
explicit length; no NUL terminators or implicit string normalization. Names are
raw, well-formed UTF-8 (1-24 bytes), not hex on the wire; reject U+0000-U+001F
and U+007F-U+009F, overlong UTF-8, surrogates and invalid code points. Preserve
valid Unicode bytes without NFC/NFKC conversion. This supersedes the initial
proposal's uppercase text/hex argument convention for new messages only.

G = gate ID B[16]; C = gate challenge B[16]; Np = phone nonce B[16];
Ni = invitation nonce B[16]; O = enrollment operation ID B[16];
P = phone ID B[4]; S = session ID B[16]. Role 1 is tenant, role 2 admin.
Admin P is 00000000; tenant/invitation P must be nonzero. Phone IDs must not
collide with any current joined/waiting ID. Try at most 16 random draws, then
return RANDOM_FAILED without changing state. All nonces/keys use a secure RNG;
test counters are never used in an installed build.

For domain separation define:
F(label, X) = ASCII("D5EVO2|") || U8(1) || U8(byteLength(label))
              || ASCII(label) || U32(byteLength(X)) || X
M(K, label, X) = HMAC-SHA256(K, F(label, X))
Labels below are literal, case-sensitive ASCII. There are no trailing NULs.
The first U8(1) is the suite identifier; version 2 is bound by D5EVO2 and H.
Never substitute string representations of integers or hex-encoded digests.

Setup code parsing and KDF are specified in R1. setupFingerprint =
M(setupKey, "SETUPFP", G), all 32 bytes. A suite change is a protocol/config
migration, never a negotiated reduction in security. Discovery cannot override
the app's required suite/version or the gate identity pinned during enrollment.

| Request type | Name | Bytes after H, in order | Exact/max bytes |
| --- | --- | --- | --- |
| 01 | DISCOVER (public STATE) | none | 6 |
| 02 | CLAIM | G, C, Np, O, U8(mode=0), proof B[32] | 103 |
| 03 | JOIN | G, P, C, Np, O, proof B[32] | 106 |
| 04 | AUTH2 | G, C, Np, U8(role), P, proof B[32] | 91 |
| 10 | COMMAND | G, U8(role), P, S, U32(seq), U8(verb), U16(argLen), args, tag B[32] | 82 + argLen, max 111 |

All message type, verb, frame kind and marker values everywhere in C1-C10 are
hexadecimal, including inline H(type=82/83/84/90/E0). Result/transport-error
codes, roles, modes, sizes, sequence/ID examples and other numbers are decimal.
No other request type is accepted. H includes the request type, so a proof
cannot be moved between CLAIM/JOIN/AUTH2.

## C2: Enrollment proofs, receipts and discovery

Let Q be the entire request except its final proof/tag; R is the complete
request including proof/tag. These definitions apply to all sections.

CLAIM proof = M(setupKey, "CLAIM", Q).
candidateAdminKey = M(setupKey, "ADMINKEY", Q).
Mode 0 is routine replacement only. There is no remote compromise-reset mode.

inviteSecret = first 16 bytes of
M(adminKey, "INVITE", G || P || Ni).

JOIN proof = M(inviteSecret, "JOIN", Q).
candidatePhoneKey = M(inviteSecret, "PHONEKEY", Q).

CLAIM/JOIN validate G and the challenge that was current immediately before
dispatch. Every syntactically valid attempt rotates C, including stale/locked
attempts. Bad proof starts backoff and ends existing privileges; no state is
changed. Known-valid proof with a failed/uncertain store operation does not
consume an invitation or confirm an enrollment. A committed mutation can exist
without the client having received a success receipt.

Success reply layout:
- CLAIM: H(type=82) || U8(OK) || G || U32(adminGeneration) || receipt B[32].
  Total 59 bytes.
- JOIN: H(type=83) || U8(OK) || G || P || receipt B[32]. Total 59 bytes.
- receipt = M(candidateKey, "CLAIM-RECEIPT" or "JOIN-RECEIPT",
  SHA(R) || replyWithoutReceipt).
Receipt validation precedes any success display. Commit/cancel old sessions and
caches first, then publish only the new operation's receipt. CLAIM/JOIN never
grant a command session. Client follows with AUTH2 using the saved candidate.

Enrollment failure uses the untrusted ERROR format in C5, never a fake success
receipt. After loss or failure, authenticate saved candidates before an explicit
fresh attempt. Keep a maximum of two unresolved candidates per gate/role plus
the previous confirmed credential. Never automatically evict a candidate.
Successful mutual AUTH2 reconciles the chosen key; delete other candidates and
setup/invitation material only then. An app restart resumes this process.

DISCOVER reply is:
H(type=81) || U8(OK) || U8(suite=1) || G || C ||
U8(health) || U8(setupConfigured) || U8(legacyAccepted) || U32(retryAfterMs).
Total 47 bytes. Health: 0 ready, 1 recovery-required, 2 bench, 3 provisioning.
Booleans are exactly 0 or 1; retry is remaining backoff, saturated to U32.
Recovery replies may use all-zero C if RNG initialization failed, but must not
accept any authentication/physical command. DISCOVER does not rotate C.
All discovery fields are hints. The app never trusts a claimed legacy/bench
mode to bypass authentication. Preserve the legacy challenge characteristic as
32 uppercase hex characters (or disabled only in explicit no-auth bench mode).

## C3: Mutual authentication and command sessions

AUTH2 client proof = M(credentialKey, "AUTH-CLIENT", Q).
With the same Q, derive:
S = first 16 bytes of M(credentialKey, "SESSION-ID", Q);
Kc = M(credentialKey, "SESSION-C2S", Q);
Kg = M(credentialKey, "SESSION-S2C", Q).

Successful gate reply:
H(type=84) || U8(OK) || G || U8(role) || P || S || U32(ttlMs) ||
gateProof B[32], total 80 bytes.
gateProof = M(credentialKey, "AUTH-GATE", Q || replyWithoutGateProof).
ttlMs = min(30000, remaining hard connection tenure at authentication), must
be positive. Start expiry at gate acceptance, not phone receipt. Client uses
its request-send monotonic time plus ttlMs as a conservative local deadline.
Only accept a response matching its outstanding Q, expected G/role/P/S and proof.

The gate verifies the client before creating a session. The phone verifies the
gate before sending COMMAND. No separate unauthenticated authorized notification
can substitute for that proof. Every well-formed AUTH2 attempt clears prior
session state and consumes C; a successful fresh auth resets sequence to 1.
A failed proof or unknown credential ends privileges and starts backoff.
No-session/status/error strings cannot cause key deletion.

COMMAND tag = M(Kc, "COMMAND", Q). Verify the envelope, current session,
credential existence/generation, G/role/P/S, expiry and seq before executing.
Admin and tenant credentials cannot be relabeled. An admin generation change,
disconnect or boot invalidates old sessions and queued work.

REPLY is:
H(type=90) || G || U8(role) || P || S || U32(seq) || SHA(R) ||
U8(result) || U16(dataLen) || data || tag B[32].
tag = M(Kg, "REPLY", replyWithoutTag).
Total 114 + dataLen, max 190 bytes. The MAC binds opposite direction and the
complete request hash. The client compares all echoed fields and verifies the
MAC before trusting data, result, counts or successful mutation.

Sequence is U32, starts at 1, and advances by exactly one for each authenticated
accepted request, including signed BAD_ARGS/NOT_ADMIN/BUSY errors. Sequence
0xFFFFFFFF is allowed once; then reauthenticate, never wrap. A request with an
invalid envelope is not accepted and has no side effect. An exact duplicate of
the last accepted R in the still-valid session returns its immutable cached
reply without reexecution; it must have the same request ID and sequence.
Changed bytes with a reused ID/sequence, old sequences and gaps are rejected.
Only the most recent logical request/reply is retained; the client must not send
a new request before completing its current one. On expiry/disconnect, do not
serve a stale success as current authorization.

A valid MAC with a sequence gap, older sequence or sequence exhaustion produces
untrusted E0/REPLAY, without advancing counters or repeating effects, and ends
the session. Reused logical request IDs with changed bytes have the same result.
They do not start authentication backoff, but count toward the three-error abuse
limit. Reauthenticate and reconcile state; never automatically retry PED.
An exact cached duplicate at the final sequence 0xFFFFFFFF is still retrievable
until expiry; a new COMMAND thereafter requires a fresh AUTH2 session.

For an uncertain PED, never automatically generate a new request/sequence or
session to retry it. The client may retrieve an already cached reply, but
ordinary application retry policy must not resend the physical action. Report
uncertainty and require a new deliberate tap after the person checks the gate.
Other uncertain mutations are reconciled through authenticated STATE/LIST or
candidate AUTH2 before an explicit retry on a new session.

## C4: Command arguments and results

| Verb | Operation / allowed role | Exact args | OK data |
| --- | --- | --- | --- |
| 01 | STATE / admin | empty | bootId B[16], U64(storeRevision), U64(listRevision), U32(adminGeneration), U8(adminSet), U8(legacyAccepted), U8(joinedCount), U8(waitingCount), U8(health) (41 bytes) |
| 02 | PED / admin or tenant | empty | empty; pulse accepted, not gate-position confirmation |
| 03 | INVITE / admin | U32(ttlSeconds), U8(nameLen), name bytes (max 29) | P, Ni (20 bytes) |
| 04 | LIST / admin | bootId B[16], U64(listRevision), U8(index) (25 bytes) | bootId B[16], U64(listRevision), U8(index), U8(total), P, U8(kind), U32(secondsLeft), U8(nameLen), name bytes, Ni B[16] (52 + nameLen, max 76) |
| 05 | REVOKE / admin | P (4 bytes) | empty |
| 06 | LEGACY / admin | U8(enabled) (1 byte) | empty |

STATE is the authenticated administrative view; DISCOVER is public discovery.
No command has optional/trailing fields. No silent shortening of names.
INVITE permits exactly 600, 86400 or 604800 seconds. Generate P and Ni only
after authorization/argument/capacity checks. Do not store secrets in replies.
LIST kind: 1 joined, 2 waiting. Joined secondsLeft=0 and Ni is all zeros.
Waiting secondsLeft=floor((expiry-now)/1000), so zero can mean less than one
second remains; an invitation at now>=expiry is removed before listing.
Joined rows use enrollment order; waiting rows use creation order. Index is
zero-based, total excludes admin. Empty/out-of-range lists return NOT_FOUND.
The admin row is local presentation, not an extra list record.

bootId is a fresh random B[16] per boot, public and distinct from G. listRevision
is U64 RAM state starting at 1 each boot; increment once per visible list change
(including batch expiry, invite/cancel/join/revoke/admin-reclaim). STATE/LIST
first purge expired invitations. Request bootId/listRevision must match after
purging or return STALE_LIST; refresh STATE and restart pagination.
storeRevision is persisted separately and advances on each authorization
snapshot mutation. Neither counter wraps: exhaustion enters recovery.

LEGACY 1 requires a nonempty compiled legacy passphrase; otherwise BAD_ARGS.
LEGACY 0 is idempotent. REVOKE missing ID -> NOT_FOUND. Repeating an already
committed operation after reconnect requires reconciliation; NOT_FOUND alone
is not proof the earlier operation succeeded. INVITE/REVOKE of waiting entries
change RAM only. All persistent changes are committed before OK.
PED returns BUSY if already pulsing, COOLDOWN with U32(remainingMs) if cooling
down. The cooldown remains measured from pulse start, as in current firmware.

## C5: Errors and precedence

Result codes are U8 decimal:
0 OK; 1 BAD_ARGS; 2 DENIED; 3 LOCKED_OUT; 4 UNKNOWN_CREDENTIAL;
5 NO_SETUP; 6 NOT_FOUND; 7 EXPIRED; 8 NOT_ADMIN; 9 FULL; 10 BUSY;
11 COOLDOWN; 12 STALE_LIST; 13 UNSUPPORTED; 14 STORE_FAILED;
15 RECOVERY_REQUIRED; 16 STALE_CHALLENGE; 17 REPLAY; 18 SESSION_EXPIRED;
19 WRONG_GATE; 20 CRYPTO_FAILED; 21 RANDOM_FAILED.

Signed COMMAND error data is empty except COOLDOWN, which carries U32 remaining
milliseconds. Every authenticated semantic error consumes sequence and gets a
cached signed reply. Success data must exactly match C4. Unknown verbs with
valid envelopes return signed BAD_ARGS. Check the MAC before semantic argument
validation or role checks; no untrusted request may exercise storage or relay.

Pre-session failures or unverified/missing/expired-session envelopes use:
H(type=E0) || U8(code) || U32(retryAfterMs), total 11 bytes, no MAC.
retryAfterMs is nonzero only for LOCKED_OUT. These are hints, not proof of
revocation or state. Never attach a secret, echo received payloads, or return
names/counts in these errors. Unknown request types use UNSUPPORTED. Incorrect
G -> WRONG_GATE; mismatched C -> STALE_CHALLENGE; bad proof -> DENIED.
JOIN for missing/purged invite -> NOT_FOUND (EXPIRED is reserved; no tombstones).
Malformed logical messages -> BAD_ARGS without side effects.

If a command's envelope is valid but NVS becomes indeterminate, sign
RECOVERY_REQUIRED using the current session keys, publish the result, then
disable further command execution. It confirms uncertainty, not nonexecution.
STORE_FAILED is permitted only when definitely no persistent write started.
Recovery has priority over ordinary application work. Read-only discovery
remains available if BLE/RNG initialization permits it.

Known-bad proof/tag or UNKNOWN_CREDENTIAL starts a global boot-local 15000 ms
backoff only if none is active. Attempts during backoff get LOCKED_OUT and do
not extend it; reconnect does not clear it. Syntax, stale challenge, wrong gate
and transport errors do not extend backoff but count toward abuse limits.
Unauthenticated DISCOVER remains readable during backoff. Three malformed
logical/framing errors on one connection cause disconnect; invalid MAC ends
the session immediately. Authentication attempts consume C even when locked.
Neither success nor reconnect clears an unexpired backoff.

## C6: BLE framing, reads and scheduling

ATT write-with-response carries at most 20 bytes for version 2, even at a larger
MTU. This deliberately fixes framing independently of negotiated MTU. Writes
must complete at transport level before the next frame; that completion is not
an operation commit. Frame marker bytes D5 02 distinguish version 2 from legacy
ASCII. The first well-formed legacy command or version-2 frame selects that mode
until disconnect; mixing is rejected and never changes privilege. A malformed
D5-prefixed frame is not retried as legacy text.

DATA write:
D5 02 01 || U32(requestId) || U8(index) || U8(count) || U16(totalLen) || chunk.
Header 11 bytes, chunk 1-9 bytes. totalLen 6..512, count=ceil(totalLen/9)
(1..57), index 0..count-1. Every nonfinal chunk is exactly 9 bytes; the final
chunk is exactly the remainder (9 when divisible). The embedded logical H
requestId must equal the frame ID. One 512-byte assembly buffer per connection.
Begin at index 0; indices strictly increase. An identical repeat of an already
received frame is acknowledged with no new bytes or deadline extension; an
inconsistent repeat, skipped index or changed metadata aborts the assembly.
Deadline is 5000 ms from first fragment, not reset by progress. On expiry clear
assembly and require a new request ID; never execute a partial request.
Only a metadata-valid index-0 frame can start a new assembly and advance the
highest started new ID. Track that ID so aborted IDs cannot be reused. A full exact
retry of the last dispatched ID is the only exception, checked against cached R.

At most one assembled request can be queued/processing. While processing,
reject new request DATA as BUSY; no second command is queued. The protocol
owner may finish a mutation already started when disconnect/expiry occurs;
it must not begin a new side effect after those conditions. Recheck generation,
deadline and credentials immediately before persistence/pulse acceptance.
Completion of an in-flight commit is reconciled into store state but never
delivered to a new connection. A queued, not-started stale job is discarded.

To read reply page i, write:
D5 02 02 || U32(requestId) || U8(i), exactly 8 bytes, then read the reply
characteristic. SELECT only selects an immutable response page and has no
application side effects. It is allowed while processing; requestId must name
the pending or last dispatched operation. It does not advance logical IDs,
consume a challenge, refresh authentication or extend deadlines.

Reply characteristic read returns exactly one of:
- D5 02 00: EMPTY, no request completed/queued (3 bytes).
- D5 02 10 || U32(requestId): PENDING (7 bytes).
- D5 02 11 || U32(requestId) || U8(index) || U8(count) ||
  U16(totalLen) || chunk: DATA, same 9-byte partitioning as writes.
- D5 02 12 || U32(requestId) || U8(transportError): ERROR (8 bytes).

transportError: 1 BAD_FRAME, 2 BUSY, 3 NO_REPLY, 4 BAD_PAGE, 5 ASSEMBLY_TIMEOUT.
Pending requests allow SELECT page 0 only; other pages -> BAD_PAGE. After DATA
is ready, read every page using SELECT then read, checking metadata; concatenate
and verify the logical response. Select/page errors do not overwrite the cached
logical response. If a new request is accepted, the previous cache may be
replaced; the one-outstanding-request rule prevents legitimate interleaving.
Notifications/legacy read status are never trusted version-2 replies.
Initially selected page is 0. Plain reads without SELECT return the selected
page for the current pending/completed ID or EMPTY. Reads never rotate C.

Do not send default-MTU long writes or depend on Read Blob. All reply frame
values are <=20 bytes, which fit the minimum read response. A server may reject
version-2 characteristic writes longer than 20 bytes as BAD_FRAME. Legacy
AUTHRESP text remains supported through its existing write handling.

Application owner runs pulse-release checks first on every tick, then at most
one bounded protocol step. No NVS mutation/expensive crypto begins while the
relay is active: return BUSY (signed when an existing valid session permits)
or untrusted BUSY for bootstrap requests. Never defer a pulse for execution
after a session/connection expires. Raw BLE callbacks perform only bounded
copy/framing checks; single-owner state is published as immutable read snapshots.

The hard 60000 ms connection cap starts at connect and never resets. The
unauthenticated timer starts at connect, pauses only for an active authenticated
session, and restarts at the instant that session expires/ends; limit 15000 ms.
Legacy successful AUTHRESP counts as authenticated for this connection timer.
CLAIM/JOIN success alone does not. Read/write activity may reset the existing
60000 ms idle timer only. Backoff, fragment timeout and hard cap are independent.
At any cap, clear assembly/session/caches, cancel queued work and disconnect.
Do not terminate an in-flight flash write by pretending it did not happen.
Clients poll PENDING at most once per 100 ms and stop on timeout/disconnect;
they must reconnect/reconcile, never spin indefinitely.

## C7: Snapshot schema and durable commit

Authorization storage uses native ESP-IDF NVS APIs in a dedicated gatecfg
partition, namespace d5gate, key state. Preferences' size-only returns cannot
reliably distinguish absent data from I/O failure, so this adapter uses
nvs_open_from_partition/nvs_get_blob/nvs_set_blob/nvs_commit directly. It must
not call erase/reinitialize-on-error in Installed mode.

Single-blob snapshot, big-endian, in this order:
ASCII("D5GS") B[4], U16(schema=2), U16(totalBytes),
U8(commissioned=1), G B[16], U8(suite=1),
U8(setupPresent), setupFingerprint B[32],
U8(adminPresent), adminKey B[32], U32(adminGeneration),
U8(legacyEnabled), U64(storeRevision), U8(phoneCount),
then phoneCount rows of P B[4] || U8(nameLen) || name || phoneKey B[32],
then SHA(all preceding snapshot bytes) B[32].
Absent fingerprint/admin key fields are all zero. adminPresent requires
setupPresent and adminGeneration>=1. Initial generation is 0 until a claim or
setup-change transition increments it. storeRevision starts at 1; increment
on every persistent mutation, not on a no-op. Booleans are 0/1, phoneCount<=16,
IDs unique/nonzero, rows in enrollment order, no trailing bytes. Size is
138 + sum(37+nameLen), max 1114 bytes. Reject totalBytes/length/hash mismatch.
Hash is corruption detection, not protection from physical tampering.

Load with a read-only namespace handle. Bound blob length to 138..1114 before
allocating/reading content. Validate magic/declared length/hash first, then
schema/suite and fields; an unsupported schema/suite is Unsupported, not a
new-store signal. Missing namespace/key -> Missing;
type/length/hash/schema validation failures -> Corrupt or Unsupported; other
NVS errors -> IoFailure. Installed mode locks on any non-Valid result.
No alternate/older application snapshot is consulted. Ready RAM state is never
constructed by filling missing fields with permissive defaults.

Commit algorithm:
1. Validate/stage complete candidate bytes and counters in RAM; ensure no pulse,
   current authorization and now strictly before connection/session deadlines.
2. Open a read-write handle. Any failure before invoking nvs_set_blob ->
   NotCommitted/STORE_FAILED; leave visible RAM unchanged.
3. Invoke nvs_set_blob, then nvs_commit, close/reopen read-only and read back.
   Only if both calls succeed and readback exactly equals candidate bytes with
   valid schema/hash classify Committed and publish RAM state/success.
4. Any error after invoking nvs_set_blob, including readback disagreement, is
   Indeterminate. Enter RecoveryRequired, stop electronic commands and preserve
   both old/candidate evidence in RAM until reset. Do not label it uncommitted
   based on a size return or revert to old RAM permissions.
5. BLE reconnect never clears RecoveryRequired. A physical controller reboot
   performs the normal single-snapshot load without fallback. A valid committed
   snapshot may restore service; inspect authenticated STATE/LIST to reconcile
   the uncertain operation. An invalid store requires explicit local repair.
   Never promise exactly-once delivery
   across a power cut; durable state, candidate keys and explicit reconciliation
   determine what happened.

NVS guarantees are relied on only after native commit success plus readback,
and must be tested by power-cut injection. Acknowledged revocation/legacy-off
must survive reboot. Backup/restore of an older snapshot is not a supported
recovery path. Setup-fingerprint transitions follow R4 using this same commit
procedure; preserve tenant entries and legacy policy, clear admin and advance
generation/revision. Counter overflow requires recovery, never reset to zero.
Invites are RAM-only and never serialized. Expire them before capacity checks.
consumeInvite replaces its reserved slot logically: commit joined phone before
publishing removal of the RAM invitation. Failed commit keeps the old invite
while powered; uncertainty stops service; reboot discards all RAM invites.

## C8: Partition, provisioning and toolchain contract

The inspected local board profile is 4 MB esp32dev. Use a future partitions.csv
with the following offsets/sizes; no existing firmware partition is moved:

| Name | Type/subtype | Offset | Size |
| --- | --- | --- | --- |
| nvs | data/nvs | 0x9000 | 0x5000 |
| otadata | data/ota | 0xE000 | 0x2000 |
| app0 | app/ota_0 | 0x10000 | 0x140000 |
| app1 | app/ota_1 | 0x150000 | 0x140000 |
| spiffs | data/spiffs | 0x290000 | 0x150000 |
| gatecfg | data/nvs | 0x3E0000 | 0x10000 |
| coredump | data/coredump | 0x3F0000 | 0x10000 |

The default nvs stays first: the inspected Arduino startup finds/erases the
first NVS partition for certain initialization errors. Explicitly initialize
gatecfg with nvs_flash_init_partition; never let its errors trigger erase in
Installed mode. This adds a future partition-table file and reduces unused
SPIFFS space by 64 KiB. No current application filesystem use was found. Verify
actual flash size/layout and absence of user filesystem data before any future
upload; mismatch is an installation blocker, not permission to erase data.
Firmware is never flashed as part of this docs-only protocol finalization.

Future build profiles use D5_EVO_RUN_MODE: 0 Bench, 1 Provisioning, 2 Installed.
Default is 2. Provisioning disables all relay commands and only initializes an
empty gatecfg with explicitly selected legacy setting, then exposes discovery
health=3. Provisioning accepts DISCOVER only; other logical requests get
RECOVERY_REQUIRED and legacy physical commands stay locked. Reboot must not
reprovision nonempty/corrupt state. Bench mode uses a
separate test device and synthetic secrets; no-auth is only allowed with setup
and legacy secrets empty and relay disconnected from the gate. Installed mode
never initializes or erases gatecfg automatically. A normal update must preserve
gatecfg. Explicit local compromise recovery alone may erase/reinitialize it.

Pin the implementation baseline to the inspected local versions:
platformio/espressif32 7.0.1; framework-arduinoespressif32
3.20017.241212+sha.dcc1105b (Arduino 2.0.17 family, bundled mbedTLS 2.28.7);
Node 22.22.1 for reference tests. Pin these in future platformio.ini/tooling;
do not edit them in this PR. A clean build remains an implementation check,
not a claim made from manifest inspection. Any provider/version replacement
must reproduce fixtures and the platform/hardware checks before release.

Create the BLE service with an explicit 24-handle budget. The planned service
needs at least 15 (service, six characteristic declaration/value pairs and two
notification descriptors); spare capacity is intentional. Verify discovery on
old and new clients rather than relying on the library's default.

## C9: Invitation text and release compatibility

The exact canonical invitation is ASCII("D5G2:") followed by 66 uppercase,
unpadded RFC 4648 Base32 characters. Decoded bytes:
U8(2) || G B[16] || P B[4] || secret B[16] ||
first4(SHA(U8(2) || G || P || secret)).
Reject zero P, wrong length/version/checksum and nonzero Base32 padding bits.
For manual input only, trim leading/trailing ASCII whitespace, accept lowercase
in both the D5G2: prefix and suffix, and remove ASCII spaces/hyphens only inside
the Base32 suffix; do not strip arbitrary
Unicode characters or accept URLs. Canonical QR has no whitespace/separators.
The checksum detects mistakes; it does not authenticate the sender or revoke
copies. Save candidate key material before JOIN; clear secret only after mutual
AUTH2 confirms the enrolled key.

Setup input is canonical 32 hex characters representing 16 random bytes. Manual
input permits ASCII spaces/hyphens and case folding, not arbitrary Unicode.
The decoded bytes, not hex text, are the PBKDF2 password. OS authentication may
use biometric or supported device-credential fallback. Prompt before connecting
for admin mutations and before displaying/copying/sharing invitation material;
do not let the prompt consume the unauthenticated connection deadline.

There is no downgrade negotiation. Unknown version/suite yields UNSUPPORTED.
Version 2 messages never reuse legacy AUTHRESP derivations or shared-password
sessions. Changes to field layout, labels, limits affecting interoperability or
KDF suite require a new reviewed wire/suite version and migration plan.
D9's numeric timing bounds remain provisional until measured and owner-ratified;
the requirements to measure, prevent unintended actuation and stop on failure
are accepted. This frozen document is a contract, not security certification
or evidence that the firmware or apps implement it.

## C10: Synthetic conformance examples

These fixed inputs are test material only, never provisioning secrets. Each
CLAIM/JOIN/AUTH2 vector starts a separate connection with DISCOVER request 1
and handshake request 2. CLAIM generation is 1. AUTH2 is tenant role 1 with
ttlMs=30000; the following COMMAND is request 3, sequence 1, PED, empty args.
Its REPLY is OK with empty data. The distinct challenges/nonces below are fixed
for reproducibility, not a production randomness policy.

| Input | Hex bytes |
| --- | --- |
| G | 000102030405060708090A0B0C0D0E0F |
| setupCode | 101112131415161718191A1B1C1D1E1F |
| cClaim | 202122232425262728292A2B2C2D2E2F |
| npClaim | 303132333435363738393A3B3C3D3E3F |
| oClaim | 404142434445464748494A4B4C4D4E4F |
| Ni | 505152535455565758595A5B5C5D5E5F |
| cJoin | 606162636465666768696A6B6C6D6E6F |
| npJoin | 707172737475767778797A7B7C7D7E7F |
| oJoin | 808182838485868788898A8B8C8D8E8F |
| cAuth | A0A1A2A3A4A5A6A7A8A9AAABACADAEAF |
| npAuth | B0B1B2B3B4B5B6B7B8B9BABBBCBDBEBF |
| P | A1B2C3D4 |

| Output | Hex bytes |
| --- | --- |
| setupKey | 31DCB93433F97F8740EDC9842407BA2EB7182A29C642DF12F1F0F7D5B6A54096 |
| fingerprint | B8A19C5618D887BAD9B897568EA99195B024A83A42469BD281B10D47EFD0DBF1 |
| claimQ | 020200000002000102030405060708090A0B0C0D0E0F202122232425262728292A2B2C2D2E2F303132333435363738393A3B3C3D3E3F404142434445464748494A4B4C4D4E4F00 |
| claimProof | 9930C8B73792D20911F1BDE7188377C513FA751A4687E58546F1BCD5ACBA73B9 |
| adminKey | 2EEA93344AFC905A34CD5672D446BB22953C61D240A58EF2E6CA936904E15B8B |
| claimReceipt | B6391263D6784E0A7D48860547689C949DDF58D75DD61B94A08AEF66B1BBF849 |
| inviteSecret | AF7E276B419FEC453E454467047B7AF7 |
| joinProof | F5C4BF3BA33DE3BDB5BBFE905FD26397393EB14DB32D1DFE553CCB697B536E4B |
| phoneKey | 8A646DF638CDAC561B4825CAB3EE9AC72C0CA3F6CCB020A95DF7D208D669AA08 |
| joinReceipt | 04798560C654631413515FD5392D57A7E59A93FA16A7B64873339EDF1C82A4DE |
| authQ | 020400000002000102030405060708090A0B0C0D0E0FA0A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBF01A1B2C3D4 |
| authClient | 8E5F9EFD161BC75F4ABD96C179D05A3757175555D6BD6D0F501C29252574C351 |
| S | B712D615CDC05B6555BC7C85F570A6A2 |
| Kc | 8173CAF793C6CAE77BEC359AC95358064778EBA5E877275E1A353DFDC7675210 |
| Kg | 19F1B7B7EEAEDF8F02B804C66F25DAFC0C81F792ED9F8D4BECA8D5B9AC8958B2 |
| authGate | B9073FC20C8B7EAB5F6F63229BE86326DAC5FE1165B762B03C97EA549C1D9BF8 |
| commandQ | 021000000003000102030405060708090A0B0C0D0E0F01A1B2C3D4B712D615CDC05B6555BC7C85F570A6A200000001020000 |
| commandTag | A30E3EC3050A05682E59B2282C07059EE76A71C0C81AD16FF92C19E33553E1C1 |
| commandRequestHash | B92DD314BB8D8A6DFC33D33DBEC64E4A294E27237381CB2BA4E85A2D8F421727 |
| replyTag | 01EBA46EAFB0702B4D4B62C5FFEB6A001A1376AB57722215DAB33EF35C1EB30F |
| commandFirstFrame | D5020100000003000A0052021000000003000102 |
| commandLastFrame | D5020100000003090A0052C1 |

Canonical invitation text:

D5G2:AIAACAQDAQCQMBYIBEFAWDANBYH2DMWD2SXX4J3LIGP6YRJ6IVCGOBD3PL3RQHZGDM

COMMAND is 82 bytes in 10 DATA frames; the first/last frame bytes are above.
Maximum request/reply are 111/190 bytes; a generic 512-byte assembly takes
57 frames of at most 20 bytes. Maximum snapshot is 1114 bytes.
Byte counts, framing reconstruction and the values above were checked with a
temporary TypeScript calculator on Node 22.22.1; 16 KDF/HMAC results also matched
the OpenSSL 3.6.2 command-line calculation. These are two invocation paths, not
independent crypto-engine certification (Node also uses OpenSSL). The future
mbedTLS, Kotlin and Swift fixture checks, malformed-message tests, real transport
and hardware testing remain unperformed. The temporary calculator is not a
product implementation and is not included in this docs-only PR.


## Acceptance and remaining verification

R1-R7 are requirements, not claims of completed implementation or testing.

- D1-D9: accepted, with D9's numeric timing bounds provisional until tested.
- C1-C9: finalized wire, crypto, transport, storage and provisioning contract.
  Any interoperability change requires an explicit contract/version review.
- Firmware plus minimal real iOS/Android transport proof precedes full admin UI.
  A laptop-only bench client cannot establish mobile compatibility.
- Each requirement maps to named tests and fault injection in the firmware plan.
  Updated vector results, builds, cross-platform results and hardware evidence
  must be recorded with versions and dates; no fixed test count stands for coverage.
- C8 pins the inspected platform/providers and explicit handle budget. Clean
  builds, actual BLE discovery, callback/stack behavior and timing still need
  implementation verification before release.
- If bounds or platform behavior fail, revise these documents before expanding
  architecture/hardware scope. No implementation is authorized by this PR update.

## References and related plan

- [Firmware plan](../plans/2026-11-21-phone-admin-firmware.md).
- [Bluetooth SIG Security and Privacy Best Practices](https://www.bluetooth.com/download/bluetooth-security-and-privacy-best-practices-guide/): integrity, authenticated pairing, relay attacks and availability limits.
- [Espressif NVS documentation](https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/storage/nvs_flash.html): power-loss resilience and physical tampering limits do not define application recovery policy.
- [ESP-IDF 4.4 flash concurrency](https://docs.espressif.com/projects/esp-idf/en/release-v4.4/esp32/api-reference/storage/spi_flash_concurrency.html): flash operations can block other CPU work; measure timing.

The 2026-11-22 read-only source check also inspected the local pinned platform
manifest, Preferences/NVS error handling, Arduino startup erase path, default
partition table and BLE handle defaults. The public Arduino 2.0.17
[Preferences source](https://github.com/espressif/arduino-esp32/blob/2.0.17/libraries/Preferences/src/Preferences.cpp)
and [ESP-IDF 4.4.7 NVS reference](https://docs.espressif.com/projects/esp-idf/en/v4.4.7/esp32/api-reference/storage/nvs_flash.html)
support the storage API choice. Document arithmetic checks are recorded in C10;
real firmware/mobile protocol execution and hardware timing remain unvalidated.
Runtime docs to update during future
implementation: root and applicable app README/AGENTS/CLAUDE files,
docs/architecture.md and include/app_config_local.example.h.
