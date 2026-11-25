# Yap

Nobody gets to silence you.

Yap is a free, open-source messenger that works with or without the internet. Phones talk to each other directly over Bluetooth and Wi-Fi, and pass messages along through other phones until they reach the right person. It's built for protests and shutdowns, so it has to be simple to use and it can't leave a trail.

**This is the dev build. It is not safe for real protest use yet.** Nothing here has had an independent security audit and most of it hasn't been tested on real phones. Don't trust it with anything that could get someone hurt.

## What works right now

**Messaging**
- One-to-one encrypted chats using the Signal protocol (libsignal, with a post-quantum key). Only you and the person you're talking to can read them.
- You add someone by scanning each other's QR codes in person. Both phones have to confirm, so nobody can quietly slip in between.
- Your messages and keys are stored encrypted on the phone and never go into backups.

**Nearby messaging (no internet needed)**
- Works over Bluetooth and Wi-Fi at the same time. One switch: "Turn on nearby messaging".
- Wi-Fi works with phones on the same network, or with everyone joined to one phone's hotspot. No internet is used.
- If you refuse Bluetooth permission, Wi-Fi still works.
- Messages hop through other Yap phones to reach people out of range (up to 8 hops, kept for up to 24 hours).
- Keeps running in the background with the phone locked. You'll see a plain "Nearby messaging is on" notification with a Pause button. New messages show as a plain "New message" alert, never who sent it or what it says, and neither shows on the lock screen.

**Not leaving a trail**
- Nothing a phone sends to strangers nearby is permanent. Each phone uses a random ID that changes every time nearby messaging starts and every 10–20 minutes.
- Your identity key is only sent to a phone that has actually scanned your QR code. It's never broadcast.

**Storage**
- Your own messages and messages you carry for other people are kept separately.
- Your phone carries up to 10,000 messages for others (64 MB max). When it's full, the oldest ones are deleted to make room.

**Spam protection**
- If one connection sends more than 40 messages in a minute, it gets slowed to one message every 3 seconds for 5 minutes.
- If the whole phone gets more than 100 in a minute, everything slows to one a second for 2 minutes. That catches spammers who keep reconnecting.
- Nothing gets lost. Slowed messages just wait and get sent again.

**Internet (optional)**
- There's a private relay server you can run yourself, over an encrypted connection. It can't read messages. We're thinking of dropping it in favour of Tor so nothing is stored anywhere.

## What's still left

**Must do before anyone relies on it**
- Test on real phones: two phones on the same Wi-Fi, a hotspot, Bluetooth only, three phones with one in the middle carrying messages, and phones locked in pockets. Steps are in [testing on Android](docs/testing-android.md).
- An independent security audit.
- Message labels: each message still carries a fixed address label so phones know where to pass it. A modified app nearby could collect those and recognise the same person over time. Fixing it changes the encryption design, so it needs a written plan first.

**Next up**
- Limited copies ("spray and wait"): right now a carrier phone hands a message on and deletes its copy. If it then meets the actual recipient first, it has nothing to give. The plan is to start each message with about 8 copies.
- A crowd simulation to pick the right number of copies.
- An "effort stamp" (proof of work) on each message so mass spam is slow and expensive to make.
- Group chats. Protests need them, and they're the hardest part to get right.
- Making the internet side work through Tor instead of our own server.
- Fixing the open security findings, mainly: a wrong clock can lock you out, someone with deep access to the phone could roll back its data, and a contact could fill your message history and jam the chat. Full list in the [security audit](docs/audit/2026-11-25-security-audit.md).

**Known limits**
- Android only. iPhones heavily restrict apps running in the background, so iOS is a big open question.
- Some phone brands (Xiaomi, Huawei, Samsung and others) still kill background apps. You may need to turn off battery saving for Yap by hand.
- Doesn't restart after the phone is switched off and on. Open Yap once.
- Background running uses more battery. Bring a power bank.
- Anyone scanning nearby can tell a phone is running Yap, just not whose it is.

## For developers and agents

- Build: [android/README.md](android/README.md)
- How nearby messaging works (Wi-Fi, rotating IDs, background, storage, spam): [local mesh v2](docs/proposals/local-mesh-v2.md)
- Security audit and what's fixed: [security audit](docs/audit/2026-11-25-security-audit.md)
- Ground rules: [AGENTS.md](AGENTS.md), [security](docs/SECURITY.md), [architecture](docs/ARCHITECTURE.md)

Licence: GPL-3.0 (core) and AGPL-3.0 (endpoint and Android native code). See [LICENSE](LICENSE).
