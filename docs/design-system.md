# Yap design direction

## The idea

**A living network meets a new internet.** Yap should feel youthful and alive without becoming a sci-fi dashboard. Messaging comes first. The network is visible when it helps people understand whether their messages can travel.

Our implementation reference is the **approved refined six-screen concept, v2**, based on the original concept: [view the design](../design/proposals/yap-refined-six-screen-v2.png). It preserves the original visual identity. The user approved the v2 framed message timeline and a network visualisation that distinguishes local mesh from internet links.

The board uses sample data and includes planned features. It does not authorise new cryptography, routing, receipt or group-governance protocols.

## Starting colours

| Role | Colour |
| --- | --- |
| Ink background | `#0D1018` |
| Raised surface | `#191E2B` |
| Electric lime | `#C7FF3C` |
| Cobalt | `#314CFF` |
| Coral accent | `#FF7958` |

These are starting brand tokens, not a complete accessible palette. Test contrast on actual surfaces before using any pairing.

The native implementation keeps the original Android type family and weights, with outline icons, quieter surfaces and inset separators. The ink background is deeper than the initial token so the network fabric and lime controls have clearer contrast. Settings uses a plain heading and grouped rows; the detached corner flourish has been removed.

## Layout and interaction

- Primary navigation: Chats, Network and Settings. Groups live within Chats.
- Keep the chat list compact, with the wordmark, search, new chat and All/Groups/Unread filters. Conversations and detail screens use Back navigation without the primary tab bar.
- Conversations should be immediately usable, with a clear composer and readable bubbles.
- Network view: display only verified facts from the actual connection layer. Discovery does not equal trust.
- The living network visualisation is a signature of Yap. A continuous, gently moving particle fabric surrounds the centred local phone, with peers arranged around it. Solid lime links denote local connections; dashed cobalt links denote internet connections. A text legend accompanies the links being shown. Node positions are abstract, not geographical distances. Respect both Android's Remove animations setting and the in-app Reduce motion control.
- Live mode currently shows only local Bluetooth observations. Do not draw persistent connections after a write disconnects, or invent peers, relays or internet links. A received message does not establish an authenticated identity.
- Message journey: show as an optional detail view. Display local events that were observed; show route information only when routing data exists.
- Use the approved rounded, lime-framed vertical timeline. Live message details show observed local attempt and Bluetooth acknowledgement times, with recipient delivery explicitly unconfirmed. Do not call an attempt a queued message when no queue exists. The completed Queued/Sent to peer/Delivered journey belongs to the labelled future-state preview until its supporting protocol exists.
- Prefer restrained pulses and transitions; honour reduced-motion settings.
- Use scalable text, adequate contrast and Android touch targets of at least 48dp.
- Keep queued, relayed, acknowledged and delivered states distinct. A BLE write acknowledgement is not proof a person received or read a message.

Primary screens use short product labels. Connection limits, identity checks and security details remain available in Connections and Privacy & identity, rather than repeated prototype banners, geometry explanations or planned-transport legends. Live status still distinguishes a connected phone from one seen recently. Preview sample labels and unconfirmed recipient receipts remain explicit.

## Live mode and design preview

The app starts in live Bluetooth mode. **Settings → Preview the full design** opens a read-only sample experience covering all six screens. Keep the **Design preview · Sample data** banner visible on every preview screen and provide Exit preview. Preview controls must never send Bluetooth messages, change live message history, or imply that group, attachment, internet or mesh transport features are implemented.

Settings rows should lead to useful controls or honest explanations of unavailable capabilities. No enabled controls that silently do nothing. Message history stays in memory for the app session and survives activity recreation; there is no saved history.

## Build sequence

1. Implement shared colour, spacing and typography tokens.
2. Build a static Chats screen and Conversation screen with clearly labelled sample data.
3. Wire real Bluetooth status and incoming messages into the interface.
4. Build a Network view based on real discovery events.
5. Test on different Android screen sizes and with large font settings.
6. Carry the same design language to iOS without copying Android-specific interaction patterns.

This is a design specification, not a claim that these screens or secure features are already implemented.

## Facelift, November 2026

The user approved a refined direction that keeps the original identity (black, electric lime, cobalt, coral) and makes it more polished:

- **Black base** (`#06080E`) with a faint lime glow, raised gradient cards with hairline borders, and per-friend avatar colours (lime, cobalt, coral, violet).
- **A much larger wordmark** on Chats. The user supplied a new logo image to replace the typed one; it goes through the single `Wordmark` composable.
- **No pills.** Filters are text tabs with a lime underline, status is plain text with a dot, buttons and the composer are rounded rectangles, and the navigation is a flat bar with a lime marker above the selected tab. Avoid chips and fully rounded buttons.
- **Network screen:** a centred layout with rings around "You", solid lime local links and dashed periwinkle internet links. As before, show only verified connections; the sample network exists only in the labelled preview.
- **Onboarding and profile:** first-run onboarding and a local profile (name and avatar colour, stored on the phone only and never sent to contacts).

This supersedes the earlier mockup for look and feel. The rules about honest network state, receipts and sample data still apply.
