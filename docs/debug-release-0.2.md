# Yap 0.2.0 debug

This Android build integrates experimental encrypted one-to-one messaging, durable
queues, local mesh transport and an optional private internet relay. It is for
development testing. It has not received an independent security audit.

## Install and pair

Install `yap-0.2.0-debug.apk` on both phones. Android 8 or newer is required;
encrypted local mesh requires Android 10 or newer and Bluetooth support for LE
L2CAP. Both phones must keep Yap open. Enable local mesh in Network → Connections
and grant Bluetooth permissions. No public internet relay is configured.

Choose **Pair a contact**. Compare the displayed fingerprints in person, scan
each other's pairing codes and confirm. Both people must complete this step.
If discovery cannot exchange the public bundles, use **Share package**, paste
the other phone's package under manual pairing, then exchange **Share pairing
reply** packages. Only confirm a package whose key you have checked with its
owner. Keep both phones connected until the contact reports ready.

Open the paired contact's conversation. Messages support up to 4,096 UTF-8 bytes.
Queued ciphertext survives restart; retry does not encrypt it again. **Relayed**
means custody only. **Delivered** requires an authenticated recipient receipt.
There is no read receipt. Retiring a session rejects its old keys and hints;
already forwarded ciphertext cannot be recalled.

Internet messaging requires an operator-provisioned TLS relay, this phone's
mailbox token and a bearer credential. Configure its `wss://host:port/yap/v1`
address under Connections. A private public CA certificate can be supplied;
hostname verification remains enabled. Operator instructions are in
[relay-server/README.md](../relay-server/README.md).

The old unencrypted 20-byte Bluetooth chat has been removed; all messaging uses the
encrypted endpoint. Design preview screens use labelled sample data and cannot send.

## Boundaries

- Multi-phone LE L2CAP exchange and physical A–B–C forwarding still require real
  Android hardware testing. Emulator endpoint/relay tests do not establish radio
  range, interoperability or battery behaviour.
- Operation is foreground only. iOS, groups, attachments, multi-device access,
  lost-device recovery and remote revocation are unavailable.
- Group voting thresholds, objection duration and partition conflict rules still
  need a project decision before implementation.
- Losing Android Keystore keys makes saved identity/history inaccessible. Backup
  and transfer are disabled; storage corruption or missing keys fail closed.
- This debug APK is signed with the project's private key from CI (see [signing.md](signing.md)); a local build uses Android's own debug key and includes arm64-v8a and x86_64
  native libraries. It is not a production release or a guarantee of safety for
  sensitive communication.

The reviewed endpoint contract is [android-endpoint-v1.md](android-endpoint-v1.md).
Test evidence is recorded in [debug-verification-0.2.md](debug-verification-0.2.md).
The accompanying source archive includes build scripts and dependency versions.
The JNI endpoint links AGPL-3.0-only libsignal; the relay core is GPL-3.0-only.
