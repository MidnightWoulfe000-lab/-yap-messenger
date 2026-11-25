# Local mesh v2: Wi-Fi transport and rotating link IDs

**Status: implemented on branch `ccr-ba12ab3f-getmod` (25 November 2026). Not yet
tested on real phones. Not independently reviewed.**

> **Update:** the Wi-Fi transport below only works on a shared network or hotspot, which
> is not the drop-in, drop-out proximity behaviour the owner wanted. Proximity Wi-Fi
> (Aware, with Direct as a fallback) is in
> [local-mesh-v3-proximity-wifi.md](local-mesh-v3-proximity-wifi.md). The Aware/Direct
> follow-up at the end of this page is superseded by it.

**Audience: coding agents and maintainers.** Read this before changing
`MeshHub.kt`, `MeshBluetooth.kt`, `MeshWifi.kt`, `LocalMesh.kt` or the pairing code
in `SecureModel.kt`.

## Why

1. The owner asked for Wi-Fi as well as Bluetooth for nearby messaging. Wi-Fi gives
   more range and speed than Bluetooth and works with no internet: phones on the
   same network, or joined to one phone's hotspot.
2. The owner's rule, stated directly: **nothing a phone sends to strangers may be
   permanent or easy to track.** Audit findings 1 and 2
   (`docs/audit/2026-11-25-security-audit.md`) showed v1 broke this rule: Bluetooth
   handed the permanent routing token to anyone who connected, and every phone that
   opened the pairing screen sent its identity key to every nearby phone.
3. The owner wants the app simple. Users see one switch, **Turn on nearby
   messaging**. Which radio carries a message is invisible. Wi-Fi needs no runtime
   permission; if Bluetooth permission is refused, Wi-Fi still runs.

## Design

### One link layer, several transports
- `MeshHub` (no Android dependencies, unit-tested on the JVM) owns framing, link
  IDs, the peer table, slots (max 12), timeouts, keepalive and duplicate handling.
- `MeshBluetooth` and `MeshWifi` only discover phones and open sockets, then call
  `MeshHub.attach(...)`. `LocalMesh` starts and stops them together.
- Frame format is unchanged from v1: 4-byte length, 1-byte type, payload, max
  65,537 bytes. Frame types: HELLO 1, BUNDLE 2, CONFIRM 3, ENVELOPE 4, ACK 5,
  KEEPALIVE 6, **BUNDLE_REQUEST 7 (new)**.

