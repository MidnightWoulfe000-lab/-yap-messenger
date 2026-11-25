# Local mesh v3: proximity Wi-Fi (Aware and Direct)

**Status: implemented. Compiles; unit tests and lint pass (26 November 2026, with
placeholder native libraries, so this proves compilation only). Never run on a phone.
Not independently reviewed.**

**Audience: coding agents and maintainers.** Read with [local-mesh-v2.md](local-mesh-v2.md).

## Why

v2 added Wi-Fi nearby messaging, but it only works when phones are already on the same
network: a shared router or one phone's hotspot. Someone has to set that up first. The
owner wants the opposite: phones in range link up, phones out of range drop off, with no
router, no hotspot and no setup. Bluetooth already behaves like that but is slow and
short-range. This change brings the same behaviour to Wi-Fi.

## Design

Two new transports plug into `MeshHub` beside `MeshBluetooth` and `MeshWifi`. Framing,
rotating link IDs, duplicate handling, store-and-forward and the single "nearby
messaging" switch are unchanged. The user sees no new controls.

### Wi-Fi Aware (`MeshWifiAware.kt`), the main transport
- Needs Android 10+ and a Wi-Fi chip with Aware (`FEATURE_WIFI_AWARE`). Most recent
  Pixels and Samsungs have it; many budget phones do not.
- Every phone publishes and subscribes to service `yapmesh`. The publish info is the
  16-byte rotating link ID and nothing else. Discovery restarts on every rotation.
- Only the phone with the lower link ID initiates (same rule as the other transports).
  It sends HELLO; the publisher opens a `ServerSocket`, requests its side of the data
  path, waits 1.5 s, then sends READY carrying the TCP port and the link mode. Only then
  does the initiator request its side, connect to the peer's IPv6 address on that port,
  then `MeshHub.attach`. Android only carries a port inside the data path for passphrase
  links (`setPort()` on an open link makes `build()` throw), so the port is in READY.
- Limits: at most 2 outgoing and 2 incoming setups at once (chips allow few data paths);
  each setup times out after 30 s. Discovered peers are retried every 15 s.
- Link mode (decided 2026-11-25): the data path is tried open first. Open data paths
  failed between a Pixel 9 Pro XL and a Nothing Phone A063 until READY was delayed by
  1.5 s. With the delay, an open link formed on the first attempt (tested 2026-11-25), so
  the cause was the timing race. In case other phones refuse open links, after a failed
  setup with a peer the publisher switches that peer to a passphrase link and alternates
  until one works. The passphrase path has not yet been exercised on a real phone. The passphrase is a public constant in the source. It is **not** a
  security measure and must never be described as one: it only satisfies phones that
  will not make open links. All envelopes stay end-to-end encrypted, and the link is
  unauthenticated, exactly like Bluetooth and the v2 Wi-Fi link.

### Wi-Fi Direct (`MeshWifiDirect.kt`), fallback for phones without Aware
- Runs when Aware is unsupported on the phone, or when Aware is supported but fails to
  attach (seen on a Pixel 9 Pro XL).
- DNS-SD service discovery with one TXT record, `n=yap-<link ID hex>`, re-published on
  rotation. The lower link ID calls `WifiP2pManager.connect`.
- Once a group forms, the client dials the group owner on the existing `MeshWifi` port
  (47651); the owner is already listening. Idle groups are removed after 60 s.

### Permissions
- Android 13+: `NEARBY_WIFI_DEVICES` (declared `neverForLocation`). Android 12 and older:
  `ACCESS_FINE_LOCATION` (the manifest limit moved from API 30 to 32). Asked together with
  the Bluetooth prompt; refusing it leaves Bluetooth and shared-network Wi-Fi working.
- Also `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` (install time).
- v2 said Wi-Fi needs no runtime permission. That is still true for shared-network
  Wi-Fi only.

## Privacy

- Aware: only the rotating link ID is advertised. Aware uses randomised interface
  addresses per session; this is platform behaviour, not verified by us.
- **Direct leaks a stable identifier.** The Wi-Fi Direct device address is visible to
  nearby phones during discovery and apps cannot rotate it. This breaks the rule that
  nothing sent to strangers may be permanent or trackable. That is why Direct is a
  fallback and off wherever Aware exists. **The owner must decide whether to accept
  this for phones without Aware.** It is the main reason to review this change.
- Advertising the service name still reveals that a phone runs Yap, as in v2.

## Not done / open questions
1. Real-phone tests. Aware and Direct are the least predictable Android radio APIs.
   Expect bugs in data-path setup, group negotiation and a system prompt on connect.
2. Compilation and JVM tests do not exercise the radios. Aware and Direct have only been
   type-checked against the Android 15 SDK.
3. Should Direct take over when Aware is supported but fails?
4. Every link is reported as the generic Wi-Fi transport; Connections does not say which
   radio is in use (by design: one switch).
5. Battery cost of running Aware, Bluetooth and the foreground service together is
   unmeasured.

## Tests
`MeshHubTest.awareAndDirectDiscoveryAcceptOnlyOneLinkId` covers the discovery payload
parsing only. Everything radio-related needs hardware: see
[testing-android.md](../testing-android.md), "Nearby messaging with no router".
