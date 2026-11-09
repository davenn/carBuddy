# carBuddy

An experiment: show the phone's screen on the Android Auto car screen.

carBuddy is a sideloaded Android Auto app. It mirrors the phone with Android's
own screen capture (the "Start now" prompt), so nothing else needs installing.

```
Android Auto ──gives a navigation app──► a raw Surface covering the car screen
                                              ▲
phone screen ──MediaProjection──► VirtualDisplay (mirror) renders straight into it

car taps / drags ──► mapped to phone coordinates ──► TouchService (accessibility) gestures
```

## How it works

| Piece | File |
|---|---|
| Android Auto entry point, car screens | `CarBuddyService.kt` |
| Car surface ↔ mirror link, tap/drag mapping | `CarDisplay.kt` |
| The capture and its virtual display | `Mirror.kt` |
| Capture prompt, foreground service, notifications | `Projection.kt` |
| Optional touch control (gestures, Back, Home) | `TouchService.kt` |
| Phone setup screen | `MainActivity.kt` |

- **Why navigation category:** of all Android Auto app categories, only
  navigation apps get a raw `Surface` to draw on (for the map). Everything else is
  fixed templates.
- **One prompt per drive:** opening carBuddy in the car shows the capture prompt
  on the phone. If Android blocks that as a background launch, a "tap to start"
  notification does the same. Android 14+ allows one display per approval, so the
  display is kept for the whole drive and only its surface is swapped when the
  car screen comes and goes. Disconnecting the car stops the capture.
- **Layout:** Android scales the mirrored screen to fit and centres it, so a
  portrait phone shows with black bars. Turning the phone sideways fills the car.
- **Touch:** Android Auto reports taps (`onClick`, car API 5+) with a position,
  but drags only as distances (`onScroll`). Taps are mapped through the same
  fit-and-centre transform to phone coordinates. Drags move a virtual finger that
  starts mid-screen, sent as one continuing accessibility stroke.
- **Screen on:** the mirror is a copy of the phone's display, so it goes black
  when the phone screen turns off. A screen wake lock is held while mirroring.

## Setup

1. Install carBuddy.
2. Android Auto → Settings → tap **Version** ~10 times → ⋮ → **Developer
   settings** → turn on **Unknown sources**.
3. Optional, for touch: in carBuddy tap **Turn on touch control**, choose
   carBuddy, switch it on. On Android 13+ a sideloaded app is first blocked as a
   "restricted setting": App info → ⋮ → **Allow restricted settings**, then retry.
4. In the car, open carBuddy and tap **Start now** on the phone. Grid button =
   open an app, arrow = Back, house = Home.

## Expected limits

- ~30 fps and 0.1–0.3 s latency: everything goes through Android Auto's video
  stream to the car.
- DRM video (Netflix etc.) shows black: those apps block capture.
- The phone shows the same thing as the car; it is a mirror, not a second screen.
- Locking the phone turns the car screen black until it is unlocked.

The first version used Shizuku to run apps on a separate virtual display instead
of mirroring. It needed no capture prompt but required Shizuku, restarted
after every reboot; it is in the git history (commit 729b69f).

## Build

No Android Studio needed:

```
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir=...`, or
`ANDROID_HOME`). `debug.keystore` is committed on purpose, so a build from any
machine installs over the previous one. It is a throwaway debug key, not a
release key.

Pushes are built by GitHub Actions. On `main`, the APK is published to the
rolling `latest` release.
