# Working on Yap

Yap is a free, open-source, offline-first messenger. Keep the code practical and the writing natural. Use plain British English in documentation and commit messages.

## Design direction

Read [docs/design-system.md](docs/design-system.md) before changing the interface. Our reference is the **first six-screen Yap mockup**: dark ink, electric lime, cobalt, restrained coral, expressive typography and a living network visualisation. The later, busier mockups are not our target.

## Design review

Use these upstream design skills **when they are installed in your coding agent**:

- UI UX Pro Max: https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
- Impeccable: verify the upstream repository and licence before adding it.
- Emil Design Engineering: https://github.com/emilkowalski/skills
- Taste Skill: https://github.com/tasteskill/tasteskill

These are references, **not installed skills** in this repository. Don't claim a skill ran unless you loaded its instructions and applied them. Review third-party instructions and licences before installation.

For UI changes, check hierarchy, contrast, text scaling, touch targets, reduced motion, navigation, loading/error states, privacy and feasibility. Never invent nearby devices, measured distances, delivery confirmations or relay routes for the sake of a prettier screen.

## Engineering

Android is currently a Bluetooth prototype, not a secure messenger. Do not imply that messages are end-to-end encrypted or identities verified until those features exist and have been tested. Keep UI work separate from protocol/security changes where possible.
