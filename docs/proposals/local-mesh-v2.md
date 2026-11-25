# Local mesh v2: Wi-Fi transport and rotating link IDs

**Status: implemented on branch `ccr-ba12ab3f-getmod` (25 November 2026). Not yet
tested on real phones. Not independently reviewed.**

**Audience: coding agents and maintainers.** Read this before changing
`MeshHub.kt`, `MeshBluetooth.kt`, `MeshWifi.kt`, `LocalMesh.kt` or the pairing code
in `SecureModel.kt`.

## Why

1. The owner asked for Wi-Fi as well as Bluetooth for nearby messaging. Wi-Fi gives
   more range and speed than Bluetooth and works with no internet: phones on the
   same network, or joined to one phone's hotspot.
2. The owner's rule, stated directly: **nothing a phone sends to strangers may be
   permanent or easy to track.** Yap is meant for protesters. Audit findings 1 and 2
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

## What is still permanent (not fixed here, needs a spec)

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
