# Adding and removing phones from the admin phone

Status: approved by the owner on 2026-11-21
Date: 2026-11-21

## Why

Today every phone opens the gate with one shared passphrase. The passphrase is
compiled into the ESP32 firmware and into every app build. The ESP32 cannot
tell phones apart and saves nothing between reboots.

This causes two problems for the owner:

- Removing one person means changing the passphrase, reflashing the ESP32, and
  rebuilding and reinstalling the app on every other phone.
- Every app copy has to be built and installed from the owner's laptop, because
  the passphrase is inside the build.

The owner wants the gate to work like a smart lock. One admin phone adds a new
phone by showing a code. The admin phone can remove a phone later, and that
phone stops working even though the app is still installed.

## Goals

- One admin: the owner's phone.
- The admin adds a phone by creating a one-time code at the gate. The code is
  shown as a QR and as text. The new person scans or pastes it.
- The code never contains the admin secret or any other phone's key.
- Each joined phone has its own key. The ESP32 keeps a list of joined phones.
- The admin removes a phone from that list. Removal takes effect at once.
- The app build contains no secret, so one build can be given to everyone.
- Phones on the current app keep working until the admin decides to cut them
  off.

## Non-goals

- Publishing the app to the App Store or Play Store. That is a separate project
  that follows this one. This design only makes it possible.
- Adding or removing phones over the internet. The ESP32 has Bluetooth only.
- More than one admin.
- Time-limited access, schedules, or a log of who opened the gate. The ESP32
  has no clock.
- More than one gate per app install.
- Encrypting the Bluetooth link.
- Any change to relay timing, relay polarity, cooldown, or GPIO23.

## What must never happen

- A phone becomes admin without the setup code.
- A removed phone opens the gate after the shared passphrase is switched off.
- A secret or a key is sent over Bluetooth as plain text.
- A secret is compiled into an app build.
- A firmware update alone stops current phones from working.
- The relay behaves differently from today.

## Constraints

- The ESP32 has no internet and no real-time clock.
- The Bluetooth link is not encrypted. Assume someone nearby can record every
  byte sent in either direction.
- Only one phone is connected at a time. Advertising stops while a phone is
  connected.
- Existing service and characteristic UUIDs stay as they are. `AUTHRESP`, `PED`
  and legacy `TRIGGER` keep working.
- The firmware uppercases every command before parsing it. All new command
  arguments are therefore hex or digits.
- UUIDs, labels and derivation steps must match exactly across firmware,
  Android and iOS.
- iOS deployment target is 17.0. Android `minSdk` is 26.
- Real secrets stay in the ignored local config files.

## Approaches considered

1. **Each phone shares its own secret key with the gate. Chosen by the owner.**
   The QR carries a long random secret. The phone and the ESP32 each compute
   the phone's key from it, so the key never crosses the air. Unlocking is a
   challenge and a keyed hash, like today. All three codebases already use
   SHA-256, so this is the smallest change. The cost is that the join code must
   be long, about 26 characters. A short code could be guessed offline by
   someone who recorded the join. People scan the QR or paste shared text.

2. **Each phone makes a key pair.** The private half stays in the phone's
   secure hardware and the ESP32 stores public halves only. Short typed codes
   would be safe. It needs new signature code in three codebases, and the
   signature check is heavier on the ESP32. Its extra protection matters mainly
   if someone reads the ESP32's flash, and a person with the box open can
   already bridge the relay by hand. Not chosen.

3. **The phone's own Bluetooth pairing.** iOS gives an app no control over
   pairing, so the app could not run a QR join or show a list of named phones.
   Ruled out.

## Decisions the owner made

- One public app build with no secret inside.
- Codes are created while the admin phone is at the gate (not offline).
- The admin phone becomes admin by entering a setup code chosen at flash time.
  Entering the setup code on another phone makes that phone the admin and the
  old phone stops being admin.
- Per-phone shared keys (approach 1).
- The screens in "Screens" below.

## How it works

