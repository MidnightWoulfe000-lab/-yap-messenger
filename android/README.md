# Yap for Android

We've got the first Bluetooth greeting working on two real Android phones. Now we're trying actual, user-written messages.

## What this version does

- One phone advertises Yap over Bluetooth Low Energy.
- The other phone finds it and writes a short text message to its Bluetooth service.
- The receiving phone shows the message on screen.
- To reply, both phones can advertise, then take turns pressing Send.

**This is a very early test.** Messages are limited to 20 UTF-8 bytes, so some emoji and non-English characters take more than one byte. There is no encryption at the Yap protocol layer, authentication, chat history, offline queue or delivery guarantee. Don't use it for anything sensitive.

## Test it on two Android phones

1. Get the APK from a successful **Android debug build** in GitHub Actions.
2. Install the new build on both phones and keep Yap open.
3. Tap **Make this phone discoverable** on both phones. Wait for the visible confirmation.
4. On phone A, type a short message, such as `Hi from A`, and tap **Send to nearby Yap phone**.
5. Phone B should show **Message received: Hi from A**. Phone A should show **Message delivered to the other phone**.
6. Repeat from phone B to phone A.

If a message fails, record the status text and the phone models. The sender's confirmation only means the Bluetooth write was acknowledged, not that a person read the message.

## What's next

We need message framing, identity, persistent storage, multi-hop routing and end-to-end encryption. The app is not ready for private communication.
