# carBuddy

An experiment: show the phone's screen on the Android Auto car screen while
parked.

carBuddy is a sideloaded Android Auto **parked app**: an ordinary activity that
Android Auto runs on the car screen while the car is parked. Its car screen shows
a live mirror of the phone, made with Android's own screen capture (the "Start
now" prompt), and touches on the car screen are replayed on the phone.

```
Android Auto ──runs carBuddy's activity on──► its own car-sized display (e.g. 1920×720)
                                                 │
phone screen ──MediaProjection──► VirtualDisplay ──► SurfaceView filling that display
                                                 │
car touches ──► mapped to phone coordinates ──► TouchService (accessibility) gestures
```

## Why a parked app

Current Android Auto (seen on 17.x) rejects sideloaded **Car App Library** apps
even with Unknown sources on — its log says `CAR.VALIDATOR: Package DENIED`, and
installing with the Play Store as installer does not help. An earlier carBuddy and
a minimal test app were both rejected this way. A parked app is declared
differently (game category, `CAR_LAUNCHER` on the launcher activity, automotive
hardware optional — the same markers Android Auto Maker adds), and a sideloaded
one does appear.

Android Auto only lets carBuddy itself onto that display: launching any other app
there shows "This can't be accessed on your Android Auto at this time". So other
apps are reached through the mirror: the car screen's apps button opens them on
the phone, and the mirror shows them.

## How it works

| Piece | File |
|---|---|
| The one activity: setup on the phone, the mirror on the car display | `MainActivity.kt` |
| Car screen: mirror surface, touch mapping, buttons, app list | `CarScreen.kt` |
| The capture and its virtual display | `Mirror.kt` |
| Capture prompt, foreground service, notifications | `Projection.kt` |
| Optional touch control (gestures, Back, Home) | `TouchService.kt` |

- **Start:** pressing Start on the car screen opens the capture prompt on the
  phone's own display (Android Auto would block it on the car's). Since Android 14
  one approval allows one display, so the display is kept while carBuddy's car
  screen exists and only its surface is swapped. Closing that screen stops it.
- **Layout:** Android scales the mirrored screen to fit and centres it, so a
  portrait phone shows with black bars, where the car-screen buttons sit. Turning
  the phone sideways fills the screen.
- **Touch:** the car's touchscreen delivers normal touch events to carBuddy. Each
  finger becomes one continuing accessibility gesture on the phone, sent in short
  segments, so taps, long-presses and swipes all carry over.
- **Screen on:** the mirror is a copy of the phone's display, so it goes black
  when the phone screen turns off. A screen wake lock is held while mirroring.

## Setup

1. Install carBuddy. On the test phone (Pixel, Android Auto 17.x) the working
   install was `adb install -r -i com.android.vending carbuddy.apk`.
2. Android Auto → Settings → tap **Version** ~10 times → ⋮ → **Developer
   settings** → turn on **Unknown sources**.
3. Optional, for touch: in carBuddy tap **Turn on touch control**, choose
   carBuddy, switch it on. On Android 13+ a sideloaded app is first blocked as a
   "restricted setting": App info → ⋮ → **Allow restricted settings**, then retry.
4. While parked, open carBuddy from Android Auto's launcher, press **Start**, and
   tap **Start now** on the phone. Side buttons: Back, Home, Apps, Stop.

## Expected limits

- Parked only: Android Auto closes parked apps when the car moves.
- ~30 fps and some latency: everything goes through Android Auto's video stream.
- DRM video (Netflix, Disney+ etc.) shows black: those apps block capture.
- The phone shows the same thing as the car; it is a mirror, not a second screen.
- Locking the phone turns the mirror black until it is unlocked.

## History

- `729b69f` — Car App Library navigation app + Shizuku virtual display.
- `30ef94c` — Car App Library navigation app + screen capture.

Both were rejected by Android Auto 17.x as sideloaded Car App Library apps.

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