### Firmware configuration

`include/app_config.h` and `include/app_config_local.example.h` gain one value:

- `D5_EVO_ADMIN_SETUP_CODE`. Empty by default. A long passphrase, at least four
  random words or 20 characters.

`D5_EVO_AUTH_PIN` stays. It is now called the shared passphrase in docs and UI.

How the two values combine:

| Setup code | Shared passphrase | Behaviour |
| --- | --- | --- |
| empty | empty | No auth. Same as today's bench mode. |
| empty | set | Same as today. No admin features. |
| set | set | Joined phones work. Shared passphrase works while the admin leaves it on. |
| set | empty | Joined phones only. |

### What the ESP32 stores

Stored in flash (NVS, through the Arduino `Preferences` library) so it survives
reboots and firmware updates:

- The admin key (32 bytes), and a fingerprint of the setup code it came from.
- Whether the shared passphrase is accepted. Default: yes.
- Up to 16 joined phones. Each has an id (4 random bytes), a name (up to 24
  bytes of UTF-8) and a key (32 bytes).

Kept in memory only:

- Codes that are waiting to be used. Each has an id, a name, a 16-byte secret
  and an expiry measured in uptime. A power cut cancels them. Waiting codes
  count toward the 16-phone limit.

On boot the firmware compares the stored fingerprint with the compiled setup
code. If they differ, it erases the admin key. This lets the owner replace a
leaked setup code by reflashing. The phone list is kept.

### Keys and proofs

Every keyed hash is HMAC-SHA256. Every message that is hashed starts with a
fixed label so a value made for one purpose cannot be reused for another.
`C` is the value of the existing auth challenge characteristic. The gate
rotates `C` after every attempt, as it does today.

| Step | Who computes | Value |
| --- | --- | --- |
| Stretch the setup code | gate at boot, phone at claim | `setupKey = PBKDF2-HMAC-SHA256(setupCode, salt "D5-EVO-SETUP-V1", N rounds)` |
| Claim proof | phone | `HMAC(setupKey, "CLAIM\|" + C + "\|" + Np)` |
| Admin key | both | `adminKey = HMAC(setupKey, "ADMINKEY\|" + C + "\|" + Np)` |
| Code secret | both | `inviteSecret = first 16 bytes of HMAC(adminKey, "INVITE\|" + id + "\|" + Ni)` |
| Join proof | new phone | `HMAC(inviteSecret, "JOIN\|" + C + "\|" + Np)` |
| Phone key | both | `phoneKey = HMAC(inviteSecret, "PHONEKEY\|" + C + "\|" + Np)` |
| Unlock proof | phone | `HMAC(key, "AUTH\|" + C)` where key is `phoneKey` or `adminKey` |

`Np` is 16 random bytes made by the phone. `Ni` is 16 random bytes made by the
gate. Only `C`, `Np`, `Ni`, ids and proofs cross the air. No key does.

The legacy `AUTHRESP` algorithm is unchanged.

### Commands

New commands are written to the existing command characteristic. One new
characteristic carries replies: UUID `4b8c2ec4-3f66-4f00-8a43-95f79d2c0cc7`,
read only. The app writes a command, then reads the reply. Reads are used
because they work at any MTU size.

| Command | Needs | Effect | Reply |
| --- | --- | --- | --- |
| `CLAIM <Np> <proof>` | setup code configured | Replaces the admin key. Starts an admin session. | `OK` or `ERR <reason>` |
| `AUTH2 <id> <proof>` | nothing | Starts a session for that phone. `<id>` is `ADMIN` for the admin key. | via auth status |
| `JOIN <id> <Np> <proof>` | a waiting code with that id | Stores the phone, deletes the code. | `OK` or `ERR <reason>` |
| `INVITE <ttlSeconds> <nameHex>` | admin session | Creates a waiting code. | `INVITE <id> <Ni>` |
| `LIST <index>` | admin session | Nothing. | `PHONE <index> <total> <id> <J or W> <secondsLeft> <nameHex> <Ni or ->` |
| `REVOKE <id>` | admin session | Deletes a joined phone or a waiting code. | `OK` or `ERR <reason>` |
| `LEGACY <0 or 1>` | admin session | Turns the shared passphrase off or on. | `OK` |
| `STATE` | nothing | Nothing. | `STATE <protocolVersion> <adminSet> <legacyOn> <joined> <waiting>` |