### Rotating link IDs (replaces the route token on the link)
- Each phone has a random 16-byte **link ID** (`LinkIdentity`). A new one is made
  on every start of nearby messaging and every 10–20 minutes (random jitter so
  rotations across phones don't line up).
- On rotation every open link is closed and the transports re-advertise. Links then
  reconnect under the new ID; queued ciphertext is retried. This costs a brief
  reconnect; it is the price of not keeping an ID alive for hours.
- The previous ID is remembered so our own lingering adverts are not mistaken for
  another phone.
- HELLO payload is now **link ID (16) + random per-link nonce (8)**. Nothing else.
- The link ID is used for discovery, "who dials" ordering and the peer table only.
  The messaging route token and identity keys are never sent to find or name a link.

### Bluetooth changes
- New service and characteristic UUIDs (`…4301`, `…4302`) so v1 builds, which
  expose the route token, never talk to v2 builds.
- The GATT characteristic returns `version 2 + PSM (2) + link ID (16)`.
- Adverts carry only the service UUID; device name is explicitly excluded.

### Wi-Fi transport (new)
- TCP server on port 47651 (falls back to any free port if taken).
- Discovery: Android `NsdManager` (mDNS/DNS-SD), service type `_yapmesh._tcp`,
  service name `yap-<link ID in hex>`. The name rotates with the link ID.
- **Hotspot fallback:** many hotspots and routers block multicast, so every 20
  seconds a client also dials its Wi-Fi gateway (the hotspot phone) on port 47651.
  It never dials public addresses, only site-local and link-local ones.
- Plain TCP. Acceptable only because every envelope is end-to-end encrypted; the
  link is unauthenticated, exactly like the Bluetooth link.
- New permission: `ACCESS_NETWORK_STATE` (install-time, no prompt).

### Duplicate links
Two phones can open two links to each other (both dialled, gateway dial plus mDNS,
or Bluetooth plus Wi-Fi). Both sides must keep the **same** link, so the choice uses
only values both sides know: prefer Wi-Fi; otherwise keep the link whose initiator
sent the lower (link ID, nonce) pair. Normally only the phone with the lower link ID
dials, so duplicates are rare.

### Pairing over the mesh (bundles no longer broadcast)
v1 sent the current pairing bundle (which contains the permanent identity key and
route) to every phone that connected. v2:
1. Phone B scans phone A's QR. The QR contains a 32-byte commitment (hash of A's
   bundle; A's bundle is fresh for each pairing code).
2. B sends `BUNDLE_REQUEST(commitment)` to all its links, every 5 s for up to 10 min.
3. A answers with BUNDLE **only if** the commitment matches its current, unexpired
   offer. Strangers who never saw the QR cannot get the bundle.
4. B ignores any BUNDLE it did not ask for, and checks the hash before pairing.
5. B remembers which link delivered the bundle and sends its signed confirmation
   only on that link. A learns B's link from B's valid (signed) confirmation, or
   from its own request when it scans B's QR. Previously confirmations were routed
   by matching the permanent route token against the link token.

Pasting a full pairing package (QR + bundle) still works, unchanged.

## Multi-hop (store and forward)

How it works (unchanged from v1, now over both transports): the sender offers each
envelope to every nearby link and keeps it in its outbox until the recipient's
encrypted receipt arrives. A phone that is not the recipient stores the envelope in
its native relay database (`core/src/relay.rs`, up to 24 h, 1024 envelopes / 8 MB)
and offers it to its other links, one hop count used per forward (max 8). When the
next phone durably stores it and replies with ACK, the relay releases its copy. The
receipt returns the same way.

**Rotation side effect, fixed:** the relay excludes "the link it came from" by link
token. With rotating IDs, a relay could hand an envelope back to its own sender
after a rotation. `SecureModel` now remembers the relay keys of envelopes it sent
itself (`ownKeys`) and, if one comes back, accepts it off the relay's hands without
storing a relay copy (its outbox is already retrying). Between two relays a bounce
after rotation is harmless: the original relay's 10-minute recent cache refuses it.

**Limits:**
- See "Spray and wait" below: carriers no longer give up their only copy.
- The core A→B→C path is covered by the Rust test
  `custody_survives_restart_and_a_b_c_forwarding_keeps_payload_identical`. It has
  **never been tested on three real phones.**

## Spray and wait (12 copies) and effort stamps

Owner decisions (25 Nov 2026): limited-copy routing with 12 copies, and an effort stamp.

**Frame:** nearby ENVELOPE frames are now `copies (1) + effort stamp (8) + envelope`.
New frame DELIVERED (8, 48-byte relay key) means "I am the recipient and have it".

**Rules:**
- The sender starts each envelope with 12 copies. Anyone holding more than one copy
  offers half to each new link (`Spray.give`); when that link ACKs, the holder keeps
  the rest. 12 goes 6, 3, 1, 1.
- With one copy left, the holder only delivers straight to the recipient. A phone
  that is not the recipient declines a one-copy offer (no ACK, nothing stored).
- The recipient answers DELIVERED. A carrier then deletes its copy entirely; the
  sender only updates the message status and keeps its outbox copy until the
  encrypted receipt arrives, as before.
- Carriers store copies and stamp beside the envelope (`relay_routing_v1` table,
  `Relay::admit_carried`, `Relay::hand_off`). Missing row = 1 copy, no stamp.
- Offers are tracked per link for 60 s so an ACK subtracts the right number, and so
  waiting envelopes at the head of the queue cannot starve the rest (the host asks
  the native store for 128 and sends up to 16 not recently offered on that link).
- The sender's copy counts and stamps live in memory: after an app restart its own
  envelopes start again at 12 copies and are re-stamped. Accepted for the dev build.

**Effort stamp:** `EffortStamp.kt`. The sender finds 8 bytes so that
`SHA-256("Yap stamp v1\0" || relay key || stamp)` starts with 18 zero bits (about a
quarter of a million hashes, roughly 0.3–1 s on a mid-range phone, once per
envelope, receipts included). Every phone checks it with one hash before the flood
guard or any native work, and drops envelopes without a valid stamp. Carriers pass
it on unchanged. It slows phone-based spam; a laptop can still mint many per minute,
so flood slow mode and limited copies stay the main defences.

**Accepted trade-offs and gaps:**
- A modified app can lie about copies (claim 12 every time). That only spreads its
  own messages further; flood slow mode still limits how many it can push.
- A modified app can send DELIVERED for envelopes it is not the recipient of, making
  that one carrier drop its copy. Other copies survive.
- No phone-wide "delivered" signal yet: other copies wander until they expire (24 h).
  A forgery-proof one needs the address-label work first.
- Copy count 12 and stamp difficulty 18 bits are starting values. A crowd simulation
  to tune them is still to do.

Tests: `SprayAndStampTest.kt`; core `spray_and_wait_copies_and_stamp_travel_with_the_envelope`,
`copies_are_clamped_and_plain_admit_means_one_copy`.

## Background running (MeshService)

Owner priority: messaging must keep working with the phone locked in a pocket.

- `SecureModel` is now a process-wide runtime (`SecureModel.get(application)`), no
  longer an Activity `ViewModel`, so it survives the screen closing.
- `MeshService` is a foreground service (type `connectedDevice`) started whenever
  nearby messaging is on and a Yap screen is visible (Android 12+ forbids starting
  it from the background). It returns `START_STICKY`, so Android restarts it after
  killing the process; the runtime then reopens the identity and resumes the mesh.
- While it runs, `SecureModel.active` is true even with no screen, so receiving,
  carrying and the 3-second outbox pump continue.
- It holds a partial wake lock (renewed every 10 minutes) so the CPU keeps links and
  retries moving with the screen off. **This costs battery.** Users stop it with
  **Pause** in the notification or in Connections.
- Notifications are generic and hidden on the lock screen (`VISIBILITY_SECRET`):
  "Nearby messaging is on" and "New message". Never a name or text, because phones
  can be seen by others. Turning on nearby messaging also asks for notification
  permission on Android 13+; refusing it does not stop messaging.
- Android 14 requires a matching permission for `connectedDevice`;
  `CHANGE_NETWORK_STATE` (install-time) satisfies it even if Bluetooth is refused.
- Known gaps: some manufacturers (Xiaomi, Huawei, Samsung and others) kill
  background apps despite this; users may need to exempt Yap from battery
  optimisation by hand. Not restarted after a phone reboot (no boot receiver yet).
  Bluetooth scans continue with the screen off because they use a service filter.

## Carrier storage (separate from the user's own messages)

Owner decision: two separate stores.
- **Your own messages** stay in the encrypted endpoint snapshot (`EndpointRepository`),
  unchanged (still capped at 512 visible messages; see audit finding 5).
- **Carried messages** for other people live in the native relay database. On phones
  it now holds up to **10,000 envelopes** (also capped at 64 MiB, because one
  envelope can be up to 64 KiB). When full, the **oldest** carried envelopes are
  deleted to make room (`RelayConfig::evict_oldest_when_full`). The internet relay
  server keeps rejecting when full, because it promises custody to senders.
- Trade-off: a flood of junk pushes out real carried messages sooner. Limited-copy
  routing and a proof-of-work stamp (both proposed, not built) reduce that.
- Scans that run on every operation now read only record headers, so a full store
  stays fast: about 2.5 ms per store and 5 ms per lookup on a development machine
  with 10,000 entries. Phones will be slower; not yet measured on hardware.
- Tests: `core/tests/relay.rs` `carrier_store_evicts_oldest_when_full`,
  `carrier_store_evicts_by_bytes_and_still_rejects_oversized`.

## Flood slow mode (FloodGuard)

Owner idea: if one phone sends far more messages than a person would, the mesh slows
it down. Implemented in `FloodGuard.kt`, applied to every ENVELOPE from a nearby link
before any native work.

- Per link: more than 40 envelopes in a minute puts that link in slow mode, one
  envelope per 3 s for 5 minutes.
- Phone-wide: more than 100 in a minute across all links puts the phone in slow mode,
  one per second for 2 minutes. This catches spammers who reconnect under new IDs.
- Refused envelopes get no ACK, so honest senders keep and retry them. Honest
  carriers with a big backlog are slowed too (accepted trade-off).
- Rejected alternatives, and why: telling "fresh" from "carried" messages (hop counts
  or per-carrier stamps can be faked; signed stamps would record who carried what,
  a tracking trail); recognising a phone across reconnects (contradicts rotating IDs);
  neighbours warning each other about spammers (a modified app could falsely accuse
  real users).
- Thresholds are starting values; tune them with real crowd tests. Tests:
  `FloodGuardTest.kt`.

## What is still permanent (not fixed here, needs a spec)

**A plan now exists:** [rotating address labels](rotating-address-labels.md), awaiting review.


The **envelope header** still carries the recipient's permanent route token
(`destination`) and a per-contact `sender_hint`. Every envelope is sent to every
nearby link for store-and-forward, so any nearby phone running modified software
can still collect these and recognise the same recipient over time. Fixing it means
deriving rotating destination tags from a secret shared by the two contacts and
having recipients trial-match them. That is a cryptographic protocol change, so per
`AGENTS.md` it needs a written proposal and review first. It also breaks the current
relay server's fixed mailbox matching, which the owner is considering removing.
Track it as audit finding 1b.

Other remaining signals, accepted for now:
- The Yap Bluetooth service UUID, the `_yapmesh._tcp` service type and an open port
  47651 reveal that a phone runs Yap, though not which person.
- An attacker who reads a phone's current link ID can claim it first and block that
  phone's links for at most one rotation period (finding 2, reduced, not removed).
  Fixing it needs link authentication, which needs a spec.

## Tests

- `android/app/src/test/java/org/yapmessenger/app/MeshHubTest.kt` (JVM, real
  loopback TCP): link and frame delivery, HELLO contains only the random ID and
  nonce, fresh ID per start, scheduled rotation, own reflected HELLO rejected,
  oversized and unknown frames close the link, both sides keep the same link when
  two are opened, Wi-Fi preferred over Bluetooth, BUNDLE_REQUEST size check,
  Wi-Fi service name parsing.
- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
  :app:compileDebugAndroidTestKotlin` all passed. The APK was built with placeholder
  native libraries, so this checks compilation only.
- **Not done:** real-phone tests. See `docs/testing-android.md`, "Nearby messaging
  over Wi-Fi". Expect bugs in mDNS on some Android versions and in hotspot setups.

## Follow-up for agents
1. Two-phone and three-phone tests on real hardware (same Wi-Fi, hotspot, Bluetooth
   only, mixed). Record Android versions and phone models.
2. Write the proposal for rotating envelope destination tags (finding 1b).
3. Consider Wi-Fi Aware on phones that support it, as an extra transport behind the
   same hub. Avoid Google Nearby Connections (needs Google Play services, closed source).
