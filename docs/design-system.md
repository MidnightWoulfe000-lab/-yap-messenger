# Yap design direction

## The idea

**A living network meets a new internet.** Yap should feel youthful and alive without becoming a sci-fi dashboard. Messaging comes first. The network is visible when it helps people understand whether their messages can travel.

Our reference is the **original six-screen concept** from the design discussion, not the later neon-heavy alternatives. The reference image has not been committed to this repository, so treat this document as the current written source of truth until we add approved assets.

## Starting colours

| Role | Colour |
| --- | --- |
| Ink background | `#171923` |
| Electric lime | `#C7FF3C` |
| Cobalt | `#314CFF` |
| Coral accent | `#FF7958` |

These are starting brand tokens, not a complete accessible palette. Test contrast on actual surfaces before using any pairing.

## Layout and interaction

- Primary navigation: Chats, Network and Settings. Groups live within Chats.
- Conversations should be immediately usable, with a clear composer and readable bubbles.
- Network view: display only verified facts from the actual connection layer. Discovery does not equal trust.
- Message journey: show as an optional detail view, and only when routing data exists.
- Prefer restrained pulses and transitions; honour reduced-motion settings.
- Use scalable text, adequate contrast and Android touch targets of at least 48dp.
- Keep queued, relayed, acknowledged and delivered states distinct. A BLE write acknowledgement is not proof a person received or read a message.

## Build sequence

1. Implement shared colour, spacing and typography tokens.
2. Build a static Chats screen and Conversation screen with clearly labelled sample data.
3. Wire real Bluetooth status and incoming messages into the interface.
4. Build a Network view based on real discovery events.
5. Test on different Android screen sizes and with large font settings.
6. Carry the same design language to iOS without copying Android-specific interaction patterns.

This is a design specification, not a claim that these screens or secure features are already implemented.
