# Proposal: photos, voice notes and files

**Status: draft for review; do not implement.** Per `AGENTS.md` this adds new encrypted
content and new frames, so it must be reviewed before any code is written. It ships in
the same update as [local mesh v3.1](local-mesh-v3.1-group-envelopes.md) and
[local storage](local-storage-v1.md) (owner, 27 November 2026).

**Audience: reviewers, maintainers and coding agents.**

## Owner decisions (27 November 2026)

- Photos, voice notes and files are all supported.
- **Small attachments travel through the mesh, big ones only directly.** A compressed
  photo or a short voice note is carried by strangers like a message. Anything bigger
  only moves when the two phones are linked to each other.

## Problem

An envelope holds at most 64 KiB. Under mesh v3.1, anything strangers carry ends up sent
once to almost every phone near a holder (v3.1 §1.3), so the radio cost of a carried
item is paid by everyone nearby. A 5 MB file carried that way would cost every nearby
phone 5 MB of radio time and fill carrier stores. There is also nowhere to keep
attachments today, and no way to encrypt them.

## Proposed design

### 1. Encrypting an attachment

Each attachment gets a fresh random 32-byte **file key** and a fresh random 19-byte
**stream nonce**. The file is encrypted with RustCrypto's STREAM construction,
`StreamBE32` over XChaCha20-Poly1305 (the `aead-stream` crate, pinned in the same
crate family as the outer layer's `chacha20poly1305`). The exact cut:
- For a file of `n` plaintext bytes, the segment count is `max(1, ceil(n / 65,536))`.
  Every segment except the last holds exactly 65,536 bytes; the last holds the rest,
  from 1 to 65,536 bytes. An empty file is one empty last segment. A file of exactly
  65,536 × k bytes has k full segments and no extra empty one.
- Each segment is encrypted to its plaintext length + 16 bytes of tag, so the
  encrypted size is `n + 16 × count`.
- STREAM's final flag is not a byte in the file: it is part of the nonce. The receiver
  knows which segment is last from the count in the manifest, decrypts it with the
  final flag and every other segment without it, so a file cut short, or with a
  non-final segment presented as last, fails.

The **encrypted file** is the segments back to back, nothing else.

The **message** that announces it is an ordinary end-to-end encrypted text message
(one-to-one or group) carrying, inside the encryption:
- a random 16-byte attachment ID;
- the file key and stream nonce;
- the kind (photo, voice note, file), the plaintext size, the encrypted size, the
  segment count and the SHA-256 of the encrypted file;
- for photos, a thumbnail of at most 4 KB; for voice notes, the length; for files, the
  name (at most 255 bytes) and type;
- whether it is small (mesh) or big (direct).

Nobody but the recipients learns any of this. Carriers and links only ever see
encrypted bytes.

This **manifest** is authenticated by the message that carries it, so it comes from the
sender the message came from. Before showing or saving an attachment, the phone checks
all of it together: the encrypted bytes are exactly the announced size and hash, there
are exactly the announced number of segments, every segment decrypts, and the last one
carries the final flag. Nothing is shown, opened or kept as a finished file until all of
that passes. Pieces and chunks need no sender authentication of their own: anything
that does not match the hash in the manifest is refused, whoever sent it.

### 2. Small attachments: through the mesh

- **What counts as small:** an encrypted file of at most **262,144 bytes** (§6), so a
  prepared file of at most 262,080 bytes (four segments' tags fit in the rest). A photo is
  resized to at most 1,600 pixels on its longest side and compressed to fit. A voice
  note is at most **60 seconds**, recorded as Opus at 16 kbit/s (about 120 KB).
- **Pieces.** The encrypted file is cut into at most 5 pieces of up to 60 KiB, by byte
  offset, independently of the 64 KiB segments. Each piece travels in its own envelope,
  addressed with the same label as a message from that sender would be (rotating
  address labels §3, or the sender's group label) and wrapped with the outer layer.
  Inside the outer layer: the attachment ID, the byte offset, the total encrypted size
  and the bytes.
- **What strangers can tell:** pieces share the label of the sender's other messages
  in that hour, so strangers can see that several envelopes in a burst belong to the
  same pair or group, and their sizes suggest an attachment. They cannot see what kind,
  or read it. Spacing pieces out would not hide the shared label and would risk them
  expiring, so it is not proposed.
- **Putting it together:** once a manifest is accepted and its space reserved (§4),
  pieces are written into its staging file by offset. A piece whose offset and length
  fall outside the encrypted size, or overlap a piece already received with different
  bytes, is dropped.
- **A piece that arrives before its manifest** is held **in memory only**, never
  written: at most 64 such pieces and 4 MB per phone, for at most 10 minutes, each kept
  with the label it came under. When a manifest arrives, only held pieces from the same
  sender's label scope with the same attachment ID are used; the rest expire. A held
  piece's claimed total is not trusted: the manifest's size decides.
- Once the staging file holds every byte, the manifest checks above run; if they fail,
  the staging file is deleted.
- **Same rules as messages:** each piece has its own effort stamp and follows the copy
  rules in mesh v3.1 §1.3. The receiver puts the pieces together, checks the hash and
  each chunk, and shows the attachment once all have arrived. Pieces that never all
  arrive expire with their envelopes (24 hours) and the attachment is shown as not
  received.
- **The cost, stated plainly:** a small attachment costs up to 5 messages' worth of
  stamps (a few seconds on a phone) and up to 5 times a message's radio traffic. In the
  800-phone crowd of the copy-count simulation, one message was sent about 850 times;
  a 256 KB attachment would then move about 200 MB over the air in total, about 250 KB
  per nearby phone.

### 3. Big attachments: directly between the two phones

- **What counts as big:** anything over 256 KB, up to **100 MB**, including full-size
  photos and any file.
- **When it moves:** only over a direct link where **each** phone recognises the other
  as that contact (mesh v3.1 §3), and **neither** has hidden from the other (§3.9). A
  phone that has hidden from a friend never offers or accepts a direct transfer with
  them, even though it may still recognise them. Strangers never carry big
  attachments.
- **A separate encrypted channel.** Recognition only shows that a shared secret was
  used; it is not a channel. So before any transfer frame, the two phones run a Noise
  handshake, **`Noise_NNpsk0_25519_ChaChaPoly_SHA256`** with the same library as
  encrypted pairing:
  - the pre-shared key is `HKDF-SHA256(ikm = linkKey, salt = "Yap direct transfer v1",
    info = the two link IDs and two HELLO nonces, initiator's first)`;
  - `linkKey` is for the week named in the initiator's first message, which must be
    the receiver's previous, current or next week (mesh v3.1 §3.4);
  - the initiator is the phone with the smaller identity key; the prologue names both
    identity keys, the week and the link;
  - Noise then gives each direction its own key and message counter, so the two phones
    never reuse a key and nonce, and a replayed or reordered frame fails.

  The transfer frames travel inside that channel as Noise transport messages, in two
  new mesh frame types: `DIRECT_HANDSHAKE` (13) and `DIRECT_DATA` (14). Inside:
  `FILE_OFFER`, `FILE_CHUNK`, `FILE_ACK` and `FILE_DONE` messages. Each `FILE_CHUNK`
  holds at most 60 KiB of the encrypted file at a stated offset, so it fits a frame.
- **Resuming.** A dropped link ends the channel. On the next suitable link, a new
  handshake starts; the receiver says which byte ranges it already holds, and the
  sender sends the rest. Received ranges are saved, so a restart resumes too.
- **What a listener sees:** that two phones are moving a lot of data. A link relayed
  between two places (mesh v3.1 §3.1) can carry the transfer, but only ever sees
  encrypted bytes; recognition never proves the friend is nearby.
- **Hidden:** the attachment says "Waiting until you can both recognise each other
  nearby". A friend can therefore tell, over time, that big files to you never start,
  which hints that recognition is off between you. The mesh v3.1 §3.9 statement that
  hiding is not visible is qualified to say so.
- **Groups:** a big group attachment goes directly to members who are also your paired
  contacts. Each member who has it passes it on the same way to their own paired
  contacts in the group. A member paired with nobody in the group who has it never
  gets it; the app says so instead of promising delivery. Each member that finishes
  sends an authenticated "received" group message; the app shows how many members
  have confirmed, counting each member once.

### 4. Storage

Attachments live in `noBackupFilesDir/attachments/` as files named by random IDs, still
encrypted with their file keys. The keys and references live in the store's `history`
table ([local storage](local-storage-v1.md) §3). Attachments have their own space limit,
shown and changeable in Settings (starting value 5 GB), with the same fair-share rule as
messages. Clearing a chat or removing a contact deletes its attachment files.

**Carrier store:** small-attachment pieces are ordinary envelopes up to 60 KiB, so they
fill the carrier store's 64 MiB far sooner than text does. The limit stays at 64 MiB
until the simulator measures bytes (it does not yet); raising it would not raise the
native ingress limit of 1 MiB a minute or reduce last-copy radio traffic.

**Admission, before anything is written** (applies to both kinds):
- Space for an attachment's full encrypted size is **reserved** against the
  attachments limit when its manifest is accepted, before any piece or chunk is
  written. No reservation, no transfer.
- A manifest larger than the limits in §6, or for which there is no space, is shown as
  "Too big to receive" and nothing is reserved.
- At most 8 incomplete attachments per chat and 64 per phone. Incomplete small
  attachments are deleted when their envelopes expire; incomplete big ones when their
  sender's announcing message is 7 days old.
- At most 2 big transfers run at once, each with one stream.
- **Before any decoder runs,** declared and actual sizes are checked against fixed
  limits: incoming images at most 50 megapixels and 16,384 pixels a side; thumbnails at
  most 4 KB and 256 pixels a side; in-app audio playback only for voice notes, at most
  10 minutes. Anything over a limit, or that fails to decode, shows "Can't show this
  here", with the choice to open it in another app or delete it. Files are never
  decoded by Yap.
- **After a crash,** at start: a staging file with no accepted manifest is deleted; an
  accepted manifest with no staging file gets a new empty one, and its received ranges
  are reset, so it can still receive; reservations are recounted from the accepted
  manifests.

### 5. Privacy and safety

- **Photo details are removed before sending:** location, camera, time and every other
  EXIF or metadata field, by re-encoding the image rather than editing tags.
- **Files are never opened automatically.** The app shows the name, type and size, and
  warns before opening a file from someone else.
- **Images and audio are decoded by Android's own decoders** in the app process, with
  size and pixel limits checked before decoding.
- **Voice notes** never leave the phone until the user taps send.

### 6. Limits

- The **encrypted size** is the limit that counts, checked when the manifest arrives:
  at most 262,144 bytes for a small attachment and 104,857,600 bytes for a big one
  (starting values). The phone preparing a small attachment makes the plaintext small
  enough that, with 16 bytes per segment, the encrypted file fits.
- Each piece counts as one message towards the per-contact rate in local storage §5.
- Waiting outgoing big transfers count against the attachments limit too: a phone
  keeps at most 20 waiting per contact **and** never more than the space left; older
  ones show "Not sent yet" until the user resends.
- Filenames are shown with control and path characters removed, and a file is only
  ever opened by an app the user picks.

## What changes elsewhere

- **Mesh v3.1:** pieces use the normal envelope classes; two new frame types for the
  direct channel, `DIRECT_HANDSHAKE` 13 and `DIRECT_DATA` 14 (encrypted pairing uses 17
  and 18); recognition now also gates direct transfers (v3.1 §3.1).
- **Local storage:** the `history` table holds attachment references; a new attachments
  folder and space limit.
- **Screens:** attach and record buttons, photo preview, progress and status for big
  transfers. Made on the UI branch once the data exists.

## Open questions for the reviewer

1. `StreamBE32` over XChaCha20-Poly1305 with 64 KiB segments, and the manifest checks
   (§1).
2. The direct channel: `NNpsk0` keyed from `linkKey`, the week rule and resuming (§3).
3. Linking pieces through their shared label and sizes (§2).
4. The admission and cleanup rules (§4).
5. The starting values: 256 KB, 60 seconds, 100 MB, 5 GB, 8 and 64 incomplete, 2
   transfers.

## Test plan

- A photo with GPS EXIF arrives with no metadata.
- Files of 0, 1, 65,535, 65,536 and 65,537 bytes encrypt to the stated sizes and
  segment counts and open; a file cut at a segment boundary fails; both size ceilings
  are enforced on the encrypted size; a crash after a manifest but before the first
  chunk still lets it finish; an oversized thumbnail or image declaration is refused
  before decoding; pieces before their manifest stay in memory and expire.
- A 262,144-byte encrypted attachment in 5 pieces arrives through two carriers; one missing piece leaves
  it "not received" after expiry; a changed, swapped or reordered chunk is refused.
- A 100 MB file moves directly, survives the link dropping three times, and resumes
  from the last confirmed chunk.
- A listener on the link sees no attachment ID, name, type or plaintext.
- With recognition hidden, big transfers wait and say why.
- A group of 10 with a connected pairing graph: every member gets a big file.
- Clearing a chat deletes its attachment files.
- A crafted image or audio file over the size or pixel limits is refused before
  decoding.

## Implementation steps (after approval)

1. Endpoint (Rust): file encryption, announcement messages, pieces, reassembly.
2. Android: photo preparation and metadata removal, voice recording, file picking,
   attachments folder.
3. Mesh: direct transfer frames and resume.
4. Screens, on the UI branch.
