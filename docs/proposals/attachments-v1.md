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

Each attachment gets a fresh random 32-byte **file key**. The file is encrypted in
64 KiB chunks with the STREAM construction over XChaCha20-Poly1305
(`aead::stream` in the RustCrypto crates, the same family as the outer layer), so each
chunk is checked on its own and chunks cannot be reordered, dropped from the end or
swapped between files.

The **message** that announces it is an ordinary end-to-end encrypted text message
(one-to-one or group) carrying, inside the encryption:
- a random 16-byte attachment ID;
- the file key;
- the kind (photo, voice note, file), size, chunk count and SHA-256 of the encrypted
  file;
- for photos, a thumbnail of at most 4 KB; for voice notes, the length; for files, the
  name (at most 255 bytes) and type;
- whether it is small (mesh) or big (direct).

Nobody but the recipients learns any of this. Carriers and links only ever see
encrypted bytes.

### 2. Small attachments: through the mesh

- **What counts as small:** at most **256 KB** after the phone prepares it. A photo is
  resized to at most 1,600 pixels on its longest side and compressed to fit. A voice
  note is at most **60 seconds**, recorded as Opus at 16 kbit/s (about 120 KB).
- **Pieces.** The encrypted file is cut into at most 5 pieces of up to 60 KiB. Each
  piece travels in its own envelope, addressed with the same label as a message would
  be (rotating address labels §3, or the group label) and wrapped with the outer layer,
  so strangers cannot tell a piece from a message or link pieces to each other except
  by size and timing. Inside the outer layer: the attachment ID, the piece number and
  the piece.
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
- **When it moves:** only over a direct link to a phone that the sender recognises as
  that contact (mesh v3.1 §3). The sender offers it when the link opens; the transfer
  resumes from the last confirmed chunk if the link drops. Strangers never carry it.
- **On the link:** the frames are encrypted with a key made from the pair's `linkKey`
  and both `HELLO` nonces (HKDF-SHA256, salt `Yap direct transfer v1`), so a listener
  sees only that two phones are moving a lot of data. New frame types `FILE_OFFER`,
  `FILE_CHUNK`, `FILE_ACK` and `FILE_DONE`. *For review:* this is a new use of the link
  key and needs the same review as the recognition probe.
- **Recognition switched off:** if either phone has hidden from the other (mesh v3.1
  §3.9), their links are never recognised, so big attachments between them wait. The
  app says so on the attachment ("Waiting until you are both nearby with recognition
  on").
- **Groups:** a big group attachment goes directly to members who are also your paired
  contacts. Each member who has it passes it on the same way to their own paired
  contacts in the group. Members connected to nobody else in the group by pairing only
  get it if someone they paired with has it. The app shows how many members have
  confirmed it.
- **Relayed links:** a link relayed between two places (mesh v3.1 §3.1) only carries
  encrypted chunks, so it can slow a transfer but not read or change it.

### 4. Storage

Attachments live in `noBackupFilesDir/attachments/` as files named by random IDs, still
encrypted with their file keys. The keys and references live in the store's `history`
table ([local storage](local-storage-v1.md) §3). Attachments have their own space limit,
shown and changeable in Settings (starting value 5 GB), with the same fair-share rule as
messages. Clearing a chat or removing a contact deletes its attachment files.

**Carrier store:** small-attachment pieces are ordinary envelopes up to 60 KiB, so they
fill the carrier store's 64 MiB far sooner than text does. *For review:* raising the
phone carrier store's byte limit to 256 MiB (the 10,000-envelope count stays).

### 5. Privacy and safety

- **Photo details are removed before sending:** location, camera, time and every other
  EXIF or metadata field, by re-encoding the image rather than editing tags.
- **Files are never opened automatically.** The app shows the name, type and size, and
  warns before opening a file from someone else.
- **Images and audio are decoded by Android's own decoders** in the app process, with
  size and pixel limits checked before decoding.
- **Voice notes** never leave the phone until the user taps send.

### 6. Limits

- At most 256 KB per small attachment and 100 MB per big one (starting values).
- Each piece counts as one message towards the per-contact rate in local storage §5.
- A phone keeps at most 20 big transfers waiting per contact; older ones show "Not
  sent yet" until the user resends.

## What changes elsewhere

- **Mesh v3.1:** pieces use the normal envelope classes; four new frame types for
  direct transfer, after `WANT` (12): `FILE_OFFER` 13, `FILE_CHUNK` 14, `FILE_ACK` 15,
  `FILE_DONE` 16.
- **Local storage:** the `history` table holds attachment references; a new attachments
  folder and space limit.
- **Screens:** attach and record buttons, photo preview, progress and status for big
  transfers. Made on the UI branch once the data exists.

## Open questions for the reviewer

1. STREAM over XChaCha20-Poly1305 for files: chunk size, and how the hash and chunk
   count are bound.
2. The direct-transfer key made from `linkKey` (§3).
3. Whether pieces of one attachment, sent together, can be linked by strangers through
   timing and size, and whether to space them out.
4. The carrier store byte limit (§4).
5. The starting values: 256 KB, 60 seconds, 100 MB, 5 GB.

## Test plan

- A photo with GPS EXIF arrives with no metadata.
- A 256 KB attachment in 5 pieces arrives through two carriers; one missing piece leaves
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
