# Working on Yap

Yap is a free, open-source, offline-first messenger. Keep the code practical and the writing natural. Use plain British English in documentation and commit messages.

## Project decisions and security guardrails

Before making architectural, messaging, identity, encryption, routing or group changes, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/SECURITY.md](docs/SECURITY.md), plus [docs/GROUP_MECHANICS.md](docs/GROUP_MECHANICS.md) for any group-related work. These preserve agreed direction but explicitly mark unresolved details. Never invent cryptographic or group-governance policy. Propose a written specification and obtain review before implementing it. The old unencrypted Bluetooth chat is removed in the mesh v3.1 update (owner, 27 November 2026); until then, keep it intact while redesigning the UI.

Security audit findings, status and rules for follow-up work are in [docs/audit/2026-11-25-security-audit.md](docs/audit/2026-11-25-security-audit.md). Read it before changing the endpoint, native bridge, relay or Bluetooth mesh code. Nearby messaging (Bluetooth and Wi-Fi) is described in [docs/proposals/local-mesh-v2.md](docs/proposals/local-mesh-v2.md): nothing sent to nearby strangers may be permanent or trackable, and users should only ever see one "nearby messaging" switch.

## Protected code: nearby messaging and the mesh

The nearby messaging system (Bluetooth, Wi-Fi, Wi-Fi Aware, Wi-Fi Direct and the mesh that joins them) took a lot of work and is working. **UI and design work must not change it.** Screens may call it as they do now, but must not edit, move, rename, reformat or delete these files, or change their behaviour:

- Android mesh and transport: `LocalMesh.kt`, `MeshHub.kt`, `MeshService.kt`, `MeshLog.kt`, `MeshBluetooth.kt`, `MeshWifi.kt`, `MeshWifiAware.kt`, `MeshWifiDirect.kt`, `FloodGuard.kt`, `EffortStamp.kt`, `YapBluetooth.kt`, `ChatSession.kt`
- Android encryption, relay and storage: `SecureModel.kt`, `EndpointRepository.kt`, `NativeBridge.kt`, `InternetRelay.kt`, `BoundedWebSocket.kt`
- Their tests in `android/app/src/test/` and `android/app/src/androidTest/`
- Permissions, services and receivers in `AndroidManifest.xml`
- The Rust code: `core/`, `endpoint/`, `native/`, `relay-server/`, `groups/`

(The Android files are in `android/app/src/main/java/org/yapmessenger/app/`.)

Screens and styling (`*Screen.kt`, `SecureScreens.kt`, `YapApp.kt`, `YapComponents.kt`, `YapButtons.kt`, `YapTheme.kt`, `DesignPreview.kt`, the composables in `Profile.kt`, and `res/`) are open to UI work. If a redesign needs something new from a protected file, stop and ask for that change separately, with its own review. Do UI work on its own branch, and run the unit and instrumented tests before merging.

## Design direction

Read [docs/design-system.md](docs/design-system.md) before changing the interface. Our reference is the **approved refined six-screen v2 mockup**, based on the first concept: dark ink, electric lime, cobalt, restrained coral, expressive typography and a living network visualisation. The approved image is linked from the design document; unrelated, busier alternatives are not our target.

## Design review

Use these upstream design skills **when they are installed in your coding agent**:

- UI UX Pro Max: https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
- Impeccable: verify the upstream repository and licence before adding it.
- Emil Design Engineering: https://github.com/emilkowalski/skills
- Taste Skill: https://github.com/tasteskill/tasteskill

These are references, **not installed skills** in this repository. Don't claim a skill ran unless you loaded its instructions and applied them. Review third-party instructions and licences before installation.

For UI changes, check hierarchy, contrast, text scaling, touch targets, reduced motion, navigation, loading/error states, privacy and feasibility. Never invent nearby devices, measured distances, delivery confirmations or relay routes for the sake of a prettier screen.

## Engineering

Before changing paired Android messaging, read [the endpoint contract](docs/android-endpoint-v1.md) and [release boundaries](docs/debug-release-0.2.md). Until the legacy chat is removed in the mesh v3.1 update, keep the experimental encrypted endpoint separate from it. Never infer an independent audit, physical multi-hop validation or production readiness from emulator tests.
