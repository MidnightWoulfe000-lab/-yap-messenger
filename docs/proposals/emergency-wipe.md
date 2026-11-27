# Proposal: emergency wipe ("go anonymous")

**Status: draft proposal. Not reviewed, not implemented.** Asked for by the project
owner on 27 November 2026, for people who may have their phone taken from them, such as
students at a protest. It deletes keys, so it needs review before any code.

**Audience: the project owner, reviewers and coding agents.** Read with
[GROUP_MECHANICS.md](../GROUP_MECHANICS.md) §6 (scramble), which already lets an owner
wipe **all Yap data** on a lost phone **from another device**, and
[rotating address labels](rotating-address-labels.md) §1 (pair secrets).

## What it is

A setting, **Enhanced security**, adds a **Go anonymous** button to the app. Pressing it
deletes everything that ties the phone to its owner's Yap identity, so if the phone is
taken soon after, there is nothing left to find or use. Afterwards the app starts again
as a brand-new identity with no contacts and no chats.

Scramble covers a phone that is already gone. This covers a phone that is about to be.

## What it deletes

The same set as an owner-triggered scramble: all Yap data on the phone.
- The identity private key and every libsignal session, pre-key and stored key.
- Every pair secret and key made from it (labels, outer layer, friend names).
- Contacts, chats, group state, group keys and the group event log.
- The outbox and the carrier store (`relay-v1.redb`), including other people's
  envelopes it was carrying.
- The Android Keystore key that encrypts the endpoint snapshot, so a leftover copy of
  the snapshot file cannot be decrypted.
- Mesh state held in memory (links, recognised friends).

The snapshot lives in `noBackupFilesDir`, so Android backups do not hold a copy.

## How it is pressed

- The button is off until **Enhanced security** is switched on, so nobody wipes by
  accident while exploring.
- It must be quick under stress but hard to press by mistake. Proposed: press and hold
  for 3 seconds, with no second dialog.
- It needs no password, network or other phone.
- There is no undo. The setting screen says so plainly before it can be switched on.

## What it cannot do

- **Copies already made.** If the phone was copied earlier, the copy still holds the old
  keys. Pair secret replacement (rotating address labels §1) limits how long such a copy
  stays useful to friends' traffic.
- **Friends' phones.** Friends still hold their copies of the shared chats and secrets.
  Messages they send to the old identity can no longer be read by anyone holding the
  wiped phone, but whoever takes a friend's phone gets that friend's copy.
- **Flash storage.** Deleting a file on a phone does not always erase the bytes on the
  chip at once. Deleting the Keystore key that encrypts the snapshot is what makes it
  unreadable, not overwriting the file.
- **Keys in memory.** Audit finding 6: the decrypted state crosses into Java as strings
  that cannot be reliably wiped. A wipe ends the app's process as its last step, so the
  memory is released, but this is not a guarantee.
- **Hiding that Yap was installed.** The app stays on the phone.

## After the wipe

- The app starts a fresh identity, as on first install.
- Friends see nothing at once. Their messages to the old identity go unanswered. To talk
  again, they re-add the new identity by QR code.
- Groups: the old identity stays a member until removed under the group's rules.

## Open questions

1. Should the button also be reachable from the lock screen or a quick-settings tile,
   for speed? That makes it easier to reach and also easier to trigger by mistake.
2. Should friends be told? A signed "this identity is gone" message would help them,
   but sending it takes time and shows the wipe happened.
3. Should groups get an automatic "left the group" event, or should the old identity
   simply go quiet?
4. Is press-and-hold for 3 seconds right?

## Tests required

- After a wipe: no identity key, session, pair secret, contact, chat, group state,
  outbox or carrier envelope remains; the Keystore key is gone; a copy of the old
  snapshot file cannot be decrypted.
- The app starts as a new identity with a new identity key.
- The button does nothing while Enhanced security is off.
- A wipe interrupted part way (for example by the phone dying) finishes on next start
  before anything else runs.
