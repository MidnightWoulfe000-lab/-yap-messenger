# Working on Yap

Yap is a free, open-source, offline-first messenger. Keep the code practical and the writing natural. Use plain British English in documentation and commit messages.

## Project decisions and security guardrails

Before making architectural, messaging, identity, encryption, routing or group changes, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/SECURITY.md](docs/SECURITY.md). These preserve agreed direction but explicitly mark unresolved details. Never invent cryptographic or group-governance policy. Propose a written specification and obtain review before implementing it. Keep the existing working Bluetooth prototype intact while redesigning the UI.

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

Before changing paired Android messaging, read [the endpoint contract](docs/android-endpoint-v1.md) and [release boundaries](docs/debug-release-0.2.md). Keep the experimental encrypted endpoint separate from the unencrypted legacy chat. Never infer an independent audit, physical multi-hop validation or production readiness from emulator tests.