A session lasts `D5_EVO_AUTH_SESSION_MS`, as today. An admin session also
allows `PED`. The apps re-authenticate when a session has expired, as they do
today before `PED`.

Auth status gains one value. `unknown` means `AUTH2` named an id that is not in
the list. The app shows the "No access" screen for it. `denied` still means a
wrong proof and still starts the lockout.

`AUTHRESP` is accepted only when a shared passphrase is configured and the
admin has left it on.

### Adding a phone

1. The admin phone connects, starts an admin session and sends `INVITE` with
   the name and the lifetime (10 minutes, 1 day or 7 days).
2. The gate replies with the id and `Ni`. The admin phone computes the code
   secret and shows the QR and the text code.
3. The admin app disconnects from the gate as soon as the code is on screen.
   The gate serves one phone at a time, so the new phone could not connect
   otherwise.
4. The new person scans the QR or pastes the text. Their app stores the id and
   the code secret.
5. At the gate, their app connects, reads `C`, sends `JOIN`, and stores the
   phone key. It then unlocks with `AUTH2` like any joined phone.

Joining must be safe to retry. The app saves the phone key it computed before
it sends `JOIN`, and keeps the code secret until an `AUTH2` has succeeded. If
the connection drops, the app tries `AUTH2` with the saved key on the next
connection. `authorized` means the gate stored the phone. `unknown` means it
did not, and the app sends `JOIN` again with a fresh `C` and `Np`.

A `JOIN` with a wrong proof starts the lockout but does not use up the code.
Otherwise anyone nearby could cancel a code by sending a bad `JOIN`.

The gate deletes a code when its time runs out. Expired codes do not appear in
`LIST`.

The admin can reopen a waiting code from the list. The app recomputes it from
the id and `Ni` in the `LIST` reply.

### Removing a phone

The admin phone connects, starts an admin session and sends `REVOKE <id>`. The
gate deletes the entry. The next `AUTH2` from that phone gets `unknown`.

### Becoming admin

The owner opens "I'm the owner" from the menu, stands at the gate and types the
setup code. The app stretches it, sends `CLAIM`, and stores the admin key. Any
earlier admin key stops working because the gate keeps only the newest one.

A recorded claim can be attacked offline by guessing setup codes. The stretch
step and a long setup code are the defence. The docs must say this.

### Storage on the phones

- iOS: Keychain, this device only, not synced and not restored to another
  phone.
- Android: protected by the Android Keystore and excluded from backup.
  `android:allowBackup` is `true` today and must stop covering these values.

A wiped or replaced phone needs a new code.

### Screens

The agreed wireframes are saved in
`.superpowers/brainstorm/93032-1795241890/content/screens-overview.html`. That
folder is ignored by git, so the content is repeated here.

Tenant phone:

- **First launch.** A "Join a gate" card with "Scan QR code" and "Enter code
  instead". The ⋯ menu has one item, "I'm the owner".
- **Joined.** The screen tenants use today: Gate, Lock and Link status, and the
  Pedestrian button. Nothing about other phones.
- **Removed.** A "No access" card: "This phone was removed from the gate. Ask
  the gate owner for a new code." A "Scan new code" button.

Admin phone:

- **Owner setup.** A setup code field and "Make this phone the admin". Says to
  stand near the gate. Says that a previous admin phone stops being admin.
- **Home.** The tenant home screen plus one "Phones" row showing counts, for
  example "4 joined · 1 waiting". Tenants never see this row.
