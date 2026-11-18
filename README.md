# CallBell

When your Android phone rings, your Mac shows who's calling. That's the whole app.

- **Android app** notices the phone ringing, looks up the contact name, and sends one tiny
  encrypted message over Wi-Fi. When the call ends it sends "missed" or "answered".
- **Mac app** sits in the menu bar. It shows a card in the top-right corner of the screen,
  plays a sound, and keeps a list of recent calls. The card also shows during Focus / Do Not
  Disturb and over full-screen apps, and a missed-call card stays until you close it.

No cloud, no account, no Bluetooth. Both devices just need to be on the same Wi-Fi.

## Build

```sh
./build-android.sh     # -> dist/CallBell.apk   (builds inside Docker)
./mac/build.sh         # -> dist/CallBell.app   (needs Xcode command line tools)
```

## Set up (once)

**Mac**
1. Drag `dist/CallBell.app` into `/Applications` and open it. A phone icon appears in the menu bar.
2. If macOS asks to allow CallBell to find devices on the local network or accept incoming
   connections, click **Allow**.
3. Click the phone icon. You'll see a pairing code like `DUMA-XQHK`. Tick **Open at login**.

**Android**
1. Copy `dist/CallBell.apk` to the phone and install it (allow "install unknown apps" when asked).
2. Open CallBell and:
   - **Grant permissions**: Phone, Call logs (Android only reveals the caller's number with
     this), Contacts (for names).
   - Type the **pairing code** from the Mac.
   - Tap **Send a test to the Mac**. A green "CallBell test" card should appear on the Mac.
   - Tap **Turn off battery optimisation** so Android doesn't delay it while the phone sleeps.

You don't need to keep the app open. Android starts it whenever the phone rings.

## Troubleshooting

| Problem | Fix |
|---|---|
| Test says "The Mac did not answer" | Same Wi-Fi? (Guest networks often block devices from seeing each other.) Code typed correctly? CallBell running on the Mac? Try typing the Mac's IP (System Settings → Wi-Fi → Details) into "Mac IP address" on the phone. |
| Mac menu says "Not listening" | Another copy is already running. Quit it. |
| Name missing, only the number | Contacts permission is off, or the number isn't saved in your contacts. |
| "Unknown caller" | Call logs permission is off, or the caller hid their number. |
| Works, then stops after a while (Samsung/Xiaomi/OnePlus) | Settings → Apps → CallBell → Battery → **Unrestricted**. |

## How it works

- Phone → Mac over UDP port **47823**. The phone sends to the Mac's last known IP *and* as a Wi-Fi
  broadcast, so nothing needs configuring and it still works if the Mac's IP changes.
  The Mac replies with an acknowledgement; the phone retries for about 4 s until it gets one.
- Every message is encrypted and authenticated with AES-256-GCM, using a key derived from the
  pairing code. Other devices on the Wi-Fi can't read who's calling, and can't fake calls.
  Messages older than 15 minutes are ignored.
- Message: `{type: ringing|missed|answered|test, call, number, name, device, ts}`.

## Files

```
android/                 Android app (Kotlin, no external libraries)
  app/src/main/java/com/callbell/
    CallReceiver.kt      wakes on phone-state changes, decides ringing/missed/answered
    Sender.kt            UDP send + wait for ack
    Crypto.kt            AES-GCM packet format
    MainActivity.kt      setup screen
  keystore/              signing key (generated on first build, keep it for updates)
mac/
  Sources/               SwiftUI menu-bar app
  build.sh
tools/
  android-builder.Dockerfile
  TestClient.java        pretend-phone for testing the Mac app without a phone
build-android.sh
```

Test the Mac app without a phone (runs in Docker):

```sh
CODE=$(defaults read com.callbell.mac code)
docker run --rm -v "$PWD/tools":/t eclipse-temurin:17-jdk-jammy \
  java /t/TestClient.java host.docker.internal $CODE ringing call-1 "Alice" "+1 555 0100"
```