- **Phones.** Three sections. "Joined" lists names, with the admin's own row
  tagged Admin and not removable. "Waiting to join" lists names with time left.
  "Old app versions" has the "Accept shared passphrase" switch with the note
  "Turn off once everyone has joined with a code". A + button adds a phone.
  The list is read from the gate on connect. Away from the gate it shows the
  last list seen, greyed out, and nothing can be changed.
- **Add phone.** A name field, a lifetime choice of 10 min, 1 day or 7 days,
  and "Create code".
- **Code.** The QR, the text code, "Works once · expires in …", "Share…" and
  "Cancel this code".
- **Remove.** A confirmation: "Remove <name>? It stops opening the gate
  straight away. The app stays on the phone but cannot rejoin without a new
  code from you."

Platform differences are navigation only. On iPhone, Phones is a pushed screen,
rows swipe left to remove, and Add phone and Code are sheets. On Android,
Phones is its own screen with a top bar, a row's menu has Remove, and Add phone
and Code are full screens. Wording and behaviour are the same on both.

One change from the wireframes: the text code is about 26 characters shown in
groups of four, not the short code drawn there. This follows from approach 1.

### Moving from the current setup

1. Flash the new firmware with the current shared passphrase and a new setup
   code. Current apps keep working.
2. On the owner's phone, install the new app and become admin.
3. For each tenant, create a code. They install the new app and join.
4. When everyone has joined, turn off "Accept shared passphrase". Old builds
   stop working from that moment.

If the new app meets a gate without the reply characteristic, it says the gate
needs a firmware update.

## Calls I made (not decided by the owner)

- Admin screens are built on both iPhone and Android. The owner asked what the
  UI looks like on both and has not said which platform the admin phone uses.
- The admin can open the gate with the admin key and does not need to join as a
  separate phone.
- The admin is not an entry in the gate's phone list. The admin app draws its
  own "Admin" row at the top of the list.
- If the gate answers `denied` to the admin key, another phone has claimed
  admin. The app forgets its admin key and returns to the first-launch screen.
- The new app only works with a gate that has a setup code. A gate with no
  auth at all (bench mode) is driven with a generic BLE tool that writes `PED`.
- Limit of 16 phones, names up to 24 bytes.
- Phone ids are random, not slot numbers, so a reused slot cannot be confused
  with a removed phone.
- Waiting codes live in memory only and die on a power cut.
- PBKDF2 for stretching the setup code, computed once at boot on the ESP32.
- Replies are read from a new characteristic instead of sent as notifications.
- No "last used" information in the list.
- The existing lockout resets when a phone reconnects. This design does not
  change that. The new keys and codes are long enough that they do not depend
  on the lockout.

## Left to the builder

- The PBKDF2 round count. Target under two seconds at boot on the ESP32.
- The exact text and QR encoding of the code. Only the two apps parse it. It
  must carry a format version, the id and the 16-byte secret, and must be the
  same on both platforms.
- NVS layout.
- QR scanning and QR drawing libraries on each platform. iOS needs a camera
  usage string in `Info.plist`.
- How the Android app is restructured. `MainActivity.kt` holds all BLE code
  today, and the admin screens need to share that connection.
- Where the heavy hashing runs in the firmware, if it does not fit inside the
  BLE write callback.
- Build order. Suggested: firmware, then iOS, then Android, then docs.

## Testing

- Write shared test vectors for every row of the "Keys and proofs" table: fixed
  inputs and the expected output. Put them in `docs/architecture.md`. Check
  them with unit tests on Android and iOS, and on the firmware where practical.
- Run the existing commands for each subsystem changed, as listed in
  `CLAUDE.md`.
- Bench test on real hardware before live use: claim, add, join, unlock,
  remove, shared passphrase on and off, power cut with a waiting code, and an
  old app build against the new firmware. Software builds do not prove any of
  this.

## Docs to update in the same change

`README.md`, `AGENTS.md`, `CLAUDE.md`, the `android-app` and `ios-app` copies
of those files, `docs/architecture.md`, and
`include/app_config_local.example.h`.
